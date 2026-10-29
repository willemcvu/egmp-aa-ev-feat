package com.google.android.projection.protocol;

import java.util.Collections;
import java.util.List;

/**
 * Decision logic for automatic battery preconditioning (docs/PRECONDITIONING.md).
 *
 * Pure Java: the car (binder), the ownership journal and the log are behind
 * interfaces so the host test drives it with fakes. Not thread-safe: Precond
 * calls every method from its single handler thread. Getter fields are
 * volatile so other threads (a logger) can read them.
 *
 * Command profile and safety rules are the manual Launchpad app's
 * (gen5w-preconditioning PreconditioningProfile / PreconditioningController):
 *   Start    = HEV 65(km), 66(min), sleep 10 ms, 62(2,3), sleep 130 ms
 *   Maintain = HEV 65(km), 66(min) every 5 s; 62 is never resent
 *   Cancel   = HEV 65(2047), 66(1023), 62(0,0), sleep 130 ms (all attempted)
 *   Start only with setting enabled (HEV flag 94 == 2), READY (flag 44 == 1)
 *   and the BCM indicator (txn 109) Off; ownership journalled before the
 *   first command; a recovered journal only ever cancels; 5 min without
 *   Active after Start cancels; READY lost / setting disabled / Active->Off
 *   cancels.
 */
public final class PrecondCore {

    /** Car access. Implementations must refuse anything outside the profile. */
    public interface Vehicle {
        /** Device + firmware fingerprint + service descriptors verified. */
        boolean compatible();

        int readEnabled() throws Exception;    // HEV txn 3 flag 94: 2 = enabled

        int readReady() throws Exception;      // HEV txn 3 flag 44: 1 = READY

        int readIndicator() throws Exception;  // BCM txn 109: 0 Off, 1 Active

        void write(int transaction, int[] values) throws Exception;

        void pause(long ms);
    }

    /** Persistent "we may own a request" flag. */
    public interface Journal {
        boolean pending() throws Exception;

        void set(boolean owned) throws Exception;
    }

    public interface Logger {
        void log(String msg);
    }

    // ---- states (int so a logger can read them cheaply) --------------------
    public static final int ST_OFF = 0;         // switch off: parse only
    public static final int ST_IDLE = 1;        // no DC charger target
    public static final int ST_WAITING = 2;     // target, conditions not met
    public static final int ST_REQUESTED = 3;   // Start sent, not Active yet
    public static final int ST_ACTIVE = 4;      // Start sent, car reports Active
    public static final int ST_CANCELLING = 5;  // Cancel sent, waiting for Off
    public static final int ST_DONE = 6;        // finished for this target
    public static final int ST_HANDS_OFF = 7;   // Active, not started by us
    public static final int ST_ERROR = 8;       // incompatible / journal broken

    private static final String[] STATE_NAMES = {
        "OFF", "IDLE", "WAITING", "REQUESTED", "ACTIVE", "CANCELLING", "DONE",
        "HANDS_OFF", "ERROR",
    };

    public static String stateName(int s) {
        return s >= 0 && s < STATE_NAMES.length ? STATE_NAMES[s] : "?";
    }

    // ---- constants ----------------------------------------------------------
    public static final long REFRESH_MS = 5000;
    public static final long ACTIVATION_TIMEOUT_MS = 5 * 60 * 1000L;
    public static final int WINDOW_SECONDS = 60 * 60;
    public static final int ARRIVAL_METERS = 200;
    /** Precondition for charging stops strictly above this power. */
    public static final int MIN_DC_KW = 50;
    public static final int MAX_STARTS_PER_ROUTE = 3;
    /** After Cancel, wait this long for the indicator to read Off. */
    public static final long CANCEL_CONFIRM_MS = 60 * 1000L;
    public static final int CANCEL_RETRIES = 12;

    static final int TX_STATION = 62;
    static final int TX_ROUTE_KM = 65;
    static final int TX_REMAIN_MIN = 66;
    static final int KM_INVALID = 2047;
    static final int MIN_INVALID = 1023;
    static final int KM_MAX = 2046;
    static final int MIN_MAX = 1022;

    private final Vehicle vehicle;
    private final Journal journal;
    private final Logger logger;

    // nav data
    private boolean navActive;
    private List<NavProto.Destination> dests =
            Collections.<NavProto.Destination>emptyList();
    private List<NavProto.Remaining> rem =
            Collections.<NavProto.Remaining>emptyList();
    private String lastDestSummary = "";

    // target
    private NavProto.Destination target;
    private int targetKw = -1;
    private int targetId;
    private boolean latched;
    private int latchedId;
    private int startsThisRoute;

    // ownership
    private boolean owned;
    private boolean activeSeen;
    private long startedAt;
    private long nextMaintain;
    private boolean cancelling;
    private boolean cancelPending;
    private int cancelTries;
    private long cancelAt;
    private boolean journalBroken;
    private String waitReason = "";

    // published for other threads
    private volatile int pubState = ST_OFF;
    private volatile boolean pubNavActive;
    private volatile int pubMeters = -1;
    private volatile int pubSeconds = -1;
    private volatile boolean pubIsCharger;
    private volatile int pubKw = -1;

    public PrecondCore(Vehicle vehicle, Journal journal, Logger logger) {
        this.vehicle = vehicle;
        this.journal = journal;
        this.logger = logger;
    }

    // ---- getters (any thread) -------------------------------------------------
    public int state() {
        return pubState;
    }

    public boolean navActive() {
        return pubNavActive;
    }

    public int remainingMeters() {
        return pubMeters;
    }

    public int remainingSeconds() {
        return pubSeconds;
    }

    public boolean isCharger() {
        return pubIsCharger;
    }

    public int chargerKw() {
        return pubKw;
    }

    /** Needs periodic tick(): guiding, owning a request or cancelling. */
    public boolean busy() {
        return navActive || owned || cancelling;
    }

    // ---- inputs (handler thread) ----------------------------------------------

    /**
     * Process start: a journal left by an earlier process means a request
     * may still be live in the car. Cancel it; never Start from here.
     */
    public void recover(long now) {
        boolean pending;
        try {
            pending = journal.pending();
        } catch (Exception e) {
            journalBroken = true;
            logger.log("journal unreadable, automatic start disabled: " + e);
            publish();
            return;
        }
        if (pending) {
            logger.log("stale ownership journal: cancelling (no start)");
            owned = true;
            startedAt = now;
            cancel("recovery", now);
        }
        publish();
    }

    /** AA NavigationState: 1 ACTIVE, 3 REROUTING count as guiding. */
    public void onNavStatus(int status, long now) {
        boolean active = status == 1 || status == 3;
        if (active == navActive) {
            return;
        }
        navActive = active;
        logger.log("nav " + (active ? "active" : "inactive") + " (" + status + ")");
        if (!active) {
            endRoute("route ended", now);
        }
        publish();
    }

    public void onDestinations(List<NavProto.Destination> d, long now) {
        if (d.isEmpty()) {
            return;  // no destination info in this update: keep the last one
        }
        dests = d;
        String sum = summary(d);
        if (!sum.equals(lastDestSummary)) {
            lastDestSummary = sum;
            logger.log("dests " + sum);
        }
        pickTarget(now);
        publish();
    }

    public void onRemaining(List<NavProto.Remaining> r, long now) {
        if (r.isEmpty()) {
            return;
        }
        rem = r;
        if (owned && target != null) {
            NavProto.Remaining t = remainingFor(target);
            if (t != null && t.meters >= 0 && t.meters < ARRIVAL_METERS) {
                cancelAndLatch("arrived", now);
            }
        }
        publish();
    }

    /** AA session end / GPM shutdown. */
    public void stop(long now) {
        navActive = false;
        endRoute("session end", now);
        publish();
    }

    /** Periodic (REFRESH_MS) evaluation. */
    public void tick(long now) {
        try {
            tickInner(now);
        } finally {
            publish();
        }
    }

    // ---- logic ----------------------------------------------------------------

    private void tickInner(long now) {
        if (cancelling) {
            finishCancel(now);
            return;
        }
        if (owned) {
            tickOwned(now);
            return;
        }
        if (!navActive || target == null) {
            return;
        }
        if (latched && latchedId == targetId) {
            return;
        }
        tryStart(now);
    }

    private void tickOwned(long now) {
        if (!navActive || target == null) {
            cancel(!navActive ? "route ended" : "target gone", now);
            return;
        }
        NavProto.Remaining r = remainingFor(target);
        if (r != null && r.meters >= 0 && r.meters < ARRIVAL_METERS) {
            cancelAndLatch("arrived", now);
            return;
        }
        int enabled, ready, indicator;
        try {
            enabled = vehicle.readEnabled();
            ready = vehicle.readReady();
            indicator = vehicle.readIndicator();
        } catch (Exception e) {
            // Unknown feedback never prolongs a pending request.
            if (!activeSeen && now - startedAt >= ACTIVATION_TIMEOUT_MS) {
                cancelAndLatch("no Active within 5 min", now);
            }
            return;
        }
        if (indicator == 1 && !activeSeen) {
            activeSeen = true;
            logger.log("car reports conditioning Active");
        }
        if (!activeSeen && now - startedAt >= ACTIVATION_TIMEOUT_MS) {
            cancelAndLatch("no Active within 5 min", now);
            return;
        }
        if (ready == 0) {
            cancelAndLatch("not READY", now);
            return;
        }
        if (enabled == 1) {
            cancelAndLatch("setting disabled", now);
            return;
        }
        if (activeSeen && indicator == 0) {
            cancelAndLatch("car ended conditioning", now);
            return;
        }
        if (now >= nextMaintain && r != null) {
            try {
                metadata(km(r.meters), minutes(r.seconds));
                nextMaintain = now + REFRESH_MS;
            } catch (Exception e) {
                logger.log("maintain failed: " + e);
            }
        }
    }

    private void tryStart(long now) {
        NavProto.Remaining r = remainingFor(target);
        if (r == null || r.meters < 0 || r.seconds < 0) {
            waiting("no distance for target");
            return;
        }
        if (r.meters < ARRIVAL_METERS) {
            waiting("at the charger");
            return;
        }
        if (r.seconds > WINDOW_SECONDS) {
            waiting("more than 60 min away");
            return;
        }
        if (startsThisRoute >= MAX_STARTS_PER_ROUTE) {
            waiting("start limit for this route");
            return;
        }
        if (journalBroken) {
            waiting("journal unavailable");
            return;
        }
        int enabled, ready, indicator;
        try {
            enabled = vehicle.readEnabled();
            ready = vehicle.readReady();
            indicator = vehicle.readIndicator();
        } catch (Exception e) {
            waiting("vehicle status unavailable");
            return;
        }
        if (indicator == 1) {
            waiting("hands off: car already Active");
            return;
        }
        if (enabled != 2) {
            waiting("battery conditioning setting not enabled (" + enabled + ")");
            return;
        }
        if (ready != 1) {
            waiting("not READY (" + ready + ")");
            return;
        }
        if (indicator != 0) {
            waiting("indicator unknown (" + indicator + ")");
            return;
        }
        if (!vehicle.compatible()) {
            waiting("vehicle interface not the verified firmware");
            return;
        }
        try {
            journal.set(true);  // record ownership before any transmission
        } catch (Exception e) {
            journalBroken = true;
            logger.log("start not sent: journal write failed: " + e);
            return;
        }
        owned = true;
        activeSeen = false;
        startedAt = now;
        startsThisRoute++;
        int km = km(r.meters), min = minutes(r.seconds);
        logger.log("start: " + km + " km, " + min + " min, " + targetKw + " kW");
        try {
            metadata(km, min);
            vehicle.pause(10);
            try {
                vehicle.write(TX_STATION, new int[] {2, 3});
            } finally {
                vehicle.pause(130);
            }
            nextMaintain = now + REFRESH_MS;
            waitReason = "";
        } catch (Exception e) {
            logger.log("start failed: " + e);
            cancelAndLatch("start failed", now);
        }
    }

    private void waiting(String why) {
        if (!why.equals(waitReason)) {
            waitReason = why;
            logger.log("waiting: " + why);
        }
    }

    private void endRoute(String why, long now) {
        dests = Collections.<NavProto.Destination>emptyList();
        rem = Collections.<NavProto.Remaining>emptyList();
        lastDestSummary = "";
        target = null;
        targetKw = -1;
        latched = false;
        startsThisRoute = 0;
        waitReason = "";
        if (owned) {
            cancel(why, now);
        }
    }

    private void pickTarget(long now) {
        NavProto.Destination found = null;
        int kw = -1;
        for (NavProto.Destination d : dests) {
            if (d.hasStation && d.maxPowerKw > MIN_DC_KW) {
                found = d;
                kw = d.maxPowerKw;
                break;
            }
        }
        int id = found == null ? 0 : identity(found);
        boolean changed = (found == null) != (target == null)
                || (found != null && id != targetId);
        target = found;
        targetKw = kw;
        targetId = id;
        if (changed) {
            waitReason = "";
            if (found != null) {
                logger.log("target: stop #" + found.index + ", " + kw + " kW"
                        + (found.hasStation ? "" : " (name match)"));
            } else {
                logger.log("no DC charger target");
                if (owned) {
                    cancel("target gone", now);
                }
            }
        }
    }

    private static int identity(NavProto.Destination d) {
        return d.key != 0 ? d.key : 1000 + d.index;
    }

    private NavProto.Remaining remainingFor(NavProto.Destination d) {
        if (d == null || d.index >= rem.size()) {
            return null;
        }
        return rem.get(d.index);
    }

    private void cancelAndLatch(String why, long now) {
        if (target != null) {
            latched = true;
            latchedId = targetId;
        }
        cancel(why, now);
    }

    private void cancel(String why, long now) {
        if (!owned) {
            return;
        }
        owned = false;
        cancelling = true;
        cancelTries = 0;
        logger.log("cancel: " + why);
        sendCancel(now);
    }

    private void sendCancel(long now) {
        cancelTries++;
        try {
            Exception failure = null;
            try {
                vehicle.write(TX_ROUTE_KM, new int[] {KM_INVALID});
            } catch (Exception e) {
                failure = e;
            }
            try {
                vehicle.write(TX_REMAIN_MIN, new int[] {MIN_INVALID});
            } catch (Exception e) {
                if (failure == null) {
                    failure = e;
                }
            }
            try {
                vehicle.write(TX_STATION, new int[] {0, 0});
            } catch (Exception e) {
                if (failure == null) {
                    failure = e;
                }
            }
            vehicle.pause(130);
            if (failure != null) {
                throw failure;
            }
            cancelPending = false;
            cancelAt = now;
        } catch (Exception e) {
            cancelPending = true;
            logger.log("cancel failed (try " + cancelTries + "): " + e);
        }
    }

    /** CANCELLING: retry a failed Cancel, then wait for Off to drop the journal. */
    private void finishCancel(long now) {
        if (cancelPending) {
            if (cancelTries >= CANCEL_RETRIES) {
                // Leave the journal: the next GPM start cancels again.
                logger.log("cancel unconfirmed; journal kept for next start");
                cancelling = false;
                cancelPending = false;
                return;
            }
            sendCancel(now);
            return;
        }
        int indicator = -1;
        try {
            indicator = vehicle.readIndicator();
        } catch (Exception ignored) {
            // treat as unknown
        }
        boolean off = indicator == 0;
        if (!off && now - cancelAt < CANCEL_CONFIRM_MS) {
            return;
        }
        try {
            journal.set(false);
        } catch (Exception e) {
            logger.log("journal clear failed: " + e);
            return;
        }
        cancelling = false;
        logger.log(off ? "cancel confirmed: car reports Off"
                : "cancel sent; indicator not Off after 60 s, released");
    }

    private void metadata(int km, int min) throws Exception {
        vehicle.write(TX_ROUTE_KM, new int[] {km});
        vehicle.write(TX_REMAIN_MIN, new int[] {min});
    }

    static int km(int meters) {
        int v = (meters + 500) / 1000;
        return v < 0 ? 0 : (v > KM_MAX ? KM_MAX : v);
    }

    static int minutes(int seconds) {
        int v = (seconds + 30) / 60;
        return v < 0 ? 0 : (v > MIN_MAX ? MIN_MAX : v);
    }

    private static String summary(List<NavProto.Destination> d) {
        StringBuilder sb = new StringBuilder();
        sb.append("n=").append(d.size());
        for (NavProto.Destination x : d) {
            sb.append(" [").append(x.index).append(':');
            if (x.hasStation) {
                sb.append(" station kw=").append(x.maxPowerKw)
                        .append(" dep=").append(x.departureWh)
                        .append("Wh chg=").append(x.chargeSeconds).append('s');
            } else {
                sb.append(" -");
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private void publish() {
        int s;
        if (cancelling) {
            s = ST_CANCELLING;
        } else if (owned) {
            s = activeSeen ? ST_ACTIVE : ST_REQUESTED;
        } else if (journalBroken) {
            s = ST_ERROR;
        } else if (!navActive || target == null) {
            s = ST_IDLE;
        } else if (latched && latchedId == targetId) {
            s = ST_DONE;
        } else if (waitReason.startsWith("hands off")) {
            s = ST_HANDS_OFF;
        } else if (waitReason.startsWith("vehicle interface")) {
            s = ST_ERROR;
        } else {
            s = ST_WAITING;
        }
        if (s != pubState) {
            logger.log("state " + stateName(pubState) + " -> " + stateName(s));
        }
        pubState = s;
        pubNavActive = navActive;
        NavProto.Destination focus = target != null ? target
                : (dests.isEmpty() ? null : dests.get(0));
        NavProto.Remaining r = remainingFor(focus);
        pubMeters = r == null ? -1 : r.meters;
        pubSeconds = r == null ? -1 : r.seconds;
        if (focus == target && target != null) {
            pubIsCharger = true;
            pubKw = targetKw;
        } else {
            pubIsCharger = focus != null && focus.hasStation;
            pubKw = focus != null && focus.hasStation ? focus.maxPowerKw : -1;
        }
    }
}

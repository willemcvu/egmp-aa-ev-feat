package com.google.android.projection.protocol;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Automatic battery preconditioning while Google Maps (Android Auto) guides
 * to a DC fast charger. See docs/PRECONDITIONING.md.
 *
 * Entry points (called from the patched GpmNaviTBTManager smali, each
 * wrapped in a catch-all there too; none of them ever throws):
 *   onSessionStart(Context)  GpmNaviTBTManager.<init>: start the thread,
 *                            cancel a stale ownership journal (never start)
 *   onNavDump(String)        onNextTurn type 0x21: "S ..." / "P ..." payloads
 *   onNavStatus(int)         onStatus: AA NavigationState
 *   stop()                   GpmNaviTBTManager.destroy() (AA session end)
 *                            and SensorSource.shutdown() (GAL teardown)
 *
 * Always on, no switch: the car's own "battery conditioning" EV setting
 * (HEV flag 94) is the off control. Requests preconditioning for the next
 * stop when it is a charging stop above PrecondCore.MIN_DC_KW.
 *
 * Everything runs on one HandlerThread; PrecondCore holds the logic.
 */
public final class Precond {
    static final String TAG = "EvPrecond";
    private static final String JOURNAL_NAME = "evprecond.owned";

    private static final Object LOCK = new Object();
    private static HandlerThread sThread;
    private static Handler sHandler;
    private static volatile PrecondCore sCore;
    private static boolean sTicking;
    private static boolean sLoggedParseError;

    private Precond() {}

    // ---- read-only getters for other components (e.g. a logger) ------------

    /** PrecondCore.ST_* (ST_OFF before the first AA session). */
    public static int state() {
        PrecondCore c = sCore;
        return c == null ? PrecondCore.ST_OFF : c.state();
    }

    public static String stateName() {
        return PrecondCore.stateName(state());
    }

    public static boolean navActive() {
        PrecondCore c = sCore;
        return c != null && c.navActive();
    }

    /** Metres to the selected charger, else to the next stop; -1 unknown. */
    public static int remainingMeters() {
        PrecondCore c = sCore;
        return c == null ? -1 : c.remainingMeters();
    }

    /** Seconds to the selected charger, else to the next stop; -1 unknown. */
    public static int remainingSeconds() {
        PrecondCore c = sCore;
        return c == null ? -1 : c.remainingSeconds();
    }

    public static boolean isCharger() {
        PrecondCore c = sCore;
        return c != null && c.isCharger();
    }

    /** max_power_kw of that stop (150 for a name match); -1 unknown. */
    public static int chargerKw() {
        PrecondCore c = sCore;
        return c == null ? -1 : c.chargerKw();
    }

    // ---- entry points ----------------------------------------------------------

    public static void onSessionStart(Context ctx) {
        try {
            ensure(ctx);
        } catch (Throwable t) {
            Log.w(TAG, "init failed: " + t);
        }
    }

    public static void onNavDump(final String line) {
        try {
            if (line == null || line.length() < 2
                    || (line.charAt(0) != 'S' && line.charAt(0) != 'P')) {
                return;
            }
            Handler h = ensure(null);
            if (h == null) {
                return;
            }
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        handleDump(line);
                    } catch (Throwable t) {
                        Log.w(TAG, "dump failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            // never disturb GPM
        }
    }

    public static void onNavStatus(final int status) {
        try {
            Handler h = ensure(null);
            if (h == null) {
                return;
            }
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        sCore.onNavStatus(status, now());
                        scheduleTick();
                    } catch (Throwable t) {
                        Log.w(TAG, "status failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            // never disturb GPM
        }
    }

    /** AA session end: cancel if we own a request. Waits up to 1.5 s. */
    public static void stop() {
        try {
            Handler h;
            synchronized (LOCK) {
                h = sHandler;
            }
            if (h == null) {
                return;
            }
            final CountDownLatch done = new CountDownLatch(1);
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        sCore.stop(now());
                        scheduleTick();  // keeps going only while cancelling
                    } catch (Throwable t) {
                        Log.w(TAG, "stop failed: " + t);
                    } finally {
                        done.countDown();
                    }
                }
            });
            if (Thread.currentThread() != h.getLooper().getThread()) {
                done.await(1500, TimeUnit.MILLISECONDS);
            }
        } catch (Throwable t) {
            // never disturb GPM
        }
    }

    // ---- internals (handler thread unless noted) -------------------------------

    private static long now() {
        return SystemClock.elapsedRealtime();
    }

    /** Any thread. Creates the thread and core once; returns the handler. */
    private static Handler ensure(Context ctx) {
        synchronized (LOCK) {
            if (sHandler != null) {
                return sHandler;
            }
            if (ctx == null) {
                ctx = appContext();
            }
            File dir = ctx == null ? null : ctx.getFilesDir();
            HandlerThread t = new HandlerThread(TAG);
            t.start();
            Handler h = new Handler(t.getLooper());
            sCore = new PrecondCore(new HkmcVehicle(), new FileJournal(dir),
                    new PrecondCore.Logger() {
                        @Override
                        public void log(String msg) {
                            Log.i(TAG, msg);
                        }
                    });
            sThread = t;
            sHandler = h;
            h.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        long n = now();
                        sCore.recover(n);
                        scheduleTick();
                    } catch (Throwable t) {
                        Log.w(TAG, "recover failed: " + t);
                    }
                }
            });
            Log.i(TAG, "started");
            return h;
        }
    }

    private static Context appContext() {
        try {
            return (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void handleDump(String line) {
        long n = now();
        NavProto.Dump d;
        try {
            d = NavProto.parse(line);
        } catch (NavProto.ParseException e) {
            if (!sLoggedParseError) {
                sLoggedParseError = true;
                Log.w(TAG, "NAVDUMP " + line.charAt(0) + " not parsed: "
                        + e.getMessage() + " (logged once)");
            }
            return;
        }
        if (d == null) {
            return;
        }
        if (d.kind == 'S') {
            sCore.onDestinations(d.destinations, n);
        } else {
            sCore.onRemaining(d.remaining, n);
        }
    }

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            sTicking = false;
            try {
                long n = now();
                sCore.tick(n);
                scheduleTick();
            } catch (Throwable t) {
                Log.w(TAG, "tick failed: " + t);
            }
        }
    };

    /** Handler thread. Ticks every 5 s while guiding, owning or cancelling. */
    private static void scheduleTick() {
        if (sTicking || !sCore.busy()) {
            return;
        }
        sTicking = true;
        sHandler.postDelayed(TICK, PrecondCore.REFRESH_MS);
    }


    // ---- ownership journal -----------------------------------------------------

    /** A small file in GPM's files dir: present = we may own a request. */
    static final class FileJournal implements PrecondCore.Journal {
        private final File file;

        FileJournal(File dir) {
            file = dir == null ? null : new File(dir, JOURNAL_NAME);
        }

        @Override
        public boolean pending() throws Exception {
            if (file == null) {
                throw new IllegalStateException("no files dir");
            }
            return file.exists();
        }

        @Override
        public void set(boolean owned) throws Exception {
            if (file == null) {
                throw new IllegalStateException("no files dir");
            }
            if (owned) {
                FileOutputStream out = new FileOutputStream(file);
                try {
                    out.write('1');
                    out.flush();
                    out.getFD().sync();
                } finally {
                    out.close();
                }
            } else if (file.exists() && !file.delete()) {
                throw new IllegalStateException("delete failed");
            }
        }
    }

    // ---- car access (Launchpad's OemVehicleClient, Ioniq 5 NEEV profile) -----

    static final class HkmcVehicle implements PrecondCore.Vehicle {
        private static final String HEV_SERVICE = "com.hkmc.micom.hev";
        private static final String HEV = "com.hkmc.hev.IHEVService";
        private static final String BCM_SERVICE = "com.hkmc.micom.bcm";
        private static final String BCM = "com.hkmc.bcm.IBCMService";
        private static final int TX_GET_HEV_STATUS = 3;
        private static final int FLAG_READY = 44;
        private static final int FLAG_BATT_COND = 94;
        private static final int TX_BCM_COND_INDICATOR = 109;

        // gen5w-preconditioning PreconditioningProfile (Ioniq 5 US 260507);
        // matches our private/car-neev-260507 framework.
        static final String DEVICE = "wp_daudioplus_neev23_us";
        static final String FRAMEWORK_SHA256 =
                "dd72e04bce0a2b4bda1506308ac21f91002c1436553b8156c345cc4313661338";
        static final String SERVICE_SHA256 =
                "3cf40fb00854c63afa49abea1dad18a0afcbc0dd68b17de9ed7ea3f5aeab4222";

        private Boolean compatible;  // null = not checked yet

        @Override
        public boolean compatible() {
            if (compatible != null) {
                return compatible.booleanValue();
            }
            boolean ok;
            try {
                ok = Build.VERSION.SDK_INT == 19 && DEVICE.equals(Build.DEVICE)
                        && FRAMEWORK_SHA256.equals(
                                sha256("/system/framework/automotive.odex"))
                        && SERVICE_SHA256.equals(
                                sha256("/system/framework/automotive-service.odex"));
            } catch (Throwable t) {
                Log.w(TAG, "firmware check failed: " + t);
                return false;  // retried next time
            }
            compatible = Boolean.valueOf(ok);
            Log.i(TAG, "firmware profile " + (ok ? "matches" : "DOES NOT match")
                    + " (device " + Build.DEVICE + ")");
            return ok;
        }

        @Override
        public int readEnabled() throws Exception {
            return getter(service(HEV_SERVICE, HEV), HEV, TX_GET_HEV_STATUS,
                    FLAG_BATT_COND);
        }

        @Override
        public int readReady() throws Exception {
            return getter(service(HEV_SERVICE, HEV), HEV, TX_GET_HEV_STATUS,
                    FLAG_READY);
        }

        @Override
        public int readIndicator() throws Exception {
            return getter(service(BCM_SERVICE, BCM), BCM,
                    TX_BCM_COND_INDICATOR, -1);
        }

        @Override
        public void write(int transaction, int[] values) throws Exception {
            if (!((transaction == PrecondCore.TX_STATION && values.length == 2)
                    || ((transaction == PrecondCore.TX_ROUTE_KM
                            || transaction == PrecondCore.TX_REMAIN_MIN)
                            && values.length == 1))) {
                throw new IllegalArgumentException(
                        "command outside the preconditioning profile");
            }
            if (!compatible()) {
                throw new IllegalStateException("unverified firmware");
            }
            IBinder hev = service(HEV_SERVICE, HEV);
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(HEV);
                for (int v : values) {
                    data.writeInt(v);
                }
                if (!hev.transact(transaction, data, reply, 0)) {
                    throw new IllegalStateException("command rejected");
                }
                reply.readException();
                if (reply.dataAvail() != 0) {
                    throw new IllegalStateException("unexpected command reply");
                }
            } finally {
                data.recycle();
                reply.recycle();
            }
        }

        @Override
        public void pause(long ms) {
            SystemClock.sleep(ms);
        }

        private static IBinder service(String name, String descriptor)
                throws Exception {
            IBinder b = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, name);
            if (b == null || !b.isBinderAlive()
                    || !descriptor.equals(b.getInterfaceDescriptor())) {
                throw new IllegalStateException(name + " unavailable");
            }
            return b;
        }

        /** arg < 0: no argument. */
        private static int getter(IBinder b, String descriptor, int tx, int arg)
                throws Exception {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(descriptor);
                if (arg >= 0) {
                    data.writeInt(arg);
                }
                if (!b.transact(tx, data, reply, 0)) {
                    throw new IllegalStateException("getter rejected");
                }
                reply.readException();
                if (reply.dataAvail() != 4) {
                    throw new IllegalStateException("unexpected reply");
                }
                return reply.readInt();
            } finally {
                data.recycle();
                reply.recycle();
            }
        }

        private static String sha256(String path) throws Exception {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            FileInputStream in = new FileInputStream(path);
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            } finally {
                in.close();
            }
            StringBuilder sb = new StringBuilder();
            for (byte x : md.digest()) {
                sb.append(String.format(Locale.US, "%02x", x & 0xff));
            }
            return sb.toString();
        }
    }
}

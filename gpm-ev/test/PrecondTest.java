package com.google.android.projection.protocol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Host (JVM, no Android) test for NavProto and PrecondCore. Run with
 * gpm-ev/test/run.sh.
 */
public final class PrecondTest {

    // Real 0x8007 payload from the 2026-10-27 wired session (no address in it):
    // { 1 { 1 { 2 "0", 3 6 } }, 2 { 1 { 1 22230, 2 "14", 3 4 }, 2 "10:50 PM", 3 1449 } }
    static final String REAL_P =
            "P 36 0a070a05120130180612190a0a08d6ad01120231341804120831303a353020504d18a90b";
    // The real 0x8006 payload from the same session, byte for byte except the
    // two place strings, replaced by same-length placeholders (privacy):
    // { 1 step {1 {1 1}, 2 {1 "toward ..."}, 4 {1 "toward ..."}}, 2 { 1 "<address>" } }
    static final String REAL_S = "S 69 0a300a02080112140a12" + hexOf("toward Example Rd.")
            + "22140a12" + hexOf("toward Example Rd.") + "12110a0f" + hexOf("100 Example Ave");

    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    // ---- fakes ------------------------------------------------------------------

    static final class FakeVehicle implements PrecondCore.Vehicle {
        int enabled = 2, ready = 1, indicator = 0;
        boolean compatible = true;
        boolean readFails, writeFails;
        final List<String> log = new ArrayList<String>();

        public boolean compatible() {
            return compatible;
        }

        public int readEnabled() throws Exception {
            if (readFails) throw new Exception("read");
            return enabled;
        }

        public int readReady() throws Exception {
            if (readFails) throw new Exception("read");
            return ready;
        }

        public int readIndicator() throws Exception {
            if (readFails) throw new Exception("read");
            return indicator;
        }

        public void write(int tx, int[] v) throws Exception {
            StringBuilder sb = new StringBuilder().append(tx).append('(');
            for (int i = 0; i < v.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(v[i]);
            }
            log.add(sb.append(')').toString());
            if (writeFails) throw new Exception("write");
        }

        public void pause(long ms) {
            log.add("sleep" + ms);
        }

        String drain() {
            String s = String.join(" ", log);
            log.clear();
            return s;
        }
    }

    static final class FakeJournal implements PrecondCore.Journal {
        boolean owned;
        boolean fail;
        int writes;

        public boolean pending() {
            return owned;
        }

        public void set(boolean o) throws Exception {
            if (fail) throw new Exception("disk");
            owned = o;
            writes++;
        }
    }

    static final boolean VERBOSE = System.getenv("VERBOSE") != null;

    static final class Rig {
        final FakeVehicle v = new FakeVehicle();
        final FakeJournal j = new FakeJournal();
        final PrecondCore c = new PrecondCore(v, j, new PrecondCore.Logger() {
            public void log(String m) {
                if (VERBOSE) System.out.println("    EvPrecond: " + m);
            }
        });
        long t = 1000000;

        Rig(int mode) {
            c.setMode(mode, t);
            c.recover(t);
        }

        void dump(String line) throws Exception {
            NavProto.Dump d = NavProto.parse(line);
            if (d.kind == 'S') c.onDestinations(d.destinations, t);
            else c.onRemaining(d.remaining, t);
        }

        void ticks(int n) {
            for (int i = 0; i < n; i++) {
                t += PrecondCore.REFRESH_MS;
                c.tick(t);
            }
        }

        /** Route to a single stop with the given station (null = none). */
        void route(int[] station, String addr, int meters, int seconds) throws Exception {
            c.onNavStatus(1, t);
            dump(sMsg(new Object[] {addr, station}));
            dump(pMsg(new int[][] {{meters, seconds}}));
        }
    }

    // ---- protobuf builders --------------------------------------------------------

    static String hexOf(String s) {
        return hex(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    static void varint(ByteArrayOutputStream o, long v) {
        while ((v & ~0x7fL) != 0) {
            o.write((int) ((v & 0x7f) | 0x80));
            v >>>= 7;
        }
        o.write((int) v);
    }

    static void field(ByteArrayOutputStream o, int f, byte[] b) {
        varint(o, (f << 3) | 2);
        varint(o, b.length);
        o.write(b, 0, b.length);
    }

    static void field(ByteArrayOutputStream o, int f, long v) {
        varint(o, f << 3);
        varint(o, v);
    }

    /** dests: pairs of {address String or null, int[]{conn, avail, kw} or null}. */
    static String sMsg(Object[] dests) {
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        ByteArrayOutputStream step = new ByteArrayOutputStream();
        ByteArrayOutputStream man = new ByteArrayOutputStream();
        field(man, 1, 1);
        field(step, 1, man.toByteArray());
        field(m, 1, step.toByteArray());
        for (int i = 0; i < dests.length; i += 2) {
            ByteArrayOutputStream d = new ByteArrayOutputStream();
            if (dests[i] != null) field(d, 1, ((String) dests[i]).getBytes());
            int[] st = (int[]) dests[i + 1];
            if (st != null) {
                ByteArrayOutputStream cs = new ByteArrayOutputStream();
                if (st[0] >= 0) field(cs, 1, st[0]);
                if (st[1] >= 0) field(cs, 2, st[1]);
                if (st[2] >= 0) field(cs, 3, st[2]);
                field(d, 2, cs.toByteArray());
            }
            field(m, 2, d.toByteArray());
        }
        byte[] b = m.toByteArray();
        return "S " + b.length + " " + hex(b);
    }

    /** entries: {meters, seconds}. Shaped like the real 0x8007. */
    static String pMsg(int[][] entries) {
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        ByteArrayOutputStream step = new ByteArrayOutputStream();
        ByteArrayOutputStream sd = new ByteArrayOutputStream();
        field(sd, 2, "0".getBytes());
        field(sd, 3, 6);
        field(step, 1, sd.toByteArray());
        field(m, 1, step.toByteArray());
        for (int[] e : entries) {
            ByteArrayOutputStream d = new ByteArrayOutputStream();
            ByteArrayOutputStream dist = new ByteArrayOutputStream();
            field(dist, 1, e[0]);
            field(dist, 2, "14".getBytes());
            field(dist, 3, 4);
            field(d, 1, dist.toByteArray());
            field(d, 2, "10:50 PM".getBytes());
            field(d, 3, e[1]);
            field(m, 2, d.toByteArray());
        }
        byte[] b = m.toByteArray();
        return "P " + b.length + " " + hex(b);
    }

    static final int[] DC150 = {8, 6, 150};
    static final String START_30_20 = "65(30) 66(20) sleep10 62(2,3) sleep130";
    static final String CANCEL = "65(2047) 66(1023) 62(0,0) sleep130";

    // ---- tests ------------------------------------------------------------------

    static void testNavProto() throws Exception {
        NavProto.Dump p = NavProto.parse(REAL_P);
        check("real P parses", p != null && p.kind == 'P' && p.remaining.size() == 1);
        check("real P meters", p.remaining.get(0).meters == 22230);
        check("real P seconds", p.remaining.get(0).seconds == 1449);

        NavProto.Dump s = NavProto.parse(REAL_S);
        check("real S length kept", REAL_S.startsWith("S 69 ") && REAL_S.length() == 5 + 138);
        check("real S parses", s != null && s.kind == 'S' && s.destinations.size() == 1);
        NavProto.Destination d = s.destinations.get(0);
        check("real S not a charger", !d.hasStation && d.maxPowerKw == -1 && !d.networkMatch);
        check("real S has identity", d.key != 0);

        NavProto.Dump c = NavProto.parse(sMsg(new Object[] {"x", DC150}));
        NavProto.Destination cd = c.destinations.get(0);
        check("station parsed", cd.hasStation && cd.connectorCount == 8
                && cd.availableCount == 6 && cd.maxPowerKw == 150);

        NavProto.Dump two = NavProto.parse(sMsg(new Object[] {"a", null, "b", DC150}));
        check("two destinations", two.destinations.size() == 2
                && !two.destinations.get(0).hasStation && two.destinations.get(1).hasStation
                && two.destinations.get(1).index == 1);

        check("name match EA", NavProto.isDcNetwork("Electrify America - Walmart 1234"));
        check("name match case", NavProto.isDcNetwork("TESLA SUPERCHARGER"));
        check("no name match", !NavProto.isDcNetwork("100 Example Ave"));

        boolean threw = false;
        try {
            NavProto.parse("S 69 0a300a0208");  // truncated by the logger
        } catch (NavProto.ParseException e) {
            threw = true;
        }
        check("length mismatch rejected", threw);
        threw = false;
        try {
            NavProto.parse("S 3 0a0501");  // field claims 5 bytes, has 1
        } catch (NavProto.ParseException e) {
            threw = true;
        }
        check("truncated field rejected", threw);
        threw = false;
        try {
            NavProto.parse("P 2 zz00");
        } catch (NavProto.ParseException e) {
            threw = true;
        }
        check("bad hex rejected", threw);
        check("other kinds ignored", NavProto.parse("X 1 00") == null);
        // fixed32 / fixed64 skipping
        NavProto.Dump fx = NavProto.parse("P 16 0d01020304" + "090102030405060708" + "1000");
        check("fixed32/64 skipped", fx != null && fx.remaining.isEmpty());

        check("km rounding", PrecondCore.km(22230) == 22 && PrecondCore.km(-5) == 0);
        check("km clamp", PrecondCore.km(5000000) == 2046);
        check("min rounding", PrecondCore.minutes(1449) == 24);
        check("min clamp", PrecondCore.minutes(1000000) == 1022 && PrecondCore.minutes(-1) == 0);
    }

    static void testRealNonCharger() throws Exception {
        Rig r = new Rig(PrecondCore.MODE_ON_NAMES);
        r.c.onNavStatus(1, r.t);
        for (int i = 0; i < 100; i++) {
            r.dump(REAL_S);
            r.dump(REAL_P);
            r.ticks(1);
        }
        check("real non-charger route: no commands", r.v.drain().isEmpty());
        check("real non-charger route: IDLE", r.c.state() == PrecondCore.ST_IDLE);
        check("getters: nav data exposed", r.c.navActive() && r.c.remainingMeters() == 22230
                && r.c.remainingSeconds() == 1449 && !r.c.isCharger() && r.c.chargerKw() == -1);
        check("no journal write", r.j.writes == 0);
    }

    static void testStartMaintainArrive() throws Exception {
        Rig r = new Rig(PrecondCore.MODE_ON);
        r.route(DC150, "Some charger", 30000, 1200);
        check("getters: charger", r.c.isCharger() && r.c.chargerKw() == 150);
        check("no start before tick", r.v.drain().isEmpty());
        r.ticks(1);
        check("start profile", r.v.drain().equals(START_30_20));
        check("journal written before start", r.j.owned && r.j.writes == 1);
        check("REQUESTED", r.c.state() == PrecondCore.ST_REQUESTED);
        r.dump(pMsg(new int[][] {{25000, 1000}}));
        r.ticks(1);
        check("maintain uses live values, no 62", r.v.drain().equals("65(25) 66(17)"));
        r.v.indicator = 1;
        r.ticks(1);
        check("ACTIVE", r.c.state() == PrecondCore.ST_ACTIVE);
        check("maintain again", r.v.drain().equals("65(25) 66(17)"));
        r.ticks(100);  // active sessions have no 5-minute cutoff
        check("long active session never resends 62", !r.v.drain().contains("62"));
        r.dump(pMsg(new int[][] {{150, 20}}));
        check("cancel on arrival", r.v.drain().equals(CANCEL));
        check("CANCELLING", r.c.state() == PrecondCore.ST_CANCELLING);
        check("journal kept until Off", r.j.owned);
        r.ticks(1);
        check("still waiting for Off", r.j.owned);
        r.v.indicator = 0;
        r.ticks(1);
        check("journal cleared on Off", !r.j.owned);
        check("DONE", r.c.state() == PrecondCore.ST_DONE);
        r.dump(pMsg(new int[][] {{5000, 300}}));  // GPS jitter: away again
        r.ticks(20);
        check("no restart for the same charger", r.v.drain().isEmpty());
    }

    static void testRouteEnd() throws Exception {
        Rig r = new Rig(PrecondCore.MODE_ON);
        r.route(DC150, "C", 30000, 1200);
        r.ticks(1);
        r.v.drain();
        r.c.onNavStatus(2, r.t);
        check("cancel on route end", r.v.drain().equals(CANCEL));
        r.ticks(3);
        check("no commands after route end", r.v.drain().isEmpty() && !r.j.owned);
        // rerouting keeps it
        Rig q = new Rig(PrecondCore.MODE_ON);
        q.route(DC150, "C", 30000, 1200);
        q.ticks(1);
        q.v.drain();
        q.c.onNavStatus(3, q.t);
        q.ticks(1);
        check("rerouting does not cancel", q.v.drain().equals("65(30) 66(20)"));
    }

    static void testTargetChange() throws Exception {
        Rig r = new Rig(PrecondCore.MODE_ON);
        r.route(DC150, "C", 30000, 1200);
        r.ticks(1);
        r.v.drain();
        r.dump(sMsg(new Object[] {"Somewhere else", null}));
        check("cancel when target becomes non-charger", r.v.drain().equals(CANCEL));
        r.ticks(3);
        check("no restart for non-charger", r.v.drain().isEmpty());

        Rig q = new Rig(PrecondCore.MODE_ON);
        q.route(DC150, "C", 30000, 1200);
        q.ticks(1);
        q.v.drain();
        q.dump(sMsg(new Object[] {"Other charger", new int[] {4, 4, 350}}));
        q.ticks(1);
        check("charger -> other DC charger keeps request (no new 62)",
                q.v.drain().equals("65(30) 66(20)"));
        q.dump(sMsg(new Object[] {"L2", new int[] {2, 2, 11}}));
        check("charger -> L2 cancels", q.v.drain().equals(CANCEL));
    }

    static void testTimeout() throws Exception {
        Rig r = new Rig(PrecondCore.MODE_ON);
        r.route(DC150, "C", 30000, 1200);
        r.ticks(1);
        r.v.drain();
        r.ticks(59);  // 4 min 55 s after start
        String s = r.v.drain();
        check("maintaining while pending", s.startsWith("65(30) 66(20)") && !s.contains("2047"));
        r.ticks(1);  // 5 min
        check("cancel after 5 min without Active", r.v.drain().equals(CANCEL));
        r.ticks(30);
        check("no restart after timeout", r.v.drain().isEmpty()
                && r.c.state() == PrecondCore.ST_DONE && !r.j.owned);
        // timeout also applies when status reads fail
        Rig q = new Rig(PrecondCore.MODE_ON);
        q.route(DC150, "C", 30000, 1200);
        q.ticks(1);
        q.v.drain();
        q.v.readFails = true;
        q.ticks(59);
        check("no maintain on unknown status", q.v.drain().isEmpty());
        q.ticks(1);
        check("timeout with unreadable status", q.v.drain().equals(CANCEL));
    }

    static void testCarEnds() throws Exception {
        Rig r = new Rig(PrecondCore.MODE_ON);
        r.route(DC150, "C", 30000, 1200);
        r.ticks(1);
        r.v.indicator = 1;
        r.ticks(1);
        r.v.drain();
        r.v.indicator = 0;
        r.ticks(1);
        check("cancel when car goes Active -> Off", r.v.drain().equals(CANCEL));
        Rig q = new Rig(PrecondCore.MODE_ON);
        q.route(DC150, "C", 30000, 1200);
        q.ticks(1);
        q.v.drain();
        q.v.ready = 0;
        q.ticks(1);
        check("cancel when READY lost", q.v.drain().equals(CANCEL));
        Rig w = new Rig(PrecondCore.MODE_ON);
        w.route(DC150, "C", 30000, 1200);
        w.ticks(1);
        w.v.drain();
        w.v.enabled = 1;
        w.ticks(1);
        check("cancel when setting disabled", w.v.drain().equals(CANCEL));
    }

    static void testStaleJournal() throws Exception {
        for (int mode = 0; mode <= 2; mode++) {
            FakeVehicle v = new FakeVehicle();
            FakeJournal j = new FakeJournal();
            j.owned = true;
            PrecondCore c = new PrecondCore(v, j, new PrecondCore.Logger() {
                public void log(String m) {}
            });
            long t = 5000;
            c.setMode(mode, t);
            c.recover(t);
            check("stale journal mode " + mode + ": cancel only", v.drain().equals(CANCEL));
            v.indicator = 0;
            t += 5000;
            c.tick(t);
            check("stale journal cleared after Off", !j.owned);
            check("stale journal never starts", !v.drain().contains("62(2,3)"));
        }
        // cancel failing: retried, journal kept
        Rig r = new Rig(PrecondCore.MODE_OFF);
        r.j.owned = true;
        r.v.writeFails = true;
        r.c.recover(r.t);
        r.ticks(PrecondCore.CANCEL_RETRIES + 5);
        String s = r.v.drain();
        int n = s.split("62\\(0,0\\)", -1).length - 1;
        check("failed cancel retried " + n + "x", n == PrecondCore.CANCEL_RETRIES);
        check("failed cancel keeps journal", r.j.owned);
    }

    static void testSwitchOff() throws Exception {
        Rig r = new Rig(PrecondCore.MODE_OFF);
        r.route(DC150, "Electrify America", 30000, 1200);
        r.ticks(50);
        check("switch off: never sends", r.v.drain().isEmpty() && r.j.writes == 0);
        check("switch off: OFF state", r.c.state() == PrecondCore.ST_OFF);
        check("switch off: nav data still exposed", r.c.isCharger() && r.c.chargerKw() == 150
                && r.c.remainingMeters() == 30000);
        r.c.setMode(PrecondCore.MODE_ON, r.t);
        r.ticks(1);
        check("switch on: starts", r.v.drain().equals(START_30_20));
        r.c.setMode(PrecondCore.MODE_OFF, r.t);
        check("switch off while active: cancel", r.v.drain().equals(CANCEL));
        r.ticks(10);
        check("switch off: nothing more", !r.v.drain().contains("62(2,3)"));
    }

    static void testConditions() throws Exception {
        Rig l2 = new Rig(PrecondCore.MODE_ON_NAMES);
        l2.route(new int[] {2, 2, 11}, "Level 2", 30000, 1200);
        l2.ticks(10);
        check("L2 charger: no start", l2.v.drain().isEmpty());

        Rig nm = new Rig(PrecondCore.MODE_ON);
        nm.route(null, "Electrify America Walmart", 30000, 1200);
        nm.ticks(10);
        check("name match ignored in mode 1", nm.v.drain().isEmpty());
        nm.c.setMode(PrecondCore.MODE_ON_NAMES, nm.t);
        nm.ticks(1);
        check("name match in mode 2 starts", nm.v.drain().equals(START_30_20)
                && nm.c.chargerKw() == 150);

        Rig far = new Rig(PrecondCore.MODE_ON);
        far.route(DC150, "C", 150000, 90 * 60);
        far.ticks(10);
        check("outside 60 min window: no start", far.v.drain().isEmpty()
                && far.c.state() == PrecondCore.ST_WAITING);
        far.dump(pMsg(new int[][] {{90000, 59 * 60}}));
        far.ticks(1);
        check("inside window: start", far.v.drain().equals("65(90) 66(59) sleep10 62(2,3) sleep130"));

        Rig ho = new Rig(PrecondCore.MODE_ON);
        ho.v.indicator = 1;
        ho.route(DC150, "C", 30000, 1200);
        ho.ticks(10);
        check("hands off when already Active", ho.v.drain().isEmpty()
                && ho.c.state() == PrecondCore.ST_HANDS_OFF);

        Rig dis = new Rig(PrecondCore.MODE_ON);
        dis.v.enabled = 1;
        dis.route(DC150, "C", 30000, 1200);
        dis.ticks(10);
        check("setting disabled: no start", dis.v.drain().isEmpty());

        Rig nr = new Rig(PrecondCore.MODE_ON);
        nr.v.ready = 0;
        nr.route(DC150, "C", 30000, 1200);
        nr.ticks(10);
        check("not READY: no start", nr.v.drain().isEmpty());

        Rig fw = new Rig(PrecondCore.MODE_ON);
        fw.v.compatible = false;
        fw.route(DC150, "C", 30000, 1200);
        fw.ticks(10);
        check("unverified firmware: no start", fw.v.drain().isEmpty() && fw.j.writes == 0
                && fw.c.state() == PrecondCore.ST_ERROR);

        Rig jf = new Rig(PrecondCore.MODE_ON);
        jf.j.fail = true;
        jf.route(DC150, "C", 30000, 1200);
        jf.ticks(10);
        check("journal write failure: no start", jf.v.drain().isEmpty());

        Rig near = new Rig(PrecondCore.MODE_ON);
        near.route(DC150, "C", 150, 30);
        near.ticks(5);
        check("already at the charger: no start", near.v.drain().isEmpty());

        Rig inactive = new Rig(PrecondCore.MODE_ON);
        inactive.dump(sMsg(new Object[] {"C", DC150}));
        inactive.dump(pMsg(new int[][] {{30000, 1200}}));
        inactive.ticks(5);
        check("nav not active: no start", inactive.v.drain().isEmpty());
    }

    static void testMultiStop() throws Exception {
        Rig r = new Rig(PrecondCore.MODE_ON);
        r.c.onNavStatus(1, r.t);
        r.dump(sMsg(new Object[] {"Charger A", DC150, "Home", null}));
        r.dump(pMsg(new int[][] {{30000, 1200}, {300000, 12000}}));
        r.ticks(1);
        check("multi-stop: targets first charger with its own distance",
                r.v.drain().equals(START_30_20));
        r.dump(pMsg(new int[][] {{100, 10}, {270000, 10800}}));
        check("multi-stop: arrival at A cancels", r.v.drain().equals(CANCEL));
        r.v.indicator = 0;
        r.ticks(1);
        // Maps drops A, B is a second charger further on
        r.dump(sMsg(new Object[] {"Charger B", new int[] {4, 2, 350}, "Home", null}));
        r.dump(pMsg(new int[][] {{60000, 2400}, {270000, 10800}}));
        r.ticks(1);
        check("multi-stop: starts again for the next charger",
                r.v.drain().equals("65(60) 66(40) sleep10 62(2,3) sleep130"));

        Rig m = new Rig(PrecondCore.MODE_ON);
        m.c.onNavStatus(1, m.t);
        m.dump(sMsg(new Object[] {"Lunch", null, "Charger", DC150}));
        m.dump(pMsg(new int[][] {{10000, 600}}));  // no entry for stop #1
        m.ticks(3);
        check("multi-stop: no distance for the charger -> no start", m.v.drain().isEmpty());
        m.dump(pMsg(new int[][] {{10000, 600}, {40000, 1800}}));
        m.ticks(1);
        check("multi-stop: second stop's distance used", m.v.drain().equals(
                "65(40) 66(30) sleep10 62(2,3) sleep130"));
        check("getters follow the target", m.c.remainingMeters() == 40000
                && m.c.isCharger() && m.c.chargerKw() == 150);
    }

    static void testSessionStopAndChurn() throws Exception {
        Rig r = new Rig(PrecondCore.MODE_ON);
        r.route(DC150, "C", 30000, 1200);
        r.ticks(1);
        r.v.drain();
        r.c.stop(r.t);
        check("cancel on session end", r.v.drain().equals(CANCEL));
        check("not guiding after session end", !r.c.navActive() && r.c.remainingMeters() == -1);

        // A charger that flips in and out of the destination list: at most
        // MAX_STARTS_PER_ROUTE starts per route.
        Rig q = new Rig(PrecondCore.MODE_ON);
        q.route(DC150, "C", 30000, 1200);
        for (int i = 0; i < 10; i++) {
            q.ticks(1);
            q.dump(sMsg(new Object[] {"X", null}));
            q.v.indicator = 0;
            q.ticks(1);
            q.dump(sMsg(new Object[] {"C", DC150}));
        }
        String s = q.v.drain();
        int starts = s.split("62\\(2,3\\)", -1).length - 1;
        check("start churn capped (" + starts + ")", starts == PrecondCore.MAX_STARTS_PER_ROUTE);

        Rig e = new Rig(PrecondCore.MODE_ON);
        e.route(DC150, "C", 30000, 1200);
        e.ticks(1);
        e.v.drain();
        e.dump("S 4 0a020800");  // steps only
        e.ticks(1);
        check("S update without destinations keeps the target",
                e.v.drain().equals("65(30) 66(20)"));
    }

    public static void main(String[] args) throws Exception {
        testNavProto();
        testRealNonCharger();
        testStartMaintainArrive();
        testRouteEnd();
        testTargetChange();
        testTimeout();
        testCarEnds();
        testStaleJournal();
        testSwitchOff();
        testConditions();
        testMultiStop();
        testSessionStopAndChurn();
        System.out.println("passed: " + passed + ", failed: " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}

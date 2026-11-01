package com.google.android.projection.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Host test for BtHandoff's scheduling: first try 3 s after the session
 * starts, retry every 2 s while evEarlyHandoff() says not ready, give up after
 * MAX_TRIES (the phone's own pairing request still does the handoff), and
 * drop everything once the session ends. A fake service stands in for
 * GalReceiverService (BtHandoff finds the explorer by field name, as on the
 * car) and a fake Delayer for the main looper.
 */
public final class BtHandoffTest {
    static int passed;
    static int failed;

    static void check(boolean ok, String what) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + what);
        }
    }

    /** Stands in for sink.e.a: answers RETRY (1) notReady times, then 0. */
    public static final class FakeExplorer {
        int notReady;
        int calls;

        public int evEarlyHandoff() {
            calls++;
            return calls <= notReady ? 1 : 0;
        }
    }

    /** Stands in for GalReceiverService: the field name is what BtHandoff reads. */
    static final class FakeService {
        private Object mBluetoothExplorer;

        FakeService(Object explorer) {
            mBluetoothExplorer = explorer;
        }
    }

    static final class FakeDelayer implements BtHandoff.Delayer {
        final List<Runnable> pending = new ArrayList<Runnable>();
        final List<Long> delays = new ArrayList<Long>();

        @Override public void post(Runnable r, long delayMs) {
            pending.add(r);
            delays.add(delayMs);
        }

        /** Runs queued work until none is left; returns how many ran. */
        int drain() {
            int n = 0;
            while (!pending.isEmpty()) {
                pending.remove(0).run();
                n++;
            }
            return n;
        }
    }

    static FakeDelayer fresh() {
        FakeDelayer d = new FakeDelayer();
        BtHandoff.sDelayer = d;
        return d;
    }

    public static void main(String[] args) {
        // Ready at once: one call, 3 s after start.
        FakeDelayer d = fresh();
        FakeExplorer x = new FakeExplorer();
        BtHandoff.onSessionStart(new FakeService(x));
        d.drain();
        check(x.calls == 1, "ready: one call, got " + x.calls);
        check(d.delays.size() == 1 && d.delays.get(0) == 3000L, "ready: first try at 3 s " + d.delays);

        // Bluetooth manager not ready for 3 tries (car just booted): retries
        // every 2 s, then stops after the handoff.
        d = fresh();
        x = new FakeExplorer();
        x.notReady = 3;
        BtHandoff.onSessionStart(new FakeService(x));
        d.drain();
        check(x.calls == 4, "retry: 3 not-ready + 1 done, got " + x.calls);
        check(d.delays.size() == 4 && d.delays.get(1) == 2000L && d.delays.get(3) == 2000L,
                "retry: 2 s between tries " + d.delays);

        // Never ready: gives up after 15 tries.
        d = fresh();
        x = new FakeExplorer();
        x.notReady = 1000;
        BtHandoff.onSessionStart(new FakeService(x));
        d.drain();
        check(x.calls == 15, "give up: 15 tries, got " + x.calls);

        // Session ends before the first try: nothing runs.
        d = fresh();
        x = new FakeExplorer();
        BtHandoff.onSessionStart(new FakeService(x));
        BtHandoff.stop();
        d.drain();
        check(x.calls == 0, "stopped before first try: no call, got " + x.calls);

        // Session ends while retrying: no more tries.
        d = fresh();
        x = new FakeExplorer();
        x.notReady = 1000;
        BtHandoff.onSessionStart(new FakeService(x));
        d.pending.remove(0).run();
        d.pending.remove(0).run();
        BtHandoff.stop();
        d.drain();
        check(x.calls == 2, "stopped while retrying: 2 calls, got " + x.calls);

        // A new session replaces an old one still pending: only the new one runs.
        d = fresh();
        FakeExplorer old = new FakeExplorer();
        x = new FakeExplorer();
        BtHandoff.onSessionStart(new FakeService(old));
        BtHandoff.onSessionStart(new FakeService(x));
        d.drain();
        check(old.calls == 0 && x.calls == 1, "new session: old " + old.calls + ", new " + x.calls);

        // No explorer, or one without the patched method: no crash, no retry.
        d = fresh();
        BtHandoff.onSessionStart(new FakeService(null));
        check(d.drain() == 1, "null explorer: one attempt, no retry");
        d = fresh();
        BtHandoff.onSessionStart(new FakeService(new Object()));
        check(d.drain() == 1, "unpatched explorer: one attempt, no retry");

        System.out.println("BtHandoffTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }
}

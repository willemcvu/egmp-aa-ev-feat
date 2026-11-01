package com.google.android.projection.protocol;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Early Bluetooth handoff at the start of a wireless Android Auto session.
 *
 * The phone's Android Auto skips its first Bluetooth pairing request on
 * purpose and sends it 120 s later. Stock GPM answers that request
 * (sink.e.a.ag()) by putting the car's Bluetooth manager in phone-projection
 * mode, which drops the phone's A2DP/AVRCP link (HFP stays). The phone then
 * loses its A2DP device and media apps pause (ACTION_AUDIO_BECOMING_NOISY),
 * about two minutes into the drive.
 *
 * This does the same switch a few seconds after the session starts, before
 * anything is playing: patched BluetoothEndpoint.init() calls onSessionStart,
 * BluetoothEndpoint.shutdown() calls stop (tools/patch_gpm_smali.py). The
 * work is sink.e.a.evEarlyHandoff(), added in smali, which runs ag()'s guards
 * and returns RETRY while the Bluetooth manager, car power or a call is not
 * ready. Its connectivity broadcast is not answered with a pairing response;
 * the phone's own request later is handled by stock ag() as before.
 *
 * Off switch: persist.gpm.bt.early.off=1 (checked at each session start).
 */
public final class BtHandoff {
    private static final String TAG = "BtHandoff";
    private static final long FIRST_MS = 3000;
    private static final long RETRY_MS = 2000;
    private static final int MAX_TRIES = 15;
    private static final int RETRY = 1;

    /** Posts delayed work; the main looper on the car, a fake in BtHandoffTest. */
    interface Delayer {
        void post(Runnable r, long delayMs);
    }

    static Delayer sDelayer;
    private static int sGen;

    private BtHandoff() {}

    public static synchronized void onSessionStart(Object service) {
        int gen = ++sGen;
        if ("1".equals(prop("persist.gpm.bt.early.off"))) {
            Log.i(TAG, "off (persist.gpm.bt.early.off=1)");
            return;
        }
        if (sDelayer == null) {
            final Handler h = new Handler(Looper.getMainLooper());
            sDelayer = new Delayer() {
                @Override public void post(Runnable r, long delayMs) {
                    h.postDelayed(r, delayMs);
                }
            };
        }
        post(service, gen, 1, FIRST_MS);
    }

    public static synchronized void stop() {
        sGen++;
    }

    private static void post(final Object service, final int gen, final int tries, long delay) {
        sDelayer.post(new Runnable() {
            @Override public void run() {
                attempt(service, gen, tries);
            }
        }, delay);
    }

    private static synchronized void attempt(Object service, int gen, int tries) {
        if (gen != sGen) return;
        try {
            Field f = service.getClass().getDeclaredField("mBluetoothExplorer");
            f.setAccessible(true);
            Object explorer = f.get(service);
            if (explorer == null) {
                Log.w(TAG, "no Bluetooth explorer");
                return;
            }
            Method m = explorer.getClass().getMethod("evEarlyHandoff");
            int r = (Integer) m.invoke(explorer);
            if (r != RETRY) return;
            if (tries >= MAX_TRIES) {
                Log.w(TAG, "not ready after " + tries + " tries, leaving it to the phone's request");
                return;
            }
            post(service, gen, tries + 1, RETRY_MS);
        } catch (Throwable t) {
            Log.w(TAG, "early handoff failed", t);
        }
    }

    private static String prop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            return (String) c.getMethod("get", String.class, String.class).invoke(null, key, "0");
        } catch (Throwable t) {
            return "0";
        }
    }
}

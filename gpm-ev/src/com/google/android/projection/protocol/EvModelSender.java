package com.google.android.projection.protocol;

import android.util.Base64;
import android.util.Log;

/**
 * Periodic sender for the vehicle energy model (sensor 23).
 *
 * Started from SensorSource.init() and stopped from SensorSource.shutdown().
 * Every PERIOD_MS it builds a SensorBatch message from EvModelTemplate (a
 * single warm-pack model: for road-trip planning the car preconditions on the
 * way to a charger, so the ideal-temperature charge curve is the realistic
 * one) with live values patched in, and queues it through the patched
 * nativeReportDiagnosticsData (see tools/patch_library.py).
 *
 * Live data (read through EvCar, shared with the always-on EvDriveLog):
 *  - DISPLAY_SOC (HEV 0x19)
 *  - the nav battery packet: DATC_OUT_TEMP_SNSR patches model field 1.7
 *    (aux/HVAC table lookup) and road load A (cold penalty,
 *    EvModelTemplate.COLD_*)
 *  - EV_AVLBL_ENRG / HEV 0x31 (BMS available energy, 2 Wh steps): current
 *    energy, and usable capacity learned as available energy / SOC (see
 *    learnCapacity); the charge curve and charge-time tables are rescaled to
 *    that capacity so their taper stays at the same SOC (see scaleTemplate)
 *
 * Logging is not done here: EvDriveLog logs whenever the head unit is on and
 * reads this sender's state through the static getters (aaActive, roadA...).
 *
 * Threading: all sends happen holding LOCK; stop() takes LOCK and joins the
 * thread, and shutdown() calls stop() before nativeShutdown(), so a send never
 * touches the native object after it is freed.
 */
public final class EvModelSender {
    private static final String TAG = "EvModelSender";
    private static final int PERIOD_MS = 5000;
    private static final long SHARED_MAX_AGE_MS = 1500;  // reuse EvDriveLog's reads

    private static final int HEV_FLAG_DISPLAY_SOC = 0x19;  // SOC in 0.5% steps

    // BMS remaining usable energy: HEV flag 0x31 ENERGYMAP_1000MS, the same
    // signal as the nav packet's EV_AVLBL_ENRG (docs/DATA-SOURCES.md 1.1), in
    // 2 Wh steps like BMS_EVENERGYCAPA; 0xFFFF = invalid. Usable capacity is
    // learned as energy / display SOC: 14462 at 46.0% on the owner's car =
    // 28.9 kWh -> ~62.9 kWh. Energy reaches 0 near display 2%, so low-SOC
    // samples read a little low: samples at >= CAP_GOOD_HALF_PCT win, lower
    // ones (>= CAP_MIN_HALF_PCT) only fill in until one exists. Smoothed;
    // kept across sessions in CAP_FILE ("<Wh> <1 if good>").
    static final int ENRG_WH_PER_UNIT = 2;
    private static final int CAP_MIN_HALF_PCT = 60;      // 30% SOC
    private static final int CAP_GOOD_HALF_PCT = 120;    // 60% SOC
    private static final int CAP_MIN_WH = 30000;
    private static final int CAP_MAX_WH = 90000;
    static final int ENRG_MAX_RAW = CAP_MAX_WH / ENRG_WH_PER_UNIT;
    private static final float CAP_ALPHA = 0.1f;         // EMA weight per sample
    private static final String CAP_FILE = "capacity_wh";
    private static final Object CAP_LOCK = new Object();
    private static float sCapacityEst = -1f;             // <0: none yet (CAP_LOCK)
    private static boolean sCapacityGood;                // from >= 60% samples
    private static boolean sCapacityLoaded;
    private static int sEnergyRaw = -1;                  // last valid 0x31 raw

    private static final int MIN_ENERGY_WH = 500;  // Maps drops models with 0

    // Outside temp: unsigned raw x 0.5 - 40 C (Hyundai's own decode of the
    // DATC signal, docs/DATA-SOURCES.md 2); 0xFE/0xFF = invalid.
    private static final int COLD_HYST_C = 3;
    private static volatile int sColdTempC = Integer.MIN_VALUE;  // temp A is based on
    private static volatile float sRoadA = Float.NaN;            // last A sent

    private static final Object LOCK = new Object();
    private static Thread sThread;
    private static volatile boolean sRunning;
    private static SensorSource sSource;
    private static byte[] sTemplate;
    private static byte[] sScaled;          // sTemplate scaled for sScaledCapWh
    private static int sScaledCapWh = -1;
    private static int sLastGoodHalfPct = -1;

    private EvModelSender() {}

    public static void start(SensorSource source) {
        EvDriveLog.start();  // normally already running since boot; never throws
        synchronized (LOCK) {
            sSource = source;
            if (sTemplate == null) {
                sTemplate = Base64.decode(EvModelTemplate.TEMPLATE_B64,
                        Base64.DEFAULT);
                if (!checkPlaceholders(sTemplate)) {
                    Log.e(TAG, "template placeholder mismatch, not starting");
                    sTemplate = null;
                    sSource = null;
                    return;
                }
            }
            if (sRunning) {
                return;
            }
            sRunning = true;
            sThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "EvModelSender");
            sThread.setDaemon(true);
            sThread.start();
            Log.i(TAG, "started");
        }
    }

    public static void stop() {
        Thread t;
        synchronized (LOCK) {
            sRunning = false;
            t = sThread;
            sThread = null;
            sSource = null;
            sRoadA = Float.NaN;
        }
        if (t != null) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Log.i(TAG, "stopped");
        }
    }

    // ---- state for EvDriveLog (any thread) --------------------------------------

    /** An Android Auto session is running (the sender is started). */
    static boolean aaActive() {
        return sRunning;
    }

    /** Road load A last sent to Maps (N), NaN outside an AA session. */
    static float roadA() {
        return sRunning ? sRoadA : Float.NaN;
    }

    /** Temperature A is based on (C), Integer.MIN_VALUE if none. */
    static int coldTempC() {
        return sColdTempC;
    }

    private static void loop() {
        while (true) {
            synchronized (LOCK) {
                if (!sRunning) {
                    return;
                }
                if (sSource != null) {
                    try {
                        tickLocked();
                    } catch (Throwable t) {
                        logTickFailure(t);
                    }
                }
            }
            try {
                Thread.sleep(PERIOD_MS);
            } catch (InterruptedException e) {
                // ignore
            }
        }
    }

    /**
     * One send. Runs inside GPM: anything it throws is caught by loop(),
     * because an uncaught exception here kills GPM and with it Android Auto.
     */
    private static void tickLocked() {
        EvCar.pollNav();
        int halfPct = displaySocHalfPct();
        int availWh = availableEnergyWh();
        learnCapacity(halfPct, availWh);
        int cap = capacityWh();
        int wh = currentEnergyWh(halfPct, availWh, cap);
        byte[] msg = (byte[]) scaledTemplateLocked(cap).clone();
        writeFixedVarint(msg, EvModelTemplate.CAPACITY_OFFSET, cap,
                EvModelTemplate.CAPACITY_WIDTH);
        writeFixedVarint(msg, EvModelTemplate.ENERGY_OFFSET, wh,
                EvModelTemplate.ENERGY_WIDTH);
        float outC = EvCar.outsideTempC();
        boolean outOk = !Float.isNaN(outC);
        if (outOk) {
            writeFloatLe(msg, EvModelTemplate.TEMP_OFFSET, outC);
        }
        float roadA = coldRoadLoadA(outOk ? Math.round(outC)
                : Integer.MIN_VALUE);
        writeFloatLe(msg, EvModelTemplate.ROAD_A_OFFSET, roadA);
        int rc = sSource.sendRawMessage(msg);
        sRoadA = roadA;
        Log.i(TAG, "energy model sent: " + wh + " Wh of " + cap
                + " Wh (" + capacitySource() + "), soc=" + halfPct
                + "/200, enrg=" + sEnergyRaw + ", rc=" + rc
                + ", out=" + (outOk ? String.valueOf(outC) : "?")
                + "C, A=" + Math.round(roadA) + "N, " + EvCar.navSummary());
    }

    private static int sTickFailures;

    /** Log a caught failure (first few in full, then a count every 100). */
    private static void logTickFailure(Throwable t) {
        sTickFailures++;
        if (sTickFailures <= 3) {
            Log.e(TAG, "tick failed (caught, AA unaffected)", t);
        } else if (sTickFailures % 100 == 0) {
            Log.e(TAG, "tick failed " + sTickFailures + " times, last: " + t);
        }
    }

    // ---- cold road load ------------------------------------------------------

    /**
     * Road load A for the outside temperature: base A plus the cold penalty
     * (EvModelTemplate.COLD_*, linear, clamped). The temperature it is based
     * on only moves once the reading is COLD_HYST_C away, so a value
     * wobbling around an edge doesn't keep changing A (Maps re-routes when A
     * moves by >= 50 N). Unknown temperature keeps the last one used, or the
     * warm base A if there is none yet.
     */
    private static float coldRoadLoadA(int outC) {
        if (outC != Integer.MIN_VALUE && (sColdTempC == Integer.MIN_VALUE
                || Math.abs(outC - sColdTempC) >= COLD_HYST_C)) {
            sColdTempC = outC;
        }
        if (sColdTempC == Integer.MIN_VALUE) {
            return EvModelTemplate.ROAD_A_N;
        }
        return EvModelTemplate.ROAD_A_N + coldDeltaA(sColdTempC);
    }

    /** Extra road load (N) at temp C; COLD_TEMPS_C runs warm to cold. */
    private static float coldDeltaA(float t) {
        float[] ts = EvModelTemplate.COLD_TEMPS_C;
        float[] da = EvModelTemplate.COLD_DELTA_A_N;
        if (t >= ts[0]) {
            return da[0];
        }
        for (int i = 1; i < ts.length; i++) {
            if (t >= ts[i]) {
                return da[i - 1] + (da[i] - da[i - 1]) * (t - ts[i - 1])
                        / (ts[i] - ts[i - 1]);
            }
        }
        return da[da.length - 1];
    }

    // ---- energy/capacity ---------------------------------------------------

    /** DISPLAY_SOC in 0.5% steps (0..200), last good value on a failed read. */
    private static int displaySocHalfPct() {
        int halfPct = EvCar.hev(HEV_FLAG_DISPLAY_SOC, SHARED_MAX_AGE_MS);
        if (halfPct < 0 || halfPct > 200) {
            return sLastGoodHalfPct;
        }
        sLastGoodHalfPct = halfPct;
        return halfPct;
    }

    /**
     * BMS remaining usable energy in Wh (HEV 0x31, falling back to a fresh
     * nav packet), -1 if neither has a valid value.
     */
    private static int availableEnergyWh() {
        int raw = EvCar.energyRaw(SHARED_MAX_AGE_MS, ENRG_MAX_RAW);
        sEnergyRaw = raw;
        return raw > 0 ? raw * ENRG_WH_PER_UNIT : -1;
    }

    /**
     * Current energy in Wh: the BMS available energy when known (finer than
     * SOC), else SOC x capacity. Never below MIN_ENERGY_WH.
     */
    static int currentEnergyWh(int halfPct, int availWh, int capacityWh) {
        int wh;
        if (availWh > 0) {
            wh = availWh;
        } else if (halfPct >= 0) {
            wh = (int) ((long) halfPct * capacityWh / 200);
        } else {
            wh = EvModelTemplate.ENERGY_PLACEHOLDER_WH;
        }
        return Math.max(wh, MIN_ENERGY_WH);
    }

    /** One capacity sample (available energy / SOC) into the estimate. */
    private static void learnCapacity(int halfPct, int availWh) {
        synchronized (CAP_LOCK) {
            loadCapacity();
            if (availWh <= 0 || halfPct < CAP_MIN_HALF_PCT) {
                return;
            }
            boolean good = halfPct >= CAP_GOOD_HALF_PCT;
            if (sCapacityGood && !good) {
                return;
            }
            float sample = (float) availWh * 200f / halfPct;
            if (sample < CAP_MIN_WH || sample > CAP_MAX_WH) {
                return;
            }
            int before = Math.round(sCapacityEst);
            boolean upgrade = good && !sCapacityGood;
            sCapacityEst = sCapacityEst < 0 || upgrade ? sample
                    : sCapacityEst + CAP_ALPHA * (sample - sCapacityEst);
            sCapacityGood |= good;
            if (upgrade || Math.abs(Math.round(sCapacityEst) - before) >= 100) {
                saveCapacity();
            }
        }
    }

    /** Usable capacity in Wh, rounded to 100 Wh; nominal until learned. Any thread. */
    static int capacityWh() {
        synchronized (CAP_LOCK) {
            loadCapacity();
            if (sCapacityEst < 0) {
                return EvModelTemplate.CAPACITY_WH;
            }
            return Math.round(sCapacityEst / 100f) * 100;
        }
    }

    /** Any thread. */
    static String capacitySource() {
        synchronized (CAP_LOCK) {
            loadCapacity();
            return sCapacityEst < 0 ? "nominal" : "bms";
        }
    }

    /** Caller holds CAP_LOCK. */
    private static void loadCapacity() {
        if (sCapacityLoaded) {
            return;
        }
        sCapacityLoaded = true;
        java.io.File f = appFile(CAP_FILE);
        if (f == null || !f.isFile()) {
            return;
        }
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader(f));
            try {
                String[] f2 = r.readLine().trim().split(" ");
                int v = Integer.parseInt(f2[0]);
                if (v >= CAP_MIN_WH && v <= CAP_MAX_WH) {
                    sCapacityEst = v;
                    sCapacityGood = f2.length > 1 && "1".equals(f2[1]);
                    Log.i(TAG, "learned capacity loaded: " + v + " Wh"
                            + (sCapacityGood ? "" : " (low-SOC samples)"));
                }
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "capacity file unreadable: " + t);
        }
    }

    /** Caller holds CAP_LOCK. */
    private static void saveCapacity() {
        java.io.File f = appFile(CAP_FILE);
        if (f == null) {
            return;
        }
        try {
            java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
            java.io.FileWriter w = new java.io.FileWriter(tmp);
            try {
                w.write(Math.round(sCapacityEst) + (sCapacityGood ? " 1" : " 0") + "\n");
            } finally {
                w.close();
            }
            if (!tmp.renameTo(f)) {
                tmp.delete();
            }
        } catch (Throwable t) {
            Log.w(TAG, "capacity file not saved: " + t);
        }
    }

    /** A file in GPM's own files dir, or null without an app context. */
    static java.io.File appFile(String name) {
        try {
            android.content.Context ctx = EvCar.appContext();
            return ctx == null ? null : new java.io.File(ctx.getFilesDir(), name);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- template patching ---------------------------------------------------

    /** Overwrites a varint in place; never changes its width. */
    private static void writeFixedVarint(byte[] buf, int off, int value, int width) {
        for (int i = 0; i < width; i++) {
            int b = value & 0x7f;
            value >>>= 7;
            buf[off + i] = (byte) (i == width - 1 ? b : b | 0x80);
        }
    }

    private static void writeFloatLe(byte[] buf, int off, float v) {
        int bits = Float.floatToIntBits(v);
        for (int i = 0; i < 4; i++) {
            buf[off + i] = (byte) (bits >>> (8 * i));
        }
    }

    // ---- capacity scaling ------------------------------------------------------

    /**
     * The template with its charge curve (4.1) and charge-time tables (8.1)
     * scaled for capWh, cached until the capacity changes. Caller holds LOCK.
     * Falls back to the unscaled template if scaling fails, so a model is
     * still sent.
     */
    private static byte[] scaledTemplateLocked(int capWh) {
        if (sScaled == null || capWh != sScaledCapWh) {
            try {
                sScaled = scaleTemplate(sTemplate, capWh);
                Log.i(TAG, "charge curve/tables scaled to " + capWh + " Wh");
            } catch (Throwable t) {
                logTickFailure(t);
                sScaled = sTemplate;
            }
            sScaledCapWh = capWh;
        }
        return sScaled;
    }

    /**
     * A copy of template with every 4.1/8.1 energy axis and 8.1 seconds value
     * multiplied by k = capWh / CAPACITY_WH. Charging limits follow SOC (the
     * BMS tapers by cell voltage), so a smaller usable pack reaches the same
     * taper at the same SOC: same power, k x the energy per segment, k x the
     * time. Powers are untouched. Identity at k = 1.
     */
    static byte[] scaleTemplate(byte[] template, int capWh) {
        byte[] out = (byte[]) template.clone();
        long ref = EvModelTemplate.CAPACITY_WH;
        if (capWh == ref) {
            return out;
        }
        int width = EvModelTemplate.SCALE_WIDTH;
        int max = (1 << (7 * width)) - 1;
        int[] offs = EvModelTemplate.AXIS_OFFSETS;
        int[] wh = EvModelTemplate.AXIS_WH;
        for (int i = 0; i < offs.length; i++) {
            long v = (wh[i] * (long) capWh + ref / 2) / ref;
            writeFixedVarint(out, offs[i], (int) Math.min(v, max), width);
        }
        offs = EvModelTemplate.TIME_OFFSETS;
        int[] s = EvModelTemplate.TIME_S;
        for (int i = 0; i < offs.length; i++) {
            long v = (s[i] * (long) capWh + ref / 2) / ref;
            writeFixedVarint(out, offs[i], (int) Math.min(v, max), width);
        }
        return out;
    }

    /** Fixed-width varint at off, or -1 if it isn't exactly width bytes. */
    private static int readFixedVarint(byte[] buf, int off, int width) {
        int v = 0;
        for (int i = 0; i < width; i++) {
            int b = buf[off + i] & 0xff;
            if ((b & 0x80) == 0 ? i != width - 1 : i == width - 1) {
                return -1;
            }
            v |= (b & 0x7f) << (7 * i);
        }
        return v;
    }

    /**
     * The generated offsets match the template: placeholders, road A, and
     * every scaled varint holding its reference value.
     */
    static boolean checkPlaceholders(byte[] template) {
        try {
            if (readFixedVarint(template, EvModelTemplate.ENERGY_OFFSET,
                    EvModelTemplate.ENERGY_WIDTH) != EvModelTemplate.ENERGY_PLACEHOLDER_WH
                    || readFixedVarint(template, EvModelTemplate.CAPACITY_OFFSET,
                    EvModelTemplate.CAPACITY_WIDTH) != EvModelTemplate.CAPACITY_WH) {
                return false;
            }
            byte[] aWant = new byte[4];
            writeFloatLe(aWant, 0, EvModelTemplate.ROAD_A_N);
            for (int i = 0; i < 4; i++) {
                if (template[EvModelTemplate.ROAD_A_OFFSET + i] != aWant[i]) {
                    return false;
                }
            }
            int width = EvModelTemplate.SCALE_WIDTH;
            int[] offs = EvModelTemplate.AXIS_OFFSETS;
            if (offs.length != EvModelTemplate.AXIS_WH.length) {
                return false;
            }
            for (int i = 0; i < offs.length; i++) {
                if (readFixedVarint(template, offs[i], width) != EvModelTemplate.AXIS_WH[i]) {
                    return false;
                }
            }
            offs = EvModelTemplate.TIME_OFFSETS;
            if (offs.length != EvModelTemplate.TIME_S.length) {
                return false;
            }
            for (int i = 0; i < offs.length; i++) {
                if (readFixedVarint(template, offs[i], width) != EvModelTemplate.TIME_S[i]) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}

package com.google.android.projection.protocol;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import com.hkmc.cluster.NavigationCluster;
import com.hkmc.cluster.NavigationClusterListener;

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
 * Live data sources:
 *  - DISPLAY_SOC via binder com.hkmc.micom.hev txn 3
 *  - the nav battery packet via NavigationCluster.requestBatteryInfoReq():
 *    DATC_OUT_TEMP_SNSR patches model field 1.7 (aux/HVAC table lookup)
 *    and road load A (cold penalty, EvModelTemplate.COLD_*);
 *    XEV_MIN_BATT_TEMP / EV_AVLBL_ENRG / VCU_DTE_* are logged for later
 *    calibration (cold-pack charge caps, remaining-energy units)
 *  - EV_AVLBL_ENRG (BMS available energy, 2 Wh steps): current energy, and
 *    usable capacity learned as available energy / SOC (see learnCapacity)
 *  - BMS SOH via tm_hscan_service txn 0xd6: logged only (a health index,
 *    not usable capacity)
 *
 * Threading: all sends happen holding LOCK; stop() takes LOCK and joins the
 * thread, and shutdown() calls stop() before nativeShutdown(), so a send never
 * touches the native object after it is freed.
 */
public final class EvModelSender {
    private static final String TAG = "EvModelSender";
    private static final int PERIOD_MS = 5000;
    private static final long NAV_FRESH_MS = 30000;

    private static final int HEV_FLAG_DISPLAY_SOC = 0x19;  // SOC in 0.5% steps
    private static final int HEV_TX_GET_HEV_STATUS = 3;
    private static final String HEV_SERVICE = "com.hkmc.micom.hev";
    private static final String HEV_TOKEN = "com.hkmc.hev.IHEVService";

    // BMS SOH (whole percent; 95 on the owner's car). A health index
    // (internal resistance, balance), NOT usable capacity: only logged.
    private static final String HCAN_SERVICE = "tm_hscan_service";
    private static final String HCAN_TOKEN =
            "com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService";
    private static final int HCAN_TX_GET_SOH = 0xd6;  // getXEV_Bms_GDS_SOH

    // Usable capacity is learned from the BMS available energy (nav packet
    // EV_AVLBL_ENRG, 2 Wh steps like BMS_EVENERGYCAPA) divided by display
    // SOC. 14462 at 46.0% on the owner's car = 28.9 kWh -> 62.9 kWh usable.
    // Only learned at >= CAP_MIN_HALF_PCT, where SOC's 0.5% step costs
    // < 2.5%; smoothed; kept across sessions in CAP_FILE.
    private static final int ENRG_WH_PER_UNIT = 2;
    private static final int CAP_MIN_HALF_PCT = 40;      // 20% SOC
    private static final int CAP_MIN_WH = 30000;
    private static final int CAP_MAX_WH = 90000;
    private static final float CAP_ALPHA = 0.1f;         // EMA weight per sample
    private static final String CAP_FILE = "capacity_wh";
    private static float sCapacityEst = -1f;             // <0: none yet
    private static boolean sCapacityLoaded;

    // Navigation.BATTERY_INFO_RX packet indices (see
    // com.hkmc.micom.protocol.Navigation$BATTERY_INFO_RX).
    private static final int IDX_MIN_BATT_TEMP = 1;   // XEV_MIN_BATT_TEMP, C
    private static final int IDX_EV_AVLBL_ENRG = 2;   // EV_AVLBL_ENRG, 2 Wh steps
    private static final int IDX_OUT_TEMP = 4;        // DATC_OUT_TEMP_SNSR, C
    private static final int IDX_DTE_BASE = 6;        // VCU_DTE_BASE
    private static final int IDX_DTE_ADD = 7;         // VCU_DTE_ADD

    private static final int MIN_ENERGY_WH = 500;  // Maps drops models with 0

    // Outside temp sanity range (C); the raw DATC value's scaling is not
    // confirmed, so anything outside this is treated as unknown.
    private static final int OUT_TEMP_MIN_C = -45;
    private static final int OUT_TEMP_MAX_C = 55;
    private static final int COLD_HYST_C = 3;
    private static int sColdTempC = Integer.MIN_VALUE;  // temp A is based on

    private static final Object LOCK = new Object();
    private static Thread sThread;
    private static boolean sRunning;
    private static SensorSource sSource;
    private static byte[] sTemplate;
    private static IBinder sHev;
    private static IBinder sHcan;
    private static int sLastGoodHalfPct = -1;
    private static int sSohRaw = Integer.MIN_VALUE;      // read once per process

    // nav battery packet state (written by the cluster callback thread)
    private static NavigationCluster sNavCluster;
    private static volatile long sNavPacketAt;
    private static volatile int sPackTempC = Integer.MIN_VALUE;
    private static volatile int sOutsideTempC = Integer.MIN_VALUE;
    private static volatile int sOutTempRaw = Integer.MIN_VALUE;
    private static volatile int sAvlblEnrg = -1;
    private static volatile int sDteBase = -1;
    private static volatile int sDteAdd = -1;

    private EvModelSender() {}

    public static void start(SensorSource source) {
        synchronized (LOCK) {
            sSource = source;
            if (sTemplate == null) {
                sTemplate = Base64.decode(EvModelTemplate.TEMPLATE_B64,
                        Base64.DEFAULT);
                if (!checkPlaceholders()) {
                    Log.e(TAG, "template placeholder mismatch, not starting");
                    sTemplate = null;
                    sSource = null;
                    return;
                }
            }
            initNavClusterLocked();
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
            if (sNavCluster != null) {
                try {
                    sNavCluster.removeListener(sNavListener);
                } catch (Throwable ignored) {
                }
                sNavCluster = null;
            }
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

    private static void loop() {
        while (true) {
            synchronized (LOCK) {
                if (!sRunning) {
                    return;
                }
                if (sSource != null) {
                    pollNavClusterLocked();
                    int halfPct = displaySocHalfPct();
                    int availWh = availableEnergyWh();
                    learnCapacity(halfPct, availWh);
                    int cap = capacityWh();
                    int wh = currentEnergyWh(halfPct, availWh, cap);
                    byte[] msg = (byte[]) sTemplate.clone();
                    writeFixedVarint(msg, EvModelTemplate.CAPACITY_OFFSET, cap,
                            EvModelTemplate.CAPACITY_WIDTH);
                    writeFixedVarint(msg, EvModelTemplate.ENERGY_OFFSET, wh,
                            EvModelTemplate.ENERGY_WIDTH);
                    int outC = outsideTempC();
                    if (outC != Integer.MIN_VALUE) {
                        writeFloatLe(msg, EvModelTemplate.TEMP_OFFSET,
                                (float) outC);
                    }
                    float roadA = coldRoadLoadA(outC);
                    writeFloatLe(msg, EvModelTemplate.ROAD_A_OFFSET, roadA);
                    int rc = sSource.sendRawMessage(msg);
                    Log.i(TAG, "energy model sent: " + wh + " Wh of " + cap
                            + " Wh (" + capacitySource() + "), soc=" + halfPct
                            + "/200, rc=" + rc + ", pack=" + packTempStr()
                            + "C, out=" + (outC == Integer.MIN_VALUE ? "?" : outC)
                            + "C (raw " + sOutTempRaw + "), A=" + Math.round(roadA)
                            + "N, enrg=" + sAvlblEnrg
                            + ", dte=" + sDteBase + "+" + sDteAdd
                            + ", soh=" + sohRaw());
                }
            }
            try {
                Thread.sleep(PERIOD_MS);
            } catch (InterruptedException e) {
                // ignore
            }
        }
    }

    // ---- nav battery packet ------------------------------------------------

    private static final NavigationClusterListener sNavListener =
            new NavigationClusterListener() {
                @Override
                public void onHandleBatteryInfoResp(int[] d) {
                    if (d == null || d.length <= IDX_DTE_ADD) {
                        return;
                    }
                    sNavPacketAt = SystemClock.uptimeMillis();
                    sPackTempC = (byte) d[IDX_MIN_BATT_TEMP];
                    sOutsideTempC = (byte) d[IDX_OUT_TEMP];
                    sOutTempRaw = d[IDX_OUT_TEMP];
                    sAvlblEnrg = d[IDX_EV_AVLBL_ENRG];
                    sDteBase = d[IDX_DTE_BASE];
                    sDteAdd = d[IDX_DTE_ADD];
                }
            };

    private static void initNavClusterLocked() {
        if (sNavCluster != null) {
            return;
        }
        try {
            Context ctx = (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
            if (ctx == null) {
                Log.w(TAG, "no application context yet");
                return;
            }
            NavigationCluster nc = new NavigationCluster(ctx);
            nc.addListener(sNavListener);
            sNavCluster = nc;
            Log.i(TAG, "nav cluster listener registered");
        } catch (Throwable t) {
            Log.w(TAG, "nav cluster unavailable: " + t);
            sNavCluster = null;
        }
    }

    /** Caller holds LOCK. */
    private static void pollNavClusterLocked() {
        if (sNavCluster == null) {
            initNavClusterLocked();
        }
        if (sNavCluster != null) {
            try {
                sNavCluster.requestBatteryInfoReq();
            } catch (Throwable t) {
                sNavCluster = null;
            }
        }
    }

    private static boolean navFresh() {
        return sNavPacketAt != 0
                && SystemClock.uptimeMillis() - sNavPacketAt < NAV_FRESH_MS;
    }

    private static String packTempStr() {
        return navFresh() ? String.valueOf(sPackTempC) : "?";
    }

    private static int outsideTempC() {
        if (!navFresh() || sOutsideTempC < OUT_TEMP_MIN_C
                || sOutsideTempC > OUT_TEMP_MAX_C) {
            return Integer.MIN_VALUE;
        }
        return sOutsideTempC;
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
        int halfPct = getHevStatus(HEV_FLAG_DISPLAY_SOC);
        if (halfPct < 0 || halfPct > 200) {
            return sLastGoodHalfPct;
        }
        sLastGoodHalfPct = halfPct;
        return halfPct;
    }

    /** BMS available energy in Wh from a fresh nav packet, -1 if none. */
    private static int availableEnergyWh() {
        if (!navFresh() || sAvlblEnrg <= 0) {
            return -1;
        }
        int wh = sAvlblEnrg * ENRG_WH_PER_UNIT;
        return wh <= CAP_MAX_WH ? wh : -1;
    }

    /**
     * Current energy in Wh: the BMS available energy when known (finer than
     * SOC), else SOC x capacity. Never below MIN_ENERGY_WH.
     */
    private static int currentEnergyWh(int halfPct, int availWh, int capacityWh) {
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
        loadCapacity();
        if (availWh <= 0 || halfPct < CAP_MIN_HALF_PCT) {
            return;
        }
        float sample = (float) availWh * 200f / halfPct;
        if (sample < CAP_MIN_WH || sample > CAP_MAX_WH) {
            return;
        }
        int before = Math.round(sCapacityEst);
        sCapacityEst = sCapacityEst < 0 ? sample
                : sCapacityEst + CAP_ALPHA * (sample - sCapacityEst);
        if (Math.abs(Math.round(sCapacityEst) - before) >= 100) {
            saveCapacity();
        }
    }

    /** Usable capacity in Wh, rounded to 100 Wh; nominal until learned. */
    private static int capacityWh() {
        if (sCapacityEst < 0) {
            return EvModelTemplate.CAPACITY_WH;
        }
        return Math.round(sCapacityEst / 100f) * 100;
    }

    static String capacitySource() {
        return sCapacityEst < 0 ? "nominal" : "bms";
    }

    static int sohRaw() {
        if (sSohRaw == Integer.MIN_VALUE) {
            sSohRaw = getSohRaw();
            Log.i(TAG, "BMS SOH raw: " + sSohRaw);
        }
        return sSohRaw;
    }

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
                int v = Integer.parseInt(r.readLine().trim());
                if (v >= CAP_MIN_WH && v <= CAP_MAX_WH) {
                    sCapacityEst = v;
                    Log.i(TAG, "learned capacity loaded: " + v + " Wh");
                }
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "capacity file unreadable: " + t);
        }
    }

    private static void saveCapacity() {
        java.io.File f = appFile(CAP_FILE);
        if (f == null) {
            return;
        }
        try {
            java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
            java.io.FileWriter w = new java.io.FileWriter(tmp);
            try {
                w.write(Math.round(sCapacityEst) + "\n");
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
            Context ctx = (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
            return ctx == null ? null : new java.io.File(ctx.getFilesDir(), name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Raw getXEV_Bms_GDS_SOH() (transaction 0xd6), -1 on failure. */
    private static int getSohRaw() {
        try {
            if (sHcan == null) {
                sHcan = (IBinder) Class.forName("android.os.ServiceManager")
                        .getMethod("getService", String.class)
                        .invoke(null, HCAN_SERVICE);
                if (sHcan == null) {
                    return -1;
                }
            }
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(HCAN_TOKEN);
                if (!sHcan.transact(HCAN_TX_GET_SOH, data, reply, 0)) {
                    return -1;
                }
                reply.readException();
                return reply.readInt();
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable t) {
            sHcan = null;
            return -1;
        }
    }

    /** IHEVService.getHEVStatus(flag) via binder transaction 3, -1 on failure. */
    private static int getHevStatus(int flag) {
        try {
            if (sHev == null) {
                sHev = (IBinder) Class.forName("android.os.ServiceManager")
                        .getMethod("getService", String.class)
                        .invoke(null, HEV_SERVICE);
                if (sHev == null) {
                    return -1;
                }
            }
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(HEV_TOKEN);
                data.writeInt(flag);
                if (!sHev.transact(HEV_TX_GET_HEV_STATUS, data, reply, 0)) {
                    return -1;
                }
                reply.readException();
                return reply.readInt();
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable t) {
            sHev = null;
            return -1;
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

    private static boolean checkPlaceholders() {
        byte[] want = new byte[EvModelTemplate.ENERGY_WIDTH];
        writeFixedVarint(want, 0, EvModelTemplate.ENERGY_PLACEHOLDER_WH,
                EvModelTemplate.ENERGY_WIDTH);
        for (int i = 0; i < want.length; i++) {
            if (sTemplate[EvModelTemplate.ENERGY_OFFSET + i] != want[i]) {
                return false;
            }
        }
        byte[] capWant = new byte[EvModelTemplate.CAPACITY_WIDTH];
        writeFixedVarint(capWant, 0, EvModelTemplate.CAPACITY_WH,
                EvModelTemplate.CAPACITY_WIDTH);
        for (int i = 0; i < capWant.length; i++) {
            if (sTemplate[EvModelTemplate.CAPACITY_OFFSET + i] != capWant[i]) {
                return false;
            }
        }
        byte[] aWant = new byte[4];
        writeFloatLe(aWant, 0, EvModelTemplate.ROAD_A_N);
        for (int i = 0; i < 4; i++) {
            if (sTemplate[EvModelTemplate.ROAD_A_OFFSET + i] != aWant[i]) {
                return false;
            }
        }
        return true;
    }
}

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
 *    usable capacity learned as available energy / SOC (see learnCapacity);
 *    the charge curve and charge-time tables are rescaled to that capacity
 *    so their taper stays at the same SOC (see scaleTemplate)
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

    // BMS remaining usable energy: HEV flag 0x31 ENERGYMAP_1000MS, the same
    // signal as the nav packet's EV_AVLBL_ENRG (docs/DATA-SOURCES.md 1.1), in
    // 2 Wh steps like BMS_EVENERGYCAPA; 0xFFFF = invalid. Usable capacity is
    // learned as energy / display SOC: 14462 at 46.0% on the owner's car =
    // 28.9 kWh -> ~62.9 kWh. Energy reaches 0 near display 2%, so low-SOC
    // samples read a little low: samples at >= CAP_GOOD_HALF_PCT win, lower
    // ones (>= CAP_MIN_HALF_PCT) only fill in until one exists. Smoothed;
    // kept across sessions in CAP_FILE ("<Wh> <1 if good>").
    private static final int HEV_FLAG_ENERGYMAP = 0x31;
    private static final int ENRG_WH_PER_UNIT = 2;
    private static final int ENRG_INVALID = 0xffff;
    private static final int CAP_MIN_HALF_PCT = 60;      // 30% SOC
    private static final int CAP_GOOD_HALF_PCT = 120;    // 60% SOC
    private static final int CAP_MIN_WH = 30000;
    private static final int CAP_MAX_WH = 90000;
    private static final float CAP_ALPHA = 0.1f;         // EMA weight per sample
    private static final String CAP_FILE = "capacity_wh";
    private static float sCapacityEst = -1f;             // <0: none yet
    private static boolean sCapacityGood;                // from >= 60% samples
    private static boolean sCapacityLoaded;
    private static int sEnergyRaw = -1;                  // last valid 0x31 raw

    // Logged-only HEV flags (raw; docs/DATA-SOURCES.md 1.2, 5, 6): trip sums
    // reset each drive. Order = EvLogger columns trip_* / hvac_raw / charge.
    private static final int[] LOG_FLAGS = {
        0x58,  // XEV_BATPWRSUM_KWH     trip battery energy, 0.1 kWh
        0x42,  // DATC_CLMTPWRSUM_W     trip climate energy
        0x49,  // LDC_LOADPWRSUMVAL     trip 12 V / electronics energy
        0x44,  // XEV_BATHEATPWRSUM_WH  trip battery heater energy
        0x47,  // MCU_MG1MOTPOWER_WH    trip front motor drive energy
        0x48,  // MCU_MG2MOTPOWER_WH    trip rear motor drive energy
        0x59,  // MCU_MG1GENPOWER_WH    trip front regen energy
        0x5a,  // MCU_MG2GENPOWER_WH    trip rear regen energy
        0x4f,  // DATC_CLMTPWRCNSMPTN_W live climate power (~20 W/unit)
        0x07,  // CHARGE_STATE          3 = not charging
    };
    private static final int HEV_FLAG_ODOMETER = 0x1f;   // raw, 0.1 km
    private static final int HCAN_TX_GET_CAR_SPEED = 0x0b;  // getCarSpeed(B)F
    private static final int HCAN_TX_GET_VIN = 0x2f;        // getVIN()String
    private static int sOdoStartRaw = -1;
    private static String sVinModel;                     // null = not read yet

    // Navigation.BATTERY_INFO_RX packet indices (see
    // com.hkmc.micom.protocol.Navigation$BATTERY_INFO_RX).
    private static final int IDX_MIN_BATT_TEMP = 1;   // XEV_MIN_BATT_TEMP, C
    private static final int IDX_EV_AVLBL_ENRG = 2;   // EV_AVLBL_ENRG, 2 Wh steps
    private static final int IDX_OUT_TEMP = 4;        // DATC_OUT_TEMP_SNSR raw
    private static final int IDX_DTE_BASE = 6;        // VCU_DTE_BASE, km
    private static final int IDX_DTE_ADD = 7;         // VCU_DTE_ADD, km

    private static final int MIN_ENERGY_WH = 500;  // Maps drops models with 0

    // Outside temp: unsigned raw x 0.5 - 40 C (Hyundai's own decode of the
    // DATC signal, docs/DATA-SOURCES.md 2); 0xFE/0xFF = invalid.
    private static final int COLD_HYST_C = 3;
    private static int sColdTempC = Integer.MIN_VALUE;  // temp A is based on

    private static final Object LOCK = new Object();
    private static Thread sThread;
    private static boolean sRunning;
    private static SensorSource sSource;
    private static byte[] sTemplate;
    private static byte[] sScaled;          // sTemplate scaled for sScaledCapWh
    private static int sScaledCapWh = -1;
    private static IBinder sHev;
    private static IBinder sHcan;
    private static int sLastGoodHalfPct = -1;
    private static int sSohRaw = Integer.MIN_VALUE;      // until a value > 0

    // nav battery packet state (written by the cluster callback thread)
    private static NavigationCluster sNavCluster;
    private static volatile long sNavPacketAt;
    private static volatile int sPackTempC = Integer.MIN_VALUE;
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
                if (!checkPlaceholders(sTemplate)) {
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
            EvLogger.startSession(SystemClock.uptimeMillis());
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
            EvLogger.endSession();
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
     * One send (and log row). Runs inside GPM: anything it throws is caught
     * by loop(), because an uncaught exception here kills GPM and with it
     * Android Auto.
     */
    private static void tickLocked() {
                    pollNavClusterLocked();
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
                    float outC = outsideTempC();
                    boolean outOk = !Float.isNaN(outC);
                    if (outOk) {
                        writeFloatLe(msg, EvModelTemplate.TEMP_OFFSET, outC);
                    }
                    float roadA = coldRoadLoadA(outOk ? Math.round(outC)
                            : Integer.MIN_VALUE);
                    writeFloatLe(msg, EvModelTemplate.ROAD_A_OFFSET, roadA);
                    int rc = sSource.sendRawMessage(msg);
                    Log.i(TAG, "energy model sent: " + wh + " Wh of " + cap
                            + " Wh (" + capacitySource() + "), soc=" + halfPct
                            + "/200, enrg=" + sEnergyRaw + ", rc=" + rc
                            + ", pack=" + packTempStr()
                            + "C, out=" + (outOk ? String.valueOf(outC) : "?")
                            + "C (raw " + sOutTempRaw + "), A=" + Math.round(roadA)
                            + "N, dte=" + sDteBase + "+" + sDteAdd + "km");
                    try {
                        logRow(halfPct, wh, cap, outOk ? Float.valueOf(outC) : null,
                                roadA);
                    } catch (Throwable t) {
                        logTickFailure(t);
                    }
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
                    sOutTempRaw = d[IDX_OUT_TEMP] & 0xff;
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

    /** Outside temp in C from a fresh nav packet, NaN if unknown. */
    private static float outsideTempC() {
        int raw = sOutTempRaw;
        if (!navFresh() || raw < 0 || raw >= 0xfe) {
            return Float.NaN;
        }
        return raw * 0.5f - 40f;
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

    /**
     * BMS remaining usable energy in Wh (HEV 0x31, falling back to a fresh
     * nav packet), -1 if neither has a valid value.
     */
    private static int availableEnergyWh() {
        int raw = getHevStatus(HEV_FLAG_ENERGYMAP);
        if (raw <= 0 || raw >= ENRG_INVALID) {
            raw = navFresh() ? sAvlblEnrg : -1;
        }
        if (raw <= 0 || raw >= ENRG_INVALID || raw * ENRG_WH_PER_UNIT > CAP_MAX_WH) {
            sEnergyRaw = -1;
            return -1;
        }
        sEnergyRaw = raw;
        return raw * ENRG_WH_PER_UNIT;
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

    /** BMS SOH raw; re-read until it reports a value (it reads 0 at boot). */
    static int sohRaw() {
        if (sSohRaw <= 0) {
            int v = getSohRaw();
            if (v != sSohRaw) {
                Log.i(TAG, "BMS SOH raw: " + v);
            }
            sSohRaw = v;
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
            Context ctx = (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
            return ctx == null ? null : new java.io.File(ctx.getFilesDir(), name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** One EvLogger row (columns in EvLogger.COLUMNS order). */
    private static void logRow(int halfPct, int wh, int cap, Float outC, float roadA) {
        boolean fresh = navFresh();
        int odo = getHevStatus(HEV_FLAG_ODOMETER);
        if (odo > 0 && sOdoStartRaw < 0) {
            sOdoStartRaw = odo;
        }
        Object odoDelta = odo > 0 && odo >= sOdoStartRaw
                ? Float.valueOf((odo - sOdoStartRaw) / 10f) : null;
        float speed = hscanFloat(HCAN_TX_GET_CAR_SPEED, 0);  // 0 = km/h
        Object[] v = new Object[EvLogger.COLUMNS.length - 1];  // all but t_s
        int i = 0;
        v[i++] = halfPct >= 0 ? Integer.valueOf(halfPct) : null;
        v[i++] = Integer.valueOf(wh);
        v[i++] = Integer.valueOf(cap);
        v[i++] = sEnergyRaw > 0 ? Integer.valueOf(sEnergyRaw) : null;
        v[i++] = fresh && sOutTempRaw >= 0 ? Integer.valueOf(sOutTempRaw) : null;
        v[i++] = outC;
        v[i++] = fresh ? Integer.valueOf(sPackTempC) : null;
        v[i++] = fresh && sDteBase >= 0 ? Integer.valueOf(sDteBase + sDteAdd) : null;
        v[i++] = Integer.valueOf(Math.round(roadA));
        v[i++] = sColdTempC != Integer.MIN_VALUE ? Integer.valueOf(sColdTempC) : null;
        int soh = sohRaw();
        v[i++] = soh > 0 ? Integer.valueOf(soh) : null;
        v[i++] = EvModelTemplate.MODEL_REV;
        v[i++] = Float.isNaN(speed) || speed < 0 ? null : Float.valueOf(speed);
        v[i++] = odoDelta;
        for (int f : LOG_FLAGS) {
            int r = getHevStatus(f);
            v[i++] = r >= 0 ? Integer.valueOf(r) : null;
        }
        boolean nav = Precond.navActive();
        int remM = Precond.remainingMeters();
        int remS = Precond.remainingSeconds();
        int kw = Precond.chargerKw();
        v[i++] = Integer.valueOf(nav ? 1 : 0);
        v[i++] = nav && remM >= 0 ? Integer.valueOf(remM) : null;
        v[i++] = nav && remS >= 0 ? Integer.valueOf(remS) : null;
        v[i++] = nav ? Integer.valueOf(Precond.isCharger() ? 1 : 0) : null;
        v[i++] = nav && kw >= 0 ? Integer.valueOf(kw) : null;
        v[i++] = Integer.valueOf(Precond.state());
        if (i != v.length) {
            throw new IllegalStateException("log row has " + i + " values for "
                    + v.length + " columns");
        }
        EvLogger.row(SystemClock.uptimeMillis(), v);
    }

    /**
     * VIN characters 1-8 and 10 (make, model, body, motor, model year) for
     * the log header; never the check digit, plant or serial. "" if unknown.
     */
    static String vinModel() {
        if (sVinModel == null) {
            String vin = hscanString(HCAN_TX_GET_VIN);
            sVinModel = vin != null && vin.matches("[A-HJ-NPR-Z0-9]{17}")
                    ? vin.substring(0, 8) + vin.charAt(9) : "";
        }
        return sVinModel;
    }

    private static IBinder hscan() throws Exception {
        if (sHcan == null) {
            sHcan = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, HCAN_SERVICE);
        }
        return sHcan;
    }

    /** hscan float getter with one byte argument, NaN on failure. */
    private static float hscanFloat(int txn, int arg) {
        try {
            IBinder b = hscan();
            if (b == null) {
                return Float.NaN;
            }
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(HCAN_TOKEN);
                data.writeByte((byte) arg);
                if (!b.transact(txn, data, reply, 0)) {
                    return Float.NaN;
                }
                reply.readException();
                return reply.readFloat();
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable t) {
            sHcan = null;
            return Float.NaN;
        }
    }

    /** hscan String getter, null on failure. */
    private static String hscanString(int txn) {
        try {
            IBinder b = hscan();
            if (b == null) {
                return null;
            }
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(HCAN_TOKEN);
                if (!b.transact(txn, data, reply, 0)) {
                    return null;
                }
                reply.readException();
                return reply.readString();
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Throwable t) {
            sHcan = null;
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
     * time. The thermal pause (TIME_FIXED_S) is a cooling stop and not
     * scaled. Powers are untouched. Identity at k = 1.
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
        int[] fixed = EvModelTemplate.TIME_FIXED_S;
        for (int i = 0; i < offs.length; i++) {
            long v = ((s[i] - fixed[i]) * (long) capWh + ref / 2) / ref + fixed[i];
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
            if (offs.length != EvModelTemplate.TIME_S.length
                    || offs.length != EvModelTemplate.TIME_FIXED_S.length) {
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

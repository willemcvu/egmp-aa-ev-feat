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
 *  - DISPLAY_SOC via binder com.hkmc.micom.hev txn 3 (energy = SOC x capacity)
 *  - the nav battery packet via NavigationCluster.requestBatteryInfoReq():
 *    DATC_OUT_TEMP_SNSR patches model field 1.7 (aux/HVAC table lookup);
 *    XEV_MIN_BATT_TEMP / EV_AVLBL_ENRG / VCU_DTE_* are logged for later
 *    calibration (cold-pack charge caps, remaining-energy units)
 *  - SOH probe via tm_hscan_service txn 0xd6 (capacity, gated by
 *    persist.gpm.ev.sohcap=1; raw value always logged once per session)
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

    // Battery SOH probe (degradation-aware capacity). Unverified transaction:
    // the raw value is always logged; it is only USED when
    // persist.gpm.ev.sohcap=1 and the value is in 0.1% units in [500, 1000].
    private static final String HCAN_SERVICE = "tm_hscan_service";
    private static final String HCAN_TOKEN =
            "com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService";
    private static final int HCAN_TX_GET_SOH = 0xd6;  // getXEV_Bms_GDS_SOH
    private static final String PROP_SOH_CAP = "persist.gpm.ev.sohcap";

    // Navigation.BATTERY_INFO_RX packet indices (see
    // com.hkmc.micom.protocol.Navigation$BATTERY_INFO_RX).
    private static final int IDX_MIN_BATT_TEMP = 1;   // XEV_MIN_BATT_TEMP, C
    private static final int IDX_EV_AVLBL_ENRG = 2;   // EV_AVLBL_ENRG, units TBD
    private static final int IDX_OUT_TEMP = 4;        // DATC_OUT_TEMP_SNSR, C
    private static final int IDX_DTE_BASE = 6;        // VCU_DTE_BASE
    private static final int IDX_DTE_ADD = 7;         // VCU_DTE_ADD

    private static final int MIN_ENERGY_WH = 500;  // Maps drops models with 0

    private static final Object LOCK = new Object();
    private static Thread sThread;
    private static boolean sRunning;
    private static SensorSource sSource;
    private static byte[] sTemplate;
    private static IBinder sHev;
    private static IBinder sHcan;
    private static int sLastGoodHalfPct = -1;
    private static int sCapacityWh = -1;  // <0: not probed yet

    // nav battery packet state (written by the cluster callback thread)
    private static NavigationCluster sNavCluster;
    private static volatile long sNavPacketAt;
    private static volatile int sPackTempC = Integer.MIN_VALUE;
    private static volatile int sOutsideTempC = Integer.MIN_VALUE;
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
                    int cap = capacityWh();
                    int wh = currentEnergyWh(cap);
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
                    int rc = sSource.sendRawMessage(msg);
                    Log.i(TAG, "energy model sent: " + wh + " Wh of " + cap
                            + " Wh, rc=" + rc + ", pack=" + packTempStr()
                            + "C, out=" + (outC == Integer.MIN_VALUE ? "?" : outC)
                            + "C, enrg=" + sAvlblEnrg
                            + ", dte=" + sDteBase + "+" + sDteAdd);
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
        return navFresh() ? sOutsideTempC : Integer.MIN_VALUE;
    }

    // ---- energy/capacity ---------------------------------------------------

    /** Current battery energy in Wh from DISPLAY_SOC (0.5% steps) x capacity. */
    private static int currentEnergyWh(int capacityWh) {
        int halfPct = getHevStatus(HEV_FLAG_DISPLAY_SOC);
        if (halfPct < 0 || halfPct > 200) {
            halfPct = sLastGoodHalfPct >= 0
                    ? sLastGoodHalfPct : EvModelTemplate.ENERGY_PLACEHOLDER_WH * 200
                            / capacityWh;
        } else {
            sLastGoodHalfPct = halfPct;
        }
        int wh = (int) ((long) halfPct * capacityWh / 200);
        return Math.max(wh, MIN_ENERGY_WH);
    }

    /**
     * Usable capacity in Wh: nominal unless the SOH probe is enabled
     * (persist.gpm.ev.sohcap=1) and returns a sane 0.1% value.
     */
    private static int capacityWh() {
        if (sCapacityWh < 0) {
            sCapacityWh = probeCapacityWh();
        }
        return sCapacityWh;
    }

    private static int probeCapacityWh() {
        int nominal = EvModelTemplate.CAPACITY_WH;
        int raw = getSohRaw();
        Log.i(TAG, "SOH probe raw value: " + raw
                + (isSohCapEnabled() ? " (sohcap enabled)" : " (sohcap disabled)"));
        if (!isSohCapEnabled() || raw < 500 || raw > 1000) {
            return nominal;
        }
        int cap = (int) ((long) nominal * raw / 1000);
        Log.i(TAG, "using SOH-derived capacity: " + cap + " Wh");
        return cap;
    }

    private static boolean isSohCapEnabled() {
        try {
            String v = (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, PROP_SOH_CAP, "0");
            return "1".equals(v);
        } catch (Throwable t) {
            return false;
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
        return true;
    }
}

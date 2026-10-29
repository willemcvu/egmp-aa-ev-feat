package com.google.android.projection.protocol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Always-on EV log (docs/LOGGING.md, format v2): logs whenever the head unit
 * is on, with or without Android Auto.
 *
 * Started when the GPM process starts (patched StartReceiver.onReceive and
 * GalReceiverService.onCreate, see tools/patch_gpm_smali.py; also from
 * EvModelSender.start as a fallback). GPM is a persistent system app, so it
 * runs from boot until the head unit sleeps or powers off.
 *
 * One thread: samples energy and speed every SAMPLE_MS, builds one row every
 * ROW_MS (EvRowAgg aggregates the samples; everything else is read once per
 * row), keeps rows in memory and writes them every FLUSH_MS and on a clean
 * stop (GalReceiverService.onDestroy, ACTION_SHUTDOWN) through EvLogger,
 * which enforces the file and folder caps.
 *
 * One file per head-unit "on" period: the first flush after process start
 * opens a new file, and so does the first one after a gap of more than
 * GAP_MS between samples (the head unit was asleep; the rows from before the
 * gap go to the old file first). aa_active says whether an Android Auto
 * session (EvModelSender) was running.
 *
 * Technical data only: no location (GPS gives only the altitude change since
 * the file started), no addresses or names, no wall-clock times (the file's
 * UTC date only), no full VIN.
 *
 * Never throws out of its entry points: this runs inside GPM, where an
 * uncaught exception kills Android Auto.
 */
public final class EvDriveLog {
    private static final String TAG = "EvDriveLog";
    static final String PKG = "aa.ev.model 0.2.1";
    static final String MAGIC = "# egmp-aa-ev-log v2";

    static final long SAMPLE_MS = 1000;
    static final long ROW_MS = 5000;
    static final long FLUSH_MS = 60000;
    static final long GAP_MS = 30000;
    // Row reads may reuse a value the energy-model sender read within 1.5 s;
    // the 1 s samples must be fresh each second.
    private static final long READ_MAX_AGE_MS = 1500;
    private static final long SAMPLE_MAX_AGE_MS = 500;

    // Logged-only HEV flags (raw; docs/DATA-SOURCES.md 1.2, 5, 6): trip sums
    // reset each drive. Order = columns trip_* / hvac_raw / charge_state_raw.
    private static final int[] TRIP_FLAGS = {
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
    // v2 HEV flags, raw. Order = columns bheat_pwr_raw ... obc_ac_pwr_raw.
    private static final int[] V2_FLAGS = {
        0x43,  // XEV_BATHEATPWRMONCON_W  live battery heater power
        0x28,  // FAST_CHARGING
        0x1d,  // CHG_STS
        0x0f,  // quick-charge remaining time, min (matches the dash)
        0x22,  // TARGET_SOC AC, 0.5 % steps (200 = 100 %)
        0x23,  // TARGET_SOC DC, 0.5 % steps
        0x52,  // UNIFICATION_CHARGING_CURRENT
        0x53,  // OBC_ACPWRDIS
    };
    private static final int HEV_SOC = 0x19;
    private static final int HEV_ODOMETER = 0x1f;     // raw, 0.1 km
    private static final int HEV_VCMS_PWR = 0x6c;     // VCMS_PWRDIS
    private static final int VCMS_INVALID = 0x7fff;

    // Column order of this writer. Every name must be on the format's
    // allowlist (tools/evlog_schema.json); empty = unknown.
    static final String[] COLUMNS = {
        // v1
        "t_s", "soc_half_pct", "energy_wh", "capacity_wh", "avlbl_enrg_raw",
        "out_temp_raw", "out_temp_c", "pack_min_c", "dte_raw", "road_a_n",
        "aux_table_c", "soh_raw", "model_rev", "speed_kph", "odo_delta_km",
        "trip_batt_raw", "trip_clim_raw", "trip_ldc_raw", "trip_bheat_raw",
        "trip_mot1_raw", "trip_mot2_raw", "trip_gen1_raw", "trip_gen2_raw",
        "hvac_raw", "charge_state_raw", "nav_active", "nav_rem_m", "nav_rem_s",
        "nav_charger", "nav_charger_kw", "precond_state",
        // v2
        "aa_active", "energy_rate_w", "speed_avg_kph", "speed_max_kph",
        "alt_delta_m", "drive_mode_raw",
        "bheat_pwr_raw", "fast_charging_raw", "chg_sts_raw", "chg_quick_rem_min",
        "target_soc_ac_half_pct", "target_soc_dc_half_pct", "chg_current_raw",
        "obc_ac_pwr_raw", "vcms_pwr_raw",
        "clim_set_raw", "clim_set_c", "clim_unit_raw", "clim_blower_raw",
        "clim_mode_raw", "clim_ac_raw", "clim_auto_raw", "clim_op_raw",
        "nav_charger_dep_wh", "nav_charger_chg_s",
    };

    private static final Object LOCK = new Object();
    private static Thread sThread;
    private static EvLogger sLogger;            // LOCK
    private static volatile String sPrefix;     // file name prefix for the next new file
    private static volatile String sHeader;     // header for the next new file

    // logger-thread state
    private static final EvRowAgg sAgg = new EvRowAgg();
    private static long sFileStart;             // 0 = no file period yet
    private static long sLastStep;
    private static long sNextRow;
    private static long sNextFlush;
    private static int sOdoStartRaw = -1;
    private static double sAltStart = Double.NaN;
    private static int sClimUnit = Integer.MIN_VALUE;
    private static int sClimCelsius = -1;
    private static int sFailures;

    private EvDriveLog() {}

    // ---- entry points (any thread, never throw) ----------------------------------

    /** Start the logger thread once per process. */
    public static void start() {
        try {
            synchronized (LOCK) {
                if (sThread != null) {
                    return;
                }
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        loop();
                    }
                }, TAG);
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                sThread = t;
                t.start();
            }
            registerShutdown();
            Log.i(TAG, "started");
        } catch (Throwable t) {
            try {
                Log.w(TAG, "not started: " + t);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Write the buffered rows now (clean stop). */
    public static void flush() {
        try {
            synchronized (LOCK) {
                if (sLogger != null && sPrefix != null && sHeader != null) {
                    sLogger.flush(sPrefix, sHeader);
                }
            }
        } catch (Throwable t) {
            // never disturb GPM
        }
    }

    private static void registerShutdown() {
        try {
            Context ctx = EvCar.appContext();
            if (ctx == null) {
                return;
            }
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    flush();
                }
            }, new IntentFilter(Intent.ACTION_SHUTDOWN));
        } catch (Throwable t) {
            Log.w(TAG, "shutdown receiver not registered: " + t);
        }
    }

    // ---- logger thread -----------------------------------------------------------

    private static void loop() {
        long next = EvCar.now();
        while (true) {
            try {
                step(EvCar.now());
            } catch (Throwable t) {
                logFailure(t);
            }
            next += SAMPLE_MS;
            long n = EvCar.now();
            if (next < n - SAMPLE_MS) {
                next = n;  // fell behind (or slept): don't try to catch up
            }
            long wait = next - n;
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    // ignore
                }
            }
        }
    }

    /** One 1 s step. Logger thread only. */
    static void step(long now) {
        if (sLastStep != 0 && now - sLastStep > GAP_MS && sFileStart != 0) {
            Log.i(TAG, "head unit was asleep " + (now - sLastStep) / 1000
                    + " s: new file");
            endPeriod();
        }
        sLastStep = now;
        if (sFileStart == 0) {
            beginPeriod(now);
        }
        EvCar.pollNav();
        int raw = EvCar.energyRaw(SAMPLE_MAX_AGE_MS, EvModelSender.ENRG_MAX_RAW);
        sAgg.add(now, raw, EvCar.speedKph());
        if (now >= sNextRow) {
            logRow(now);
            sAgg.endPeriod();
            sNextRow += ROW_MS;
            if (sNextRow <= now) {
                sNextRow = now + ROW_MS;
            }
        }
        if (now >= sNextFlush) {
            flush();
            sNextFlush = now + FLUSH_MS;
        }
    }

    private static void beginPeriod(long now) {
        sFileStart = now;
        sNextRow = now + ROW_MS;
        sNextFlush = now + FLUSH_MS;
        sOdoStartRaw = -1;
        sAltStart = Double.NaN;
        sAgg.reset();
        synchronized (LOCK) {
            if (sLogger == null) {
                File dir = EvModelSender.appFile(EvLogger.DIR);
                sLogger = new EvLogger(dir, EvLogger.MAX_FILE_BYTES,
                        EvLogger.MAX_DIR_BYTES, EvLogger.MAX_BUFFER_CHARS,
                        new EvLogger.Sink() {
                            @Override
                            public void log(String msg) {
                                Log.i(TAG, msg);
                            }
                        });
            }
            sLogger.startNewFile();
        }
    }

    /** Head unit slept: write what is buffered to the old file, then end it. */
    private static void endPeriod() {
        flush();
        sFileStart = 0;
    }

    /** One row into the buffer. Crash-proof: a failure only loses the row. */
    private static void logRow(long now) {
        try {
            String row = EvLogger.csvRow(buildRow(now));
            updateHeader();
            synchronized (LOCK) {
                if (sLogger != null) {
                    sLogger.append(row);
                }
            }
        } catch (Throwable t) {
            logFailure(t);
        }
    }

    /** Values for one row, in COLUMNS order (null = unknown). */
    static Object[] buildRow(long now) {
        Object[] v = new Object[COLUMNS.length];
        int i = 0;
        boolean aa = EvModelSender.aaActive();
        int half = EvCar.hev(HEV_SOC, READ_MAX_AGE_MS);
        if (half > 200) {
            half = -1;
        }
        int raw = sAgg.lastEnergyRaw();
        int cap = EvModelSender.capacityWh();
        int availWh = raw > 0 ? raw * EvModelSender.ENRG_WH_PER_UNIT : -1;
        v[i++] = Long.valueOf((now - sFileStart) / 1000);
        v[i++] = half >= 0 ? Integer.valueOf(half) : null;
        v[i++] = raw > 0 || half >= 0
                ? Integer.valueOf(EvModelSender.currentEnergyWh(half, availWh, cap)) : null;
        v[i++] = Integer.valueOf(cap);
        v[i++] = raw > 0 ? Integer.valueOf(raw) : null;
        int outRaw = EvCar.outTempRaw();
        float outC = EvCar.outsideTempC();
        v[i++] = outRaw >= 0 ? Integer.valueOf(outRaw) : null;
        v[i++] = Float.isNaN(outC) ? null : Float.valueOf(outC);
        int pack = EvCar.packMinC();
        v[i++] = pack != Integer.MIN_VALUE ? Integer.valueOf(pack) : null;
        int dte = EvCar.dteRaw();
        v[i++] = dte >= 0 ? Integer.valueOf(dte) : null;
        float roadA = EvModelSender.roadA();
        v[i++] = aa && !Float.isNaN(roadA) ? Integer.valueOf(Math.round(roadA)) : null;
        int coldC = EvModelSender.coldTempC();
        v[i++] = aa && coldC != Integer.MIN_VALUE ? Integer.valueOf(coldC) : null;
        int soh = EvCar.sohRaw();
        v[i++] = soh > 0 ? Integer.valueOf(soh) : null;
        v[i++] = EvModelTemplate.MODEL_REV;
        v[i++] = round1(sAgg.speedLast());
        int odo = EvCar.hev(HEV_ODOMETER, READ_MAX_AGE_MS);
        if (odo > 0 && sOdoStartRaw < 0) {
            sOdoStartRaw = odo;
        }
        v[i++] = odo > 0 && sOdoStartRaw > 0 && odo >= sOdoStartRaw
                ? Float.valueOf((odo - sOdoStartRaw) / 10f) : null;
        for (int f : TRIP_FLAGS) {
            int r = EvCar.hev(f, READ_MAX_AGE_MS);
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
        // ---- v2
        v[i++] = Integer.valueOf(aa ? 1 : 0);
        float p = sAgg.powerW(EvModelSender.ENRG_WH_PER_UNIT);
        v[i++] = Float.isNaN(p) ? null : Integer.valueOf(Math.round(p));
        v[i++] = round1(sAgg.speedAvg());
        v[i++] = round1(sAgg.speedMax());
        double alt = EvCar.gpsAltitudeM();
        if (!Double.isNaN(alt) && Double.isNaN(sAltStart)) {
            sAltStart = alt;
        }
        v[i++] = Double.isNaN(alt) || Double.isNaN(sAltStart)
                ? null : round1((float) (alt - sAltStart));
        int mode = EvCar.driveMode();
        v[i++] = mode >= 0 ? Integer.valueOf(mode) : null;
        for (int f : V2_FLAGS) {
            int r = EvCar.hev(f, READ_MAX_AGE_MS);
            v[i++] = r >= 0 ? Integer.valueOf(r) : null;
        }
        int vcms = EvCar.hev(HEV_VCMS_PWR, READ_MAX_AGE_MS);
        v[i++] = vcms >= 0 && vcms != VCMS_INVALID ? Integer.valueOf(vcms) : null;
        float set = EvCar.climate(EvCar.DATC_TEMP);
        float unit = EvCar.climate(EvCar.DATC_TEMP_UNIT);
        int unitRaw = Float.isNaN(unit) ? Integer.MIN_VALUE : Math.round(unit);
        if (unitRaw != sClimUnit) {
            sClimUnit = unitRaw;
            sClimCelsius = unitRaw == Integer.MIN_VALUE ? -1
                    : EvCar.climateUnitIsCelsius(unitRaw);
        }
        v[i++] = Float.isNaN(set) ? null : Float.valueOf(set);
        v[i++] = Float.isNaN(set) || sClimCelsius < 0 ? null
                : round1(sClimCelsius == 1 ? set : (set - 32f) * 5f / 9f);
        v[i++] = unitRaw != Integer.MIN_VALUE ? Integer.valueOf(unitRaw) : null;
        v[i++] = climInt(EvCar.DATC_BLOWER_LEVEL);
        v[i++] = climInt(EvCar.DATC_BLOWER_MODE);
        v[i++] = climInt(EvCar.DATC_AC);
        v[i++] = climInt(EvCar.DATC_AUTO);
        v[i++] = climInt(EvCar.DATC_OP_STATE);
        int dep = Precond.chargerDepartureWh();
        int chg = Precond.chargerChargeSeconds();
        v[i++] = nav && dep >= 0 ? Integer.valueOf(dep) : null;
        v[i++] = nav && chg >= 0 ? Integer.valueOf(chg) : null;
        if (i != v.length) {
            throw new IllegalStateException("log row has " + i + " values for "
                    + v.length + " columns");
        }
        return v;
    }

    private static Integer climInt(int flag) {
        float f = EvCar.climate(flag);
        return Float.isNaN(f) ? null : Integer.valueOf(Math.round(f));
    }

    /** x rounded to 0.1 (null for NaN). */
    static Float round1(float x) {
        return Float.isNaN(x) || Float.isInfinite(x) ? null
                : Float.valueOf(Math.round(x * 10f) / 10f);
    }

    // ---- header ------------------------------------------------------------------

    /** Refresh the prefix/header a new file would get now. */
    private static void updateHeader() {
        String date = utcDate();
        String product = prop("ro.build.product");
        sPrefix = "egmp-" + EvLogger.productShort(product) + "-"
                + (date == null ? "00000000" : date.replace("-", "")) + "-";
        sHeader = header(product, Build.DEVICE, EvCar.vinModel(),
                EvModelSender.capacitySource(), date);
    }

    /** The full header: magic line, keys, column row. */
    static String header(String product, String device, String vinModel,
            String capacitySource, String date) {
        StringBuilder h = new StringBuilder(1200);
        h.append(MAGIC).append('\n');
        EvLogger.header(h, "product", product);
        EvLogger.header(h, "device", device);
        EvLogger.header(h, "vin_model", vinModel);
        EvLogger.header(h, "pkg", PKG);
        EvLogger.header(h, "model_capacity_wh", String.valueOf(EvModelTemplate.CAPACITY_WH));
        EvLogger.header(h, "capacity_source", capacitySource);
        EvLogger.header(h, "date", date);
        EvLogger.header(h, "period_s", String.valueOf(ROW_MS / 1000));
        EvLogger.header(h, "sample_s", String.valueOf(SAMPLE_MS / 1000));
        for (int i = 0; i < COLUMNS.length; i++) {
            if (i > 0) {
                h.append(',');
            }
            h.append(COLUMNS[i]);
        }
        h.append('\n');
        return h.toString();
    }

    /** Today's UTC date (yyyy-MM-dd), null while the clock isn't set. */
    private static String utcDate() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String d = f.format(new Date());
        return d.compareTo("2024-01-01") < 0 ? null : d;
    }

    private static String prop(String key) {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, key, "");
        } catch (Throwable t) {
            return "";
        }
    }

    /** Log a caught failure (first few in full, then a count every 100). */
    private static void logFailure(Throwable t) {
        try {
            sFailures++;
            if (sFailures <= 3) {
                Log.e(TAG, "step failed (caught, GPM unaffected)", t);
            } else if (sFailures % 100 == 0) {
                Log.e(TAG, "step failed " + sFailures + " times, last: " + t);
            }
        } catch (Throwable ignored) {
        }
    }
}

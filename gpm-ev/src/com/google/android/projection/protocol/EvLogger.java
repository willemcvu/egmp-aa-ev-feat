package com.google.android.projection.protocol;

import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Drive log for model calibration (docs/LOGGING.md, format v1).
 *
 * One CSV per Android Auto session in GPM's files dir (evlog/), written by
 * EvModelSender every PERIOD_MS. Technical data only: no location, no
 * addresses or names, no wall-clock times (date only), no full VIN. The
 * NEMESIS service svc/evlog-offload.sh copies new and grown files to any USB
 * drive that has an egmp-aa-ev-logs/ folder.
 *
 * Size limits: a file is closed at MAX_FILE_BYTES (a new one continues the
 * session) and the oldest files are deleted while the folder is over
 * MAX_DIR_BYTES, so it can never fill /data. Callers hold EvModelSender.LOCK.
 */
final class EvLogger {
    private static final String TAG = "EvLogger";
    static final String PKG = "aa.ev.model 0.2.0-dev";
    private static final String DIR = "evlog";
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    private static final long MAX_DIR_BYTES = 32L * 1024 * 1024;

    // Column order of this writer. Every name must be on the format's
    // allowlist (site/schema.json); empty = unknown.
    static final String[] COLUMNS = {
        "t_s", "soc_half_pct", "energy_wh", "capacity_wh", "avlbl_enrg_raw",
        "out_temp_raw", "out_temp_c", "pack_min_c", "dte_raw", "road_a_n",
        "aux_table_c", "soh_raw", "model_rev",
    };

    private static FileOutputStream sOut;
    private static File sFile;
    private static long sBytes;
    private static long sStartMs;
    private static String sDate;

    private EvLogger() {}

    /** Start a session: the next row() opens a new file. */
    static void startSession(long nowMs) {
        close();
        sStartMs = nowMs;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        sDate = f.format(new Date());
    }

    /** Append one row; values in COLUMNS order after t_s (null = unknown). */
    static void row(long nowMs, Object... values) {
        if (sDate == null) {
            return;
        }
        try {
            if (sOut == null || sBytes >= MAX_FILE_BYTES) {
                open();
                if (sOut == null) {
                    return;
                }
            }
            StringBuilder b = new StringBuilder(160);
            b.append((nowMs - sStartMs) / 1000);
            for (int i = 0; i < COLUMNS.length - 1; i++) {
                b.append(',');
                Object v = i < values.length ? values[i] : null;
                if (v != null) {
                    b.append(v);
                }
            }
            b.append('\n');
            write(b.toString());
        } catch (Throwable t) {
            Log.w(TAG, "log write failed, closing: " + t);
            close();
        }
    }

    static void close() {
        if (sOut != null) {
            try {
                sOut.close();
            } catch (IOException ignored) {
            }
        }
        sOut = null;
        sFile = null;
    }

    static void endSession() {
        close();
        sDate = null;
    }

    private static void open() throws IOException {
        close();
        File dir = EvModelSender.appFile(DIR);
        if (dir == null) {
            return;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create " + dir);
            return;
        }
        prune(dir);
        String product = prop("ro.build.product");
        String prefix = "egmp-" + productShort(product) + "-"
                + sDate.replace("-", "") + "-";
        int seq = 1;
        File f;
        while ((f = new File(dir, prefix + pad3(seq) + ".csv")).exists()) {
            seq++;
        }
        sFile = f;
        sOut = new FileOutputStream(f);
        sBytes = 0;
        StringBuilder h = new StringBuilder(400);
        h.append("# egmp-aa-ev-log v1\n");
        header(h, "product", product);
        header(h, "device", Build.DEVICE);
        header(h, "pkg", PKG);
        header(h, "model_capacity_wh", String.valueOf(EvModelTemplate.CAPACITY_WH));
        header(h, "capacity_source", EvModelSender.capacitySource());
        header(h, "date", sDate);
        header(h, "period_s", "5");
        for (int i = 0; i < COLUMNS.length; i++) {
            if (i > 0) {
                h.append(',');
            }
            h.append(COLUMNS[i]);
        }
        h.append('\n');
        write(h.toString());
        Log.i(TAG, "logging to " + f.getName());
    }

    private static void write(String s) throws IOException {
        byte[] b = s.getBytes("US-ASCII");
        sOut.write(b);
        sOut.flush();
        sBytes += b.length;
    }

    private static void header(StringBuilder h, String key, String value) {
        if (value == null || value.length() == 0) {
            return;
        }
        // values are ids/numbers: keep them to a safe charset
        h.append("# ").append(key).append('=')
                .append(value.replaceAll("[^A-Za-z0-9._ -]", "")).append('\n');
    }

    /** Delete the oldest logs (by name: date, then sequence) over the cap. */
    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        Arrays.sort(files);
        long total = 0;
        for (File f : files) {
            total += f.length();
        }
        for (int i = 0; i < files.length && total > MAX_DIR_BYTES; i++) {
            total -= files[i].length();
            if (!files[i].delete()) {
                Log.w(TAG, "cannot delete " + files[i].getName());
            }
        }
    }

    static String productShort(String product) {
        if (product == null || product.length() == 0) {
            return "unknown";
        }
        int dot = product.indexOf('.');
        String s = dot > 0 ? product.substring(0, dot) : product;
        return s.replaceAll("[^A-Za-z0-9]", "");
    }

    private static String pad3(int n) {
        return n < 10 ? "00" + n : n < 100 ? "0" + n : String.valueOf(n);
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
}

package com.google.android.projection.protocol;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Buffered CSV writer for the EV log (docs/LOGGING.md), with hard size caps.
 * Plain java.io (no Android), so the host test drives it with a temp dir.
 *
 * Rows are kept in memory (append) and written by flush(), which EvDriveLog
 * calls once a minute and on a clean stop. Each flush opens the file in
 * append mode, writes, and closes it again, so no file descriptor is held
 * and the offload service sees whole rows.
 *
 * Caps, enforced before every write:
 *  - a file never grows past maxFileBytes: the write goes to a new file
 *    (same session, next sequence number, fresh header);
 *  - the folder never holds more than maxDirBytes: the oldest files (by
 *    name: date, then sequence) are deleted first so that the total after
 *    the write stays within the cap (see pruneVictims). The current file is
 *    never deleted to make room for itself.
 *  - the in-memory buffer is bounded (maxBufferChars; oldest rows dropped)
 *    for when the folder can't be written.
 *
 * Not thread-safe: EvDriveLog serialises all calls.
 */
final class EvLogger {
    static final String DIR = "evlog";
    static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    static final long MAX_DIR_BYTES = 100L * 1024 * 1024;
    static final int MAX_BUFFER_CHARS = 256 * 1024;

    /** Where status messages go (android.util.Log on the car). */
    interface Sink {
        void log(String msg);
    }

    private final File dir;
    private final long maxFileBytes;
    private final long maxDirBytes;
    private final int maxBufferChars;
    private final Sink sink;
    private final StringBuilder buf = new StringBuilder(4096);
    private File cur;          // file of this session, null until first flush
    private long curBytes;
    private int droppedRows;

    EvLogger(File dir, long maxFileBytes, long maxDirBytes, int maxBufferChars,
            Sink sink) {
        this.dir = dir;
        this.maxFileBytes = maxFileBytes;
        this.maxDirBytes = maxDirBytes;
        this.maxBufferChars = maxBufferChars;
        this.sink = sink;
    }

    /** Buffer one row (must end with '\n'). */
    void append(String row) {
        buf.append(row);
        if (buf.length() > maxBufferChars) {
            // keep the newest rows: cut whole lines from the front
            int cut = buf.length() - maxBufferChars;
            int nl = buf.indexOf("\n", cut - 1);
            int end = nl < 0 ? buf.length() : nl + 1;
            for (int i = 0; i < end; i++) {
                if (buf.charAt(i) == '\n') {
                    droppedRows++;
                }
            }
            buf.delete(0, end);
        }
    }

    int pendingChars() {
        return buf.length();
    }

    File currentFile() {
        return cur;
    }

    /** The next flush starts a new file (a new head-unit "on" period). */
    void startNewFile() {
        cur = null;
        curBytes = 0;
    }

    /**
     * Write the buffered rows. prefix is the file name up to the sequence
     * number ("egmp-NEEV-20261029-"); header is the full header (magic line
     * through the column row), used only when a new file is opened. Returns
     * true when the buffer was written (or empty). Never throws.
     */
    boolean flush(String prefix, String header) {
        if (buf.length() == 0) {
            return true;
        }
        try {
            if (dir == null) {
                return false;
            }
            if (!dir.isDirectory() && !dir.mkdirs()) {
                sink.log("cannot create " + dir);
                return false;
            }
            // The offload service runs as NEMESIS root, which has no
            // CAP_DAC_OVERRIDE: it can only read these as "other". The logs
            // hold no personal data, so make them world-readable.
            dir.setReadable(true, false);
            dir.setExecutable(true, false);

            byte[] rows = buf.toString().getBytes("US-ASCII");
            boolean open = cur == null || !cur.isFile()
                    || curBytes + rows.length > maxFileBytes;
            File target = cur;
            byte[] hdr = new byte[0];
            if (open) {
                target = nextFile(prefix);
                hdr = header.getBytes("US-ASCII");
            }
            long incoming = hdr.length + rows.length;
            if (!makeRoom(open ? null : target.getName(), incoming)) {
                sink.log("log folder full even after pruning; dropping "
                        + rows.length + " bytes");
                buf.setLength(0);
                return false;
            }
            FileOutputStream out = new FileOutputStream(target, true);
            try {
                out.write(hdr);
                out.write(rows);
            } finally {
                out.close();
            }
            target.setReadable(true, false);
            if (open) {
                sink.log("logging to " + target.getName());
            }
            cur = target;
            curBytes = target.length();
            buf.setLength(0);
            if (droppedRows > 0) {
                sink.log(droppedRows + " rows dropped while the folder was unwritable");
                droppedRows = 0;
            }
            return true;
        } catch (Throwable t) {
            sink.log("log write failed (kept in memory): " + t);
            return false;
        }
    }

    private File nextFile(String prefix) {
        int seq = 1;
        File f;
        while ((f = new File(dir, prefix + pad3(seq) + ".csv")).exists()) {
            seq++;
        }
        return f;
    }

    /** Delete the oldest files so that total + incoming fits the cap. */
    private boolean makeRoom(String keep, long incoming) {
        File[] files = dir.listFiles();
        if (files == null) {
            files = new File[0];
        }
        String[] names = new String[files.length];
        long[] sizes = new long[files.length];
        for (int i = 0; i < files.length; i++) {
            names[i] = files[i].getName();
            sizes[i] = files[i].length();
        }
        int[] victims = pruneVictims(names, sizes, keep, incoming, maxDirBytes);
        long total = 0;
        for (long s : sizes) {
            total += s;
        }
        for (int i : victims) {
            if (files[i].delete()) {
                total -= sizes[i];
                sink.log("deleted old log " + names[i] + " (folder cap)");
            } else {
                sink.log("cannot delete " + names[i]);
            }
        }
        return total + incoming <= maxDirBytes;
    }

    /**
     * Which files to delete, oldest first (sorted by name), so that the
     * folder total plus incoming bytes is at most cap. Never picks keep.
     * If even deleting everything else can't make it fit, returns every
     * file but keep (the caller then refuses the write).
     */
    static int[] pruneVictims(String[] names, long[] sizes, String keep,
            long incoming, long cap) {
        long total = 0;
        for (long sz : sizes) {
            total += sz;
        }
        boolean[] taken = new boolean[names.length];
        int[] out = new int[names.length];
        int n = 0;
        // oldest first: repeatedly the smallest name not yet considered
        // (a few hundred files at most, so O(n^2) is fine)
        while (total + incoming > cap) {
            int best = -1;
            for (int i = 0; i < names.length; i++) {
                if (!taken[i] && (best < 0 || names[i].compareTo(names[best]) < 0)) {
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            taken[best] = true;
            if (names[best].equals(keep)) {
                continue;
            }
            out[n++] = best;
            total -= sizes[best];
        }
        return Arrays.copyOf(out, n);
    }

    static String productShort(String product) {
        if (product == null || product.length() == 0) {
            return "unknown";
        }
        int dot = product.indexOf('.');
        String s = dot > 0 ? product.substring(0, dot) : product;
        s = s.replaceAll("[^A-Za-z0-9]", "");
        return s.length() == 0 ? "unknown" : s;
    }

    static String pad3(int n) {
        return n < 10 ? "00" + n : n < 100 ? "0" + n : String.valueOf(n);
    }

    /** One "# key=value" header line; values kept to a safe charset. */
    static void header(StringBuilder h, String key, String value) {
        if (value == null || value.length() == 0) {
            return;
        }
        h.append("# ").append(key).append('=')
                .append(value.replaceAll("[^A-Za-z0-9._ -]", "")).append('\n');
    }

    /** One CSV row: values as strings, null = empty (unknown). */
    static String csvRow(Object[] values) {
        StringBuilder b = new StringBuilder(320);
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            if (values[i] != null) {
                b.append(values[i]);
            }
        }
        b.append('\n');
        return b.toString();
    }
}

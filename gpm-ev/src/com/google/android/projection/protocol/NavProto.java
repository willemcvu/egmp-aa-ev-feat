package com.google.android.projection.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal protobuf reader for the two Android Auto navigation payloads that
 * Leafia's native hook hands to GPM as NAVDUMP strings:
 *
 *   "S <len> <hex>"  0x8006 NavigationNotification
 *       2 destinations (repeated) { 1 address, 2 charging_station
 *                                   { 1 connector_count, 2 available_count,
 *                                     3 max_power_kw } }
 *   "P <len> <hex>"  0x8007 NextTurnDistanceEvent
 *       2 per-destination (repeated) { 1 { 1 meters, 2 text, 3 unit },
 *                                      2 eta text, 3 seconds remaining }
 *
 * Pure Java (no Android), so it runs in the host test. Address strings are
 * only looked at transiently (DC network name match, and a hash used as the
 * in-memory identity of a stop); they are never stored or logged.
 */
public final class NavProto {
    /** Thrown for malformed or truncated input. */
    public static final class ParseException extends Exception {
        public ParseException(String msg) {
            super(msg);
        }
    }

    /** One entry of NavigationNotification.destinations. */
    public static final class Destination {
        public final int index;
        /** charging_station (field 2) present. */
        public final boolean hasStation;
        public final int connectorCount;   // -1 if absent
        public final int availableCount;   // -1 if absent
        public final int maxPowerKw;       // -1 if absent
        /** Address contains a known DC fast-charging network name. */
        public final boolean networkMatch;
        /** Hash of the address (in memory only); 0 if no address. */
        public final int key;

        public Destination(int index, boolean hasStation, int connectorCount,
                int availableCount, int maxPowerKw, boolean networkMatch,
                int key) {
            this.index = index;
            this.hasStation = hasStation;
            this.connectorCount = connectorCount;
            this.availableCount = availableCount;
            this.maxPowerKw = maxPowerKw;
            this.networkMatch = networkMatch;
            this.key = key;
        }
    }

    /** One entry of NextTurnDistanceEvent field 2. */
    public static final class Remaining {
        public final int meters;   // -1 if absent
        public final int seconds;  // -1 if absent

        public Remaining(int meters, int seconds) {
            this.meters = meters;
            this.seconds = seconds;
        }
    }

    /** Result of parse(): exactly one of destinations / remaining is set. */
    public static final class Dump {
        public final char kind;  // 'S' or 'P'
        public final List<Destination> destinations;
        public final List<Remaining> remaining;

        Dump(char kind, List<Destination> d, List<Remaining> r) {
            this.kind = kind;
            this.destinations = d;
            this.remaining = r;
        }
    }

    /**
     * DC fast-charging network names for the persist.gpm.precond=2 fallback.
     * Lower case; matched as substrings of the lower-cased address.
     */
    static final String[] DC_NETWORKS = {
        "electrify america",
        "evgo",
        "ionna",
        "tesla supercharger",
        "chargepoint",
        "walmart",
        "mercedes-benz high-power",
        "mercedes-benz high power",
    };

    private NavProto() {}

    /**
     * Parses a NAVDUMP string. Returns null for kinds other than S/P.
     * Throws on malformed hex, a length mismatch (Leafia truncation) or a
     * protobuf error.
     */
    public static Dump parse(String line) throws ParseException {
        if (line == null) {
            return null;
        }
        String s = line.trim();
        if (s.length() < 3 || s.charAt(1) != ' ') {
            return null;
        }
        char kind = s.charAt(0);
        if (kind != 'S' && kind != 'P') {
            return null;
        }
        int sp = s.indexOf(' ', 2);
        if (sp < 0) {
            throw new ParseException("no payload");
        }
        int declared;
        try {
            declared = Integer.parseInt(s.substring(2, sp));
        } catch (NumberFormatException e) {
            throw new ParseException("bad length");
        }
        byte[] b = hex(s.substring(sp + 1).trim());
        if (b.length != declared) {
            throw new ParseException("length " + b.length + " != " + declared);
        }
        if (kind == 'S') {
            return new Dump('S', parseNotification(b), null);
        }
        return new Dump('P', null, parseDistances(b));
    }

    /** 0x8006 NavigationNotification body. */
    public static List<Destination> parseNotification(byte[] b)
            throws ParseException {
        List<Destination> out = new ArrayList<Destination>();
        Reader r = new Reader(b, 0, b.length);
        while (r.more()) {
            int tag = r.tag();
            if (tag == ((2 << 3) | 2)) {
                Reader d = r.sub();
                out.add(parseDestination(d, out.size()));
            } else {
                r.skip(tag);
            }
        }
        return out;
    }

    private static Destination parseDestination(Reader r, int index)
            throws ParseException {
        boolean station = false;
        int conn = -1, avail = -1, kw = -1;
        boolean network = false;
        int key = 0;
        while (r.more()) {
            int tag = r.tag();
            if (tag == ((1 << 3) | 2)) {
                byte[] a = r.bytes();
                String addr;
                try {
                    addr = new String(a, "UTF-8");
                } catch (java.io.UnsupportedEncodingException e) {
                    addr = "";
                }
                key = addr.hashCode();
                if (key == 0) {
                    key = 1;
                }
                network = isDcNetwork(addr);
            } else if (tag == ((2 << 3) | 2)) {
                station = true;
                Reader c = r.sub();
                while (c.more()) {
                    int t = c.tag();
                    if (t == ((1 << 3) | 0)) {
                        conn = (int) c.varint();
                    } else if (t == ((2 << 3) | 0)) {
                        avail = (int) c.varint();
                    } else if (t == ((3 << 3) | 0)) {
                        kw = (int) c.varint();
                    } else {
                        c.skip(t);
                    }
                }
            } else {
                r.skip(tag);
            }
        }
        return new Destination(index, station, conn, avail, kw, network, key);
    }

    /** 0x8007 NextTurnDistanceEvent body: field 2 entries. */
    public static List<Remaining> parseDistances(byte[] b)
            throws ParseException {
        List<Remaining> out = new ArrayList<Remaining>();
        Reader r = new Reader(b, 0, b.length);
        while (r.more()) {
            int tag = r.tag();
            if (tag == ((2 << 3) | 2)) {
                Reader e = r.sub();
                int meters = -1, seconds = -1;
                while (e.more()) {
                    int t = e.tag();
                    if (t == ((1 << 3) | 2)) {
                        Reader d = e.sub();
                        while (d.more()) {
                            int dt = d.tag();
                            if (dt == ((1 << 3) | 0)) {
                                meters = (int) d.varint();
                            } else {
                                d.skip(dt);
                            }
                        }
                    } else if (t == ((3 << 3) | 0)) {
                        seconds = (int) e.varint();
                    } else {
                        e.skip(t);
                    }
                }
                out.add(new Remaining(meters, seconds));
            } else {
                r.skip(tag);
            }
        }
        return out;
    }

    static boolean isDcNetwork(String addr) {
        if (addr == null || addr.length() == 0) {
            return false;
        }
        String a = addr.toLowerCase(Locale.US);
        for (String n : DC_NETWORKS) {
            if (a.indexOf(n) >= 0) {
                return true;
            }
        }
        return false;
    }

    static byte[] hex(String h) throws ParseException {
        int n = h.length();
        if ((n & 1) != 0) {
            throw new ParseException("odd hex length");
        }
        byte[] out = new byte[n / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(h.charAt(2 * i), 16);
            int lo = Character.digit(h.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new ParseException("bad hex");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** Bounds-checked protobuf wire reader over b[pos, end). */
    static final class Reader {
        private final byte[] b;
        private int pos;
        private final int end;

        Reader(byte[] b, int pos, int end) {
            this.b = b;
            this.pos = pos;
            this.end = end;
        }

        boolean more() {
            return pos < end;
        }

        int tag() throws ParseException {
            long t = varint();
            if (t <= 0 || t > Integer.MAX_VALUE || (t >>> 3) == 0) {
                throw new ParseException("bad tag");
            }
            return (int) t;
        }

        long varint() throws ParseException {
            long v = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= end) {
                    throw new ParseException("truncated varint");
                }
                int x = b[pos++] & 0xff;
                v |= (long) (x & 0x7f) << shift;
                if ((x & 0x80) == 0) {
                    return v;
                }
            }
            throw new ParseException("varint too long");
        }

        private int length() throws ParseException {
            long n = varint();
            if (n < 0 || n > end - pos) {
                throw new ParseException("truncated field");
            }
            return (int) n;
        }

        Reader sub() throws ParseException {
            int n = length();
            Reader r = new Reader(b, pos, pos + n);
            pos += n;
            return r;
        }

        byte[] bytes() throws ParseException {
            int n = length();
            byte[] out = new byte[n];
            System.arraycopy(b, pos, out, 0, n);
            pos += n;
            return out;
        }

        void skip(int tag) throws ParseException {
            switch (tag & 7) {
                case 0:
                    varint();
                    return;
                case 1:
                    advance(8);
                    return;
                case 2:
                    advance(length());
                    return;
                case 5:
                    advance(4);
                    return;
                default:
                    throw new ParseException("wire type " + (tag & 7));
            }
        }

        private void advance(int n) throws ParseException {
            if (n > end - pos) {
                throw new ParseException("truncated field");
            }
            pos += n;
        }
    }
}

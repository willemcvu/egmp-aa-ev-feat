package com.google.android.projection.protocol;

import android.os.SystemClock;
import android.util.Log;

import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;

/**
 * Which car framework this head unit runs, and which binder transaction
 * numbers are safe to call on it.
 *
 * Binder transaction numbers come from the order of methods in each AIDL
 * interface, so they can differ between car models and software versions.
 * A wrong number calls a different method of the same service, possibly a
 * setter. Two rules keep every call on the method we mean:
 *
 *  1. txn() asks the framework itself: the AIDL Stub class on the boot
 *     classpath holds TRANSACTION_<method>, and the interface must declare
 *     the method with the expected signature. Any car, any software.
 *  2. Only if that is not possible, txn() falls back to the number verified
 *     on the Ioniq 5 framework (READS_VERIFIED), and only on that framework.
 *     Otherwise the signal is skipped (-1).
 *
 * The preconditioning commands (HEV 62/65/66 writes) are only sent on a
 * framework in KNOWN, the set gen5w-preconditioning (Launchpad) verified
 * the same five HEV/BCM transactions on.
 *
 * Never throws; any thread.
 */
final class EvFirmware {
    private static final String TAG = "EvFirmware";

    static final String FRAMEWORK = "/system/framework/automotive.odex";
    static final String SERVICE = "/system/framework/automotive-service.odex";

    // {name, automotive.odex SHA-256, automotive-service.odex SHA-256},
    // from gen5w-preconditioning's PreconditioningProfile (2026-10-31).
    static final String[][] KNOWN = {
        {"Ioniq 5 US (NEEV, NEEV23) 260507",
            "dd72e04bce0a2b4bda1506308ac21f91002c1436553b8156c345cc4313661338",
            "3cf40fb00854c63afa49abea1dad18a0afcbc0dd68b17de9ed7ea3f5aeab4222"},
        {"Ioniq 5 US (NEEV23) 260206",
            "301b134d231b1ca1d0aa75ae090d219ac963fc935c3f3bc0b3b1393c6614703e",
            "efb949ec92fff388cbbb78e5bf4e16df3cc8d417dbf5cb5e87e62fb46d542549"},
        {"Ioniq 5 US (NEEV23) 260903",
            "c52e0825956880d627b32979022705a618200f059476c094d729133d1ff6a3be",
            "bf17e682d1beb349ae8ef2acaae6d6e908fe42e88853d7145ee16b29bb4d9ec1"},
        {"EV6 US (CVEV) 260507",
            "b9e7e02b81e1a8cfcf767c6b8bbb28bcf1d2c411a208fbf6a465e7f0c662774a",
            "8af745b41aa28f490953d61531215cd94e059f104b31f6236a8f6c7af23b8062"},
        {"Ioniq 6 US (CEEV) 260507",
            "408b426e14b3f8dfdf1926e608e4f02689e89fc386b7f93a97f261ac5b6e4287",
            "afbc60509dd8a8fbac842523bf618424c088685d832a125a84d1d11dc1770bd4"},
        {"Ioniq 5 KR (NEEV) 260902",
            "8186fec9601a216e8e3015b77c13b4d18c2fd2dfc2d3a6b7e42dac55b7a22c06",
            "176e2375ef9e2c8b1df16b600f0434df7e2ce7bb7378779e7180e5c23651f80d"},
    };

    /** KNOWN row whose hard-coded read transactions (EvCar) were verified. */
    static final int READS_VERIFIED = 0;

    private static final Object LOCK = new Object();
    private static int sProfile = -2;  // -2 not checked, -1 unknown framework
    private static long sFailedAt;     // last failed read (uptime ms), 0 = none
    private static final long RETRY_MS = 60000;
    private static final HashMap<String, Integer> sTxn =
            new HashMap<String, Integer>();

    private EvFirmware() {}

    /** Index into KNOWN, -1 for any other framework or if it can't be read. */
    static int profile() {
        synchronized (LOCK) {
            if (sProfile != -2) {
                return sProfile;
            }
            long now = SystemClock.uptimeMillis();
            if (sFailedAt != 0 && now - sFailedAt < RETRY_MS) {
                return -1;
            }
            int p;
            try {
                p = match(sha256(FRAMEWORK), sha256(SERVICE));
            } catch (Throwable t) {
                sFailedAt = now;
                Log.w(TAG, "framework check failed (retried in a minute): " + t);
                return -1;  // not cached
            }
            sProfile = p;
            Log.i(TAG, "framework: " + (p >= 0 ? KNOWN[p][0] : "not a known profile"));
            return p;
        }
    }

    static int match(String framework, String service) {
        for (int i = 0; i < KNOWN.length; i++) {
            if (KNOWN[i][1].equals(framework) && KNOWN[i][2].equals(service)) {
                return i;
            }
        }
        return -1;
    }

    /** The framework files were read (profile() is final). */
    static boolean checked() {
        synchronized (LOCK) {
            return sProfile != -2;
        }
    }

    /** The preconditioning commands were verified on this framework. */
    static boolean precondVerified() {
        return profile() >= 0;
    }

    static String profileName() {
        int p = profile();
        return p >= 0 ? KNOWN[p][0] : "unknown";
    }

    /**
     * Transaction number of method on AIDL interface iface (e.g.
     * "com.hkmc.datc.IDATCService"). The interface must declare it with
     * these parameter types (Class.getName(), "*" = any) and one of these
     * return types. Falls back to verified only on the READS_VERIFIED
     * framework, or on any KNOWN one if anyKnown (a number every KNOWN
     * framework was verified to share). -1: don't call it. Cached.
     */
    static int txn(String iface, String method, String[] params,
            String[] rets, int verified, boolean anyKnown) {
        String key = iface + "#" + method;
        synchronized (LOCK) {
            Integer c = sTxn.get(key);
            if (c != null) {
                return c.intValue();
            }
        }
        int t = fromStub(iface, method, params, rets);
        String how = "framework";
        if (t < 0) {
            int p = profile();
            t = p == READS_VERIFIED || (anyKnown && p >= 0) ? verified : -1;
            how = t < 0 ? "unavailable on this framework" : "verified number";
        } else if (t != verified) {
            how = "framework, differs from the Ioniq 5's " + verified;
        }
        synchronized (LOCK) {
            if (sProfile == -2) {
                return t;  // framework not read yet (I/O failure): ask again
            }
            sTxn.put(key, Integer.valueOf(t));
        }
        Log.i(TAG, iface.substring(iface.lastIndexOf('.') + 1) + "." + method
                + " = " + t + " (" + how + ")");
        return t;
    }

    /** TRANSACTION_<method> from iface$Stub if the signature fits, or -1. */
    static int fromStub(String iface, String method, String[] params,
            String[] rets) {
        try {
            Method m = null;
            for (Method x : Class.forName(iface).getMethods()) {
                if (x.getName().equals(method)) {
                    if (m != null) {
                        return -1;  // AIDL has no overloads: not an AIDL method
                    }
                    m = x;
                }
            }
            if (m == null || !signatureFits(m, params, rets)) {
                return -1;
            }
            Field f = Class.forName(iface + "$Stub")
                    .getDeclaredField("TRANSACTION_" + method);
            f.setAccessible(true);
            int t = f.getInt(null);
            return t >= 1 && t <= 0x00ffffff ? t : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean signatureFits(Method m, String[] params, String[] rets) {
        Class<?>[] p = m.getParameterTypes();
        if (p.length != params.length) {
            return false;
        }
        for (int i = 0; i < p.length; i++) {
            if (!params[i].equals("*") && !params[i].equals(p[i].getName())) {
                return false;
            }
        }
        String r = m.getReturnType().getName();
        for (String want : rets) {
            if (want.equals(r)) {
                return true;
            }
        }
        return false;
    }

    /** For tests: forget the cached profile and transaction numbers. */
    static void reset(int profile) {
        synchronized (LOCK) {
            sProfile = profile;
            sFailedAt = 0;
            sTxn.clear();
        }
    }

    static String sha256(String path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        FileInputStream in = new FileInputStream(path);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        } finally {
            in.close();
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) {
            sb.append(String.format(Locale.US, "%02x", x & 0xff));
        }
        return sb.toString();
    }
}

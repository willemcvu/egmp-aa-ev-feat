// Host-test shim: the android.jar stub throws "Stub!".
package android.os;
public final class SystemClock {
    public static long uptimeMillis() { return System.nanoTime() / 1000000L; }
    public static long elapsedRealtime() { return uptimeMillis(); }
    public static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { }
    }
}

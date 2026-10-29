package com.google.android.projection.protocol;

/**
 * Host test for EvModelSender.logRow: builds a full log row (car services
 * absent, so every reading is unknown) and checks it doesn't throw. Guards
 * against the row/column count drifting: an exception here killed GPM, and
 * with it Android Auto, on the car (2026-10-29).
 */
public final class SenderRowTest {
    public static void main(String[] args) throws Exception {
        java.lang.reflect.Method m = EvModelSender.class.getDeclaredMethod(
                "logRow", int.class, int.class, int.class, Float.class, float.class);
        m.setAccessible(true);
        m.invoke(null, 90, 28370, 62500, Float.valueOf(-2.5f), 330f);
        m.invoke(null, -1, 500, 77400, null, 196f);
        System.out.println("logRow: " + (EvLogger.COLUMNS.length - 1)
                + " values per row, no exception");
        System.out.println("passed: 1, failed: 0");
    }
}

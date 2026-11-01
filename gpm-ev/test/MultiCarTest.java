package com.google.android.projection.protocol;

/**
 * Multi-car support: EvVehicle (which car, road load from the VIN),
 * EvFirmware (framework profiles, transaction numbers from the framework's
 * AIDL stubs with a verified-only fallback) and the road-load patch points
 * in the template.
 */
public class MultiCarTest {
    static int passed;
    static int failed;

    static void check(boolean ok, String what) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + what);
        }
    }

    static final String[] NONE = {};
    static final String[] INT = {"int"};
    static final String[] FLOAT = {"float"};
    static final String[] STR = {"java.lang.String"};
    static final String[] VOID = {"void"};
    static final String IF = "fakeaidl.IFake";
    static final String MISSING = "fakeaidl.IMissing";

    public static void main(String[] args) {
        vehicles();
        firmware();
        template();
        System.out.println("passed: " + passed + ", failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    static void vehicles() {
        String ev6 = "CVEV.USA.0000.V015.012.260507";
        String i5 = "NEEV.USA.0000.V015.012.260507";
        // Ioniq 5 (any VIN, any NEEV product) keeps the owner's coast-down fit
        check(EvVehicle.detect(i5, "KM8KRDAFN") == EvVehicle.IONIQ5, "Ioniq 5");
        check(EvVehicle.detect("NEEV23.USA.0000.V016.010.260903", "") == EvVehicle.IONIQ5,
                "Ioniq 5 2023+");
        check(EvVehicle.IONIQ5.roadA == 196.0f && EvVehicle.IONIQ5.roadC == 0.493f,
                "Ioniq 5 = template values");
        check(EvVehicle.detect(null, "") == EvVehicle.IONIQ5, "no product -> Ioniq 5");
        // EV6 by VIN (NHTSA vPIC: char 5 line, char 6 drive)
        check(EvVehicle.detect(ev6, "KNDC3DLCN") == EvVehicle.EV6_AWD19, "Wind AWD");
        check(EvVehicle.detect(ev6, "KNDC4DLCR") == EvVehicle.EV6_AWD20, "GT-Line AWD");
        check(EvVehicle.detect(ev6, "KNDC34LCP") == EvVehicle.EV6_RWD, "Wind RWD");
        check(EvVehicle.detect(ev6, "KNDC44LCR") == EvVehicle.EV6_RWD, "GT-Line RWD");
        check(EvVehicle.detect(ev6, "KNDC5DLER") == EvVehicle.EV6_GT, "GT");
        check(EvVehicle.detect(ev6, "") == EvVehicle.EV6_DEFAULT, "EV6 VIN unknown");
        check(EvVehicle.detect(ev6, "KM8KRDAFN") == EvVehicle.EV6_DEFAULT,
                "EV6 product, non-EV6 VIN");
        check(EvVehicle.detect("CVEV.CAN.0000.V015.011.260206", "KNDC3DLCN")
                == EvVehicle.EV6_AWD19, "EV6 Canada");
        // physical sanity: road-load power at 65 mph (29.06 m/s), kW
        float v = 29.06f;
        float i5kw = (196.0f + 0.493f * v * v) * v / 1000;
        for (EvVehicle e : new EvVehicle[] {EvVehicle.EV6_RWD, EvVehicle.EV6_AWD19,
                EvVehicle.EV6_AWD20, EvVehicle.EV6_GT}) {
            float kw = (e.roadA + e.roadC * v * v) * v / 1000;
            check(kw > 0.8f * i5kw && kw < 1.2f * i5kw, e.name + " within 20% of Ioniq 5: "
                    + kw + " kW vs " + i5kw);
        }
        check(EvVehicle.EV6_RWD.roadA < EvVehicle.EV6_AWD20.roadA
                && EvVehicle.EV6_AWD20.roadA < EvVehicle.EV6_GT.roadA, "trim order");
        // cold penalty adds to the vehicle's own warm A
        float cold = EvModelSender.coldRoadLoadA(EvVehicle.EV6_RWD.roadA, -7);
        check(cold > EvVehicle.EV6_RWD.roadA + 100 && cold < EvVehicle.EV6_RWD.roadA + 200,
                "EV6 cold A at -7 C: " + cold);
    }

    static void firmware() {
        // known framework pairs; Ioniq 5 260507 is the reads-verified one
        check(EvFirmware.match(EvFirmware.KNOWN[0][1], EvFirmware.KNOWN[0][2])
                == EvFirmware.READS_VERIFIED, "Ioniq 5 260507 profile");
        int ev6 = -1;
        for (int i = 0; i < EvFirmware.KNOWN.length; i++) {
            if (EvFirmware.KNOWN[i][0].startsWith("EV6 US")) {
                ev6 = i;
            }
            check(EvFirmware.match(EvFirmware.KNOWN[i][1], EvFirmware.KNOWN[i][2]) == i,
                    "profile " + i);
            check(EvFirmware.KNOWN[i][1].matches("[0-9a-f]{64}")
                    && EvFirmware.KNOWN[i][2].matches("[0-9a-f]{64}"), "hash format " + i);
        }
        check(ev6 > 0, "EV6 profile present");
        check(EvFirmware.match(EvFirmware.KNOWN[0][1], EvFirmware.KNOWN[ev6][2]) == -1,
                "mixed pair is unknown");
        check(EvFirmware.match("x", "y") == -1, "unknown framework");

        // 1. the framework's own stub wins, on any framework
        EvFirmware.reset(-1);
        check(EvFirmware.txn(IF, "getCarSpeed", new String[] {"byte"}, FLOAT, 0x0b, false)
                == 11, "stub: getCarSpeed");
        check(EvFirmware.txn(IF, "getVIN", NONE, STR, 0x2f, false) == 47, "stub: getVIN");
        check(EvFirmware.txn(IF, "getSoh", NONE, new String[] {"int", "byte"}, 0x99, false)
                == 214, "stub number used even if it differs from the verified one");
        check(EvFirmware.txn(IF, "registerListener",
                new String[] {"fakeaidl.IFakeListener", "*"}, VOID, 1, false) == 1,
                "stub: wildcard parameter");
        // 2. wrong signature, overloads, or no stub field: never a guessed number
        EvFirmware.reset(-1);
        check(EvFirmware.txn(IF, "getVIN", NONE, INT, 0x2f, true) == -1,
                "wrong return type");
        check(EvFirmware.txn(IF, "getCarSpeed", new String[] {"int"}, FLOAT, 0x0b, true) == -1,
                "wrong parameter type");
        check(EvFirmware.txn(IF, "overloaded", INT, INT, 5, false) == -1, "overloaded");
        check(EvFirmware.txn(IF, "noStubField", INT, FLOAT, 7, false) == -1, "no stub field");
        check(EvFirmware.txn(MISSING, "getX", NONE, INT, 3, true) == -1,
                "missing interface, unknown framework");
        // 3. fallback to the verified number: Ioniq 5 framework only, or any
        //    known framework for numbers verified on all of them
        EvFirmware.reset(EvFirmware.READS_VERIFIED);
        check(EvFirmware.txn(MISSING, "getX", NONE, INT, 0x2f, false) == 0x2f,
                "fallback on the Ioniq 5 framework");
        check(EvFirmware.txn(IF, "noStubField", INT, FLOAT, 7, false) == 7,
                "fallback when the stub has no field");
        EvFirmware.reset(ev6);
        check(EvFirmware.txn(MISSING, "getX", NONE, INT, 0x2f, false) == -1,
                "no Ioniq 5 fallback on the EV6 framework");
        check(EvFirmware.txn(MISSING, "getHEVStatus", INT, INT, 3, true) == 3,
                "known-everywhere fallback on the EV6 framework");
        check(EvFirmware.precondVerified(), "precond verified on EV6");
        EvFirmware.reset(-1);
        check(!EvFirmware.precondVerified(), "precond not verified on unknown");
        // 4. framework not readable yet (no /system here): nothing cached
        EvFirmware.reset(-2);
        check(EvFirmware.txn(MISSING, "getY", NONE, INT, 9, false) == -1, "unread framework");
        check(!EvFirmware.checked(), "unreadable framework stays unchecked");
        EvFirmware.reset(-1);
    }

    static void template() {
        byte[] t = java.util.Base64.getMimeDecoder().decode(EvModelTemplate.TEMPLATE_B64);
        check(EvModelSender.checkPlaceholders(t), "template offsets incl. road C");
        check(Float.intBitsToFloat(le(t, EvModelTemplate.ROAD_C_OFFSET)) == 0.493f,
                "C at its offset");
        check(Float.intBitsToFloat(le(t, EvModelTemplate.ROAD_A_OFFSET)) == 196.0f,
                "A at its offset");
    }

    static int le(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16
                | (b[o + 3] & 0xff) << 24;
    }
}

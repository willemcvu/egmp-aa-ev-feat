package com.google.android.projection.protocol;

/**
 * Which car this is, for the parts of the energy model that differ between
 * E-GMP cars: road load A and C (B is 0 for all of them, see
 * build_ioniq5_model.py). The battery, charge curve, drivetrain, heater and
 * cold-weather tables are the Ioniq 5's for every car (same platform, same
 * 77.4 kWh pack in the long-range cars; capacity is learned from the BMS).
 *
 * Chosen from the head unit's product id (ro.build.product, e.g.
 * "CVEV.USA.0000.V015.012.260507") and VIN characters 5-6 (line, drive), no
 * setting. Road loads other than the Ioniq 5's are EPA target coefficients
 * (Test Car List Data, MY2022-2024) refitted as A + C*v^2 over 8-36 m/s by
 * tools/epa_road_load.py. The Ioniq 5 keeps the owner's coast-down fit,
 * which is within ~5% of EPA's AWD figures.
 */
final class EvVehicle {
    final String name;
    final float roadA;  // N, warm; EvModelSender adds the cold penalty
    final float roadC;  // N/(m/s)^2

    EvVehicle(String name, float roadA, float roadC) {
        this.name = name;
        this.roadA = roadA;
        this.roadC = roadC;
    }

    static final EvVehicle IONIQ5 = new EvVehicle("Ioniq 5",
            EvModelTemplate.ROAD_A_N, EvModelTemplate.ROAD_C_N);
    // Kia EV6 (US): EPA MY2022-24, by VIN line (char 5) and drive (char 6).
    static final EvVehicle EV6_RWD = new EvVehicle("EV6 RWD", 115.4f, 0.5206f);
    static final EvVehicle EV6_AWD19 = new EvVehicle("EV6 AWD 19in", 129.1f, 0.5159f);
    static final EvVehicle EV6_AWD20 = new EvVehicle("EV6 AWD 20in", 189.3f, 0.5053f);
    static final EvVehicle EV6_GT = new EvVehicle("EV6 GT", 214.1f, 0.5704f);
    /** An EV6 whose VIN is not readable: GT-Line AWD, the most demanding of
     *  the common trims (close to the Ioniq 5), so range errs low. */
    static final EvVehicle EV6_DEFAULT = new EvVehicle("EV6 (trim unknown)",
            EV6_AWD20.roadA, EV6_AWD20.roadC);

    private static volatile String sProduct;
    private static volatile EvVehicle sVehicle;

    /**
     * The vehicle for this head unit. Retried while the VIN is unknown (it
     * can read empty early after boot), then fixed for the process.
     */
    static EvVehicle current() {
        EvVehicle v = sVehicle;
        if (v != null) {
            return v;
        }
        String product = sProduct;
        if (product == null) {
            product = prop("ro.build.product");
            sProduct = product;
        }
        String vin = EvCar.vinModel();
        v = detect(product, vin);
        if (vin.length() > 0 || !isEv6(product)) {
            sVehicle = v;
        }
        return v;
    }

    /** vinModel: VIN chars 1-8 and 10 (EvCar.vinModel()), "" if unknown. */
    static EvVehicle detect(String product, String vinModel) {
        if (isEv6(product)) {
            return ev6(vinModel);
        }
        return IONIQ5;
    }

    static boolean isEv6(String product) {
        return product != null && product.startsWith("CVEV");
    }

    /**
     * Kia EV6 VINs (NHTSA vPIC): KNDC, then char 5 the line (3 Light/Wind,
     * 4 GT-Line, 5 GT), char 6 the drive (D AWD, 4 RWD). Wheels by trim: Light/Wind 19in,
     * GT-Line RWD 19in, GT-Line AWD 20in, GT 21in.
     */
    static EvVehicle ev6(String vinModel) {
        if (vinModel == null || vinModel.length() < 6 || !vinModel.startsWith("KNDC")) {
            return EV6_DEFAULT;
        }
        char line = vinModel.charAt(4);   // VIN char 5
        char drive = vinModel.charAt(5);  // VIN char 6
        if (line == '5') {
            return EV6_GT;
        }
        if (drive == '4') {
            return EV6_RWD;
        }
        if (drive == 'D') {
            return line == '4' ? EV6_AWD20 : EV6_AWD19;
        }
        return EV6_DEFAULT;
    }

    private static String prop(String key) {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class)
                    .invoke(null, key);
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public String toString() {
        return name + " (A=" + roadA + "N, C=" + roadC + ")";
    }
}

package com.google.android.projection.protocol;

/**
 * Aggregates the 1 s samples of one log row period (pure Java, host-tested).
 *
 * Speed: mean, max and last of the valid samples in the period.
 * Battery power: the change of BMS available energy (HEV 0x31, raw units)
 * from the last valid sample before this period (or the first one of the
 * file) to the last valid sample in it, over the time between them:
 *   P [W] = (E_last - E_ref) x whPerUnit x 3600 / dt [s]
 * Positive = energy into the battery (charging), negative = discharge.
 * The energy signal is coarse (2 Wh steps, updated about once a second and
 * often in bigger jumps), so single-row power is noisy; average over a few
 * rows, or difference energy_wh over longer windows, for analysis.
 * The first row of a file has no power: right after the head unit wakes the
 * first energy samples can be stale (seen on the car: a 36 Wh step within
 * 2 s, i.e. a bogus -65 kW), and there is no previous row to measure from.
 */
final class EvRowAgg {
    private long refT = -1;
    private int refE = -1;
    private boolean refFromPreviousRow;
    private long lastT = -1;
    private int lastE = -1;
    private int samples;
    private int speedN;
    private double speedSum;
    private float speedMax = Float.NaN;
    private float speedLast = Float.NaN;

    /** New file: forget everything, including the energy reference. */
    void reset() {
        refT = -1;
        refE = -1;
        refFromPreviousRow = false;
        lastT = -1;
        lastE = -1;
        clearPeriod();
    }

    private void clearPeriod() {
        samples = 0;
        speedN = 0;
        speedSum = 0;
        speedMax = Float.NaN;
        speedLast = Float.NaN;
    }

    /** One sample: energyRaw <= 0 = unknown, speed NaN = unknown. */
    void add(long tMs, int energyRaw, float speedKph) {
        samples++;
        if (energyRaw > 0) {
            if (refE <= 0) {
                refT = tMs;
                refE = energyRaw;
            }
            lastT = tMs;
            lastE = energyRaw;
        }
        if (!Float.isNaN(speedKph) && speedKph >= 0) {
            speedN++;
            speedSum += speedKph;
            if (Float.isNaN(speedMax) || speedKph > speedMax) {
                speedMax = speedKph;
            }
            speedLast = speedKph;
        }
    }

    int samples() {
        return samples;
    }

    /** Last valid energy raw seen in this period, -1 if none. */
    int lastEnergyRaw() {
        return lastE;
    }

    float speedAvg() {
        return speedN == 0 ? Float.NaN : (float) (speedSum / speedN);
    }

    float speedMax() {
        return speedMax;
    }

    float speedLast() {
        return speedLast;
    }

    /** Battery power over the period in W (+ = charging), NaN if unknown. */
    float powerW(int whPerUnit) {
        if (!refFromPreviousRow || refE <= 0 || lastE <= 0 || lastT <= refT) {
            return Float.NaN;
        }
        return (float) ((double) (lastE - refE) * whPerUnit * 3600000.0
                / (lastT - refT));
    }

    /** Row written: the last energy sample becomes the next reference. */
    void endPeriod() {
        if (lastE > 0) {
            refT = lastT;
            refE = lastE;
            refFromPreviousRow = true;
        }
        lastT = -1;
        lastE = -1;
        clearPeriod();
    }
}

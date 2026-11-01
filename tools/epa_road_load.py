#!/usr/bin/env python3
"""Road load for EvVehicle from EPA target coefficients.

EPA's "Test Car List Data" (https://www.epa.gov/compliance-and-fuel-economy-data/
data-cars-used-testing-fuel-economy, MY2022-2024 files) gives each tested
configuration's dyno target road load as A [lbf], B [lbf/mph], C [lbf/mph^2].
The energy model can't carry B (it is 0, so proto3 leaves it out and it has
no patch point), so each curve is refitted as A' + C' v^2 by least squares
over 8-36 m/s (18-80 mph), the range Maps plans over.

The Ioniq 5 keeps the owner's coast-down fit (A 196 N, C 0.493), which this
prints next to EPA's Ioniq 5 AWD for comparison (within ~5%).

  python3 tools/epa_road_load.py
"""

LBF = 4.44822   # N
MPH = 0.44704   # m/s

# (name, A lbf, B lbf/mph, C lbf/mph^2), copied from the EPA files
EPA = [
    ("Ioniq 5 AWD (EPA, ETW 5000)", 34.342, 0.21928, 0.022718),
    ("EV6 LR RWD (EV6_RWD)", 23.313, 0.11939, 0.022206),
    ("EV6 SR RWD", 23.365, 0.16194, 0.021063),
    ("EV6 LR AWD 19in (EV6_AWD19)", 23.992, 0.2289, 0.020912),
    ("EV6 LR AWD 20in (EV6_AWD20)", 36.158, 0.29099, 0.019825),
    ("EV6 GT MY2024 (EV6_GT)", 44.973, 0.14352, 0.024205),
]
SHIPPED_IONIQ5 = (196.0, 0.0, 0.493)


def to_si(a, b, c):
    return a * LBF, b * LBF / MPH, c * LBF / MPH ** 2


def fit_a_c(a, b, c, v0=8.0, v1=36.0, n=201):
    vs = [v0 + i * (v1 - v0) / (n - 1) for i in range(n)]
    x = [v * v for v in vs]
    y = [a + b * v + c * v * v for v in vs]
    sx, sy = sum(x), sum(y)
    sxx = sum(t * t for t in x)
    sxy = sum(p * q for p, q in zip(x, y))
    cc = (n * sxy - sx * sy) / (n * sxx - sx * sx)
    return (sy - cc * sx) / n, cc


def kw(a, b, c, mph):
    v = mph * MPH
    return (a + b * v + c * v * v) * v / 1000


def main():
    print(f"{'':30s} {'A N':>7s} {'C':>7s}   kW at 30/55/65/75 mph")
    for name, a, b, c in EPA:
        A, B, C = to_si(a, b, c)
        fa, fc = fit_a_c(A, B, C)
        speeds = "/".join(f"{kw(fa, 0, fc, s):.1f}" for s in (30, 55, 65, 75))
        print(f"{name:30s} {fa:7.1f} {fc:7.4f}   {speeds}")
    speeds = "/".join(f"{kw(*SHIPPED_IONIQ5, s):.1f}" for s in (30, 55, 65, 75))
    print(f"{'Ioniq 5 owner coast-down':30s} {196.0:7.1f} {0.493:7.4f}   {speeds}")


if __name__ == "__main__":
    main()

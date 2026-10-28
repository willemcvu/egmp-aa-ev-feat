#!/usr/bin/env python3
"""Generate the Ioniq 5 vehicle energy model (SensorBatch field 23 payload).

Replaces the Ford-derived prototype template with Ioniq 5 values per
docs/ENERGY-MODEL.md:

  block 1   battery: 77.4 kWh LR pack, ~3% reserve, live energy patched in
            at runtime (fixed-width 5-byte varint, see placeholder below)
  block 2   road load from the owner's rolldown test:
            F(v) = 196 N + 0.493 N/(m/s)^2 * v^2   (B unfittable, set 0)
  block 3   drivetrain loss map: spin loss 300 W + 40 W/(m/s) plus 6% of
            wheel power  (~91% battery->wheel at 100 kW, matching Ford's map)
  block 4   max DC charge power vs energy: E-GMP 800 V curve, ~235 kW peak,
            tapering to ~25 kW at 100%
  block 5   battery internal loss: I^2R with R = 0.1 ohm at 650 V
  block 7   aux/HVAC power vs outside temp (Q4 only, like the Ford sample):
            1500 W at -12 C, 250 W mild, 800 W at 45 C
  block 8   charge-time tables per charger power, integrated from block 4
            with AC limits (10.9 kW onboard, 1.44 kW L1)
  omitted   1.5, 1.8-1.12, 6, 9, 10 (the Ford sample works without them)

Flow: generate protobuf text -> protoc --encode -> widen the current-energy
varint to 5 bytes (length prefixes fixed up) -> wrap as the AA message
[0x80 0x03][BA 01 len][model] -> emit EvModelTemplate.java.

Outputs:
  dist/ioniq5_model.txt    generated protobuf text (review/diff aid)
  dist/ev_model.bin        bare VehicleEnergyModel (protoc --decode checks)
  dist/ev_batch.bin        the full AA message the head unit queues
  gpm-ev/src/com/google/android/projection/protocol/EvModelTemplate.java
"""

import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from protoutil import (  # noqa: E402
    fixed_varint,
    parse_fields,
    read_varint,
    write_varint,
)

ROOT = Path(__file__).resolve().parent.parent
PROTO = ROOT / "reference/vehicle_energy_model.proto"
OUT_TXT = ROOT / "dist/ioniq5_model.txt"
OUT_MODEL = ROOT / "dist/ev_model.bin"
OUT_BATCH = ROOT / "dist/ev_batch.bin"
OUT_JAVA = (
    ROOT
    / "gpm-ev/src/com/google/android/projection/protocol/EvModelTemplate.java"
)

CAPACITY_WH = 77400  # 2022 Ioniq 5 LR (US); runtime may shrink via SOH
RESERVE_WH = 2400    # ~3% of capacity
PLACEHOLDER_WH = 50000
ENERGY_WIDTH = 5
CAPACITY_WIDTH = 5   # runtime-patchable too (degradation via SOH probe)
AIR_DENSITY = 1.225  # kg/m^3 at 15 C sea level
OUTSIDE_TEMP_C = 15.0  # static for now; live temp needs DATC listener (future)

# Road load from reference/IONIQ5 roll-down test.xlsx (A, B, C).
ROAD_LOAD = (196.0, 0.0, 0.493)

# Drivetrain loss: spin(v) + 6% of wheel power. Spin grows with speed like
# Ford's map (450 W @ 0 -> 2.7 kW @ 36 m/s) but leaner. Negative x = regen
# (experiment: sint32 allows it, Ford omits it; whether the planner uses it
# is unknown - validate on a hilly route).
DL_SPEEDS_MPS = [0.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 35.0]
DL_POWERS_W = [-150000, -100000, -50000, -20000,
               0, 10000, 25000, 50000, 75000, 100000, 150000, 191000]
DL_SPIN = lambda v: 300 + 40 * v
DL_FRACTION = 0.06

# Max DC charge power vs absolute battery energy. Full 3C from 0% (no low-SOC
# ramp-in on a warm pack), plateau to ~50%, thermal step-down to ~160 kW held
# until the pause. BOTH the step-down and the pause are thermal (heat added
# THIS session), not absolute SOC: the step lands ~55-60% from a 0% start,
# ~65-70% from 10%, ~75-80% from 30%. An energy-indexed model can't express
# that, so the step sits at 53%: calibrated so the canonical road-trip number,
# 10->80% on a 350 kW charger, is ~17.5 min (owner's logbook; Hyundai's
# official 18). The pause likewise sits at ~81% (78-85% by start SOC).
CHARGE_CURVE = [
    (0, 230000),       # full send from 0%
    (20000, 235000),   # ~26%: peak
    (38700, 228000),   # 50%: plateau end
    (41022, 160000),   # 53%: thermal step-down (see note above)
    (61920, 160000),   # 80%: 160 kW holds until the thermal pause (~81%)
    (63468, 150000),   # 82%: post-pause resume level
    (69660, 60000),    # 90%
    (73530, 35000),    # 95%
    (75800, 22000),    # ~98%
    (77400, 15000),    # 100%  (95->100% avg ~23 kW, measured)
]

# Thermal pause: on fast sessions (>=200 kW charger) the car stops charging
# for 3-6 min when the hottest cell hits ~50-55 C - owner-observed at 78-82%
# on a warm pack. Placed just past 80% so a stop that ends AT 80% ("leave when
# the limit hits") doesn't pay for it. Modelled as a flat time insertion in
# the per-charger time tables only - a power-vs-energy curve cannot express a
# stop, and slow sessions never trigger it.
PAUSE_MIN_CHARGER_W = 200000
PAUSE_AT_WH = 63000        # ~81%
PAUSE_SECONDS = 300

# Battery internal loss: I^2R, R = 0.1 ohm, ~650 V nominal.
BL_OHM = 0.1
BL_VOLT = 650.0
BL_POWERS_W = list(range(0, 200001, 25000))

# Single warm-pack model: for road-trip planning the car preconditions on the
# way to a charger, so the ideal-temp curve is the realistic one. Measured
# cold-pack caps (not shipped; see docs/ENERGY-MODEL.md "field findings"):
# <20C ~160 kW, <15C ~120 kW, <10C ~90 kW.

# Aux/HVAC power vs outside temp (Q4). Calibrated against the car's live
# "climate" + "electronics" readouts: ~850 W total at -3 C, ~350-400 W
# electronics floor at mild temps.
AUX_TABLE = [
    (-20.0, 1800), (-12.0, 1400), (-5.0, 850), (5.0, 600), (12.0, 450),
    (18.0, 400), (24.0, 400), (30.0, 550), (35.0, 700), (40.0, 850),
    (45.0, 950),
]

# Charger powers for the time tables (DC + 10.9 kW AC + 1.44 kW L1).
CHARGERS_W = [350000, 250000, 200000, 175000, 150000, 125000, 100000,
              50000, 25000, 10900, 1440]
CT_STEP_WH = 3870  # 5% of capacity

# The 4.1 curve is charger-side power (what the dash/EA report). The time
# tables integrate battery-side energy, so scale by the charge efficiency:
# I^2R + cooling/pumps. Fitted against 125 real EA sessions (see
# tools/analyze_charge_history.py); eta = 1.0 biases the model ~1.8 min fast.
CHARGE_ETA = 0.95  # fitted on two deep warm sessions (0.955, 0.944)


def interp_curve(energy_wh: float) -> float:
    """Max charge power at a given energy, linear on CHARGE_CURVE."""
    pts = CHARGE_CURVE
    if energy_wh <= pts[0][0]:
        return float(pts[0][1])
    if energy_wh >= pts[-1][0]:
        return float(pts[-1][1])
    for (e0, p0), (e1, p1) in zip(pts, pts[1:]):
        if e0 <= energy_wh <= e1:
            return p0 + (p1 - p0) * (energy_wh - e0) / (e1 - e0)
    raise AssertionError


def charge_table(charger_w: int) -> list[tuple[int, int]]:
    """Cumulative seconds to reach each energy step on this charger.

    Fast chargers (>= PAUSE_MIN_CHARGER_W) get the thermal pause inserted as
    a flat +PAUSE_SECONDS when cumulative energy crosses PAUSE_AT_WH.
    """
    out = [(0, 0)]
    seconds = 0.0
    paused = False
    e = CT_STEP_WH
    while e <= CAPACITY_WH:
        # segment midpoint power, charger-side; scale to battery-side energy
        p = min(charger_w, interp_curve(e - CT_STEP_WH / 2)) * CHARGE_ETA
        seconds += 3600.0 * CT_STEP_WH / p
        if (
            not paused
            and charger_w >= PAUSE_MIN_CHARGER_W
            and e >= PAUSE_AT_WH
        ):
            seconds += PAUSE_SECONDS
            paused = True
        out.append((e, round(seconds)))
        e += CT_STEP_WH
    return out


def gen_text() -> str:
    L = []
    a = L.append
    a("battery {")
    a(f"  reserve_energy_wh {{ value: {RESERVE_WH} }}")
    a(f"  current_energy_wh {{ value: {PLACEHOLDER_WH} }}")
    a(f"  capacity_wh {{ value: {CAPACITY_WH} }}")
    a(f"  air_density: {AIR_DENSITY}")
    a(f"  outside_temp_c: {OUTSIDE_TEMP_C}")
    a("}")
    a("road_load {")
    a(f"  a_n {{ value: {ROAD_LOAD[0]} }}")
    a(f"  b_n_per_mps {{ value: {ROAD_LOAD[1]} }}")
    a(f"  c_n_per_mps2 {{ value: {ROAD_LOAD[2]} }}")
    a("}")
    a("drivetrain_loss {")
    for v in DL_SPEEDS_MPS:
        a("  rows {")
        a(f"    speed_mps: {v}")
        for p in DL_POWERS_W:
            loss = round(DL_SPIN(v) + DL_FRACTION * abs(p))
            a(f"    points {{ x: {p} y {{ value: {loss} }} }}")
        a("  }")
    a("}")
    a("charge_limits {")
    for e, p in CHARGE_CURVE:
        a(f"  max_charge_power {{ energy_wh: {e} power_w: {p} }}")
    a("}")
    a("battery_loss {")
    for p in BL_POWERS_W:
        loss = round(BL_OHM * (p / BL_VOLT) ** 2)
        a(f"  points {{ x: {p} y {{ value: {loss} }} }}")
    a("}")
    for t, w in AUX_TABLE:
        a(f"aux_power {{ outside_temp_c: {t} power4_w {{ value: {w} }} }}")
    a("charge_time {")
    for c in CHARGERS_W:
        a("  by_charger_power {")
        a(f"    charger_power_w: {c}")
        for e, s in charge_table(c):
            a(f"    points {{ energy_wh: {e} seconds: {s} }}")
        a("  }")
    a("}")
    return "\n".join(L) + "\n"


def widen_quantity(model: bytearray, bf: dict, batt, num: int, width: int) -> int:
    """Widen battery field num's Quantity.value varint to width bytes in place.

    Fixes the Quantity and Battery length prefixes and KEEPS THE Field OBJECTS
    CURRENT (callers re-parse using batt.val_len). Returns the varint's
    absolute offset within the model.
    """
    q = parse_fields(model, bf[num].val_off, bf[num].val_off + bf[num].val_len)
    assert set(q) == {1}, "Quantity must carry only field 1 (value)"
    cur = q[1]
    delta = width - cur.val_len
    assert delta > 0 and cur.val_len == 3, cur.val_len
    val, _ = read_varint(model, cur.val_off)
    model[cur.val_off : cur.val_off + cur.val_len] = fixed_varint(val, width)
    for f in (bf[num], batt):
        assert f.len_len == 1 and f.val_len + delta < 0x80
        model[f.len_off] = f.val_len + delta
        f.val_len += delta
    return cur.val_off


def widen_runtime_fields(model: bytearray) -> tuple[int, int]:
    """Widen current_energy (1.3) and capacity (1.4) for in-place patching.

    Returns (energy_offset, capacity_offset) within the model. Energy (field 3)
    is widened first: its splice shifts capacity's offset, so capacity is
    located from a fresh parse afterwards.
    """
    top = parse_fields(model, 0, len(model))
    assert set(top) == {1, 2, 3, 4, 5, 7, 8}, f"unexpected top fields {sorted(top)}"
    batt = top[1]
    bf = parse_fields(model, batt.val_off, batt.val_off + batt.val_len)
    assert set(bf) == {2, 3, 4, 6, 7}, f"unexpected battery fields {sorted(bf)}"
    energy_off = widen_quantity(model, bf, batt, 3, ENERGY_WIDTH)
    bf = parse_fields(model, batt.val_off, batt.val_off + batt.val_len)
    cap_off = widen_quantity(model, bf, batt, 4, CAPACITY_WIDTH)
    return energy_off, cap_off


def verify(model: bytes) -> None:
    top = parse_fields(model, 0, len(model))
    batt = top[1]
    bf = parse_fields(model, batt.val_off, batt.val_off + batt.val_len)
    vals = {}
    for num in (2, 3, 4):
        q = parse_fields(model, bf[num].val_off, bf[num].val_off + bf[num].val_len)[1]
        vals[num] = read_varint(model, q.val_off)[0]
    assert vals == {2: RESERVE_WH, 3: PLACEHOLDER_WH, 4: CAPACITY_WH}, vals
    # protoc must accept the result and see the same values
    r = subprocess.run(
        ["protoc", "-I", str(PROTO.parent), "--decode=vem.VehicleEnergyModel", PROTO.name],
        input=model,
        capture_output=True,
    )
    assert r.returncode == 0, r.stderr.decode()
    txt = r.stdout.decode()
    for want in (f"value: {RESERVE_WH}", f"value: {PLACEHOLDER_WH}",
                 f"value: {CAPACITY_WH}", "value: 196", "value: 0.493"):
        assert want in txt, want


def main() -> int:
    import base64

    text = gen_text()
    OUT_TXT.write_text(text)
    r = subprocess.run(
        ["protoc", "-I", str(PROTO.parent), "--encode=vem.VehicleEnergyModel", PROTO.name],
        input=text.encode(),
        capture_output=True,
    )
    if r.returncode != 0:
        print(r.stderr.decode())
        return 1
    model = bytearray(r.stdout)

    energy_off, cap_off = widen_runtime_fields(model)
    verify(bytes(model))
    # battery.outside_temp_c (field 7, wire type 5) - float bits patch point
    top = parse_fields(model, 0, len(model))
    batt = top[1]
    bf = parse_fields(model, batt.val_off, batt.val_off + batt.val_len)
    assert bf[7].wire == 5 and bf[7].val_len == 4
    temp_off_in_model = bf[7].val_off

    batch = (
        b"\x80\x03"
        + write_varint(23 << 3 | 2)
        + write_varint(len(model))
        + bytes(model)
    )
    hdr = len(batch) - len(model)
    energy_off += hdr
    cap_off += hdr
    temp_off = temp_off_in_model + hdr
    print(f"model {len(model)} bytes, batch {len(batch)} bytes; offsets: "
          f"energy@{energy_off} capacity@{cap_off} temp@{temp_off}")

    OUT_MODEL.write_bytes(bytes(model))
    OUT_BATCH.write_bytes(batch)

    b64 = base64.b64encode(batch).decode()
    lines = [b64[i : i + 100] for i in range(0, len(b64), 100)]
    concat = "\n".join(f'        + "{line}"' for line in lines[1:])
    OUT_JAVA.parent.mkdir(parents=True, exist_ok=True)
    OUT_JAVA.write_text(
        "// GENERATED by tools/build_ioniq5_model.py - do not edit.\n"
        "package com.google.android.projection.protocol;\n\n"
        "/** SensorBatch(23) Ioniq 5 model: warm-pack (preconditioned) curve,\n"
        " *  runtime-patchable energy, capacity and outside temperature. */\n"
        "final class EvModelTemplate {\n"
        "    static final int CAPACITY_WH = %d;\n"
        "    static final int CAPACITY_OFFSET = %d;\n"
        "    static final int CAPACITY_WIDTH = %d;\n"
        "    static final int ENERGY_OFFSET = %d;\n"
        "    static final int ENERGY_WIDTH = %d;\n"
        "    static final int ENERGY_PLACEHOLDER_WH = %d;\n"
        "    static final int TEMP_OFFSET = %d;  // outside_temp_c float bits\n"
        '    static final String TEMPLATE_B64 =\n'
        '        "%s"\n'
        "%s;\n\n"
        "    private EvModelTemplate() {}\n"
        "}\n"
        % (CAPACITY_WH, cap_off, CAPACITY_WIDTH, energy_off, ENERGY_WIDTH,
           PLACEHOLDER_WH, temp_off, lines[0], concat)
    )
    print(f"wrote {OUT_TXT.relative_to(ROOT)}, {OUT_MODEL.relative_to(ROOT)}, "
          f"{OUT_BATCH.relative_to(ROOT)}, {OUT_JAVA.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

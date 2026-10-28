#!/usr/bin/env python3
"""Analyze real EA charging sessions against the model's charge tables.

reference/charge_history*.csv have: end SOC, energy delivered, durations,
max charging rate - but no start SOC. Start is inferred from energy and the
pack capacity. Output: per-session table + actual-vs-model duration stats,
and a plot (dist/graphs/5_model_vs_actual.png).
"""

import csv
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_ioniq5_model import CAPACITY_WH, charge_table, interp_curve  # noqa

ROOT = Path(__file__).resolve().parent.parent


def parse_duration(s):
    """'20 min 42 sec' or '1 hr 13 min 5 sec' -> seconds; None on junk."""
    s = s.strip()
    m = re.match(r"^(\d+) min (\d+) sec$", s)
    if m:
        return int(m.group(1)) * 60 + int(m.group(2))
    m = re.match(r"^(\d+) hr (\d+) min (\d+) sec$", s)
    if m:
        return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3))
    return None


def load():
    sessions = {}
    for path in sorted((ROOT / "reference").glob("charge_history*.csv")):
        for row in csv.DictReader(open(path)):
            sid = row["SessionID"]
            try:
                end_pct = float(row["EndStateOfCharge"].rstrip("%"))
                kwh = float(row["TotalEnergyDelivered"].replace(" kWh", ""))
                maxkw = float(row["MaxChargingRate"].replace(" kW", ""))
                dur = parse_duration(row["ChargeDuration"])
                if not dur:  # EA glitch: ChargeDuration 0/negative on some
                    dur = parse_duration(row["TotalDuration"])  # old sessions
                    grace = parse_duration(row["GracePeriodDuration"]) or 0
                    if dur:
                        dur -= grace
            except (ValueError, AttributeError):
                continue
            if dur and dur > 60 and kwh > 2 and end_pct > 5:
                sessions[sid] = dict(
                    sid=sid, date=row["Date"], loc=row["LocationName"][:28],
                    end_pct=end_pct, kwh=kwh, maxkw=maxkw, dur_s=dur,
                )
    return list(sessions.values())


# Exact start SOCs spot-pulled from the EA app (calibration anchors), keyed by
# EA SessionID. Personal data: kept in reference/spot_starts.json, not published.
_SPOT = ROOT / "reference/spot_starts.json"
SPOT_STARTS = json.loads(_SPOT.read_text()) if _SPOT.exists() else {}


def charger_class(maxkw):
    """Guess the EA cabinet's rated class from the peak the car saw."""
    if maxkw > 175:
        return 350000
    if maxkw > 110:
        return 150000
    return None  # derated/broken/shared - no table match


def t_at(pts, e):
    for (e0, s0), (e1, s1) in zip(pts, pts[1:]):
        if e0 <= e <= e1:
            return s0 + (s1 - s0) * (e - e0) / (e1 - e0)
    return pts[-1][1]


def main():
    ss = load()
    ss.sort(key=lambda s: s["date"])
    print(f"{len(ss)} sessions\n")
    print(f"{'date':<22} {'end%':>4} {'start%':>6} {'kWh':>6} {'maxkW':>6} "
          f"{'avgkW':>6} {'real':>5} {'model':>5} {'Δmin':>5}  class")
    results = []
    for s in ss:
        e_used = s["kwh"] * 1000
        start_pct = SPOT_STARTS.get(
            s["sid"], s["end_pct"] - 100.0 * e_used / CAPACITY_WH)
        avg = e_used / s["dur_s"] * 3.6  # kW
        cls = charger_class(s["maxkw"])
        model_min = None
        if cls and start_pct >= 0:
            pts = charge_table(cls)
            e0 = start_pct / 100 * CAPACITY_WH
            e1 = s["end_pct"] / 100 * CAPACITY_WH
            model_min = (t_at(pts, e1) - t_at(pts, e0)) / 60.0
            results.append((s["dur_s"] / 60.0, model_min))
        print(f"{s['date'][:20]:<22} {s['end_pct']:4.0f} {start_pct:6.1f} "
              f"{s['kwh']:6.1f} {s['maxkw']:6.0f} {avg:6.0f} "
              f"{s['dur_s']/60:5.1f} "
              + (f"{model_min:5.1f} {model_min - s['dur_s']/60.0:+5.1f}"
                 if model_min else "    -     -")
              + f"  {('350' if cls == 350000 else '150' if cls else '???')}")
    if results:
        err = [m - a for a, m in results]
        print(f"\nmodel vs actual: mean err {sum(err)/len(err):+.1f} min, "
              f"mean |err| {sum(abs(e) for e in err)/len(err):.1f} min, "
              f"n={len(err)}")
    return ss, results


if __name__ == "__main__":
    main()

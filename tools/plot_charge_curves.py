#!/usr/bin/env python3
"""Plot the Ioniq 5 model's charging behaviour for sanity checking.

Reads the real constants from tools/build_ioniq5_model.py (single source of
truth - what ships is what's plotted). PNGs go to dist/graphs/.
"""

import sys
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_ioniq5_model import (
    CAPACITY_WH,
    CHARGE_CURVE,
    CHARGERS_W,
    CT_STEP_WH,
    PAUSE_AT_WH,
    PAUSE_MIN_CHARGER_W,
    PAUSE_SECONDS,
    charge_table,
    interp_curve,
)

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "dist/graphs"
OUT.mkdir(parents=True, exist_ok=True)

PCT = lambda e: 100.0 * e / CAPACITY_WH


def curve_xy():
    xs = [PCT(e) for e, _ in CHARGE_CURVE]
    ys = [p / 1000.0 for _, p in CHARGE_CURVE]
    return xs, ys


def table_xy(charger):
    pts = charge_table(charger)
    return [PCT(e) for e, _ in pts], [s / 60.0 for _, s in pts]


def effective_power(charger, e):
    return min(charger, interp_curve(e)) / 1000.0


# --- Fig 1: car capability curve (block 4.1) --------------------------------
fig, ax = plt.subplots(figsize=(9, 5))
xs, ys = curve_xy()
ax.plot(xs, ys, "o-", lw=2, color="tab:blue")
for x, y in zip(xs, ys):
    ax.annotate(f"{y:.0f}", (x, y), textcoords="offset points", xytext=(0, 7),
                ha="center", fontsize=8)
ax.set_title("Model block 4.1: max charge power vs SOC (car capability, warm pack)")
ax.set_xlabel("SOC %")
ax.set_ylabel("kW")
ax.grid(alpha=0.3)
ax.set_xlim(0, 100)
fig.tight_layout()
fig.savefig(OUT / "1_capability_curve.png", dpi=110)

# --- Fig 2: effective power per charger -------------------------------------
fig, ax = plt.subplots(figsize=(9, 5.5))
es = np.linspace(0, CAPACITY_WH, 200)
for c in [350000, 250000, 200000, 150000, 125000, 100000, 50000, 25000]:
    ax.plot([PCT(e) for e in es], [effective_power(c, e) for e in es],
            label=f"{c//1000} kW")
ax.set_title("Effective charge power vs SOC per charger (min(charger, curve))")
ax.set_xlabel("SOC %")
ax.set_ylabel("kW")
ax.legend(title="charger", ncol=2, fontsize=8)
ax.grid(alpha=0.3)
ax.set_xlim(0, 100)
fig.tight_layout()
fig.savefig(OUT / "2_effective_power_per_charger.png", dpi=110)

# --- Fig 3: cumulative charge time from 0%, with pause ----------------------
fig, ax = plt.subplots(figsize=(9, 5.5))
for c in [350000, 250000, 200000, 150000, 125000, 100000, 50000]:
    xs, ys = table_xy(c)
    ax.plot(xs, ys, lw=2, label=f"{c//1000} kW")
# annotate the pause once (it appears on every >=200 kW curve at the same x)
_ys350 = table_xy(350000)[1]
_idx = next(i for i, (e, _s) in enumerate(charge_table(350000))
            if e >= PAUSE_AT_WH)
ax.annotate(f"+{PAUSE_SECONDS//60} min thermal pause\n(≥200 kW chargers only)",
            (PCT(PAUSE_AT_WH), _ys350[_idx]),
            xytext=(PCT(PAUSE_AT_WH) + 3, 32),
            arrowprops=dict(arrowstyle="->", color="gray"),
            fontsize=8, color="gray")
ax.axvspan(10, 80, color="tab:green", alpha=0.06)
ax.text(45, 1, "typical 10-80% window", ha="center", fontsize=8,
        color="tab:green")
ax.set_title("Model block 8.1: cumulative charge time from 0% (DC)")
ax.set_xlabel("SOC %")
ax.set_ylabel("minutes")
ax.legend(title="charger", fontsize=8)
ax.grid(alpha=0.3)
ax.set_xlim(0, 100)
fig.tight_layout()
fig.savefig(OUT / "3_cumulative_time.png", dpi=110)

# --- Fig 4: 10->80% and 15->82% durations ------------------------------------
def span_minutes(charger, lo_pct, hi_pct):
    pts = charge_table(charger)
    lo = int(round(lo_pct / 100.0 * CAPACITY_WH))
    hi = int(round(hi_pct / 100.0 * CAPACITY_WH))
    # tables are on a CT_STEP_WH grid; interpolate linearly
    def t_at(e):
        for (e0, s0), (e1, s1) in zip(pts, pts[1:]):
            if e0 <= e <= e1:
                return s0 + (s1 - s0) * (e - e0) / (e1 - e0)
        return pts[-1][1]
    return (t_at(hi) - t_at(lo)) / 60.0


chargers = [350000, 250000, 200000, 175000, 150000, 125000, 100000, 50000, 25000]
labels = [f"{c//1000}" for c in chargers]
t1080 = [span_minutes(c, 10, 80) for c in chargers]

fig, ax = plt.subplots(figsize=(9, 5))
bars = ax.bar(labels, t1080, color="tab:blue")
for b, t in zip(bars, t1080):
    ax.annotate(f"{t:.0f}", (b.get_x() + b.get_width() / 2, t),
                ha="center", va="bottom", fontsize=9)
ax.set_title("10 → 80% charge time per charger (minutes, incl. thermal pause "
             "on ≥200 kW)")
ax.set_xlabel("charger kW")
ax.set_ylabel("minutes")
ax.grid(axis="y", alpha=0.3)
fig.tight_layout()
fig.savefig(OUT / "4_10_to_80_minutes.png", dpi=110)

# --- Fig 5: model vs actual EA sessions --------------------------------------
try:
    from analyze_charge_history import charger_class, load, t_at
    fig, ax = plt.subplots(figsize=(7, 7))
    pts350, pts150 = charge_table(350000), charge_table(150000)
    for s in load():
        e_used = s["kwh"] * 1000
        start = s["end_pct"] - 100.0 * e_used / CAPACITY_WH
        cls = charger_class(s["maxkw"])
        if not cls or start < 0:
            continue
        pts = pts350 if cls == 350000 else pts150
        model = (t_at(pts, s["end_pct"] / 100 * CAPACITY_WH)
                 - t_at(pts, start / 100 * CAPACITY_WH)) / 60.0
        color = "tab:blue" if cls == 350000 else "tab:orange"
        ax.plot(s["dur_s"] / 60.0, model, "o", color=color, alpha=0.6)
    lim = 45
    ax.plot([0, lim], [0, lim], "k--", lw=1)
    ax.set_xlim(0, lim)
    ax.set_ylim(0, lim)
    ax.set_xlabel("actual minutes")
    ax.set_ylabel("model minutes")
    ax.set_title("Model vs real EA sessions (blue=350 kW class, orange=150)")
    ax.grid(alpha=0.3)
    fig.tight_layout()
    fig.savefig(OUT / "5_model_vs_actual.png", dpi=110)
except Exception as e:  # analysis is auxiliary; never fail the plot script
    print("fig5 skipped:", e)

# --- console summary ---------------------------------------------------------
print("10->80% minutes:", {f"{c//1000}kW": round(t) for c, t in zip(chargers, t1080)})
print("15->82%% on 125 kW: %.1f min (user expects ~26)" % span_minutes(125000, 15, 82))
print("15->82%% on 350 kW: %.1f min (with pause)" % span_minutes(350000, 15, 82))
t_no_pause = None
saved = PAUSE_MIN_CHARGER_W
import build_ioniq5_model as m
m.PAUSE_MIN_CHARGER_W = 10**9  # disable
t_no_pause = span_minutes(350000, 15, 82)
m.PAUSE_MIN_CHARGER_W = saved
print("15->82%% on 350 kW: %.1f min (no pause)" % t_no_pause)
print("AC: 0->100%% on 10.9 kW: %.1f h; on 1.44 kW: %.1f h"
      % (span_minutes(10900, 0, 100) / 60, span_minutes(1440, 0, 100) / 60))
print("wrote", OUT)

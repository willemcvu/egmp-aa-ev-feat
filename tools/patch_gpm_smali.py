#!/usr/bin/env python3
"""Apply the EV smali edits to a copy of the Leafia v1.0.5 GPM smali tree.

Creates build/gpm-smali from private/car-current-leafia105/smali-gpm, installs
the compiled EvModelSender/EvModelTemplate classes, and edits SensorSource.smali:

  1. init(): force fuel types {10 (FUEL_TYPE_ELECTRIC)} and EV connectors
     {4 (COMBO_1), 1 (J1772)} instead of the UNKNOWN arrays GalReceiver passes.
  2. init(): after the normal sensor registration loop, register sensor 23
     (SENSOR_VEHICLE_ENERGY_MODEL_DATA) and start EvModelSender.
  3. shutdown(): stop EvModelSender before nativeShutdown() frees the native
     SensorSource object.
  4. New method sendRawMessage([B)I: exposes nativeReportDiagnosticsData so
     EvModelSender can queue a whole raw AA message (the patched library
     tail-branches it into ProtocolEndpointBase::queueOutgoing).

  5. shutdown(): also stop Precond (cancel an owned preconditioning request).

and GpmNaviTBTManager.smali (automatic battery preconditioning, see
docs/PRECONDITIONING.md; every call wrapped in a catch-all so a Precond
failure can never break navigation):

  6. <init>(Context): Precond.onSessionStart(ctx) -- thread + stale-journal
     recovery (cancel only).
  7. onNextTurn(): Precond.onNavDump(p1) in the type 0x21 (NAVDUMP) branch,
     before Leafia's log + return.
  8. onStatus(I): Precond.onNavStatus(p1) on entry.
  9. destroy(): Precond.stop() on entry (AA session teardown,
     GalConnectionSession.clearSession()).

The preconditioning hooks are not gated by persist.gpm.ev.off. Precond has
no switch: the car's own battery-conditioning EV setting is its off control.

GalReceiver.smali (which Leafia already patches) is not touched.

Idempotent: re-running rebuilds the tree from scratch. Fails loudly if any
anchor is not found exactly once.
"""

import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC_TREE = ROOT / "private/car-current-leafia105/smali-gpm"
NEW_SMALI = ROOT / "gpm-ev/build/smali"
BUILD_TREE = ROOT / "build/gpm-smali"
SENSOR_SOURCE = "com/google/android/projection/protocol/SensorSource.smali"
NAVI_TBT = "com/google/android/projection/sink/navigation/GpmNaviTBTManager.smali"
PRECOND = "Lcom/google/android/projection/protocol/Precond;"

MARK = "EV patch"

# --- Edit 1: replace the UNKNOWN fuel/connector arrays in init() -----------
# The off switch: persist.gpm.ev.off=1 keeps the original UNKNOWN arrays and
# skips sensor 23 entirely, no uninstall needed.
INIT_ARRAYS_OLD = """\
    :cond_5
    move-object v0, p0

    move v1, p1

    move-wide v2, p2

    move v4, p5

    move-object v5, p6

    move-object v6, p7

    invoke-direct/range {v0 .. v6}, Lcom/google/android/projection/protocol/SensorSource;->nativeInit(IJI[I[I)I
"""
INIT_ARRAYS_NEW = """\
    :cond_5
    # EV patch off switch: persist.gpm.ev.off=1 restores stock behaviour
    const-string v0, "persist.gpm.ev.off"

    const-string v1, "0"

    invoke-static {v0, v1}, Landroid/os/SystemProperties;->get(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    const-string v1, "1"

    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v0

    if-nez v0, :cond_ev_off

    # EV patch: fuelTypes = {10 (ELECTRIC)}, evConnectors = {4 (COMBO_1), 1 (J1772)}
    const/4 v0, 0x1

    new-array v5, v0, [I

    const/16 v0, 0xa

    const/4 v1, 0x0

    aput v0, v5, v1

    const/4 v0, 0x2

    new-array v6, v0, [I

    const/16 v0, 0x4

    aput v0, v6, v1

    const/4 v0, 0x1

    const/4 v1, 0x1

    aput v0, v6, v1

    goto :goto_ev_arrays

    :cond_ev_off
    move-object v5, p6

    move-object v6, p7

    :goto_ev_arrays
    move-object v0, p0

    move v1, p1

    move-wide v2, p2

    move v4, p5

    invoke-direct/range {v0 .. v6}, Lcom/google/android/projection/protocol/SensorSource;->nativeInit(IJI[I[I)I
"""

# --- Edit 2: register sensor 23 and start the sender ------------------------
REGISTER_OLD = """\
    :cond_1c
    iget-object v0, p0, Lcom/google/android/projection/protocol/SensorSource;->mGalReceiver:Lcom/google/android/projection/protocol/GalReceiver;
"""
REGISTER_NEW = """\
    :cond_1c
    # EV patch: register sensor 23 and start the sender (skipped when off)
    const-string v0, "persist.gpm.ev.off"

    const-string v1, "0"

    invoke-static {v0, v1}, Landroid/os/SystemProperties;->get(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    const-string v1, "1"

    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v0

    if-nez v0, :cond_ev_skip

    const/16 v1, 0x17

    invoke-direct {p0, v1}, Lcom/google/android/projection/protocol/SensorSource;->nativeRegisterSensor(I)V

    invoke-static {p0}, Lcom/google/android/projection/protocol/EvModelSender;->start(Lcom/google/android/projection/protocol/SensorSource;)V

    :cond_ev_skip
    iget-object v0, p0, Lcom/google/android/projection/protocol/SensorSource;->mGalReceiver:Lcom/google/android/projection/protocol/GalReceiver;
"""

# --- Edit 3: stop the sender before nativeShutdown() ------------------------
SHUTDOWN_OLD = """\
.method public shutdown()V
    .registers 2

    invoke-direct {p0}, Lcom/google/android/projection/protocol/SensorSource;->nativeShutdown()V
"""
SHUTDOWN_NEW = """\
.method public shutdown()V
    .registers 2

    # EV patch: stop the energy-model sender before the native object is freed
    invoke-static {}, Lcom/google/android/projection/protocol/EvModelSender;->stop()V

    invoke-direct {p0}, Lcom/google/android/projection/protocol/SensorSource;->nativeShutdown()V
"""

# --- Edit 4: raw send path for the batch ------------------------------------
SENDRAW_ANCHOR = """\
.method public shutdown()V
"""
SENDRAW_METHOD = """\
# EV patch: raw AA message send path (message type header included in arg)
.method public sendRawMessage([B)I
    .registers 3

    invoke-direct {p0, p1}, Lcom/google/android/projection/protocol/SensorSource;->nativeReportDiagnosticsData([B)I

    move-result v0

    return v0
.end method

.method public shutdown()V
"""

def guarded_call(label: str, invoke: str) -> str:
    """A static call wrapped in a catch-all that swallows the exception.

    Precond's entry points catch everything themselves; this also covers a
    class that fails to load or verify, so navigation keeps working. Same
    shape d8 emits for `try { f(); } catch (Throwable t) {}`. The handler
    clobbers v0: every hook point below writes v0 before reading it.
    """
    return f"""\
    # EV patch: automatic preconditioning hook
    :try_start_{label}
    {invoke}
    :try_end_{label}
    .catchall {{:try_start_{label} .. :try_end_{label}}} :catchall_{label}

    goto :goto_{label}

    :catchall_{label}
    move-exception v0

    :goto_{label}
"""


# --- Edit 5: stop Precond on GAL teardown too ------------------------------
# Applied after edit 3, so the anchor is the line edit 3 added.
SHUTDOWN_PRECOND_OLD = """\
    invoke-static {}, Lcom/google/android/projection/protocol/EvModelSender;->stop()V
"""
SHUTDOWN_PRECOND_NEW = SHUTDOWN_PRECOND_OLD + "\n" + guarded_call(
    "precond_shutdown", f"invoke-static {{}}, {PRECOND}->stop()V")

EDITS = [
    ("init() fuel/connector arrays", INIT_ARRAYS_OLD, INIT_ARRAYS_NEW),
    ("init() register sensor 23 + start", REGISTER_OLD, REGISTER_NEW),
    ("shutdown() stop sender", SHUTDOWN_OLD, SHUTDOWN_NEW),
    ("sendRawMessage", SENDRAW_ANCHOR, SENDRAW_METHOD),
    ("shutdown() stop precond", SHUTDOWN_PRECOND_OLD, SHUTDOWN_PRECOND_NEW),
]


# --- GpmNaviTBTManager: preconditioning hooks --------------------------------
TBT_INIT_OLD = """\
.method public constructor <init>(Landroid/content/Context;)V
    .registers 4

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
"""
TBT_INIT_NEW = TBT_INIT_OLD + "\n" + guarded_call(
    "precond_init",
    f"invoke-static {{p1}}, {PRECOND}->onSessionStart(Landroid/content/Context;)V")

TBT_NAVDUMP_OLD = """\
    const-string v0, "NAVDUMP"

    invoke-static {v0, p1}, Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I
"""
TBT_NAVDUMP_NEW = guarded_call(
    "precond_dump",
    f"invoke-static {{p1}}, {PRECOND}->onNavDump(Ljava/lang/String;)V") + "\n" + TBT_NAVDUMP_OLD

TBT_STATUS_OLD = """\
.method public onStatus(I)V
    .registers 4

"""
TBT_STATUS_NEW = TBT_STATUS_OLD + guarded_call(
    "precond_status", f"invoke-static {{p1}}, {PRECOND}->onNavStatus(I)V") + "\n"

TBT_DESTROY_OLD = """\
.method public destroy()V
    .registers 3

"""
TBT_DESTROY_NEW = TBT_DESTROY_OLD + guarded_call(
    "precond_stop", f"invoke-static {{}}, {PRECOND}->stop()V") + "\n"

NAVI_EDITS = [
    ("<init> precond session start", TBT_INIT_OLD, TBT_INIT_NEW),
    ("onNextTurn NAVDUMP -> precond", TBT_NAVDUMP_OLD, TBT_NAVDUMP_NEW),
    ("onStatus -> precond", TBT_STATUS_OLD, TBT_STATUS_NEW),
    ("destroy -> precond stop", TBT_DESTROY_OLD, TBT_DESTROY_NEW),
]

FILE_EDITS = [
    (SENSOR_SOURCE, EDITS),
    (NAVI_TBT, NAVI_EDITS),
]


def main() -> int:
    if BUILD_TREE.exists():
        shutil.rmtree(BUILD_TREE)
    shutil.copytree(SRC_TREE, BUILD_TREE)

    # Install the compiled classes.
    installed = []
    for smali in sorted(NEW_SMALI.rglob("*.smali")):
        rel = smali.relative_to(NEW_SMALI)
        dst = BUILD_TREE / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(smali, dst)
        installed.append(str(rel))
    print(f"installed {len(installed)} new classes: {installed}")

    for rel, edits in FILE_EDITS:
        path = BUILD_TREE / rel
        text = path.read_text()
        assert MARK not in text, f"{rel} already patched?"
        for name, old, new in edits:
            count = text.count(old)
            if count != 1:
                print(f"FATAL: anchor for '{name}' found {count} times (want 1)")
                return 1
            text = text.replace(old, new)
        path.write_text(text)
        print(f"patched {rel}: " + ", ".join(name for name, _, _ in edits))
    return 0


if __name__ == "__main__":
    sys.exit(main())

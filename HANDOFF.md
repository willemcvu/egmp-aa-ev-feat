# Handoff: Android Auto EV routing on a 2022 Ioniq 5 head unit

Date: 2026-10-27. Owner's own car (Hyundai Ioniq 5 US, Gen5W head unit `NEEV.USA.*` firmware 260507, rooted with NEMESIS, Leafia v1.0.5 installed).

## Goal

Make Google Maps on Android Auto treat the car as an EV: charging stops, arrival battery %, range. Maps does this when the head unit:
1. advertises fuel type ELECTRIC and the car's connector types, plus sensor 23 (vehicle energy model), in its service discovery response;
2. answers the phone's request for sensor 23 with success;
3. sends a `SensorBatch` (message 0x8003) whose field 23 carries an energy-model blob, repeatedly, with the live battery energy filled in.

The stock head unit does none of these. The task is to build, package and test a patch that adds them, working over both wired and wireless AA.

## Read first

| File | What it covers |
|---|---|
| `RESEARCH.md` §1–2 | head unit architecture, what Maps needs on the wire |
| `RESEARCH.md` §4 | findings on the stock AA library and GPM (why it can't work as shipped) |
| `RESEARCH.md` §5, §5a | the plan, and how to read live SOC/range on the head unit |
| `RESEARCH.md` §5c | dev setup (ADB over home Wi-Fi) and traps to avoid |
| **`RESEARCH.md` §5d** | **the patch design: base file md5s, the three library sites, the GPM changes, packaging and safety rules** |
| `ENERGY-MODEL.md` | the energy-model blob: recovered schema, field meanings, Ioniq 5 values |
| `reference/vehicle_energy_model.proto` | the schema; encode with `protoc --encode=vem.VehicleEnergyModel` |
| `packages/adb-home/` | example of a working NEMESIS package in this repo |

Local, gitignored inputs: `private/car-current-leafia105/` (the live GPM APK/odex, AA library, baksmali output), `private/car-neev-260507/smali/` (car framework incl. `HEVManager`), `private/staging/leafia-v1.0.5/` (Leafia package to copy install conventions from), `reference/ford_ev_model.bin` (known-good model blob).

## Current state

- Done: schema recovered and verified (the Ford sample re-encodes byte for byte); live SOC readable on the head unit; wired AA baseline captured; ADB dev setup working; patch sites identified (§5d).
- **Built (2026-10-27, offline-verified, not yet on the car):** `sh tools/build_all.sh` runs the whole pipeline:
  1. `tools/patch_library.py` → `dist/libautoreceiver_infobank.so` (md5 `37cbe6bf…`): the three §5d patches, verified to differ from Leafia's only at 7 intended bytes.
  2. `tools/build_ioniq5_model.py` → `dist/ev_model.bin` / `dist/ev_batch.bin` / generated `EvModelTemplate.java`: full Ioniq 5 model (rolldown road load 196/0/0.493, E-GMP charge curve, 6%+spin drivetrain map, I²R 0.1 Ω @ 650 V, aux-vs-temp, charge-time tables) with current-energy as a fixed-width 5-byte varint at template offset 16 so Java patches it in place. protoc round-trip verified. (Ford-sample variant: `tools/build_model_template.py`.)
  3. `gpm-ev/` (EvModelSender.java: 5 s loop, SOC via binder `com.hkmc.micom.hev` transaction 3 flag 0x19 → Wh = SOC×77400/100, min 500 Wh) compiled via javac→d8→baksmali (`gpm-ev/build.sh`).
  4. `tools/patch_gpm_smali.py` → `build/gpm-smali`: SensorSource.smali only — fuelTypes `{10}`, connectors `{4,1}`, register sensor 23, start/stop sender (lock discipline before `nativeShutdown`), new `sendRawMessage([B)I`. Off switch `persist.gpm.ev.off=1` gates both edits; GalReceiver untouched.
  5. `dist/HKMC_GPM.apk` = Leafia APK with only classes.dex replaced (verified).
  6. `packages/aa-ev-model/` NEMESIS package → `dist/aa.ev.model.zip`: md5-gated on the Leafia v1.0.5 files, snapshot + byte-for-byte uninstall only if live files are still ours, dexopt/dalvik handling copied from Leafia. `bash tools/test_package.sh` runs the real scripts in a host sandbox (install / uninstall / Leafia-removed-in-between / reinstall / unknown-base refusal): 21/21.
- **Not done:** car testing (wired first), the off-car aa-proxy-rs + Desktop Head Unit proof (§6 step 1, now optional since the car patch exists), nav battery packet layout, Ioniq 5-specific model blocks (road load from the rolldown, charging curve, aux table).

## Install and first car test (package built 2026-10-27)

1. `adb connect <car-ip>:5555` (adb.home, home Wi-Fi), `adb push dist/aa.ev.model.zip /data/local/tmp/`
2. `adb shell /system/xbin/nemesis install /data/local/tmp/aa.ev.model.zip`
3. Check `/data/nemesis/aa.ev.model.log` (`nemesis root -c 'cat …'`): expect `preinstall ok`, `OK: … replaced` ×2, `done. Reboot…`. Payload lib md5 on the unit: `37cbe6bfe7462444ca1f6d88013383d9`.
4. Reboot the HU.
5. Phone **Bluetooth off** (§5b trap), `adb logcat | grep -E "EvModelSender|SensorSource"` streaming **before** plugging in, then wired AA.
6. Success: `EvModelSender: started`, then `energy model sent: <N> Wh, rc=0` every 5 s, no SensorSource parse failure; Maps shows EV routing with battery % tracking the cluster SOC. Note: rc=0 also means "endpoint not open" per §5d's send-path notes, so Maps' EV UI is the real proof.
7. Off switch: `setprop persist.gpm.ev.off 1`, replug AA (persists across reboot; `0` to re-enable). Uninstall: `nemesis uninstall aa.ev.model` — restores Leafia files byte for byte (sandbox-tested), then reboot.

## Work to do

1. **Model blob.** Start with the Ford sample with only the battery block (field 1) replaced; aa-proxy-rs shows Maps accepts it from other cars. Later, generate the Ioniq 5 model from `ENERGY-MODEL.md`. Current energy (field 1.3) must always be > 0, or Maps ignores the model.
2. **GPM (Java) changes**, per §5d: fuel types `{ELECTRIC (10)}`, connectors `{COMBO_1 (4), J1772 (1)}`, register sensor 23, and a periodic sender that builds the batch from live SOC and capacity. Leafia already replaces GPM's `classes.dex`, so the change must be built on Leafia's dex, not stock.
3. **AA library changes**, per §5d: let type-23 sensor requests parse, reply success for them, and provide a raw send path for the batch. The base is Leafia's patched library (md5 in §5d); Leafia's own changes must stay intact.
4. **NEMESIS package** following Leafia's conventions (§5d "Packaging and safety requirements").
5. **Testing.** Safer first: prove the blob and handshake off-car with phone ↔ aa-proxy-rs ↔ Google's Desktop Head Unit (`RESEARCH.md` §6 step 1). Then on the car, wired first.

## Findings from the last session (not yet in RESEARCH.md)

- **Send path may not need a new code segment.** `SensorSource::reportDiagnosticsData(this, data, len)` (0xbb638) takes the same arguments as the queue function `sendSensorBatch` uses (0xb1972, `this` = the SensorSource). Redirecting it there would send whatever bytes Java passes as a whole message. The message format the queue expects is `[type hi][type lo][serialized proto]`, i.e. `80 03` + SensorBatch bytes. The queue copies the data into frames before returning. Return: 0 queued, −5 no channel, 0 endpoint not open.
- **Widening `SensorType_IsValid` (0x144ec0) is low-risk.** Only three parsers call it: `SensorSourceService_Sensor`, `SensorRequest` and `SensorError`. The head unit's own discovery serializer doesn't.
- **GPM changes can all go in `SensorSource`** (`com/google/android/projection/protocol/SensorSource.smali`). `init(IJ[II[I[I)Z` receives the sensor, fuel-type and connector arrays and registers each sensor. Every teardown goes through `shutdown()`. Editing only these two methods avoids touching Leafia's `GalReceiver` changes. A sender thread must stop before `nativeShutdown()` runs (e.g. a lock held while sending and taken by `shutdown()`), or it can call into a freed native object.
- **Live data from inside GPM:** binder service `com.hkmc.micom.hev`, interface token `com.hkmc.hev.IHEVService`, transaction 3 = `getHEVStatus(int flag)`, reply = `readException()` then `readInt()`. Flags and units in `RESEARCH.md` §5a (SOC 0x19 in 0.5 % steps; capacity 0x35 in 2 Wh steps). GPM runs as the system uid (`android.uid.system`, persistent).
- **APK signing is not a blocker.** Leafia's GPM APK keeps the original signed `META-INF` even though its `classes.dex` digest no longer matches, and the car accepts it.
- Useful tools: `baksmali`/`smali` 2.5.2, javac + d8 (build-tools 34) + `platforms/android-19`, and an ARM Thumb disassembler (capstone or Ghidra, both available).

## Requirements

- Gate install on the exact md5s of the live files (§5d). Refuse on any mismatch.
- Snapshot the live files; on uninstall, restore them byte for byte, but only if the live files are still ours (if Leafia was uninstalled in between, don't put Leafia's files back).
- Verify the patched library differs from Leafia's only at the intended bytes.
- Provide an off switch that doesn't need an uninstall.
- Neither file is boot-critical; the expected failure mode is AA not starting. Keep the uninstall path tested.

## Done when

- Over wired AA, the phone requests sensor 23 and gets a success reply (no `SensorSource` parse failure in logcat).
- Maps shows EV routing: battery % on the route, charging stops on long trips.
- Battery % in Maps tracks the car's SOC within a couple of percent.
- Uninstall restores Leafia's files exactly, and AA works as before.

## Open questions

- Does Maps need the batch only after it requests sensor 23, or is sending it unprompted fine? (aa-proxy-rs sends periodically.)
- Layout of the navigation battery packet (`EV_AVLBL_ENRG`), which would give degradation-aware usable energy (§5a).
- Outside temperature for model field 1.7 (`DatcBypassStatus.getOutTempC()` is a candidate).
- Meaning of the model's unidentified fields (`ENERGY-MODEL.md`); safe to leave out.

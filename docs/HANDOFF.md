# Handoff: Android Auto EV routing on a 2022 Ioniq 5 head unit

Updated: 2026-10-29 (v0.2.0). Owner's own car: Hyundai Ioniq 5 US, Gen5W head unit `NEEV.USA.*` firmware 260507, rooted with NEMESIS, Leafia v1.0.5 installed.

## Goal

Make Google Maps on Android Auto treat the car as an EV: charging stops, arrival battery %, range, charge times. Maps does this when the head unit:
1. advertises fuel type ELECTRIC and the car's connector types, plus sensor 23 (vehicle energy model), in its service discovery response;
2. answers the phone's request for sensor 23 with success;
3. sends a `SensorBatch` (message 0x8003) whose field 23 carries an energy-model blob, repeatedly, with the live battery energy filled in.

The stock head unit does none of these. On top of that the package now preconditions the battery for fast-charging stops and logs drives for calibration.

## Read first

| File | What it covers |
|---|---|
| `RESEARCH.md` §1–2 | head unit architecture, what Maps needs on the wire |
| `RESEARCH.md` §4, §5d | why the stock AA library and GPM can't do it; the patch design (base file md5s, the three library sites, GPM changes, packaging and safety rules) |
| `ENERGY-MODEL.md` | the energy-model blob: recovered schema, field meanings, Ioniq 5 values, capacity scaling, charge-time semantics |
| `DATA-SOURCES.md` | every vehicle signal the head unit exposes and how to read it |
| `PRECONDITIONING.md` | automatic preconditioning: car interface, Maps' nav messages, rules, tests |
| `LOGGING.md` | EV log format (v2) and tools |
| `reference/vehicle_energy_model.proto` | the schema; encode with `protoc --encode=vem.VehicleEnergyModel` |

Local, gitignored inputs: `private/car-current-leafia105/` (the live GPM APK/odex, AA library, baksmali output), `private/car-neev-260507/smali/` (car framework incl. `HEVManager`), `private/staging/leafia-v1.0.5/` (Leafia package to copy install conventions from), `reference/ford_ev_model.bin` (known-good model blob).

## Current state (v0.2.0, build test7, verified on the car 2026-10-29)

`sh tools/build_all.sh` runs the whole pipeline:
1. `tools/patch_library.py` → `dist/libautoreceiver_infobank.so`: the three §5d patches, verified to differ from Leafia's only at 7 intended bytes.
2. `tools/build_ioniq5_model.py` → `dist/ev_model.bin` / `dist/ev_batch.bin` / generated `EvModelTemplate.java`: the Ioniq 5 model (rolldown road load, drivetrain and battery losses, heater/aux table, cold road-load table, charge curve and 13 charge-time rows in headroom form), with offsets of every runtime-patched value.
3. `gpm-ev/` (javac → d8 → baksmali, `gpm-ev/build.sh`):
   - `EvModelSender`: every 5 s, SOC (HEV 0x19), BMS energy (HEV 0x31 × 2 Wh, sent as current energy), learned usable capacity (energy ÷ SOC, kept in `files/capacity_wh`), outside temp from the nav battery packet (raw × 0.5 − 40 °C) → model field 1.7 and road load A; scales the charge curve/tables to the capacity. Reads the car through `EvCar` (shared with the logger).
   - `Precond` / `PrecondCore` / `NavProto`: automatic preconditioning (`PRECONDITIONING.md`).
   - `EvDriveLog` / `EvRowAgg` / `EvLogger` / `EvCar`: the always-on EV log, one CSV per head-unit "on" period in GPM's files dir, 1 s samples, 5 s rows, written once a minute, 100 MB folder cap (`LOGGING.md`).
4. `tools/patch_gpm_smali.py` → `build/gpm-smali`: `SensorSource` (fuel type, connectors, sensor 23, sender start/stop, raw send), the `GpmNaviTBTManager` hooks for `Precond`, and the `StartReceiver`/`GalReceiverService` hooks that start the always-on `EvDriveLog`. `persist.gpm.ev.off=1` gates the `SensorSource` edits (not the EV log).
5. `dist/HKMC_GPM.apk` = Leafia APK with only `classes.dex` replaced.
6. `packages/aa-ev-model/` → `dist/aa.ev.model.zip`: md5-gated on the Leafia v1.0.5 files (or this package's own, for an upgrade), snapshot + byte-for-byte uninstall only if live files are still ours, plus the `evlog-offload` service. It carries only lpatch deltas, no Hyundai/Google binaries.

Verified on the car: EV routing; capacity ~62.9 kWh learned from the BMS; outside temp matches the dash; Maps' charge times look right; preconditioning REQUESTED → ACTIVE on short routes, cancel on route end (Off within ~2.5 s), stays REQUESTED on a 58-min route; EV logs (v1) copied to USB with the pop-up.

Tests (host): `sh gpm-ev/test/run.sh` (PrecondTest, SenderRowTest, ScaleTest), `bash tools/test_package.sh` (install / uninstall / upgrade / Leafia-removed-in-between / refusal / log offload in a sandbox), `sh tools/test_evlog.sh`.

## Build prerequisites

The build patches files pulled from your own head unit. Put them in `private/` (gitignored):

- `private/car-current-leafia105/`: `HKMC_GPM.apk`, `HKMC_GPM.odex`, `libautoreceiver_infobank.so` from a unit with Leafia v1.0.5, and `smali-gpm/` (baksmali of that APK's `classes.dex`)
- `private/car-neev-260507/LEAFIA/private/stock/app/HKMC_GPM.apk`: the stock GPM APK (Leafia's backup)
- Kia EV6: the same files in `private/ev6/leafia105/` and `private/ev6/stock/HKMC_GPM.apk`

`tools/cars.sh` lists each car's inputs. `build_all.sh` builds every car whose inputs are present (or `CARS="neev ev6"`), and the package carries one `files/<car>/` per car, picked on the unit from `ro.build.product`. `tools/test_package.sh` tests one car (`CAR=neev` or `CAR=ev6`).

Tools: Android SDK build-tools + `platforms/android-19`, smali/baksmali 2.5.2, Python with `capstone` and `bsdiff4`, `protoc`, `zip`, and `qemu-arm-static` (runs the ARM `lpatch` on the host).

## Install / upgrade

USB: copy the release zip's contents to a drive, plug it in, tap Yes (`release/README.txt`). Over ADB:

1. `adb connect <car-ip>:5555`, `adb push dist/aa.ev.model.zip /data/local/tmp/`
2. `adb shell /system/xbin/nemesis install --update /data/local/tmp/aa.ev.model.zip` (`--update` runs the installed version's preuninstall first; the package upgrades in place)
3. The head unit reboots by itself when the install succeeds (ADB drops). Check `/data/nemesis/aa.ev.model.log` (`nemesis root -c 'cat …'`): expect `rebuilt payloads …`, `preinstall ok`, `OK: … replaced` ×2, `done - rebooting`. A refused install changes nothing and doesn't reboot; the log says why.
4. Stream `adb logcat -v time -s EvModelSender EvPrecond EvDriveLog EvCar` **before** plugging in AA. Expect `energy model sent: <N> Wh of <C> …, out=<°C>` every 5 s.
5. Off switch: `setprop persist.gpm.ev.off 1`, replug AA (`0` to re-enable). Uninstall: NEMESIS Manager or `nemesis uninstall aa.ev.model`, then reboot; restores Leafia's files byte for byte.

## Troubleshooting (operational notes)

- **ADB drops when AA starts:** with the phone's Bluetooth on, wireless AA takes the head unit's single Wi-Fi radio for its hotspot and the unit leaves home Wi-Fi. Use wired AA with phone Bluetooth off while developing.
- **TCP ADB shows "offline":** usually a stale connection on the laptop side (`adb kill-server`, reconnect); otherwise toggle the head unit's Wi-Fi.
- **First boot after install:** GPM is re-optimized, so the AA button can take an extra minute.
- **Wi-Fi shows "error" after a reboot:** the Wi-Fi chip occasionally fails to power up. A full power-down of the car (not just a head-unit reboot) fixes it.
- **Root is limited:** NEMESIS root has only CAP_SETUID/SETGID, no DAC override. To read GPM's files, act as its system uid: `nemesis root 1000 -c '…'`.
- **No text tools on the unit:** no `sed`, `awk`, `wc`, `head`, `tail` or `stat`. Scripts use `ls -l`, `cmp`, `grep` and shell builtins.
- **Logs:** install `/data/nemesis/aa.ev.model.log`; offload `/data/nemesis/aa.ev.model.offload.log`; EV logs `/data/data/com.google.android.projection.sink/files/evlog/`.

## Requirements (unchanged)

- Gate install on the exact md5s of the live files (§5d). Refuse on any mismatch.
- Snapshot the live files; on uninstall, restore them byte for byte, but only if the live files are still ours.
- Verify the patched library differs from Leafia's only at the intended bytes.
- Provide an off switch that doesn't need an uninstall.
- Neither file is boot-critical; the expected failure mode is AA not starting. Keep the uninstall path tested.
- Car-side writes: only the preconditioning profile (HEV 62/65/66), only on the framework it was verified on (SHA-256 of `automotive.odex` / `automotive-service.odex`). Everything else is read-only.

## Open questions

- Usable capacity: the BMS gives ~62.9 kWh, but recent DC sessions suggest ~70–71 kWh. A logged DC charge (ΔENERGYMAP vs charger kWh) settles it (`ROADMAP.md` item 3).
- Is the single 0x8007 entry always the next stop on a real multi-stop drive?
- Does Maps' server use the 4.1 curve, the 8.1 tables or both, and how does it treat AC rows (`ENERGY-MODEL.md`)?
- Meaning of the model's unidentified fields; safe to leave out.

# egmp-aa-ev-feat

Adding an energy model and live vehicle data to Android Auto on Hyundai/Kia Gen5W head units, so Google Maps does real EV routing: battery % on arrival, charging stops and range. Built on [NEMESIS](https://github.com/xwtk/hmg_nemesis) and [HMG Leafia Plus](https://github.com/VysseCodes/HMG-Leafia-Plus) for the 2022 Ioniq 5.

Out of the box the head unit tells Android Auto nothing about the car's fuel type or battery, so Maps routes it like a gas car. This project patches the head unit's Android Auto stack so it does what EVs with native Android Auto EV support do: it advertises an electric car with CCS1/J1772 ports, and every 5 seconds it sends Maps a vehicle energy model with the car's live state of charge.

> [!WARNING]
> This modifies files on your car's infotainment system. The installer checks every file before touching it, keeps a byte-for-byte backup and refuses anything it doesn't recognise, but you use it at your own risk. Not affiliated with Hyundai, Kia or Google.

## Status

| | |
|---|---|
| Car | Hyundai Ioniq 5 (US), 2022, Gen5W `NEEV.USA.0000.V015.012.260507` |
| Requires | NEMESIS + HMG Leafia Plus **v1.0.5** (the installer refuses other versions) |
| Works | Maps EV routing over Android Auto: battery on arrival, charging stops, range from live SOC. Tested over **wired** AA; wireless uses the same code path but isn't confirmed yet. |
| Not yet | Other E-GMP cars/firmware; automatic battery preconditioning ([research](docs/PRECONDITIONING.md)) |

## Install

1. Download `egmp-aa-ev-feat-<version>-usb.zip` from [Releases](../../releases).
2. Copy its **contents** to the root of an empty FAT32/exFAT USB drive.
3. Car on, plug the drive into a USB data port, tap **Yes** when NEMESIS asks. The head unit restarts by itself when it's done; remove the drive while it restarts.

Uninstall from NEMESIS Manager (press NAV three times) → `aa.ev.model` → uninstall, then reboot. Uninstall this **before** updating or removing Leafia. Full instructions, the off switch and troubleshooting are in the `README.txt` inside the release ([source](release/README.txt)).

The release contains no Hyundai or Google files: only install scripts, two small binary deltas and [`lpatch`](third_party/lpatch) (from Leafia, GPL-3.0). On the head unit, `lpatch` rebuilds the modified files from the unit's own Leafia files, and only if they are byte-for-byte the expected ones.

## How it works

- **AA receiver library** (`libautoreceiver_infobank.so`): 7 bytes in three places, so sensor 23 (vehicle energy model) requests are accepted and answered, and Java gets a raw send path. `tools/patch_library.py`.
- **GPM projection app**: `SensorSource` advertises fuel type ELECTRIC, connectors CCS1 + J1772 and sensor 23, and runs `EvModelSender` (`gpm-ev/src`), which reads SOC from the car's HEV service and sends the model every 5 s. `tools/patch_gpm_smali.py`.
- **Energy model**: the protobuf Maps consumes, schema recovered from Google Maps (`reference/vehicle_energy_model.proto`). Ioniq 5 values come from a rolldown test (road load), real DC charging sessions (charge curve and times) and live car data. `tools/build_ioniq5_model.py`, details in [docs/ENERGY-MODEL.md](docs/ENERGY-MODEL.md).

Runtime switches (via ADB, then replug Android Auto):

| Property | Effect |
|---|---|
| `persist.gpm.ev.off=1` | stock behaviour without uninstalling |
| `persist.gpm.ev.sohcap=1` | scale capacity by the BMS state of health (degraded packs) |

## Docs

| | |
|---|---|
| [docs/RESEARCH.md](docs/RESEARCH.md) | head unit internals, what Maps needs on the wire, live vehicle data, patch design |
| [docs/ENERGY-MODEL.md](docs/ENERGY-MODEL.md) | energy model schema, field meanings, Ioniq 5 values and calibration |
| [docs/PRECONDITIONING.md](docs/PRECONDITIONING.md) | research: triggering battery preconditioning from Maps routes |
| [docs/HANDOFF.md](docs/HANDOFF.md) | developer handoff: build, first car test, open questions |

## Repository layout

| Path | |
|---|---|
| `gpm-ev/` | Java sources for the energy-model sender, plus compile stubs |
| `tools/` | library/GPM patchers, model generator, build, package test, release build |
| `packages/aa-ev-model/` | the NEMESIS package (install/uninstall scripts) |
| `packages/adb-home/` | dev helper: ADB over your home Wi-Fi only (set your router's MAC first) |
| `reference/` | recovered model schema and protobuf decoders |
| `release/` | USB-drive descriptor and README for releases |
| `third_party/lpatch/` | Leafia's `lpatch` and delta tool (GPL-3.0) |

## Building from source

The build patches files pulled from your own head unit, which aren't in this repo. Put them in `private/` (gitignored):

- `private/car-current-leafia105/`: `HKMC_GPM.apk`, `HKMC_GPM.odex`, `libautoreceiver_infobank.so` from a unit with Leafia v1.0.5, and `smali-gpm/` (baksmali of that APK's `classes.dex`)
- `private/car-neev-260507/LEAFIA/private/stock/app/HKMC_GPM.apk`: the stock GPM APK (Leafia's backup)

Tools: Android SDK build-tools + `platforms/android-19`, smali/baksmali 2.5.2, Python with `capstone` and `bsdiff4`, `protoc`, `zip`, and `qemu-arm-static` (runs the ARM `lpatch` on the host).

```sh
PY=/path/to/python sh tools/build_all.sh   # library, model, dex, APK, NEMESIS package
bash tools/test_package.sh                 # install/uninstall/upgrade in a sandbox
sh tools/build_release.sh v0.1.0           # USB release zip (needs a clean, committed tree)
```

## Credits

- [NEMESIS](https://github.com/xwtk/hmg_nemesis) by xwtk: root and the package system (AGPL-3.0)
- [HMG Leafia Plus](https://github.com/VysseCodes/HMG-Leafia-Plus): wireless CarPlay/AA, the projection patches this builds on, and `lpatch` (GPL-3.0)
- [aa-proxy-rs](https://github.com/aa-proxy/aa-proxy-rs): the reference for Android Auto EV data and the Ford sample model
- [open-android-auto](https://github.com/mrmees/open-android-auto): Android Auto protocol definitions
- [gen5w-preconditioning](https://github.com/paytondevs/gen5w-preconditioning): the car's preconditioning interface

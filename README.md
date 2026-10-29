# egmp-aa-ev-feat

Real EV routing in Google Maps on Android Auto for the Hyundai Ioniq 5's Gen5W head unit: battery % on arrival, charging stops, realistic charge times, and automatic battery preconditioning on the way to a fast charger. Built on [NEMESIS](https://github.com/xwtk/hmg_nemesis) and [HMG Leafia Plus](https://github.com/VysseCodes/HMG-Leafia-Plus).

> [!WARNING]
> This modifies your infotainment system's files. The installer checks every file first and keeps a byte-for-byte backup, but you use it at your own risk. Not affiliated with Hyundai, Kia or Google.

## Requirements

- Hyundai Ioniq 5 (US), Gen5W software `NEEV.USA.0000.V015.012.260507`
- [NEMESIS](https://github.com/xwtk/hmg_nemesis) installed
- **[HMG Leafia Plus](https://github.com/VysseCodes/HMG-Leafia-Plus) v1.0.5 installed first.** This package builds on Leafia's modified Android Auto files; without exactly v1.0.5 the installer refuses and changes nothing.

## Features

- **EV routing:** the head unit sends Maps a vehicle energy model with the battery's live energy, so Maps plans stops and shows arrival %.
- **Real capacity:** usable capacity is learned from the BMS, not assumed from the new-car 77.4 kWh.
- **Cold weather:** live outside temperature raises heating and rolling losses in the model.
- **Charge times:** calibrated on real DC sessions and the car's own estimate, including the slow last 10–20 %.
- **Preconditioning:** when the next stop is a charger over 50 kW within an hour, the car is asked to warm the battery. Turn it off with the car's own *battery conditioning* setting.
- **EV logs:** driving and charging, whenever the head unit is on; copied to any USB drive with an `egmp-aa-ev-logs` folder; share them through an [EV log issue](https://github.com/willemcvu/egmp-aa-ev-feat/issues/new?template=log-submission.yml).

## Install

1. Download the `-usb.zip` from [Releases](../../releases) and copy its **contents** to an empty FAT32/exFAT drive.
2. Car on, plug the drive into a USB data port, tap **Yes** when NEMESIS asks. The head unit restarts when it's done.

It upgrades earlier versions in place. To uninstall: NEMESIS Manager (press NAV three times) → `aa.ev.model` → uninstall, then reboot; Leafia's files are restored exactly. Uninstall this before updating Leafia. More in the release's [README.txt](release/README.txt).

The release contains no Hyundai or Google files, only small binary deltas that [`lpatch`](third_party/lpatch) applies to your unit's own files.

## EV logs

The head unit records a small log whenever the car is on, driving or charging: battery energy and power, speed, temperatures, climate, charging and Maps' route plan. No location, no full VIN, no times of day. It keeps up to 100 MB, oldest first out.

To collect and share them:

1. Make a folder named `egmp-aa-ev-logs` at the top level of any FAT32/exFAT USB drive.
2. With the car on, plug the drive into the USB data port. Within about 20 seconds a pop-up says how many logs were copied ("… Safe to remove").
3. Open an [EV log issue](https://github.com/willemcvu/egmp-aa-ev-feat/issues/new?template=log-submission.yml) and drag the `egmp-*.csv` files from that folder into it. Issues are public.

Logs from different cars, climates and battery ages are what improve the energy model most. Format details: [LOGGING](docs/LOGGING.md).

## Docs

| | |
|---|---|
| [ENERGY-MODEL](docs/ENERGY-MODEL.md) | the energy model and its calibration |
| [PRECONDITIONING](docs/PRECONDITIONING.md) | how preconditioning works |
| [LOGGING](docs/LOGGING.md) | EV log format and `tools/evlog.py` |
| [DATA-SOURCES](docs/DATA-SOURCES.md) | vehicle data the head unit exposes |
| [RESEARCH](docs/RESEARCH.md) | head unit internals and the patch design |
| [HANDOFF](docs/HANDOFF.md) | developer guide: build, install, troubleshooting |
| [ROADMAP](docs/ROADMAP.md) | what's next |

## Building

The build patches files from your own head unit (not in this repo); see [HANDOFF](docs/HANDOFF.md) for what goes in `private/` and the toolchain.

```sh
PY=/path/to/python sh tools/build_all.sh   # build everything
sh gpm-ev/test/run.sh                      # Java host tests
bash tools/test_package.sh                 # package install/uninstall sandbox
sh tools/test_evlog.sh                     # EV log tools
sh tools/build_release.sh v0.2.0           # USB release zip
```

## Credits

[NEMESIS](https://github.com/xwtk/hmg_nemesis) (xwtk), [HMG Leafia Plus](https://github.com/VysseCodes/HMG-Leafia-Plus) (including `lpatch`, GPL-3.0), [aa-proxy-rs](https://github.com/aa-proxy/aa-proxy-rs), [open-android-auto](https://github.com/mrmees/open-android-auto), [gen5w-preconditioning](https://github.com/paytondevs/gen5w-preconditioning).

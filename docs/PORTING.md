# Other cars (Kia EV6, and how to add more)

Updated 2026-11-01. The package was built for one car (2022 Ioniq 5 US). It now carries a set of files per car and works out which car it is on.

## Status

| Car | Head unit software | Leafia v1.0.5 set | This package |
|---|---|---|---|
| Hyundai Ioniq 5 US 2022–24 | `NEEV.USA.*` / `NEEV23.USA.*` 260507 | `neev` | verified on the car |
| Kia EV6 US 2022–24 | `CVEV.USA.*` 260507 | `ev6` | code ready; needs one owner's files to build (below), then a test drive |
| Kia EV6 Canada | `CVEV.CAN.*` 260206 | `ev6_ca` | listed; NEMESIS says Canadian cars can't install it |
| Ioniq 6, GV60, EV9, others | | none | blocked until Leafia supports the car |

What decides it is the head unit's software (Settings → General → SW Info), not the model year. Like Leafia, the installer only accepts exact files it knows and changes nothing otherwise.

## What differs between cars, and how each part handles it

| Part | Ioniq 5 vs EV6 | How it's handled |
|---|---|---|
| Android Auto library | identical (stock `48ee793a…`, Leafia `c08410a8…`) | same three patches |
| GPM app (`HKMC_GPM.apk`) | different builds (Leafia EV6 result `7c6df0f5…`) | one delta per car, from that car's files |
| Car framework (`automotive*.odex`) | different | `EvFirmware`: transaction numbers come from the framework's own AIDL stubs; the hard-coded Ioniq 5 numbers are only used on the Ioniq 5 framework. Otherwise that read is skipped, never guessed |
| Preconditioning commands | same five transactions, verified by gen5w-preconditioning (Launchpad) on the EV6 | `EvFirmware.KNOWN` lists every framework they were verified on |
| Energy model | same pack (77.4 kWh), charge curve and platform; different road load | `EvVehicle`: road load A and C by product id and VIN trim, EPA coefficients (`tools/epa_road_load.py`) |

### EV6 road load by VIN

VIN character 5 is the line (3 Light/Wind, 4 GT-Line, 5 GT) and character 6 the drive (D AWD, 4 RWD), per NHTSA's decoder. The head unit already reads VIN characters 1–8 and 10 for the log header.

| Trim | A (N) | C (N/(m/s)²) | kW at 65 mph |
|---|---|---|---|
| RWD (Light, Wind, GT-Line RWD) | 115.4 | 0.5206 | 16.1 |
| Wind AWD (19 in) | 129.1 | 0.5159 | 16.4 |
| GT-Line AWD (20 in) | 189.3 | 0.5053 | 17.9 |
| GT (21 in) | 214.1 | 0.5704 | 20.2 |
| VIN not readable | GT-Line AWD | | 17.9 |
| Ioniq 5 (owner coast-down, unchanged) | 196.0 | 0.493 | 17.8 |

The cold-weather road-load penalty, heater table and charge curve are the Ioniq 5's. Logged EV6 drives (the `egmp-aa-ev-logs` folder) are what would tune them.

**Known limits on the EV6**
- The VIN comes from the `hscan` service. If the EV6 framework doesn't expose its AIDL stub, the read is skipped and the model uses the GT-Line AWD values. Look for `EvFirmware: ITM_HSCANService.getVIN = …` in logcat.
- Standard-range packs (58 kWh EV6 Light, 2022–23): usable capacity is learned from the BMS as on the Ioniq 5, but the charge curve keeps the long-range pack's power, so charge times on a 58 kWh car will read fast.
- Cars with the Electroniq hardware preconditioning button: both can ask the car to precondition. Turning off the car's own *battery conditioning* setting stops ours.

## Building for your EV6

You need NEMESIS and **HMG Leafia Plus v1.0.5** installed on the car (Leafia's files are what the build patches). No ADB needed.

1. **Copy the car's files to a USB drive.** Copy the contents of `tools/car-kit/` (`NEMESIS.data`, `egmp-car-files.sh`) to the root of an empty FAT32/exFAT drive. Car on, plug it in, tap **Yes**. A pop-up says when it's done (a few seconds). The kit only reads from the car and writes only to the drive. The drive now has `egmp-car-files/`: Leafia's GPM APK and odex, the AA library, Leafia's backup of the stock GPM APK, the car framework, and `info.txt` (software version, md5s; no VIN or location). These are Hyundai/Google files from your own car, so keep them private.
2. **Put them in `private/ev6/`** (gitignored): `egmp-car-files/*` → `private/ev6/`, keeping `stock/` and `framework/` as folders.
3. **Build:** `sh tools/build_all.sh` builds every car whose files are present. It refuses files that aren't Leafia v1.0.5's (md5s in `tools/cars.txt`). The EV6 odex md5 isn't published by Leafia, so the first build takes it from your file; add it to `tools/cars.txt` afterwards.
4. **Test:** `bash tools/test_package.sh` (the real package, every car in it). `SYNTH=1 bash tools/test_package.sh` needs no car files.
5. **Install** as in the README (`build_release.sh`, or `nemesis install` over ADB). Stream `logcat -s EvModelSender EvFirmware EvPrecond EvDriveLog EvCar` on the first drive: expect `vehicle: EV6 …`, `framework: EV6 US (CVEV) 260507`, and `energy model sent` every 5 s.

### On Windows (Git Bash)

Everything runs in Git Bash with:

- Python with `bsdiff4==1.3.1` (newer versions changed the API `mkdelta.py` uses) and `capstone`
- `protoc` on `PATH`, Android SDK with `platforms;android-19` and `build-tools;34.0.0` (`ANDROID_HOME`)
- smali/baksmali 2.5.2 jars: `SMALI_CMD="java -jar …/smali-2.5.2.jar"`, `BAKSMALI="java -jar …/baksmali-2.5.2.jar"`
- a host `lpatch.exe` instead of qemu: `python -m ziglang cc -target x86_64-windows-gnu -DHOST -O2 -I third_party/lpatch -o lpatch.exe tools/lpatch-win.c`, then `LPATCH=…/lpatch.exe` (and `LPATCH_HOST=…/lpatch.exe` for `test_package.sh`)

The repo forces LF line endings (`.gitattributes`): the package scripts run on the head unit's shell, which breaks on CRLF.

## Adding another car

1. Leafia must support it first (its `patches/cars`). Add a line to `tools/cars.txt` with Leafia's GPM APK and library result md5s from that set's `manifest`.
2. If its library isn't the US one (`c08410a8…`, e.g. Ioniq 5 Canada), `tools/patch_library.py` needs that library's patch sites; it refuses any other input.
3. Owner runs the car kit; build as above. `tools/patch_gpm_smali.py` stops if any anchor in that car's GPM differs.
4. Preconditioning: add the framework's SHA-256 pair to `EvFirmware.KNOWN` only once the five HEV/BCM transactions are verified on it (gen5w-preconditioning's porting kit does this).
5. Road load: add the car to `EvVehicle` (product prefix, VIN trims, EPA coefficients via `tools/epa_road_load.py`). Until then it gets the Ioniq 5's.

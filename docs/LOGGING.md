# EV log format v2

The format the head unit writes for [ROADMAP item 2](ROADMAP.md#2-ev-logging-for-model-calibration), and that owners check, clean and submit through the [checker page](https://willemcvu.github.io/egmp-aa-ev-feat/) (`site/`) or `tools/evlog.py`. EV logs cover driving **and** charging: the head unit logs whenever it is on, with or without Android Auto.

The allowlist of header keys and columns is single-sourced in [`site/schema.json`](../site/schema.json). `tools/evlog.py` and the page (`site/evlog.js`) both load it and produce byte-identical cleaned files. To add a field, add it to `schema.json` (and here); old parsers keep working because unknown columns are ignored.

v2 is a superset of v1: same header keys plus `sample_s`, same columns plus the ones marked *v2* below. Readers accept both (`accept_magic` in `schema.json`); a cleaned file keeps its own magic line.

## On the head unit

- **Always on.** `EvDriveLog` starts with the GPM process at boot (GPM is a persistent system app; hooks in `StartReceiver.onReceive` and `GalReceiverService.onCreate`, see `tools/patch_gpm_smali.py`) and runs until the head unit sleeps or powers off. It does not depend on Android Auto: the energy-model sender (`EvModelSender`) still runs only during Android Auto sessions, and `aa_active` says whether one was running. There is no switch.
- **One file per head-unit "on" period.** A new file starts when GPM starts, and again after a gap of more than 30 s between samples (the head unit was asleep). `t_s`, `odo_delta_km` and `alt_delta_m` count from the start of the file.
- **Sampling.** Every 1 s: BMS energy (HEV 0x31) and speed. Every 5 s one row: the 1 s samples aggregated (`speed_avg_kph`, `speed_max_kph`, `energy_rate_w`, last `speed_kph`) plus every other signal read once.
- **Buffered writes.** Rows are kept in memory and appended to the file once a minute, and on a clean stop (`GalReceiverService.onDestroy`, Android's shutdown broadcast). If the head unit loses power without a shutdown, up to a minute of rows is lost. If the folder can't be written, up to 256 KB of rows stay in memory (oldest dropped first).
- **Hard caps.** A file never grows past 4 MB (the log continues in the next file of the same period, with a fresh header). The folder (`/data/data/com.google.android.projection.sink/files/evlog/`) never holds more than **100 MB**: before every write the oldest files (by name: date, then sequence) are deleted until the total after the write fits. The file being written is never deleted to make room for itself.
- **Size.** A row is about 150–200 bytes, so about 110–150 KB per hour the head unit is on (720 rows), about 3.5 MB per 24 h. 100 MB holds roughly 700 hours.
- **Copying.** The `evlog-offload` service copies new and grown files to `<usb drive>/egmp-aa-ev-logs/` when a drive with that folder is plugged in. The file being written grows once a minute, so with a drive left in it is copied again after each append. A head-unit pop-up confirms it once per insertion ("N copied … Safe to remove", or "already up to date"). On the 2022 Ioniq 5 the phone and the drive share the one USB data port, so plug the drive in after a drive or a charge. Logs are never deleted by the copy.
- **Load.** Two binder reads per second (HEV energy, hscan speed), about 40 per 5 s row (HEV flags, DATC, nav packet every 4 s), one GPS last-fix lookup per row, and a passive BCM listener. Reads shared with the energy-model sender are cached (`EvCar`), so the two don't read the same signal twice. The DATC service writes one debug logcat line per climate read.

## File

- UTF-8, `\n` line endings.
- Name: `egmp-<product-short>-<YYYYMMDD>-<seq>.csv`, e.g. `egmp-NEEV-20261029-001.csv`. `<product-short>` is the first dot-separated part of `product` (`NEEV`); `<YYYYMMDD>` is `date` (`00000000` if the head unit's clock wasn't set); `<seq>` counts files from 1, at least three digits. The cleaners name their output this way, so an original file name never ends up in a submission.

```
# egmp-aa-ev-log v2
# product=NEEV.USA.0000.V015.012.260507
# device=wp_daudioplus_neev_us
# vin_model=KM8KRDAFN
# pkg=aa.ev.model 0.2.1-dev
# model_capacity_wh=77400
# capacity_source=bms
# date=2026-01-17
# period_s=5
# sample_s=1
t_s,soc_half_pct,energy_wh,capacity_wh,avlbl_enrg_raw,...
0,120,39740,63200,19870,...
5,120,39734,63200,19867,...
```

Full example: [`site/sample.csv`](../site/sample.csv) (synthetic).

## Header

Lines starting with `# `, each `# key=value`. The first line is exactly `# egmp-aa-ev-log v2` (or `# egmp-aa-ev-log v1` for older files). The header ends at the first line not starting with `#`. Keys, in this order:

| Key | Value | Example |
|---|---|---|
| `product` | head unit software version | `NEEV.USA.0000.V015.012.260507` |
| `device` | head unit device name | `wp_daudioplus_neev_us` |
| `vin_model` | VIN characters 1–8 plus character 10 (maker, model, body, motor, model year). **Never the full VIN**: no check digit (9), plant (11) or serial number (12–17) | `KM8KRDAFN` |
| `pkg` | logging package and version | `aa.ev.model 0.2.1-dev` |
| `model_capacity_wh` | nominal capacity of the model template, Wh | `77400` |
| `capacity_source` | where `capacity_wh` comes from: `nominal` (new-car value, nothing learned yet) or `bms` (learned from BMS available energy ÷ SOC). Readers should accept other short lower-case values | `bms` |
| `date` | UTC date the file started, `YYYY-MM-DD`. No time of day. Left out if the clock wasn't set | `2026-01-17` |
| `period_s` | row period, s | `5` |
| `sample_s` | *v2*: sampling period inside a row, s | `1` |

There is deliberately no time zone or time of day. `vin_model` is left out if the VIN can't be read.

## Data

After the header: one CSV row of column names, then one row per period. Units are in the column names. Values are numbers (plain decimal; `int` and `0/1` columns as noted), except `model_rev`, a short lower-case hex string. **An empty field means unknown** (not read, invalid, or not applicable, e.g. `road_a_n` outside Android Auto). Columns may appear in any order in a raw log; cleaned logs use the order below (the `site/schema.json` order), which is also the logger's order. The last group (`pack_max_c` … `charge_kw`) is reserved: no source on this car yet.

Raw columns (`_raw`) are logged as the car reports them where the unit isn't proven.

### v1 columns

| Column | Type | Meaning |
|---|---|---|
| `t_s` | number, **required** | seconds since the file started; strictly increasing |
| `soc_half_pct` | int | display state of charge, 0.5 % units (HEV 0x19) |
| `energy_wh` | number | current energy for the model: BMS available energy (`avlbl_enrg_raw` × 2), else SOC × capacity. Sent to Maps during Android Auto |
| `capacity_wh` | number | usable capacity estimate (sent to Maps during Android Auto) |
| `avlbl_enrg_raw` | number | BMS available energy, HEV 0x31 (same signal as the nav packet's `EV_AVLBL_ENRG`), raw, 2 Wh/unit; the last 1 s sample of the row |
| `out_temp_raw` | number | outside temperature, raw car value |
| `out_temp_c` | number | outside temperature, °C (raw × 0.5 − 40) |
| `pack_min_c` | number | lowest battery module temperature, °C |
| `dte_raw` | number | car's distance to empty, raw (km) |
| `road_a_n` | number | road-load A sent to Maps, N (only while Android Auto runs) |
| `aux_table_c` | number | temperature used for the model table lookup, °C (only while Android Auto runs) |
| `soh_raw` | int | BMS state of health, raw. A health index, not a capacity |
| `model_rev` | hex string `[0-9a-f]{4,16}` | energy model revision, e.g. `3fa9c2d1` |
| `speed_kph` | number | speed at the end of the row (last 1 s sample), km/h |
| `odo_delta_km` | number | distance since the file started, km. **Never the absolute odometer** |
| `trip_batt_raw` | int | HEV 0x58 battery energy used this drive, 0.1 kWh (resets each drive) |
| `trip_clim_raw` | int | HEV 0x42 climate energy this drive, raw (Wh inferred) |
| `trip_ldc_raw` | int | HEV 0x49 12 V / electronics energy this drive, raw |
| `trip_bheat_raw` | int | HEV 0x44 battery heater energy this drive, raw |
| `trip_mot1_raw` | int | HEV 0x47 front motor drive energy this drive, raw |
| `trip_mot2_raw` | int | HEV 0x48 rear motor drive energy this drive, raw |
| `trip_gen1_raw` | int | HEV 0x59 front motor regen energy this drive, raw |
| `trip_gen2_raw` | int | HEV 0x5a rear motor regen energy this drive, raw |
| `hvac_raw` | number | HEV 0x4f live climate power, raw (~20 W/unit, inferred) |
| `charge_state_raw` | int | HEV 0x07 charge state, raw (3 = not charging) |
| `nav_active` | 0/1 | Android Auto navigation active |
| `nav_rem_m`, `nav_rem_s` | number | navigation distance (m) and time (s) remaining |
| `nav_charger` | 0/1 | the stop being tracked (the next stop) is a charging stop |
| `nav_charger_kw` | number | that stop's charger power, kW |
| `precond_state` | int | battery preconditioning state: `PrecondCore` 0 OFF … 8 ERROR ([PRECONDITIONING.md](PRECONDITIONING.md)) |

### v2 columns

| Column | Type | Meaning |
|---|---|---|
| `aa_active` | 0/1 | an Android Auto session (the energy-model sender) was running |
| `energy_rate_w` | number | battery power derived from energy: (last valid `avlbl_enrg_raw` of this row − the last valid one before it) × 2 Wh ÷ the time between those samples, W. **Positive = into the battery (charging), negative = discharge.** The energy signal is coarse (2 Wh steps, updated about once a second, often in bigger jumps), so single rows are noisy: average several rows, or difference `energy_wh` over longer windows |
| `speed_avg_kph` | number | mean of the 1 s speed samples in the row, km/h (hscan `getCarSpeed`, display speed) |
| `speed_max_kph` | number | max of the 1 s speed samples in the row, km/h |
| `alt_delta_m` | number | GPS altitude change since the file started, m (head unit GPS, last fix under 10 s old). Only the altitude is used; no position or fix time is logged |
| `drive_mode_raw` | int | drive mode pushed by the BCM service: 0 default, 1 normal, 2 sport, 3 eco, 4 snow (`BCMConstants.DriveMode`) |
| `bheat_pwr_raw` | int | HEV 0x43 live battery heater power, raw |
| `fast_charging_raw` | int | HEV 0x28 FAST_CHARGING, raw |
| `chg_sts_raw` | int | HEV 0x1d CHG_STS, raw |
| `chg_quick_rem_min` | int | HEV 0x0f the car's own estimate of DC charging time remaining, min (matches the dash) |
| `target_soc_ac_half_pct` | int | HEV 0x22 AC charge target, 0.5 % units (200 = 100 %) |
| `target_soc_dc_half_pct` | int | HEV 0x23 DC charge target, 0.5 % units |
| `chg_current_raw` | int | HEV 0x52 charging current, raw |
| `obc_ac_pwr_raw` | int | HEV 0x53 on-board charger AC power, raw |
| `vcms_pwr_raw` | int | HEV 0x6c VCMS charging power display, raw (0x7fff when not charging is logged as empty) |
| `clim_set_raw` | number | climate driver set temperature in the display unit (`clim_unit_raw`) |
| `clim_set_c` | number | the same in °C (converted with the DATC service's own `isUnitCelsius`) |
| `clim_unit_raw` | int | climate temperature unit, raw |
| `clim_blower_raw` | int | main blower level shown, raw |
| `clim_mode_raw` | int | air distribution mode shown (vent / floor / defrost …), raw |
| `clim_ac_raw` | int | A/C indicator, raw |
| `clim_auto_raw` | int | AUTO indicator, raw |
| `clim_op_raw` | int | climate operating state, raw |
| `nav_charger_dep_wh` | number | Maps' planned battery energy on leaving the tracked charging stop, Wh |
| `nav_charger_chg_s` | number | Maps' planned charging time at that stop, s |

Climate values come from `IDATCService.getVRClimateStatus(flag)` (txn 0x29, a pure getter over the DATC service's caches; flags `DATCFlag` 2, 5, 6, 8, 9, 12, 13). Drive mode comes from a passive `IBCMListener` (the service pushes the cached mode on registration and every change); `getDriveMode()` is never called because it sends a MICOM request and can show the drive-mode pop-up.

### Reserved

| Column | Type | Meaning |
|---|---|---|
| `pack_max_c` | number | highest battery module temperature, °C |
| `batt_v`, `batt_a`, `batt_kw` | number | HV battery voltage, current, power |
| `hvac_w` | number | climate power, W |
| `charging` | 0/1 | charging |
| `charge_kw` | number | charging power, kW |

Not logged because they read invalid on this car (0xffff / 0xff): PTC heater power (HEV 0x41), compressor power (0x2a), eco level (0x08).

## Never in a log

GPS position or any location (only the altitude change since the file started); addresses, place or road names; the full VIN; phone names or identifiers; wall-clock times; the absolute odometer. The logger never writes these. The cleaners still assume a file may have been edited, and keep only what is on the allowlist.

## Parsing rules

Readers must be forward compatible:

- Ignore unknown header keys and unknown columns; treat missing columns as all-empty.
- A file whose first line isn't `# egmp-aa-ev-log v1` or `# egmp-aa-ev-log v2`, or that has no `t_s` column, is unusable.

## Cleaning rules

`evlog.py clean` and the checker page apply exactly these, and write the result deterministically (cleaning a clean file changes nothing):

1. Header: keep the magic line (v1 or v2) as it is; keep only allowlisted keys whose value matches the key's pattern/number type (`schema.json`); drop free-text comment lines, unknown keys, invalid values and duplicates (first wins). Write kept keys in schema order.
2. Columns: keep only allowlisted columns (first of any duplicate), in schema order.
3. Values: a value that isn't valid for its column type (number: `-?digits[.digits][e±digits]`; int; 0/1; string columns: their pattern) is blanked. Values are trimmed and otherwise copied verbatim.
4. Rows: drop comment lines, rows with more fields than column names, rows with no valid `t_s`, and rows whose `t_s` isn't greater than the previous kept row's.
5. Output: `\n` line endings, no BOM, no quoting.

## Tools

```sh
python3 tools/evlog.py check FILE...       # summary + what clean would remove
                                           # exit 0 clean, 1 needs cleaning, 2 unusable
python3 tools/evlog.py clean IN OUT        # allowlist filter
python3 tools/evlog.py merge DIR OUT.csv   # every *.csv in DIR, cleaned, as one table:
                                           # session id + header fields + columns
sh tools/test_evlog.sh                     # tests (compares evlog.js with evlog.py if node exists)
```

`merge` uses the file name as the session id when it follows the naming pattern, otherwise `session<N>`.

The checker page is `site/` (static, no build step), published to GitHub Pages by `.github/workflows/pages.yml`. Try it locally with `python3 -m http.server -d site`. Its "Submit on GitHub" button opens `.github/ISSUE_TEMPLATE/log-submission.yml` with the car, software and file summaries filled in; the user attaches the cleaned files to the (public) issue.

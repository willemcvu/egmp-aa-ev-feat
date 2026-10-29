# Drive log format v1

The format the head unit writes for [ROADMAP item 2](ROADMAP.md#2-drive-logging-for-model-calibration), and that owners check, clean and submit through the [checker page](https://willemcvu.github.io/egmp-aa-ev-feat/) (`site/`) or `tools/evlog.py`.

The allowlist of header keys and columns is single-sourced in [`site/schema.json`](../site/schema.json). `tools/evlog.py` and the page (`site/evlog.js`) both load it and produce byte-identical cleaned files. To add a field, add it to `schema.json` (and here); old parsers keep working because unknown columns are ignored.

## File

- One CSV file per Android Auto session. UTF-8, `\n` line endings.
- Name: `egmp-<product-short>-<YYYYMMDD>-<seq>.csv`, e.g. `egmp-NEEV-20261029-001.csv`. `<product-short>` is the first dot-separated part of `product` (`NEEV`); `<YYYYMMDD>` is `date`; `<seq>` counts sessions from 1, at least three digits. The cleaners name their output this way, so an original file name never ends up in a submission.
- Copied by the head unit to `<usb drive>/egmp-aa-ev-logs/` when a drive with that folder is plugged in.

```
# egmp-aa-ev-log v1
# product=NEEV.USA.0000.V015.012.260507
# device=wp_daudioplus_neev_us
# vin_model=KM8KRDAFN
# pkg=aa.ev.model 0.2.0
# model_capacity_wh=77400
# capacity_source=nominal
# date=2026-01-17
# period_s=5
t_s,soc_half_pct,energy_wh,capacity_wh,out_temp_c,...
0,160,61917,77400,4.0,...
5,160,61910,77400,4.0,...
```

Full example: [`site/sample.csv`](../site/sample.csv) (synthetic).

## Header

Lines starting with `# `, each `# key=value`. The first line is exactly `# egmp-aa-ev-log v1`. The header ends at the first line not starting with `#`. Keys, in this order:

| Key | Value | Example |
|---|---|---|
| `product` | head unit software version | `NEEV.USA.0000.V015.012.260507` |
| `device` | head unit device name | `wp_daudioplus_neev_us` |
| `vin_model` | VIN characters 1–8 plus character 10 (maker, model, body, motor, model year). **Never the full VIN**: no check digit (9), plant (11) or serial number (12–17) | `KM8KRDAFN` |
| `pkg` | logging package and version | `aa.ev.model 0.2.0` |
| `model_capacity_wh` | capacity sent to Maps, Wh | `77400` |
| `capacity_source` | where that capacity came from: `nominal` (new-car value), `soh`, `bms` (learned from BMS available energy ÷ SOC), … | `nominal` |
| `date` | UTC date the session started, `YYYY-MM-DD`. No time of day | `2026-01-17` |
| `period_s` | sample period, s | `5` |

There is deliberately no time zone or time of day. The current logger writes all of these except `vin_model`.

## Data

After the header: one CSV row of column names, then one row per sample. Units are in the column names. Values are numbers (plain decimal; `int` and `0/1` columns as noted), except `model_rev`, a short lower-case hex string. **An empty field means unknown.** Columns may appear in any order in a raw log; cleaned logs use the order below, which is also the order the logger writes (it currently writes `t_s` … `model_rev` minus `pack_max_c`).

| Column | Type | Meaning |
|---|---|---|
| `t_s` | number, **required** | seconds since session start; strictly increasing |
| `soc_half_pct` | int | state of charge, 0.5 % units |
| `energy_wh` | number | current energy sent to Maps, Wh |
| `capacity_wh` | number | capacity sent to Maps, Wh |
| `avlbl_enrg_raw` | number | `EV_AVLBL_ENRG` from the nav battery packet, raw |
| `out_temp_raw` | number | outside temperature, raw car value |
| `out_temp_c` | number | outside temperature, °C |
| `pack_min_c`, `pack_max_c` | number | lowest/highest battery module temperature, °C |
| `dte_raw` | number | car's distance to empty, raw |
| `road_a_n` | number | road-load A sent to Maps, N |
| `aux_table_c` | number | temperature used for the model table lookup, °C |
| `soh_raw` | int | BMS state of health, raw. A health index, not a capacity |
| `model_rev` | hex string `[0-9a-f]{4,16}` | energy model revision, e.g. `3fa9c2d1` |
| `speed_kph` | number | speed, km/h |
| `odo_delta_km` | number | distance since session start, km. **Never the absolute odometer** |
| `batt_v`, `batt_a`, `batt_kw` | number | HV battery voltage, current, power |
| `hvac_w` | number | climate power, W |
| `hvac_raw` | number | climate power or state, raw |
| `trip_batt_raw` | int | HEV 0x58 battery energy used this drive, 0.1 kWh (resets each drive) |
| `trip_clim_raw` | int | HEV 0x42 climate energy this drive, raw (Wh inferred) |
| `trip_ldc_raw` | int | HEV 0x49 12 V / electronics energy this drive, raw |
| `trip_bheat_raw` | int | HEV 0x44 battery heater energy this drive, raw |
| `trip_mot1_raw` | int | HEV 0x47 front motor drive energy this drive, raw |
| `trip_mot2_raw` | int | HEV 0x48 rear motor drive energy this drive, raw |
| `trip_gen1_raw` | int | HEV 0x59 front motor regen energy this drive, raw |
| `trip_gen2_raw` | int | HEV 0x5a rear motor regen energy this drive, raw |
| `charge_state_raw` | int | HEV 0x07 charge state, raw (3 = not charging) |
| `charging` | 0/1 | charging |
| `charge_kw` | number | charging power, kW |
| `drive_mode_raw` | int | drive mode, raw |
| `nav_active` | 0/1 | Android Auto navigation active |
| `nav_rem_m`, `nav_rem_s` | number | navigation distance (m) and time (s) remaining |
| `nav_charger` | 0/1 | destination is a charging station |
| `nav_charger_kw` | number | destination charger power, kW |
| `precond_state` | int | battery preconditioning state, raw |

## Never in a log

GPS position or any location; addresses, place or road names; the full VIN; phone names or identifiers; wall-clock times; the absolute odometer. The logger never writes these. The cleaners still assume a file may have been edited, and keep only what is on the allowlist.

## Parsing rules

Readers must be forward compatible:

- Ignore unknown header keys and unknown columns; treat missing columns as all-empty.
- A file whose first line isn't `# egmp-aa-ev-log v1`, or that has no `t_s` column, is unusable.

## Cleaning rules

`evlog.py clean` and the checker page apply exactly these, and write the result deterministically (cleaning a clean file changes nothing):

1. Header: keep only allowlisted keys whose value matches the key's pattern/number type (`schema.json`); drop free-text comment lines, unknown keys, invalid values and duplicates (first wins). Write kept keys in schema order.
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

The checker page is `site/` (static, no build step), published to GitHub Pages by `.github/workflows/pages.yml`. Try it locally with `python3 -m http.server -d site`. Its "Submit on GitHub" button opens `.github/ISSUE_TEMPLATE/log-submission.yml` with the car, software and session summaries filled in; the user attaches the cleaned files to the (public) issue.

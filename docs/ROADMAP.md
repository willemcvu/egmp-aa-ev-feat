# Roadmap

Planned additions, roughly in priority order. Each links to what we already know.

## 1. Automatic battery preconditioning

When Google Maps on Android Auto is guiding to a DC fast charger, ask the car to precondition the battery, as the car's own navigation does. No buttons.

- **Known:** the car side is solved. `IHEVService` transactions 62/65/66 (`setBattCondEvStation`, `setBattCondRouteDis`, `setBattCondRemainTime`) are the same inputs the car's own navigation uses. Distance and time to destination already reach GPM (AA 0x8007, via Leafia's `NAVDUMP` hook).
- **Open:** whether Maps marks a destination as a charger (`destinations[].charging_station` in AA 0x8006). This can be checked with no install: route to a charger and capture `NAVDUMP`.
- **Design:** Java only, in GPM; start/maintain/cancel mirroring the manual Launchpad app's command profile. Details in [PRECONDITIONING.md](PRECONDITIONING.md).
- **Status (2026-10-29):** built into the GPM dex and host-tested (`gpm-ev/test/run.sh`), not yet run on the car. Off by default: `persist.gpm.precond` 0 = parse/log only, 1 = on, 2 = on + DC network name fallback. Next: Test 1 (does Maps set `charging_station`), then PRECONDITIONING.md Test 3.

## 2. Drive logging for model calibration

The model is calibrated from a few owner anchor points. Real logged drives would let us fit it properly (cold penalty split, drivetrain losses, real capacity).

- **Goal:** the head unit logs on its own, and the logs are copied off automatically when a USB drive is plugged in. No ADB, no laptop in the car.
- **What to log** (every 5 s while AA is connected, from data `EvModelSender` already reads): SOC, outside temp, min pack temp, `EV_AVLBL_ENRG`, DTE, the model values sent (capacity, road load A), and AA nav remaining distance/time. Ideally also speed and odometer if a source is found. No GPS position by default (privacy); maybe opt-in.
- **Storage:** compact CSV under `/data/nemesis/`, rotated with a hard size cap so it can never fill `/data`.
- **Offload:** on USB insert, copy to `<drive>/egmp-aa-ev-logs/` and mark what's been copied. Needs a small NEMESIS service watching `/storage/usb*` (not the NEMESIS usb-handler).
- **Analysis:** a host tool that turns logs into Wh/mi against speed and temperature and refits the cold tables (`COLD_DELTA_A`, aux table).
- **Status (2026-10-29):** built on `dev/logging-capacity`, not yet run on the car. `EvLogger` writes format v1 ([LOGGING.md](LOGGING.md)) into GPM's files dir (32 MB cap); `svc/evlog-offload.sh` copies to drives with an `egmp-aa-ev-logs/` folder. Checker site in `site/`, `tools/evlog.py`. Still to build: the analysis/refit tool.

## 3. Real usable capacity

The model assumes the new-car 77.4 kWh. At ~100k miles the real usable capacity is probably lower, so Maps overestimates range.

- **Approach (built, not yet run on the car):** usable capacity = BMS remaining energy ÷ display SOC. HEV flag 0x31 `ENERGYMAP_1000MS` is the same signal as the nav packet's `EV_AVLBL_ENRG`, in 2 Wh steps like `BMS_EVENERGYCAPA` ([DATA-SOURCES.md](DATA-SOURCES.md) §1.1). Owner's car: 14462 at 46.0% → 28.9 kWh → **~62.9 kWh usable**. The energy sent to Maps is ENERGYMAP × 2 directly; capacity is learned (preferring ≥ 60% SOC samples, since energy reaches 0 near display 2%) and kept across sessions. No setting.
- **Not capacity:** BMS SOH (hscan 0xd6, reads 95) is a health index (resistance, balance); logged only. `BMS_EVENERGYCAPA` (0x35) is a constant 38700 (77.4 kWh nominal).
- **To confirm:** the 2 Wh unit with a logged charge (ΔENERGYMAP × 2 vs charger kWh × ~0.95, DATA-SOURCES.md probe P2), and the high-SOC capacity from a near-full log.

## To verify (existing features)

- Wireless Android Auto with EV routing (tested wired only).
- Supercharger charge times: does Maps use the 250 kW table row for Tesla stations?
- Cold model on the car: the log's `out=` (now raw × 0.5 − 40 °C) matches the dash, and Maps adds a stop on the Burlington → Syracuse trip at ~26 °F.

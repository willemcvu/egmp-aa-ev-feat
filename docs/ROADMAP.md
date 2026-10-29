# Roadmap

Planned additions, roughly in priority order. Each links to what we already know. Items 1–3 are built and verified on the car (2022 Ioniq 5, 2026-10-29, v0.2.0).

## 1. Automatic battery preconditioning (done)

When Google Maps on Android Auto is guiding to a DC fast charger, ask the car to precondition the battery, as the car's own navigation does. No buttons.

- **How:** `IHEVService` transactions 62/65/66 (`setBattCondEvStation`, `setBattCondRouteDis`, `setBattCondRemainTime`), the same inputs the car's own navigation uses, with distance and time from Maps' navigation messages (0x8006/0x8007, via Leafia's `NAVDUMP` hook). Java only, in GPM, mirroring the manual Launchpad app's command profile. Details in [PRECONDITIONING.md](PRECONDITIONING.md).
- **Status (2026-10-29): verified on the car.** Always on, no switch (the car's battery-conditioning setting is the off control). Triggers when the next stop is a charging stop above 50 kW at most 60 min away. On short routes the request goes REQUESTED → ACTIVE (the car's indicator comes on); ending the route cancels it (Off within ~2.5 s); on a 58-min route it stays REQUESTED while the BMS waits to heat.
- **Still open:** a real multi-stop drive (is the single 0x8007 entry always the next stop?) and a cold-morning drive to a charger.

## 2. EV logging for model calibration (logging done, analysis next)

The model is calibrated from a few owner anchor points. Real logged drives and charging sessions let us fit it properly (cold penalty split, drivetrain losses, real capacity).

- **Goal:** the head unit logs on its own, and the logs are copied off when a USB drive is plugged in. No ADB, no laptop in the car.
- **Status (2026-10-29): v1 verified on the car.** `EvLogger` wrote format v1 every 5 s during Android Auto sessions. **v2 (built, not yet on the car):** `EvDriveLog` logs whenever the head unit is on (driving, charging, parked; `aa_active` column), samples every 1 s, writes a row every 5 s and appends to the file once a minute; charging, climate, drive-mode, altitude-change and Maps charging-plan columns ([LOGGING.md](LOGGING.md)); 100 MB folder cap. `svc/evlog-offload.sh` copies new logs to any drive with an `egmp-aa-ev-logs/` folder and shows a head-unit pop-up once per insertion (6 logs copied in the first test). On this car the phone and the drive share the one USB data port, so logs are copied when the drive is plugged in after a session. Submitted through the `ev-log` issue form; `tools/evlog.py` checks and merges them.
- **Still to build:** the analysis tool that turns logs into Wh/mi against speed and temperature and refits the cold tables (`COLD_DELTA_A`, aux table). That is the input to item 4.

## 3. Real usable capacity (done)

The model assumed the new-car 77.4 kWh. At ~105k miles the real usable capacity is lower, so Maps overestimated range.

- **How:** usable capacity = BMS remaining energy ÷ display SOC. HEV flag 0x31 `ENERGYMAP_1000MS` is the same signal as the nav packet's `EV_AVLBL_ENRG`, in 2 Wh steps like `BMS_EVENERGYCAPA` ([DATA-SOURCES.md](DATA-SOURCES.md) §1.1). The energy sent to Maps is ENERGYMAP × 2 directly; capacity is learned (preferring ≥ 60% SOC samples, since energy reaches 0 near display 2%) and kept across sessions. The charge curve and time tables are scaled to it. No setting.
- **Status (2026-10-29): verified on the car.** ~62.9 kWh usable on the owner's pack (from BMS energy ÷ SOC), and with the tables scaled to it Maps' charge times look right to the owner.
- **Not capacity:** BMS SOH (hscan 0xd6, reads 95) is a health index (resistance, balance); logged only. `BMS_EVENERGYCAPA` (0x35) is a constant 38700 (77.4 kWh nominal).
- **Open question:** recent Electrify America sessions suggest ~70–71 kWh (several deep sessions delivered more than 62.9 kWh would allow). Settle it with a logged DC charge: ΔENERGYMAP × 2 vs charger kWh × ~0.95 (DATA-SOURCES.md probe P2), and ENERGYMAP × 2 ÷ SOC near full.

## 4. Self-adjusting model (next)

Use the EV logs (item 2) to correct the model on the car: compare predicted and actual energy use per drive and adjust road load / aux power, so the model follows the actual car, season and tyres without manual recalibration. Start offline (the analysis tool refits the tables from submitted logs), then consider a slow on-car correction with tight bounds.

## To verify (existing features)

- Supercharger charge times: does Maps use the 250 kW table row for Tesla stations?
- Cold model on a real winter trip: does Maps add the stop the warm model missed at ~26 °F? (Outside temperature itself is confirmed: raw × 0.5 − 40 °C matches the dash.)

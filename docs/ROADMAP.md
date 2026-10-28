# Roadmap

Planned additions, roughly in priority order. Each links to what we already know.

## 1. Automatic battery preconditioning

When Google Maps on Android Auto is guiding to a DC fast charger, ask the car to precondition the battery, as the car's own navigation does. No buttons.

- **Known:** the car side is solved. `IHEVService` transactions 62/65/66 (`setBattCondEvStation`, `setBattCondRouteDis`, `setBattCondRemainTime`) are the same inputs the car's own navigation uses. Distance and time to destination already reach GPM (AA 0x8007, via Leafia's `NAVDUMP` hook).
- **Open:** whether Maps marks a destination as a charger (`destinations[].charging_station` in AA 0x8006). This can be checked with no install: route to a charger and capture `NAVDUMP`.
- **Design:** Java only, in GPM; start/maintain/cancel mirroring the manual Launchpad app's command profile. Details in [PRECONDITIONING.md](PRECONDITIONING.md).

## 2. Drive logging for model calibration

The model is calibrated from a few owner anchor points. Real logged drives would let us fit it properly (cold penalty split, drivetrain losses, real capacity).

- **Goal:** the head unit logs on its own, and the logs are copied off automatically when a USB drive is plugged in. No ADB, no laptop in the car.
- **What to log** (every 5 s while AA is connected, from data `EvModelSender` already reads): SOC, outside temp, min pack temp, `EV_AVLBL_ENRG`, DTE, the model values sent (capacity, road load A), and AA nav remaining distance/time. Ideally also speed and odometer if a source is found. No GPS position by default (privacy); maybe opt-in.
- **Storage:** compact CSV under `/data/nemesis/`, rotated with a hard size cap so it can never fill `/data`.
- **Offload:** on USB insert, copy to `<drive>/egmp-aa-ev-logs/` and mark what's been copied. Needs a small NEMESIS service watching `/storage/usb*` (not the NEMESIS usb-handler).
- **Analysis:** a host tool that turns logs into Wh/mi against speed and temperature and refits the cold tables (`COLD_DELTA_A`, aux table).

## 3. Real usable capacity

The model assumes the new-car 77.4 kWh. At ~100k miles the real usable capacity is probably lower, so Maps overestimates range.

- **Candidates, best first:**
  1. **BMS state of health** via `tm_hscan_service` transaction 0xd6 (`getXEV_Bms_GDS_SOH`). Already probed and logged once per session; only used with `persist.gpm.ev.sohcap=1`. Needs a check that the raw value is sane (0.1% units).
  2. **`EV_AVLBL_ENRG`** from the nav battery packet: likely available energy, units unconfirmed. Log it against SOC to see whether it tracks capacity × SOC.
  3. **Learned from charging:** energy delivered against SOC change over charging sessions (needs logging, item 2).
- `BMS_EVENERGYCAPA` (HEV flag 0x35) is no use: constant 38700 (77.4 kWh nominal), not degradation-aware.
- **Goal:** on by default once one source is verified; no manual SOH setting.

## To verify (existing features)

- Wireless Android Auto with EV routing (tested wired only).
- Supercharger charge times: does Maps use the 250 kW table row for Tesla stations?
- Cold model on the car: the log's `out=` matches the dash (outside temp scaling unconfirmed), and Maps adds a stop on the Burlington → Syracuse trip at ~26 °F.

# Android Auto vehicle energy model: field map

Date: 2026-10-27. Schema recovered from **Google Maps 26.43.00** (`private/maps-26.43/`, jadx output). The only real sample is still aa-proxy-rs's `ford_ev_model.bin` (Ford F-150 Lightning capture, 9.5 KB), kept in `reference/`.

Files in `reference/`:
- `vehicle_energy_model.proto`: the recovered schema. Field numbers and types are exact; names are ours.
- `lite_schema.py`: dumps a protobuf-lite message tree from jadx sources (`python3 reference/lite_schema.py private/maps-26.43/jadx/sources/defpackage cdim`).
- `pbdump.py` / `ford_ev_model.dump.txt`: the schema-less dump. **Note:** it shows `sint32` fields as raw varints, so the x axes in blocks 3 and 5 there are doubled.

## How Maps handles it (from the code)

- `SensorBatch` field 23 holds the bytes of a `cdim` message directly (`pxl.java:135` `cdim.parseFrom(sensor 23 bytes)`; `pxo.java` does the same through `CarSensorEvent`). Sensor 25 is a different, smaller message (`cdhl`).
- `gfz.q()` copies `cdim` into Maps' internal `cbjh` 1:1. There are **no unit conversions**; a few submessages are renumbered (table below). Maps adds `cbjh.12` itself (source info).
- On the phone, Maps reads only **current energy** and **capacity** (battery % and change metrics, `beps.aD`). Every other block goes to Google's servers unchanged (`qft` → `qgj`, and the `ckob`/`cnmk` request protos). **The energy prediction runs server-side**, so the code doesn't show what the tables mean. Meanings below come from Maps' own demo models plus physics.
- Gate (`qis.java`): if `1.3` current energy is **0**, Maps drops the model (or keeps the previous one if that was ≥ 5000 Wh, and logs "Battery level was 0 but previous battery level was >= 5000 Wh."). **Always send `1.3` > 0.**
- Re-route triggers (`qft.java`): a new model causes a refresh only if road-load A changes by ≥ 50, B by ≥ 5, C by ≥ 0.05, `6[0].1` by ≥ 600, `6[0].3` by ≥ 500, or `10.2` by ≥ 500. Unchanged blocks are dropped from the update sent to the server.
- Enum value names are gone (R8 turned the enums into ints), and no UI or debug strings describe the fields.

### Maps' built-in models (useful reference values)

| | `bdjr` "fake" | `qgr` "demo-vemlite" | `qgr` "demo-vemzero" | Ford sample |
|---|---|---|---|---|
| `1.2` reserve Wh | 2000 | 2154 | 2501 | 3175 |
| `1.3` current Wh | 73800 | % × 78000 | caller value | 68378 |
| `1.4` capacity Wh | 82000 | 78000 | 65677 | 92403 |
| `1.9` | 170000 | 170000 | 240000 | - |
| `1.10` | 155000 | 155000 | 150000 | - |
| road load A / B / C | 1200 / 0 / 1e-10 | 138.38 / 2.18 / 0.3617 | - | 167.3 / 2.8 / 0.75 |

Every sample has a reserve of 2.4–3.8% of capacity. The vemlite road load fits a mid-size EV crossover in N, N/(m/s), N/(m/s)².

## Field map (paths are inside `SensorBatch.23`)

Confidence: **code** = proven by Maps code. **strong** = units and values fit physics and the demo models. **guess** = plausible only. `Q` = `Quantity {1: int32 value, 2: float}`. Int values go in field 1. The demos also set field 2 = 0.0; Ford leaves it out.

| Path (type) | → `cbjh` | Ford value | Meaning | Confidence |
|---|---|---|---|---|
| `1.2` Q | 1.1.i | 3175 | reserve / below-zero buffer, Wh | strong (always ~3% of capacity) |
| `1.3` Q | 1.1.d | 68378 | **current battery energy, Wh**. Must be > 0 | code |
| `1.4` Q | 1.1.e | 92403 | **capacity, Wh** (100% point for battery %) | code |
| `1.5` enum | 1.1.f | - | 3-valued enum (0,1,2), unknown | - |
| `1.6` float | 1.1.g | 1.293 | air density, kg/m³ | strong |
| `1.7` float | 1.1.h | 27.5 | outside temperature, °C | strong |
| `1.8` float | 1.1.m | - | ? | - |
| `1.9` uint32 | 1.1.j | - | power in W? (demos 170 kW / 240 kW). Maybe max DC charge power | guess |
| `1.10` uint32 | 1.1.k | - | power in W? (demos 155 kW / 150 kW). Maybe max discharge or regen | guess |
| `1.11` bool | 1.1.l | - | ? | - |
| `1.12[]` {enum(5), uint32} | 1.1.n | - | ? (could be per-connector limits) | - |
| `2.1.1` / `2.2.1` / `2.3.1` float | 2 | 167.3 / 2.8 / 0.75 | **road load A [N], B [N/(m/s)], C [N/(m/s)²]**. `x.2` float is never set | code (re-route thresholds) + strong |
| `3.7[]` {float speed m/s, `2[]` {**sint32** x, Q y}} | 10 | 10 rows × 50 pts | **drivetrain loss map**: x = wheel power 0..191498 W (step 3908), y = loss W. Efficiency at 100 kW goes from 74% (standstill) to 91% (36 m/s). Ford has no negative x (regen) points | strong |
| `3.8` uint32 | 10.d | - | ? | - |
| `4.1[]` {int32 Wh, uint32 W} | 4.b | 26 rows | **max charge power vs battery energy** (flat 207 kW, tapering above 105 kWh) | strong |
| `4.2[]` {int32, uint32} | 4.c | - | second table of the same shape (discharge/regen limit?) | guess |
| `5.4[]` {**sint32** x, Q y} | 11 | 30 pts | **battery internal loss**: x = battery power 5..150 kW, y = loss W, exactly I²R with R ≈ 0.030 Ω at 400 V | strong |
| `6[]` {int32, Q at field 3} | 6 | - | unknown. Changes to `[0]` trigger a re-route | - |
| `7[]` {float °C, Q 2, Q 3, Q 4} | 7 | 15 rows, only Q4 | **aux/HVAC power vs outside temp**, W. Q2/Q3 are probably other aux states | strong (Q4) |
| `8.1[]` {int32 charger W, `2[]` {int32 Wh, int32 s}} | 8.b | 9 tables | **charge-time tables per charger power** | strong |
| `8.2[]` {float, `2[]` as 8.1} | 8.c | - | the same tables keyed by a float, probably temperature | guess |
| `9` {int32, Q at field 2} | 9 | empty | ? | - |
| `10` {UF[], u32, FU[], u32, UF[]} | 13 | - | ? `10.2` triggers re-routes | - |

## What an Ioniq 5 model needs

Write it as protobuf text (`vem.VehicleEnergyModel`), then `protoc --encode=vem.VehicleEnergyModel reference/vehicle_energy_model.proto`, then wrap it as SensorBatch field 23 (tag bytes `ba 01` + length). Re-encoding the Ford sample this way reproduces it byte for byte.

| Block | Source |
|---|---|
| `1.3` / `1.4` | 2022 US: 58.0 kWh (SR) or 77.4 kWh (LR) for `1.4`. Live energy from `HEVManager` SOC × capacity |
| `1.2` | ~3% of capacity |
| `1.6`, `1.7` | 1.225 static density; **outside temp is live** (nav battery packet `DATC_OUT_TEMP_SNSR` via `NavigationCluster.requestBatteryInfoReq()`, patched in place at runtime) |
| `2.x` road load | **your rolldown data** (conversions below) |
| `3.7` loss map | not in rolldown. Scale Ford's map, or build one from an efficiency estimate. Remember x is **sint32** |
| `4.1` | E-GMP 800 V curve, calibrated on 233 real EA sessions: ~230–235 kW (3C) to 50%, thermal step-down to 160 kW at 53% held to 80%, 150 kW after the pause, then 60/35/22/15 kW at 90/95/98/100%. Thermal pause lives in the 8.1 tables, not here. (Cold-pack caps measured but not shipped — see "field findings") |
| `5.4` | I²R: loss = R·(P/V)². Use the pack's DC resistance and ~650–700 V nominal. R is unmeasured; estimate it from the cell count and cell resistance, or from the voltage sag under load |
| `7` aux power (Q4) | driving heater/A-C power vs outside temp; cold part calibrated with road load A (see "Cold weather") |
| `8.1` | integrated from 4.1 (×η=0.95 charger→battery) + AC limits (10.9 kW onboard, 1.44 kW L1); 13 charger rows (350/260/250/240/200/175/150/125/100/50/25 kW DC, 10.9/1.44 kW AC); +300 s thermal pause at ~81% on ≥200 kW rows. **250 kW = Tesla Supercharger**: US Superchargers are all listed at exactly 250 kW and run at ~500 V, so the 800 V pack charges via the boost converter at a flat 105 kW to 90% (owner-measured, no step-down or pause), then the normal taper. Maps keys these tables by charger power only, so the voltage limit has to live in that row; 260/240 kW rows bracket it so other stations near 250 kW stay on the 800 V profile |
| leave out | `1.5`, `1.8`–`1.12`, 6, 9, 10 (Ford works without them) |

### Unit conversion for EPA-style rolldown (target) coefficients
- A [N] = A[lbf] × 4.44822
- B [N/(m/s)] = B[lbf/mph] × 9.9506
- C [N/(m/s)²] = C[lbf/mph²] × 22.2592

If the data is your own coast-down (speed vs time), fit F(v) = m_eff·(-dv/dt) = A + B·v + C·v², with m_eff ≈ test mass × 1.03 to account for rotating inertia.

## Rolldown data (`reference/IONIQ5 roll-down test.xlsx`)

2022 Ioniq 5 SEL AWD, 2120 kg as tested, 61 °F, 40 psi, dirty. Two coast-downs in opposite directions, logged at 1 Hz:
- run 1: 51.9 → 26.1 m/s over 68 s
- run 2: 48.5 → 21.7 m/s over 74 s

The units really are m/s. Fitting the data as m/s gives CdA ≈ 0.82 m² and Crr ≈ 0.0094, which is right for an Ioniq 5 (Cd 0.288). Reading it as mph or km/h gives impossible values (CdA 1.8–3.0, Crr 0.003–0.004).

### Road load (block 2)
- **Recommended: A = 196 N, B = 0, C = 0.493 N/(m/s)².** This is one fit of F = m_eff·(−dv/dt) over both runs together, with m_eff = 2120 × 1.03 for rotating inertia. Fitted separately, the two runs give A = 143 / 189 and C = 0.492 / 0.545.
- B can't be fitted. All the data is above 21.7 m/s (48 mph), so A and B trade off against each other: a free 3-term fit gives A < 0. Setting B = 0 and letting A carry rolling resistance is the standard fix.
- The sheet's own route (separate cubic fits of power, then averaged) works out to A ≈ −0.7 N, B = 6.84, C = 0.460. It agrees with the physical fit from 60 mph up (within 1 kW). Below about 50 mph it's lower (1.5 vs 2.9 kW at 25 mph) because it has no rolling-resistance term. That's why the sheet's low-speed ranges (e.g. 1116 mi at 23 mph) are too optimistic.
- To pin A down, do a low-speed coast-down (about 45 → 5 mph, both directions).

### Aux / HVAC (block 7)
250 / 500 / 1500 W are realistic for mild / typical / about 10 °F. As a starting `7[]` table (Q4 = W): about 1500 W at −12 °C, ramping to about 250 W at 18–24 °C, then back up to about 500–800 W at 35–45 °C for A/C. Ford's Q4 values are much larger (2.6–5.4 kW), so we don't know yet whether Q4 is only HVAC/accessories. Test on the car and compare predicted vs real consumption.

### Cold weather (blocks 2 and 7, live outside temp)
The warm model (rolldown road load, ~400 W aux) gave ~293 Wh/mi for a 240 mi Burlington VT → Syracuse NY trip at 26 °F, so Maps planned 100 → 9 % with no stop. Owner anchors (highway, trip computer):

| Outside | Speed | Real | Warm model |
|---|---|---|---|
| −12 °F (−24.4 °C) | 60 mph | ~1.6 mi/kWh (625 Wh/mi) | 285 Wh/mi without heat |
| 20 °F (−6.7 °C) | 65 mph | ~2.3 mi/kWh (435 Wh/mi) | 314 Wh/mi without heat |

All of that gap as heater power (block 7, time-based) would be ~20 kW at −24 °C and absurd at city speeds, so it's split:
- **Block 7 (time-based)**: driving heater power, assumed 3.0 kW at −6.7 °C and 6.5 kW at −24.4 °C, 500 W at 10 °C, ~400 W electronics floor when mild, A/C above 24 °C. Maps looks it up server-side by `1.7`, which the sender patches from the live outside temp.
- **Road load A (distance-based)**: cold tyres, denser air, drivetrain and battery losses. `COLD_DELTA_A` is solved so the anchors come out exact: +156 N at −6.7 °C, +483 N at −24.4 °C, 0 at 10 °C and above, linear between, clamped at −35 °C (+679 N). `EvModelSender` patches A (float at `ROAD_A_OFFSET`) from the live outside temp, with 3 °C hysteresis because Maps re-routes when A moves ≥ 50 N. Unknown temp keeps the last value, or the warm A.

Result at 26 °F: ~2.6–2.75 mi/kWh at 55–60 mph (was ~3.4), so that trip now needs ~90 kWh and gets a charging stop. Mild weather is unchanged (3.1 mi/kWh at 65 mph, 50–70 °F). The heater split is an assumption; more cold trips (mi/kWh, average speed, climate share from the energy screen) at different temperatures would pin it down. The raw DATC outside-temp scaling is unconfirmed: the sender ignores values outside −45…55 °C, and the log line's `out=` should match the dash.

### Drivetrain efficiency (blocks 3 and 5)
The sheet uses a flat 0.9, battery to wheel. In the Maps model that's split between block 3 (drivetrain loss vs speed and wheel power) and block 5 (battery I²R). Don't put 10% in both. Suggested start: block 3 ≈ 7–8% of wheel power plus a few hundred W of fixed spin/inverter loss that grows with speed (Ford's map has 450 W at 0 m/s rising to 2.7 kW at 36 m/s, with zero wheel power), and block 5 with R ≈ 0.1 Ω at ~650 V. Both should then be calibrated so the total matches about 0.9 at highway load.

### DC charging (blocks 4.1 and 8.1)
The sheet's 10→X% averages (alexonautos test) work out to these averages per band:

| SOC band | avg kW |
|---|---|
| 10–50% | 214 |
| 50–60% | 200 |
| 60–70% | 180 |
| 70–80% | 155 |
| 80–90% | 115 |
| 90–100% | 99 ⚠ |

The 90–100% value comes from the sheet's 165 kW / 25 min for 10–100%. That's too fast: real E-GMP charging tapers to about 30–50 kW above 90%. Use a measured curve there.

For 4.1, x = absolute energy in Wh (SOC × 77400). These are band averages, so the peak inside the 10–50% band is higher (about 230 kW).

## Field findings (2026-10-28, from the car and 233 real EA sessions)

Calibration (`tools/analyze_charge_history.py`, `reference/charge_history*.csv`, plus exact start-SOC spot pulls from the EA app):
- **EA "TotalEnergyDelivered" is charger-side**; the battery absorbs ~5% less. Warm-weather η = 0.944–0.958 → `CHARGE_ETA = 0.95` in the time tables. Mean model error vs 233 sessions: −1.0 min, MAE 3.1 min.
- **Usable capacity ≈ 77.4 kWh when new** (2022 deep sessions fit C=77400 with η≈0.95) — Hyundai's figure is usable, not gross. Runtime degradation adjustment: SOH probe (`tm_hscan_service` txn 0xd6, `getXEV_Bms_GDS_SOH`), gated by `persist.gpm.ev.sohcap=1`.
- **Thermal step-down AND pause are session-relative (heat added this session), not absolute SOC**: full 3C from 0% (no low-SOC ramp); step 3C→~160 kW at ~55–60% from a 0% start, ~65–70% from 10%, ~75–80% from 30%; pause ~78–82% (early starts) or ~83–85%/never (late starts). The energy-indexed model can't express that, so both are placed for the canonical road-trip case (arrive at the charger low): step at 53%, pause at ~81% — **10→80% = 17.5 min** on 350 kW (owner's real figure; Hyundai's official 18), 10→85% = ~23.5 min (pause paid). Validated: a 54→99% session, 30.6 actual vs 30.7 model; top taper 35/22/15 kW at 95/98/100% (a 95→100% session: 10.4 actual vs 10.2 model). Sessions arriving warm/high-SOC will read slightly pessimistic in the mid band, slightly optimistic otherwise.
- **EA export quirks**: some 2022 sessions have `ChargeDuration = 0 min 0 sec` (use `TotalDuration − GracePeriod`); one negative-duration row; one hour-format duration.
- **Derated/broken cabinets** are obvious by max rate (e.g. 45/54/74 kW on 350-class stations). A cluster of ~104–115 kW maxes = 400 V-class cabinets or shared power — the car's boost converter caps ~105 kW on those (same as Tesla 400 V Superchargers; a route planner can't see this from charger rating alone).
- **Cold-pack caps** (owner's logbook, *not shipped* — the shipped model assumes preconditioning works): <20 °C → ~160 kW, <15 °C → ~120 kW, <10 °C → ~90 kW.

Shipped-model decisions from this:
- **Single warm-pack curve.** For road-trip planning the car preconditions en route to a charger, so the ideal-temp curve is the realistic one. Multi-variant temp-swap machinery was built, evaluated, and stripped (see below).
- **Live outside temp** (nav battery packet `DATC_OUT_TEMP_SNSR` via `NavigationCluster.requestBatteryInfoReq()`) patches field 1.7, so the block-7 aux table does real work in winter/summer.

Explored and parked:
- **Temp-keyed charge tables (8.2 `by_temp`)**: implemented, then removed. The semantics are unconfirmed, and there's a structural hole: the planner can't pick a per-stop temperature without knowing pack temp and its evolution — no pack-temp field exists in the sensor-23 schema, so any 8.2 consumer needs its own thermal model. Possible real channel for pack temp: **sensor 25 (`cdhl`)** — schema `cdig{1: int32, 2: int32, 4: repeated {3: float, 4: float}}`, unconfirmed; Ford works without it. The self-correcting mechanism (re-send every 5 s, variant/curve always reflects current pack) is the robust path.
- **Pack-temp variant swap with hysteresis**: worked in code, but "current temp" is the wrong state for planning a stop an hour away (preconditioning). Stripped per above.
- **Nav battery packet layout** (`BATTERY_INFO_RX`, 11 bytes): [0]=SOC in 0.5% steps ✓, [1]=min cell temp °C ✓, [2..3]=`EV_AVLBL_ENRG` big-endian uint16 tracking SOC (~7202 units at 24% — units unconfirmed; 2 Wh ⇒ 60 kWh full, conflicts with 77.4; maybe power, maybe display-buffered SOC), [6]=`VCU_DTE_BASE` 76 ≈ cluster km, [9]/[10] vary (status?). Pin units down with a drive/charge capture where SOC moves.
- **Regen (negative-x) drivetrain points**: shipped as an experiment (sint32 allows it, Ford omits it); validate downhill predictions on a hilly drive.

## Next steps
1. ~~Recover the real schema from Google Maps~~ (done, see above).
2. ~~Write the generator~~ (done: `tools/build_ioniq5_model.py`; shipped values below are its constants, verified accepted by Maps on the car 2026-10-28).
3. Test on the car: ~~does Maps accept the model~~ (yes). Charge tables calibrated against 233 real EA sessions (`tools/analyze_charge_history.py`, `reference/charge_history*.csv`): mean err −1.0 min, MAE 3.1 min. Exact-start spot pulls (EA app) validated: `CHARGE_ETA=0.95` (warm sessions imply 0.944–0.958; EA energy is charger-side, battery gets ~5% less), top taper 35/22/15 kW at 95/98/100% (a 95→100% session: 10.4 min actual vs 10.2 model), thermal pause +300 s at ~81% on ≥200 kW chargers (a 54→99% session: 30.6 min actual vs 30.4 model). Usable capacity ≈ 77.4 kWh when new (2022 sessions) — Hyundai's figure is usable, not gross; runtime SOH probe handles degradation. Remaining variance: pack not-quite-preconditioned sessions (preconditioning state matters, not season). Do range and arrival-SOC predictions match real drives? Then tune blocks 3 and 7.
4. Optional, to pin down the unknown fields: capture a model from a real AAOS/OEM car (e.g. a `content://google_maps_energy/model` dump), or A/B test `1.9`/`1.10` by watching how Maps' charging-stop durations change.

## Sources
- Google Maps 26.43.00 (APKMirror bundle in `private/maps-26.43/`): `cdim` (wire schema), `gfz.q` (converter), `qis` (zero-energy gate), `qft` (re-route thresholds), `bdjr`/`qgr` (demo models), `pxl`/`pxo` (sensor 23 parse)
- aa-proxy-rs `src/protos/ford_ev_model.bin`, `src/ev.rs`, `src/protos/ev.proto`: https://github.com/aa-proxy/aa-proxy-rs
- aa-proxy EV docs: https://aa-proxy.github.io/docs/ev. The EV Model Generator it links (http://152.70.21.153/index.php) returned 404 on 2026-10-27
- open-android-auto (sensor 23/25 and VehicleEnergyForecast protos): https://github.com/Deadknight/open-android-auto
- ArdaMapsEnergyProvider (the same model used on AAOS): https://github.com/Deadknight/ArdaMapsEnergyProvider
- aa-proxy-rs issue #19 (early attempts; fuel type and connectors alone didn't trigger EV routing): https://github.com/aa-proxy/aa-proxy-rs/issues/19

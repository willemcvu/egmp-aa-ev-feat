# Automatic battery preconditioning from Google Maps (Android Auto)

Date: 2026-10-28 (research); implementation 2026-10-29, **verified on the car 2026-10-29** (v0.2.0, see [Implementation](#implementation) and [Test 3](#test-3-automatic-mode-after-building)). Car: 2022 Ioniq 5, Gen5W `NEEV.USA.0000.V015.012.260507`, NEMESIS + Leafia v1.0.5 + our EV-model patch (`HANDOFF.md`).

Goal: when Google Maps on Android Auto is guiding to a DC fast charger (the destination, or a charging stop Maps added), the car preconditions the battery the way it does for its own navigation. No buttons.

## Short answer

**Yes. Time and distance arrive on the head unit, and Maps marks charging stops (confirmed on the car 2026-10-29, [Test 1](#test-1-does-maps-mark-chargers-no-install)).**

| Need | Status | Where it comes from |
|---|---|---|
| Distance to destination | **Have it, seen live** | AA message 0x8007, metres (`22230` in our capture = 13.8 mi) |
| Time to destination | **Have it, seen live** | AA message 0x8007, seconds (`1455`) |
| Route active / cancelled | **Have it** | AA NavigationState → GPM `onStatus(I)` |
| "Destination is a charging station" | **Have it, seen live** | AA 0x8006 `destinations[].2` = {1 departure energy Wh, 2 charger power **W**, 3 planned charging time s, −1 = none} |
| Charging stops along a longer route | **Have it, seen live** | 0x8006 lists every stop in order (chargers carry field 2; the final destination doesn't). 0x8007 carries **one** entry: the next stop |
| Car-side "precondition for a charger N km / M min away" | **Have it** | `IHEVService` transactions 62/65/66, the same inputs the car's own nav uses (see below) |

The open question can be answered **with no install**: Leafia's GPM already logs every 0x8006/0x8007 payload as `NAVDUMP`. One parked test session routing to a charger answers it (see [Test 1](#test-1-does-maps-mark-chargers-no-install)).

## 1. How the manual preconditioning app works (car side)

Source: `github.com/paytondevs/gen5w-preconditioning` (local clone: scratchpad `repos/gen5w-preconditioning`). `OemVehicleClient.java`, `PreconditioningProfile.java`, `PreconditioningController.java`, `COMPATIBILITY.md`.

It doesn't touch CAN directly. It does exactly what the car's own navigation does when you route to a charger: it calls the OEM HEV binder service with *fake* "charger ahead" route data.

- Service `com.hkmc.micom.hev`, descriptor `com.hkmc.hev.IHEVService` (the same one we already use to read SOC).
- Firmware-gated on SHA-256 of `/system/framework/automotive.odex` and `automotive-service.odex` (Ioniq 5 hashes in its `COMPATIBILITY.md`).

| Step | Binder calls | Meaning |
|---|---|---|
| Start | `65(9)`, `66(18)`, wait 10 ms, `62(2, 3)`, wait 130 ms | "charger is 9 km / 18 min away", then the station event |
| Maintain (every 5 s) | `65(9)`, `66(18)` | refresh distance/time only, **never** resend 62 |
| Cancel | `65(2047)`, `66(1023)`, `62(0, 0)` | invalid distance/time + station cleared |
| Status (read) | BCM `com.hkmc.micom.bcm` txn 109 → 0 Off, 1 Active | the car's own "conditioning active" indicator |
| Preconditions (read) | HEV txn 3 flag 94 (`EV_BATTERYCONDITIONING`) must be 2 = enabled in EV settings; flag 44 = READY | |

The app also has a 5-minute timeout if the car never reports Active, crash recovery that only ever cancels, and a rule that Start needs READY + the setting enabled + indicator Off.

Caveat from its own docs: at publication the **Ioniq 5 profile had not been verified in a parked car**. The field tests (Off → Active → Off) were on an EV6 with the same 260507 software family. *(Closed 2026-10-29: our automatic mode, which reuses this profile, went Off → Active → Off on the owner's 2022 Ioniq 5.)*

### What those transactions are in the firmware

Confirmed in our deodexed car framework (`private/car-neev-260507/smali/automotive{,-service}/…/HEVService.smali`, `IHEVService$Stub.smali`, `micom/protocol/HEV$Func.smali`):

| Txn | Method | MICOM function | Payload |
|---|---|---|---|
| 0x3e (62) | `setBattCondEvStation(flag, type)` | `NAVI_PRECOND_EVSTATTION_C` 0xc68 | 2 bytes, each `& 0xf`. **Auto-resets to (7,7) "Invalid" after 120 ms** (`HEVService$6` timer), so it's a pulse/event, not a level. |
| 0x41 (65) | `setBattCondRouteDis(km)` | `NAVI_PRECOND_ROUTEDIS_C` 0xc66 | 16-bit big-endian; 2047 = invalid |
| 0x42 (66) | `setBattCondRemainTime(min)` | `NAVI_PRECOND_REMAINTIME_C` 0xc65 | 16-bit big-endian; 1023 = invalid |
| — | `setBattCondEvStationFlag(i)` / `…Type(i)` | 0xc63 / 0xc64 | single-byte variants; not used by the app |

The method names (`NAVI_PRECOND_*`) confirm this is the navigation-to-charger interface. The car's BMS decides whether and when to heat from those inputs. The meaning of flag 2 / type 3 isn't spelled out in the Java layer. The app's author took them from the car's navigation code. Type might separate DC from AC chargers. This is unconfirmed, so reuse (2,3) as-is.

**Implication:** the car side is solved and needs no native work. GPM already runs as the system uid and already calls this same binder service. Automatic mode = the same calls with real distance and time instead of 9 km / 18 min.

## 2. What the phone already sends us

### Protocol version: why we get the rich nav messages at all

`Controller::sendVersionRequest` (0xb1a1c in `libautoreceiver_infobank.so`) announces the AA protocol version:

- stock: `movs r6, #4` → **1.4**
- Leafia (from NEMESIS `projection.tbt`, the `sendVersionRequest+0x12` hook): `movs r6, #6` → **1.6**

Android Auto only sends `NavigationNotification` (0x8006, with destinations) to head units at 1.6 or later. Older units get flat turn events with no destination data. So Leafia already unlocked it.

### Messages on the navigation-status channel (phone → HU)

From the open-android-auto proto set (scratchpad `repos/open-android-auto/oaa/navigation/`):

| ID | Message | Useful contents |
|---|---|---|
| 0x8003 | NavigationState | ACTIVE / INACTIVE / REROUTING |
| 0x8006 | NavigationNotification | `steps[]`, **`destinations[]` {address, `charging_station` {connector_count, available_count, max_power_kw}}** |
| 0x8007 | NextTurnDistanceEvent | step distance, and per destination: metres, display text, ETA text, **seconds remaining** |
| 0x8008 | VehicleEnergyForecast | `next_charging_stop`, `energy_at_next_stop` {distance_m, arrival_wh, time_s}, `stop_details[]`. **Gated** by the phone on a "CarInfo PDK ≥ 5.1" check. We announce 1.6, and it's unclear what that number maps to on our side. Our library almost certainly has no handler for 0x8008 either. |

0x8008 is the ideal message: Maps' own "next charging stop, this far, this long". It's the AA counterpart of the AAOS `content://google_maps_energy/forecast` path (`repos/ArdaMapsEnergyProvider`). Getting it would mean a version bump with unknown side effects plus a native handler. **Don't start there.** 0x8006/0x8007 are already flowing.

### Leafia already hands these to Java and logs them

Leafia's native hook in `NavigationStatusEndpoint::routeMessage` forwards the raw payloads to GPM's `GalConnectionSession$4.onNextTurn(String, …, type=0x21, …)` → `GpmNaviTBTManager.onNextTurn`, which logs them:

```
D NAVDUMP : S <len> <hex>     # 0x8006 NavigationNotification
D NAVDUMP : P <len> <hex>     # 0x8007 NextTurnDistanceEvent
```

GPM also parses the trip remaining natively into `GpmNaviTBTManager.onTrip(meters, seconds, flags)` → fields `mTripMeters`, `mTripSeconds` (used for the cluster's "remaining" display).

Decoded from our wired session (`private/captures/wired-session-live-20261027-221321.log`), route to a normal address while parked:

```
S (0x8006):
  1 steps    { 1 maneuver {1: 1}, 2 instruction "toward <street>", 4 road_info "toward <street>" }
  2 destinations { 1 address "<street address>" }        <- no field 2 (charging_station): not a charger

P (0x8007):
  1 step     { 1 { 2: "0", 3: 6 (feet) } }
  2 destination { 1 { 1: 22230 (m), 2: "14", 3: 4 (miles) }, 2: "10:36 PM", 3: 1455 (s) }
```

(The open-android-auto proto names 0x8007 field 2 `turn_labels`. The wire data shows it's a per-destination distance/ETA record, and it's `repeated`.)

So **distance and time are solved**. The only unknown is whether `destinations[n].charging_station` is present when Maps routes to a charger. It's a dedicated field with charger-specific contents (port counts, kW), which strongly suggests Gearhead fills it from Maps' EV routing. That still needs to be seen.

### Fallbacks

Not needed: Test 1 showed Maps marks charging stops itself. Name matching on destination addresses was considered and rejected (too unreliable); it is not implemented.

## 3. Design (implemented, see [Implementation](#implementation))

Java only, in GPM, on top of the dex the EV-model patch already replaces. No native change.

- **Hook point:** `GpmNaviTBTManager.onNextTurn(String, …)` when `p3 == 0x21` (the NAVDUMP branch). Parse the `S`/`P` hex with a small protobuf-lite reader. Also use `onStatus(I)` for route start/stop and the AA session teardown already handled by GPM.
- **Pick the target:** the first entry in `destinations[]` with charging-stop data (`.2`) above 50 kW (skip L2; preconditioning only helps DC), plus the matching 0x8007 entry by index.
- **Drive the car:** a `PrecondDriver` thread mirroring the manual app's profile. Don't invent new commands.
  - Arm when: target found, HEV flag 94 == 2 (setting enabled), READY, BCM indicator Off, and remaining time ≤ a window (e.g. 60 min; the car's own nav probably sends from route start. The BMS decides when to heat).
  - Start: `65(km)`, `66(min)`, 10 ms, `62(2,3)` once, 130 ms guard.
  - Maintain every 5 s: `65(km)`, `66(min)` from live 0x8007 values (clamp to 0..2046 / 0..1022).
  - Cancel `65(2047)`, `66(1023)`, `62(0,0)` on: route cancelled/INACTIVE, destination no longer a charger, rerouted away, arrival (distance < ~200 m), AA disconnect, GPM shutdown.
  - Keep the manual app's safety rules: write ownership to disk before the first command, cancel on recovery after a crash (never start), and treat only BCM indicator 1 as "Active".
- **No switch:** always on. The car's own "battery conditioning" EV setting (HEV flag 94) is the off control.
- **Don't fight other writers:** native nav (not guiding while AA guides, but `isGpmNaviGuidance()` is the flag it checks) and the manual Launchpad app. If the indicator is already Active and we didn't start it, stay hands-off.
- **Packaging:** same pipeline and md5 gating as `packages/aa-ev-model/` (both features replace GPM `classes.dex`). Ship as one package, or rebuild the EV-model one with both.

Risk: same class as the EV-model patch. If GPM throws, AA stops working, and uninstall restores Leafia's files. The only new car-facing action is the three HEV calls the manual app and the car's nav already make, which ask the BMS rather than override it.

## Implementation

Built 2026-10-29 into the same GPM `classes.dex` as the EV-model patch (`tools/build_all.sh`). Host-tested, then verified on the car the same day (build test7, wired AA).

**Code** (`gpm-ev/src/com/google/android/projection/protocol/`):

- `NavProto`: minimal protobuf reader for the `S` (0x8006) and `P` (0x8007) NAVDUMP strings. Returns destinations (index, charging stop present, departure Wh, charger kW from field 2's watts, planned charging seconds) and {metres, seconds} entries. Rejects truncated payloads (declared length must match the hex). Addresses are never stored or logged. Only a hash is kept in memory, as the stop's identity.
- `PrecondCore`: the decision logic, pure Java, with the car, journal and log behind interfaces.
- `Precond`: Android shell. One `HandlerThread` (`EvPrecond`); binder access modelled on Launchpad's `OemVehicleClient`; the ownership journal `files/evprecond.owned` in GPM's data dir; static getters (`navActive()`, `remainingMeters()`, `remainingSeconds()`, `isCharger()`, `chargerKw()`, `state()`/`stateName()`) for the drive logger.
- **Firmware check:** commands are sent only when the SHA-256 of `/system/framework/automotive.odex` and `automotive-service.odex` match our `private/car-neev-260507` copies (and SDK 19), since those files define the transaction numbers. There is no device-name check: the first car test was blocked only by Launchpad's device-name check, which names the 2023 unit (`…neev23_us`), while the owner's 2022 is `…neev_us` with identical framework files.

**Hooks** (`tools/patch_gpm_smali.py`; every call is wrapped in a catch-all, so a Precond failure can't break navigation):

| Where | Call | Why |
|---|---|---|
| `GpmNaviTBTManager.<init>(Context)` | `Precond.onSessionStart(ctx)` | starts the thread at each AA session; a stale journal is cancelled here |
| `GpmNaviTBTManager.onNextTurn`, type 0x21 branch | `Precond.onNavDump(p1)` | before Leafia's `NAVDUMP` log + return; payload is parsed on our thread |
| `GpmNaviTBTManager.onStatus(I)` | `Precond.onNavStatus(p1)` | 1 ACTIVE / 3 REROUTING = guiding; anything else ends the route |
| `GpmNaviTBTManager.destroy()` | `Precond.stop()` | only caller is `GalConnectionSession.clearSession()` (AA session teardown). `stop()` is not used: `GalConnectionSession$6` calls it on focus changes too |
| `SensorSource.shutdown()` | `Precond.stop()` | second teardown path (GAL shutdown), after the existing `EvModelSender.stop()` |

**No switch** (owner's call, 2026-10-29): preconditioning is always on, experimental. To turn it off, switch off "battery conditioning" in the car's EV settings (HEV flag 94 != 2 blocks Start and cancels a live request), or uninstall the package. `persist.gpm.ev.off` doesn't affect it. Targets need charging-stop data with charger power **above 50 kW**; there is no name-match fallback.

**Rules** (constants in `PrecondCore`):

- Target: the first destination that qualifies, with distance/time from the same index in 0x8007. An `S` with no destinations keeps the last list.
- Start once when guiding, target present, remaining time <= 60 min, remaining distance >= 200 m, flag 94 == 2, flag 44 == 1, BCM 109 == 0, and the firmware matches. The journal is written (fsync) first; if it can't be written, there's no Start.
- Maintain every 5 s with live km/min (rounded, clamped 0..2046 / 0..1022). Never resend 62.
- Cancel on: route inactive, target gone or no longer a > 50 kW charger, arrival (< 200 m), AA session end, GPM GAL shutdown, car status unreadable for 5 min, READY lost, setting disabled, or car Active -> Off. There is **no** "no Active within 5 min" cancel (Launchpad has one for its manual button): on the car a 58-min route stayed REQUESTED with no heating for minutes, so the BMS times the heating itself from the distance/time we keep sending.
- After arrival, timeout or car-side end, that stop is latched (no restart) until the route ends or the target changes. At most 3 Starts per route (protects against a target flickering in and out).
- If the indicator is already Active and we didn't start it: hands off.
- After Cancel, the journal is kept until BCM reads Off (or 60 s pass). A failed Cancel is retried 12 times, 5 s apart. If it still fails, the journal stays and the next session cancels again.

**States** (`Precond.state()`): 0 OFF (before the first AA session), 1 IDLE, 2 WAITING, 3 REQUESTED, 4 ACTIVE, 5 CANCELLING, 6 DONE, 7 HANDS_OFF, 8 ERROR.

**Log** (`adb logcat -s EvPrecond`): `started`; `nav active (1)`; `dests n=2 [0: station kw=350 dep=77400Wh chg=1432s] [1: -]`; `target: stop #1, 150 kW`; `waiting: <reason>`; `start: 30 km, 20 min, 150 kW`; `car reports conditioning Active`; `cancel: <reason>`; `cancel confirmed: car reports Off`; `state A -> B`. No addresses.

**Tests:** `sh gpm-ev/test/run.sh` (JVM, fake vehicle/journal; `VERBOSE=1` prints the log). `PrecondTest`, 82 checks:

- NavProto on the real 0x8007 payload, and on the real 0x8006 payload with its place names replaced by same-length placeholders. Non-charger: never starts.
- Charger routes shaped like the captured ones: start profile exact; maintain without 62; cancel on arrival, route end, session end, target -> non-charger or L2, car status unreadable for 5 min, READY lost, setting disabled, Active -> Off. A request that stays REQUESTED for over 5 min is **not** cancelled. Exactly 50 kW never starts; 51 kW does.
- Rerouting keeps the request. Multi-stop picks the right index. Window, hands-off, firmware mismatch and journal failure each block Start. Stale journal cancels only. Failed cancel is retried. Start churn is capped.

**Verified on the car (2026-10-29, wired AA):** the hooks run; the firmware check passes; short routes to a DC charger go REQUESTED → ACTIVE with the car's indicator on; ending the route cancels and the car reads Off within ~2.5 s; a 58-min route stays REQUESTED (the BMS waits to heat). Host logic, the build and the package sandbox test also pass.

**Not verified:**

- that the single 0x8007 entry is the next stop on a real multi-stop drive (Test 1 strongly suggests it);
- cancel on arrival, session end and setting-off on the car (host-tested);
- a cold-morning drive where the BMS heats en route; wireless AA.

## 4. Tests

### Test 1: does Maps mark chargers? (no install)

Parked, wired AA, phone Bluetooth off, laptop on home Wi-Fi with adb.home:

```sh
adb logcat -v time | grep --line-buffered NAVDUMP | tee private/captures/navdump-chargers-$(date +%Y%m%d-%H%M).log
```

Then in Maps on the car screen, one at a time, giving each ~30 s:

1. Route straight to a **DC fast charger** (search "EV charging", pick a 150 kW+ site).
2. Route to a **Level 2** charger (to see whether `max_power_kw` tells them apart).
3. Route to a far destination that needs a charging stop. At 23 % SOC almost anything over ~40 mi works, provided the EV model is on so Maps adds stops itself.
4. Manually **add a charger as a stop** on a normal route.

Decode each unique `S`/`P` payload with `reference/pbdump.py`. Look for `.2.2` in `S` (charging_station) and for multiple `.2` entries in both. This answers the main question and the multi-stop question.

**Result (2026-10-29, owner's car, steps 1–3):**

| Route | `destinations[]` | 0x8007 entries |
|---|---|---|
| DC fast charger | 1 dest, `.2` = {1: 33744, 2: 180000, 3: −1} | 1 (9.9 km, 665 s) |
| Level 2 charger | 1 dest, `.2` = {1: 34999, 2: 7000, 3: −1} | 1 |
| Long trip, Maps-planned stops | 3 dests: {77400, 350000, 1432}, {61859, 240000, 600}, then the destination with no `.2` | **1** (126.8 km, 6371 s: the next stop) |

So `.2` is the energy-forecast "charging station details" shape: departure energy (Wh; 77400 = charge to the capacity we sent), charger max power in **W**, planned charging time in s (int64 −1 = none planned). Not open-android-auto's {connector_count, available_count, max_power_kw}. The first `NavProto` parsed that guessed layout and would never have found a DC charger; fixed. Payloads up to 136 bytes arrived intact. Since 0x8007 only covers the next stop, a charger later in the route is only timed once it becomes the next stop.

### Test 2: car-side command on your Ioniq 5 (only if the manual app hasn't been confirmed on your car)

Run the manual Launchpad app once, cold battery, and confirm the indicator goes Active (Launchpad shows it). That validates the 62/65/66 profile on NEEV, which the automatic mode reuses. *(Superseded: Test 3 showed Active directly with the automatic mode.)*

### Test 3: automatic mode (after building)

Install the package built with this code. Phone Bluetooth off, `adb logcat -v time -s EvPrecond EvModelSender NAVDUMP` running before plugging in AA.

1. **Non-charger route.** Plug in AA and route anywhere that isn't a charger. Expect `started`, `nav active (1)` and a `dests n=… [0: -]` line, no `not parsed` line and no commands. AA and the cluster TBT should work as before.
2. **Charger route, parked, car in READY, battery conditioning enabled in EV settings.** Route to a DC charger (> 50 kW) less than 60 min away. Expect, in order: `target: stop #0, N kW`, `start: K km, M min, N kW`, `state WAITING -> REQUESTED`, then, if the BMS wants heat, `car reports conditioning Active` (`REQUESTED -> ACTIVE`, the car/Launchpad indicator on). A `waiting: …` line names the condition that is blocking Start.
3. **Cancel paths.** End the route in Maps: `cancel: route ended`, then `cancel confirmed: car reports Off`. Repeat step 2 and unplug the phone: `cancel: session end`. Repeat and switch off battery conditioning in the car's EV settings: `cancel: setting disabled`. After each one, `/data/data/com.google.android.projection.sink/files/evprecond.owned` must be gone (`nemesis root -c 'ls …/files'`).
4. **BMS timing.** Route to a DC charger 30–60 min away and keep it running. The request stays REQUESTED (maintained every 5 s) until the BMS decides to heat; `car reports conditioning Active after N s, K km / M min to go` shows when. With a warm pack it may never go Active; the request then ends at arrival or route end.
5. **Drive.** A cold morning, real route to a DC charger 20–60 min away. Watch the indicator. On arrival (< 200 m) expect `cancel: arrived`.

If anything goes wrong: switch off battery conditioning in the car's EV settings (takes effect within 5 s), or uninstall the package.

**Result (2026-10-29, build test7, wired AA, steps 2–4):** short charger routes went `start` → `REQUESTED` → `car reports conditioning Active` with the car's indicator on; ending the route gave `cancel: route ended` and the car read Off within ~2.5 s; a 58-min route stayed REQUESTED (no heating yet). Step 5 (a cold-morning drive) is still to do.

## Open questions

- Manually added charger stops (Test 1 step 4) weren't captured; (a) a charger destination and (b) Maps-planned stops both carry `.2`.
- Confirm on a real multi-stop drive that the single 0x8007 entry is the next stop (the drive log records `nav_rem_m` alongside the stops).
- Exact meaning of station flag 2 / type 3, and whether the BMS uses distance, time or both. Tyler Harvey's Ioniq5_CAN `preconditioning_messages` logs might help.
- Does Leafia's NAVDUMP pass payloads of any length? Payloads up to 136 bytes arrived intact (Test 1); multi-step routes with lanes will be longer.
- What "PDK 5.1" corresponds to, and whether 0x8008 is reachable at all.
- Leafia could drop the NAVDUMP log in a future version. Our patch should keep its own copy of that path or gate on the Leafia md5s, as the EV-model package does.

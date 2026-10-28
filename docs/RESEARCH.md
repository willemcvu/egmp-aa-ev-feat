# Android Auto EV features on Gen5W (2022 Ioniq 5): research notes

Date: 2026-10-27. Target: Hyundai Ioniq 5 US (`NEEV.USA.*`, S5W_M, firmware 260507), rooted with NEMESIS.

## 1. What the head unit is

From the HMG-Leafia-Plus repo (its `CLAUDE.md` and `docs/samples/ioniq5.txt` is a real Ioniq 5 US report):

- Telechips TCC803x, ARMv7, **Android 4.4.2 (KitKat, Dalvik)**, `ro.product.device=wp_daudioplus_neev_us`.
- NEMESIS gives root (`nemesis root -c '...'`, `nemesis root 1000 -c` for the system uid), a package format (`NEMESIS.data` + pre/post/preun scripts + `[app.N]` services), and `nemesis remount rw|ro`.
- The stock Android Auto stack:
  - `HKMC_GPM.apk/.odex`: the Java projection manager (`GalConnectionManager`, `StartReceiver`, ...).
  - `/system/lib/libautoreceiver_infobank.so`: the native AA receiver (Google GAL receiver library wrapped by Infobank). It handles the protocol, TLS and protobuf messages. US stock md5 `48ee793af8b86052503b8c952b034731`.
- Stock firmware **already contains wireless AA**. Leafia/NEMESIS "Enable Wireless Features" mostly flips feature flags (`com.hkmc.software.connectivity.androidauto_wireless.xml`, etc.) and enables the hotspot. They don't ship a new AA implementation.
- There's precedent for patching the native AA library. NEMESIS `projection.tbt` (reused by Leafia as `patches/common/libautoreceiver_infobank.tbt.lpd`) moves the program headers to 0x1c4000, adds a 9th PT_LOAD with its own Thumb code, and hooks `sendVersionRequest`+0x12, `RdsType_IsValid` and `NavigationStatusEndpoint::routeMessage`+0xe8. So the library has C++ symbols, and adding a code segment has already been done.
- Vehicle data/commands: `BCMManager` / `HEVManager` -> automotive-service -> `micomd` (`/dev/ttyTCC2`) -> MICOM -> CAN. `HEVManager` is where SOC, range and charging state should come from. The preconditioning app shows that third-party apps can use these services.

## 2. What "EV features" mean on the wire

These are known from aa-proxy-rs (`src/mitm.rs`, `src/ev.rs`, `src/protos/ev.proto`), which makes Maps EV routing work on cars that don't support it:

1. **ServiceDiscoveryResponse (HU -> phone)**, inside the service that has `sensor_source_service`:
   - add `Sensor{ sensor_type = SENSOR_VEHICLE_ENERGY_MODEL_DATA (23) }`
   - `supported_fuel_types = [FUEL_TYPE_ELECTRIC (10)]` (field 3)
   - `supported_ev_connector_types = [...]` (field 4). For a US Ioniq 5: `EV_CONNECTOR_TYPE_COMBO_1 (4)` and `EV_CONNECTOR_TYPE_J1772 (1)`.
2. **SensorRequest (phone -> HU, msg 0x8001) for type 23**: answer `SensorResponse{status=SUCCESS}` (0x8002). The stock HU would probably reject it.
3. **SensorBatch (HU -> phone, msg 0x8003)** with field 23 `EnergyModelControl`. This is a mostly reverse-engineered blob (Ford F-150/Mach-E sample, `ford_ev_model.bin`). Known fields:
   - `u1.u3.u1` = current battery energy (Wh)
   - `u1.u4.u1` = usable capacity (Wh)
   - `u1.u6` = reference air density
   - `u1.u7` = outside temperature (°C)
   - the rest (consumption curves, charging curve, mass/drag...) are opaque. Get them from the "EV Model Generator" (aa-proxy docs) or copy them from the sample.
   Send this periodically while connected.
4. Optional: the phone sends `INSTRUMENT_CLUSTER_VEHICLE_ENERGY_FORECAST` (0x8008, arrival SOC / distance to empty) on the nav-status channel. It could later go to the cluster/HUD.

None of this needs Google's cooperation. Maps turns on EV routing (charger stops, arrival SOC) when the HU advertises it.

## 3. Ways to get it onto the car

### A. On-head-unit TLS MITM proxy (wireless only). Fastest prototype.
In wireless AA the phone opens a TCP connection to the HU (the port goes out over the BT RFCOMM WifiStartRequest; usually 5288, **must confirm** with `netstat` during a session).
- Run a proxy as a NEMESIS service (static armv7 build: port aa-proxy-rs's `mitm` + `ev` code, or a small C/Rust proxy) listening on e.g. :5299.
- `iptables -t nat -A PREROUTING -i wlan0 -p tcp --dport 5288 -j REDIRECT --to-port 5299`. Leafia's kit already dumps `iptables -t nat`, so netfilter nat is present. The proxy connects on to `127.0.0.1:5288`.
- The proxy does TLS to both sides using the community HU/MD certs (aa-proxy-rs README lists where to get them), rewrites the SDR, answers SensorRequest(23), and injects SensorBatch.
- SOC feed: a small NEMESIS Java/app_process service reads `HEVManager` and pushes to the proxy (aa-proxy-rs has a REST endpoint for battery data, or use a local socket).
- Pros: no patching of Hyundai binaries, reuses proven code, and uninstall just removes the iptables rule.
- Cons: wireless only (wired AA uses USB AOA, which the proxy can't see). The HU must accept the MD cert (it works on most HUs). Adds latency/CPU on an old SoC because every video frame is decrypted and re-encrypted.

### B. Patch `libautoreceiver_infobank.so` in-process. Works wired + wireless and is the end state.
Use the same method as projection.tbt (extra PT_LOAD, `b.w` hooks):
- Hook where the SensorSource service descriptor / SDR is built, to add sensor 23 + fuel/connector types (or hook the plaintext buffer just before encryption and re-encode the SDR).
- Hook the sensor-request handler so type 23 returns success.
- Add a send path for a SensorBatch(23) using the lib's own sensor-send function, with data from Java (GPM JNI) or a unix socket fed by an HEVManager reader.
- Must stack on top of Leafia's patched library (hash-gated `.lpd`), or be merged into its recipe.
- Cheaper variant: if the lib uses the system `libssl.so` dynamically, intercept `SSL_write`/`SSL_read` for plaintext rewriting with a shim. Rewriting the SDR is easy that way. Injecting new frames is harder.

### C. Java-only (GPM patch). Only if the stock lib already supports it.
Newer GAL receiver libraries expose sensor registration plus fuel/EV connector setters. If this 2022 build does, a smali patch to `HKMC_GPM` (like Leafia's `.lpd` deltas) could register them. The opaque energy-model blob would still need a raw-bytes send path. Unlikely but cheap to check.

## 4. Findings from the car (2026-10-27, Leafia port kit)

Kit copied to `private/car-neev-260507/` (gitignored). Deodexed GPM is in `private/car-neev-260507/smali/gpm` (`baksmali x -a 18`, car boot classpath).

**Car state**
- `NEEV.USA.0000.V015.012.260507`, NEMESIS ≥ 1.15. Only `usb-handler` is installed: no ADB-TCP, Leafia or wireless package yet.
- Wi-Fi hardware is present (SDIO card, `Firmware up`) but powered off, and the wireless AA permission files are 0.
- The head unit clock reads 2023.
- Stock md5s match Leafia's `neev` set. `libautoreceiver_infobank.so` = `48ee793a…`, `HKMC_GPM.odex` = `6e67470f…`.
- The kit has no AA session: no `netstat` output or sensor traffic in the logs. iptables has a `nat` table.

**`libautoreceiver_infobank.so`** (ARM Thumb-2, stripped but **C++ dynamic symbols are present**)
- OpenSSL is linked in statically (no `libssl.so` NEEDED), so a `SSL_read`/`SSL_write` shim isn't possible.
- The `SensorSourceService` serializer knows fields 3/4 (`kSupportedFuelTypesFieldNumber`, `kSupportedEvConnectorTypesFieldNumber`). `SensorSource::addDiscoveryInfo` (0xbb490) writes them from members at `this+48` (fuel set) and `this+72` (connector vector).
- `FuelType_IsValid` accepts 0..12 (ELECTRIC = 10). `EvConnectorType_IsValid` accepts 0..9 and 101.
- `SensorType_IsValid` (0x144ec0) accepts **1..21 only**. A type-23 `SensorRequest` fails `ParseFromArray` in `SensorSource::routeMessage` (0xbb2d4): it logs and returns −253 and **sends no reply**. A registered type that reaches the `handleSensorRequest` switch outside 1..21 goes to its default branch (0xbb29c), which replies −9.
- `nativeRegisterSensor` (0xae1fa) puts any int into the sensor set with no check, so registering 23 from Java works.
- `SensorBatch` has no field 23, and this protobuf-lite drops unknown fields. **The energy-model batch can't go through the existing `SensorBatch` path.**
- Send path: `MessageRouter::marshallProto(type, msg, IoBuffer*)` (0xb578e) followed by a queue call (unnamed, 0xb1972). Used by `sendSensorBatch` (0xb939c, type 0x8003) and `sendSensorResponse` (0xb932c, 0x8002).

**`HKMC_GPM` (Java)**
- `GalReceiver` calls `SensorSource.init(3, nativePtr, sensors[], DEAD_RECKONED, fuelTypes[], evConnectors[])` with **`fuelTypes = {FUEL_TYPE_UNKNOWN}` and `evConnectors = {EV_CONNECTOR_TYPE_UNKNOWN}` hardcoded**.
- The sensor list comes from `sink/b/h.Y()`, filled by collectors `sink/b/g` (location/GPS) and `sink/b/l` (speed, night mode). No fuel/range, odometer or environment data is sent today, even though `sink/b/f` has `reportFuelData`/`reportOdometerData`/`reportEnvironmentData` wrappers.
- `SensorSource.injectForTest(SensorBatch)` only overrides location/speed/compass. It's not a raw path.
- Vehicle data: `com.infobank.jar` (fuel type, CAN "saveData") and `automotive.jar` (`SystemProtocol$FuelType`, `getFuelType`) are the places to look for SOC/range.

## 5. Revised plan: patch GPM and the library together (works wired and wireless)

With the Java side making these choices, a mix of B and C is the clear path:
1. **GPM smali** (Leafia-style `.lpd`):
   - fuel types → `{ELECTRIC(10)}`
   - connectors → `{COMBO_1(4), J1772(1)}`
   - register sensor 23
   - a small collector that turns HU SOC into a model blob
   
   This must stack on Leafia's turn-by-turn GPM build if Leafia is installed (both patch `HKMC_GPM`).
2. **Library, two small instruction patches**:
   - `SensorType_IsValid` `cmp r0,#20` → `#22`, so type-23 requests parse
   - the default branch at 0xbb29e replies 0 instead of −9 (`mvn.w r7,#8` → `movs r7,#0`)
3. **Library, a small code segment** (the same way as projection.tbt: extra PT_LOAD plus `b.w` hook) that sends a raw `0x8003` frame holding the model blob, fed from Java. A convenient carrier is the existing `nativeReportDiagnosticsData(byte[])` → `SensorSource::reportDiagnosticsData(uchar*, uint)` (0xbb638). Repoint it to marshal the bytes as the whole SensorBatch payload. Nothing registers diagnostics today.
4. Approach A (on-head-unit MITM) stays a fallback for wireless only, since nat is available.

## 5a. Live vehicle data on the head unit (confirmed 2026-10-27)

`service call com.hkmc.micom.hev 3 i32 <flag>` = `IHEVService.getHEVStatus(flag)`. It works as the plain adb shell user, no root needed. The flags are `com.hkmc.hev.HEVManager$HEVFlag`:

| Flag | Name | Value seen | Meaning |
|---|---|---|---|
| 0x19 | `DISPLAY_SOC` | 47 | **SOC in 0.5 % steps** (47 = 23.5 %, cluster showed 23 %) |
| 0x09 | `ELECTRIC_DTE` | 46 | **range in miles** (matches cluster) |
| 0x35 | `BMS_EVENERGYCAPA` | 38700 | constant. Likely nominal capacity in 2 Wh steps (77.4 kWh). Doesn't reflect degradation (car at ~100k mi) |
| 0x07 | `CHARGE_STATE` | 3 | not charging (other values TBD) |
| 0x0d | `CHARGE_STATE_SOC_PC` | −1 | not charging |
| 0x33 / 0x34 | `DATC_BATTERYCOMPPWRCON_W` / `DATC_BATTERYPOWSUM_WH` | 0 / 0 | stayed 0 with climate on. Not usable as live aux power |

**The navigation battery packet.** The car sends a battery packet to the built-in navigation for its own EV routing: `com.hkmc.micom.protocol.Navigation$BATTERY_INFO_RX`.
- `ClusterService$NaviCluster.onHandleBatteryInfoResp` receives it (a request goes out via `requestBatteryInfoReq`), forwards it to `INavigationClusterListener.onHandleBatteryInfoResp(int[])`, and **logs each packet** to logcat (`ClusterService: NaviCluster.onHandleBatteryInfoResp()::value = …`).
- About 800 such lines were in today's captures.
- Fields by name: `XEV_SOCDIS`, `XEV_MIN_BATT_TEMP`, `EV_AVLBL_ENRG`, `VCRM_SW_VER`, `DATC_OUT_TEMP_SNSR`, `VCU_BATT_COND_IND_STA`, `VCU_DTE_BASE`, `VCU_DTE_ADD`.
- The first value matches `DISPLAY_SOC` (0x2f = 47).
- **Byte layout and units not yet confirmed.** Pin them down from a capture where SOC changes (a short drive or charge).

**Capacity without manual configuration.** `EV_AVLBL_ENRG` is the car's own usable-energy figure and should reflect pack degradation. As a cross-check, derive usable capacity from energy change ÷ SOC change during a drive or charge. Goal: no SOH setting; a manual capacity only as an emergency override.

**Outside temperature:** `DatcBypassStatus.getOutTempC()` (climate manager) and `DATC_OUT_TEMP_SNSR` in the nav battery packet.

**HVAC power:** the `HEVFlag` DATC signals stayed 0 with climate on. The aux block (7) uses a static table instead (250 / 500 / 1500 W, see `ENERGY-MODEL.md`). An A/C-on-max re-read is still pending, to rule them out for good.

## 5b. Wired AA baseline (2026-10-27)

Capture: `private/captures/wired-session-live-*.log`.
- Phone protocol 1.7, TLS 1.2. Collectors register location, speed, night mode and driving status. Nav focus/status works.
- No rejected or unparsed phone requests. The library only logs sensor requests when parsing fails, so after advertising sensor 23, a `SensorSource` parse failure is the sign that the phone asked for it.
- With the phone's Bluetooth on, the head unit starts wireless setup (`WirelessExplorer ... WifiVersionRequest`) even with a cable. That moves the single radio to the hotspot and drops home Wi-Fi/ADB. Keep phone BT off during development.
- The logcat buffer holds only about a minute. Stream live (`adb logcat`) before plugging in.

## 5c. Head unit dev setup (2026-10-27)

- Packages installed: NEMESIS `adb.tcp`, Leafia `wireless.carplay.aa` v1.0.5 (full mode, all 5 parts), and our `adb.home` (`packages/adb-home/`).
- **`adb.home`** keeps the stock ADB switch off, so `eth0` stays down and doesn't clash with the hotspot. It runs `adbd` on 5555 only while `wlan0` sees the home router's MAC, and stops it after 3 away checks. It survived a reboot.
- On this unit `adbd` only runs because of the stock switch (`init.daudio.rc`). Leafia turning the switch off stops ADB entirely.
- Traps:
  - NEMESIS root has only CAP_SETUID/SETGID (`CapEff 0xc0`). Use `ndc`, not `ifconfig`.
  - `nemesis install --update` runs the old version's preuninstall first.
  - NEMESIS's usb-handler is recorded as PID 203, which is now `klogd`. USB packages are ignored. **Don't Stop it in Manager.**
- Wired AA plus phone BT off keeps home Wi-Fi and ADB up during sessions.

## 5d. Patch design on top of Leafia v1.0.5 (2026-10-27, not built)

**Base files on the car** (pulled to `private/car-current-leafia105/`, matching Leafia v1.0.5's manifest):

| File | md5 |
|---|---|
| `/system/app/HKMC_GPM.apk` | `0308224b94c7e1b81d372a8ea1535aed` (stock APK + Leafia `classes.dex`; baksmali in `smali-gpm/`) |
| `/system/app/HKMC_GPM.odex` | `7d81a60aabf89efdd4ce12ba9787248e` |
| `/system/lib/libautoreceiver_infobank.so` | `c08410a8d32a615b1aa805cd8a243d52` |

Leafia's library changes, from a byte diff against stock `48ee793a…`:
- ELF header (phoff/phnum)
- its hooks at 0xb1a2e (sendVersionRequest), 0xb6818 (nav routeMessage) and 0x144efa (RdsType_IsValid)
- a 9th PT_LOAD at 0x1c4000 (size 0x16ca, R E), running to the end of the file

**Send path in the library**
- `SensorSource::sendSensorBatch` (0xb939c) calls `MessageRouter::marshallProto(router, 0x8003, msg, IoBuffer*)` (0xb578e). That writes `[type hi][type lo][serialized proto]` into a malloc'd `IoBuffer {data, cap, off, size}`.
- It then calls 0xb1972 = `ProtocolEndpointBase::queueOutgoing(this, data, len)`. If the endpoint's channel is open (`this+4`), that calls `MessageRouter::queueOutgoing(router, channel = this+5, data, len)` (0xb5744, encrypted). Otherwise it returns without sending.
- The IoBuffer is freed right afterwards, so the queue copies the data.

**Three library spots identified** (same bytes in stock and Leafia, untouched by Leafia):

| Site | Current | Purpose of a change |
|---|---|---|
| `SensorType_IsValid` 0x144ec2 | `2814` `cmp r0,#20` (types 1..21 valid) | let type-23 `SensorRequest`s parse instead of being dropped |
| `handleSensorRequest` default branch 0xbb29e | `f06f 0708` `mvn.w r7,#8` (reply −9) | reply success for registered types outside 1..21 (sensor 23) |
| `SensorSource::reportDiagnosticsData(uchar*, uint)` entry 0xbb638 | `e92d 41f0` | candidate raw sender: same `(this, data, len)` registers as the 0xb1972 queue function |

Callers of `reportDiagnosticsData`:
- Native: only the JNI wrapper `Java_…_SensorSource_nativeReportDiagnosticsData` (0xadc88). It does GetByteArrayElements, then the call, then Release.
- Java: `SensorSource.reportDiagnosticsData([B)` and `sink/b/f.reportDiagnosticsData([B)` (private). Nothing in GPM calls them, and no diagnostics sensor is registered.

**GPM (Java) side, for the same goal**
- `GalReceiver` passes `fuelTypes = {FUEL_TYPE_UNKNOWN}` and `evConnectors = {EV_CONNECTOR_TYPE_UNKNOWN}` to `SensorSource.init`. These need to be ELECTRIC (10) and COMBO_1 (4) + J1772 (1).
- Sensor 23 needs registering (`nativeRegisterSensor` accepts any int).
- A periodic sender needs to build `SensorBatch{23: EnergyModel}` with the current energy from `getHEVStatus(DISPLAY_SOC)` (0.5 % steps), or from the nav battery packet's available energy once its layout is confirmed.
- First test model: the Ford sample with battery fields replaced (aa-proxy-rs shows Maps accepts it from non-Ford cars). Then the Ioniq 5 model from `ENERGY-MODEL.md`.

**Packaging and safety requirements (for whoever builds it)**
- NEMESIS package gated on the exact md5s above. Snapshot the live files and restore them byte for byte on uninstall.
- Install GPM the way Leafia does: stock APK + `classes.dex` via `lpatch zipadd`, delete the odex, `dexopt --preopt`, prefill the dalvik cache, `pm disable`/`enable` around the swap. Use `smali a -j 1 --api 18`.
- Verify the patched library only differs at the intended bytes.
- Neither file is boot-critical. The failure mode is AA not starting; uninstall restores Leafia's files.

**Status:** built 2026-10-27 (see HANDOFF.md "Current state"): `tools/build_all.sh` produces the patched library, the GPM dex/APK and the NEMESIS package `dist/aa.ev.model.zip`; `tools/test_package.sh` verifies the install/uninstall logic in a host sandbox. Not yet tested on the car.

## 6. Next steps
Done: ADB, Leafia, `adb.home`, wired AA baseline, live SOC/range reads.

1. **Prototype publishing to Maps with no car risk**: phone ↔ aa-proxy-rs (MITM + EV) ↔ Google's Desktop Head Unit, all on the laptop. Proves the SDR changes, the sensor-23 handshake and our Ioniq 5 model blob against real Maps before any head-unit patch.
2. Model generator (`ENERGY-MODEL.md`), feeding the prototype.
3. Confirm the nav battery packet layout (`EV_AVLBL_ENRG`) from a drive or charge capture. Settle the A/C HVAC signal question.
4. Car: GPM patch (electric + connectors + sensor 23), then the native library patch (accept the type-23 request, send the model), each with a backup and a restoring uninstall. Design and sites are in §5d. Not built.
5. Optional: offer the design to the Leafia maintainers, who already ship patches to this library.

## Sources
- Energy model details, Maps behaviour and rolldown data: `ENERGY-MODEL.md`
- https://github.com/xwtk/hmg_nemesis
- https://github.com/VysseCodes/HMG-Leafia-Plus (CLAUDE.md, patches/README.md, docs/samples/ioniq5.txt)
- https://github.com/paytondevs/gen5w-preconditioning
- https://github.com/aa-proxy/aa-proxy-rs (src/mitm.rs, src/ev.rs, src/protos/ev.proto, src/protos/protos.proto)
- https://aa-proxy.github.io/docs/ev

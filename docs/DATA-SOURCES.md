# Vehicle data sources on the Gen5W head unit (2022 Ioniq 5, NEEV.USA V015.012.260507)

Date: 2026-10-29. This is a read-only survey of the deodexed framework (`private/car-neev-260507/smali/{automotive,automotive-service,com.infobank,com.hkmc.ext.hcan}`), the Leafia GPM (`private/car-current-leafia105/smali-gpm`) and the 2026-10-27 logcat captures (`private/captures/*.log`).

Evidence labels:
- **proven**: read in code, or exactly matched in the captures.
- **strong**: the captured data fits and no other reading does.
- **inferred**: plausible, still needs a probe.

## Summary

| Signal | Best source | Units | Confidence | Notes |
|---|---|---|---|---|
| **Remaining energy** | HEV flag `0x31 ENERGYMAP_1000MS`, which is the **same value** as nav packet `EV_AVLBL_ENRG` | raw uint16; **≈2 Wh/unit** (0xFFFF = invalid) | identity proven; 2 Wh strong | MICOM scales it, Java doesn't. Updates in ~30–37-unit steps |
| Nominal capacity | HEV `0x35 BMS_EVENERGYCAPA` = 38700 | 2 Wh → 77.4 kWh | strong | constant, doesn't track degradation |
| Full usable capacity | derived: ΔENERGYMAP ÷ ΔSOC, or `XEV_BATPWRSUM` ÷ ΔSOC | Wh | inferred method | No direct "usable capacity" signal exists anywhere |
| Trip battery energy | HEV `0x58 XEV_BATPWRSUM_KWH`; hscan `0xd3 getXEV_BatPwrSum_Kwh()` | 0.1 kWh (hscan applies ×0.1) | proven scaling (hscan) | resets per drive cycle (20 → 0 seen at IGN cycle) |
| BMS SOH | hscan `0xd6 getXEV_Bms_GDS_SOH()` | % (1 byte) | proven decode | **health index, not capacity** (owner). Returns 95 |
| Display SOC | HEV `0x19 DISPLAY_SOC`; nav packet byte 0 | 0.5 % | proven | BMS (real) SOC is parsed by hscan but **not exposed** |
| Pack min temp | nav packet byte 1 (`XEV_MIN_BATT_TEMP`) | °C, signed byte | proven | no pack max temp exposed on GEN3 |
| **Outside temp** | nav packet byte 6 (`int[4]` `DATC_OUT_TEMP_SNSR`) | **raw × 0.5 − 40 °C** | proven (matches the dash, 2026-10-29) | `(byte)d[4]` is wrong. Also in `dumpsys` of the DATC service |
| Vehicle speed | hscan `0xb getCarSpeed(byte unit)`; BCM `getSpeed_KMHUnit`; ISystemService `onVehicleSpeedChanged` (GPM) | km/h or mph (hscan/BCM) | proven (hscan) | hscan: display speed ×0.5, then unit conversion |
| Odometer | hscan `0x1 getOdometer(byte)`; BCM `0x27 getOdometer(byte)`; HEV `0x1f ODOMETER` | km/mi float; HEV raw 0.1 | proven (hscan ×0.1) | personal-ish |
| Live climate power | HEV `0x4f DATC_CLMTPWRCNSMPTN_W` (pushed at 10 Hz) | raw 0–56; ~20 W/unit? | inferred units | the older `0x33`/`0x34` stay 0 on this car (CAN-FD variants are `0x4f`/`0x42`) |
| Energy breakdown (trip sums) | HEV `0x42` climate, `0x49` electronics (LDC), `0x44` battery heat, `0x47`/`0x48` motor, `0x59`/`0x5a` regen | Wh (by name) | inferred | the EV app's "energy information" screen inputs |
| Battery V / I | hscan-internal `CR_Bms_BatTotV_V` (×0.1 V), `CR_Bms_BatI_A` (×0.1 A) | — | proven decode | **no binder method exposes them** |
| Drive mode | BCM `getDriveMode()` → `onDriveModeChanged(int)` | 0 default / 1 Normal / 2 Sport / 3 Eco / 4 Snow | proven enum | async callback only |
| Regen / i-Pedal | none found in the framework | — | — | the EV app logs `mRegenLvl*` settings, not live state |
| Charging state | HEV `0x07 CHARGE_STATE`, `0x1d CHG_STS`, `0x28 FAST_CHARGING`; hscan `0x56 getChargingStatus` | enums | inferred values | — |
| Charge power | hscan `0xe0 getOBC_AcPwrDis()` (×0.1), `0xdf getVCMS_EvsePwrDis()`, `0xde getCCM_EvseMainsPwr()` | double, kW? | inferred | all populated on GEN3 |
| Target SOC / time to full | HEV `0x22`/`0x23 TARGET_SOC_*`, `0x15`, `0x0f`–`0x14` remaining-time flags | minutes for times | inferred | — |
| VIN / model | hscan `0x2f getVIN()`, `0x2e getVehicleModelName()` | String | proven method | **personal** |
| Tire pressure | hscan `0xb0`–`0xb3` | status byte only | proven | no pressure values on GEN3 |

## Access summary (who can call what)

| Service (ServiceManager name) | Descriptor | Permission checks |
|---|---|---|
| `com.hkmc.micom.hev` | `com.hkmc.hev.IHEVService` | No checks in getters (`HEVService.smali`). Works from the adb shell (confirmed). `dump()` needs `android.permission.DUMP`, which the shell has |
| `tm_hscan_service` | `com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService` | `enforceCallingPermission()` returns immediately: `CHECKPERMISSION` is a static `false` (`TM_HSCANService.smali:239`), and uid 1000 is exempt anyway. **Any uid can call** |
| `com.hkmc.micom.cluster` (inferred name) | `com.hkmc.cluster.IClusterService` | used via `NavigationCluster` (GPM already does this) |
| `com.hkmc.micom.datc` / `.bcm` / `.system` (inferred: `Service.BASE_NAME` + `.micom` + suffix) | `com.hkmc.datc.IDATCService`, `com.hkmc.bcm.IBCMService`, `com.hkmc.micom.ISystemService` | DATC/BCM `dump()` need DUMP. Confirm names with `service list \| grep hkmc` |

A GPM patch running as system uid can call all of the above. AIDL `byte` arguments go on the wire as `int`, so use `i32` with `service call`.

**Never call (car-side writes)**:
- HEV: `setEVReset` 0x3c, `setTargetSOC*` 0x29/0x2a, `setBatteryConditioning*` 0x3d–0x42, `setV2*` 0x36–0x39, `setOBCCurrent` 0x21, `setMaxPowerModVal` 0x43, `setQckChrgCustom` 0x44, `setChgStopSta` 0x47, `resetFuelEconomyGraph` 0x7, `setDepartTime*`, `reqEVIG3Change` 0x27.
- hscan: `setCanSignalValue` 0x25, `requestDiag` 0x16, `resetDrivingInfo` 0x15, `requestModemReset` 0x27, `request*Charging`/`*AirCon` 0x1b/0x1c/0x20/0x21/0x43–0x46, `reqResetSignals` 0x84, `setValetModeOnOff` 0xb4, `resetLog` 0x2c.

---

## 1. Battery energy, capacity, SOH

### 1.1 `EV_AVLBL_ENRG` ≡ HEV `ENERGYMAP_1000MS` (flag 0x31): proven

The nav packet decode is in `automotive-service/com/hkmc/micom/ClusterService$NaviCluster.smali` (`onHandleBatteryInfoResp([B)V`, line 882). It turns 11 bytes into `int[8]`:

| int[] idx (`BATTERY_INFO_RX`) | bytes | decode |
|---|---|---|
| 0 `XEV_SOCDIS` | b0 | unsigned, 0.5 % |
| 1 `XEV_MIN_BATT_TEMP` | b1 | `getSignedByteToInt` → °C |
| 2 `EV_AVLBL_ENRG` | b2<<8 \| b3 | uint16 |
| 3 `VCRM_SW_VER` | b4<<8 \| b5 | uint16 (0 seen) |
| 4 `DATC_OUT_TEMP_SNSR` | **b6** | unsigned byte |
| 5 `VCU_BATT_COND_IND_STA` | b7 | 0 seen |
| 6 `VCU_DTE_BASE` | b8<<8 \| b9 | km (78 when cluster showed 48 mi) |
| 7 `VCU_DTE_ADD` | b10 | km (5–7; probably extra range with climate off) |

Note: `RESEARCH.md`/`ENERGY-MODEL.md` read **byte** 6 as DTE. Byte 6 is the outside temperature (int index 4). DTE is in bytes 8–9.

HEVService passes MICOM payload bytes through unscaled. `ENERGYMAP_1000MS` (MICOM signal ID 0x30 → flag 0x31) is `bytesToInt(p4, 1, 2)` (`HEVService.smali` ~13661). In the 2026-10-27 capture, every `EV_AVLBL_ENRG` change lands in the same 10 ms as an identical `ENERGYMAP_1000MS` value (7170, 7140, 7067, 7031, 6994, 6981). Both drop to 65535 at the 22:04:41 IGN transition. **They are one signal** (CAN `CR_Bms_energymap_1000ms`, named in hscan's VCRM loggers). So:
- `service call com.hkmc.micom.hev 3 i32 49` reads it without the cluster listener.
- 0xFFFF must be treated as invalid. It also appeared during a wake-up, when DTE briefly went 0.

**Scaling.** No Java code scales it: the stock consumers are the native nav app `com.mnsoft.navi` (pid 1318) and the EV app (`TAppEV3`), and neither is in the repo.

| Evidence | Implies |
|---|---|
| `BMS_EVENERGYCAPA` (same MICOM 2-byte path) = 38700 vs the 77.4 kWh rating | **2 Wh/unit** for the BMS energy family |
| Parked, climate on, 21:58→22:24 (26 min): 7202 → 6981 = −221 units. The EV app logged climate ≈ 0.5–0.74 kW and "other" ≈ 0.31 kW, about 0.9–1.0 kW total | 221 × 2 Wh = 0.44 kWh ≈ 1.0 kW avg ✓. (1 Wh/unit would mean 0.5 kW, and 10 Wh/unit 5 kW) |
| Display SOC flips 24.0→23.5 at E≈7186 and 23.5→23.0 at E≈7012 | ~348 units/% locally, but quantised and noisy |
| E=7186 at 24 %, E=14462 at 46 % (owner, tonight) | **~327 units/% average → ~65 kWh per 100 % at 2 Wh**. Extrapolates to E=0 at about display 2 % |

Interpretation (inferred):
- ENERGYMAP is the BMS's own **remaining usable energy, already degraded**. Linear extrapolation gives ~64–65 kWh at display 100 %, versus 77.4 nominal (~83–84 %).
- Wh per % rises with pack voltage, so a 24→46 % slope underestimates a full-window capacity by a few percent. Expect ~66–68 kWh. That is still well under 77.4.
- The 2 Wh unit isn't proven. The probe plan pins it with a charge, where ΔENERGYMAP vs charger kWh × η ≈ 0.95 confirms 2 Wh.

**What this changes** *(implemented in v0.2.0: `EvModelSender` sends ENERGYMAP × 2 as `1.3` and learns `1.4`; ~62.9 kWh on the owner's car)*:
- Model field `1.3` (current Wh) should come straight from `ENERGYMAP × 2`, not `DISPLAY_SOC × 77400`. Tonight that's 28.9 kWh vs the 35.6 kWh sent.
- Capacity `1.4` should come from a learned `ENERGYMAP×2 / (SOC/100)` at high SOC (or a fit across a charge), with outliers rejected.
- Keep `1.4 ≥ 1.3`, and keep the battery % Maps shows consistent with the cluster. If `1.4` = learned capacity and `1.3` = ENERGYMAP×2, Maps' % ≈ display SOC.

### 1.2 Other energy counters

| Source | Decode | Status |
|---|---|---|
| HEV `0x35 BMS_EVENERGYCAPA` | 2 bytes raw, 38700 | constant nominal (2 Wh) |
| HEV `0x58 XEV_BATPWRSUM_KWH` / hscan `0xd3 getXEV_BatPwrSum_Kwh()F` | hscan `CanGEN3CsmEv.parseBMS_04_100ms_4`: `(f[8]<<8 \| f[7]) × 0.1` → kWh. HEV gives the raw value (×0.1 kWh) | proven scale. Seen 20 (2.0 kWh), reset to 0 at an IGN cycle. **Battery energy used this drive**. Divide by ΔSOC over a long drive for capacity. Whether it counts net of regen, or during charging, is unknown |
| HEV `0x47`/`0x48 MCU_MG1/MG2MOTPOWER_WH`, `0x59`/`0x5a MCU_MG1/MG2GENPOWER_WH` | raw 2 bytes | inferred: front/rear motor drive and regen energy sums, Wh |
| HEV `0x42 DATC_CLMTPWRSUM_W` | raw. MCanAPI logged `mFdClmtPwrSumWh: 1358` | climate energy this drive, Wh (inferred) |
| HEV `0x49 LDC_LOADPWRSUMVAL` | raw. MCanAPI: 60 | 12 V/electronics energy sum (inferred Wh or 10 Wh) |
| HEV `0x44 XEV_BATHEATPWRSUM_WH`, `0x43 XEV_BATHEATPWRMONCON_W` | raw | battery heater energy sum / live W |
| HEV `getDrivingHistory()` txn 0x2c → `int[]` | `HEVManager$DrivingHistoryIndex`: [1] GEN_POWER, [2] CLIMATE_POW_SUM, [3] LDC_LOAD_PWR_SUM | per-trip breakdown, the cached `mDrivingHistory` |
| HEV `getCO2Rdc()` 0x2f → `int[]` | `CO2RdcIndex`: [1] CO2_RDC, [2] BMS_HEATPWRMONCON_W, [3] BMS_HEATPWRSUM_WH | battery-care energy |
| HEV `getPwrConsumption()` 0x2e → int | not traced | probe it |
| Charged-energy (kWh in) counter | **none found** in HEV flags, hscan or cluster | integrate charge power instead, or use ΔENERGYMAP |

### 1.3 SOH

hscan `getXEV_Bms_GDS_SOH()` (txn 0xd6) → `CanGEN3CsmEv.parseBMS_26_500ms_2`: `xEV_Bms_GDS_SOH = f[8]`, a **single raw byte, no scale**. Only the GEN3 CSM fills it (all other generations return 0), so this car is GEN3.
- With IGN off and IGN3 off, it returns the value saved in the drive-segment file.
- It reads 95. Per the owner that's a health index, not capacity. Don't use it for capacity.
- `EvModelSender`'s "0.1 % units in [500, 1000]" gate can never fire (1 byte, max 255). That's harmless, since the gate is off by default, but it's dead code. *(Removed: the SOH capacity path and its setting are gone; SOH is only logged.)*

### 1.4 Display SOC vs BMS SOC

- `DISPLAY_SOC` (HEV 0x19, nav b0) = cluster SOC in 0.5 % steps.
- The BMS SOC `CR_Bms_Soc_Pc` (`parseBMS_02_100ms_2`, f[8] × 0.5 %) is decoded inside the hscan process but **no AIDL method returns it**. It's only used for VCRM statistics (`mSocPcUnder10Count`).
- hscan `remainBatteryPercentage` (0x4a) reads `BMS8_CR_Bms_DisplaySoc`, which the GEN3 CSM never fills. Expect 0 or a stale segment value (probe it).
- So the display→BMS mapping can't be read directly. The ENERGYMAP-vs-SOC fit (zero energy at ≈ display 2 %) is the only handle.

### 1.5 Battery voltage / current / cell data

`CanGEN3CsmEv` decodes:
- `CR_Bms_BatTotV_V` (BMS_01, (f7<<8\|f6) × 0.1 V)
- `CR_Bms_BatI_A` (BMS_02, signed (f2<<8\|f1) × 0.1 A)

Neither has a getter on `ITM_HSCANService`, and hscan has no `dump()`. Pack max temp and cell min/max V aren't populated on GEN3. **There's no live V/I/power source for a charge integral.** The alternatives for charging are hscan `getOBC_AcPwrDis()` (AC), `getVCMS_EvsePwrDis()` / `getCCM_EvseMainsPwr()` (EVSE power, units unproven), or ΔENERGYMAP over time.

## 2. Outside temperature

- **Nav packet** `int[4]` = byte 6, unsigned. Hyundai's own CAN decode for the same DATC signal: `Can2ACCANCsmEV` (`CONVERT_CR_Datc_OutTemp_C`) and `Can2ACsm` (`CONVERT_CR_Fatc_OutTemp`) both compute `raw × 0.5 − 40.0`:
  ```
  const-wide/high16 v3, 0x3fe0...  # 0.5   mul-double
  const-wide/high16 v3, 0x4044...  # 40.0  sub-double
  ```
  Capture 2026-10-27 22:00 (late-October night): raw alternates 0x4c/0x4d (76/77) → **−2.0 / −1.5 °C**. That's plausible, and the ±1 LSB jitter fits a 0.5 °C sensor. Tonight's value that failed the `(byte)` −45..55 check is consistent with raw > 55 (> −12.5 °C). **Fix: `outC = (d[4] & 0xff) * 0.5 − 40`, invalid if raw ≥ 0xFE** (strong). Confirm against the dash (probe P3). *(Confirmed 2026-10-29: the logged value matched the dash, 48 °F.)*
- **DATC service**: `DatcBypassStatus.getOutTempC()/getOutTempF()` are filled from the raw MICOM bytes (`UNIDatcProtocol.setAllDatcResp`: b0 = C, b1 = F). The object isn't exposed over IDATCService, only printed by `dumpsys` (`DATCService.dumpDatcSignal`). `dumpsys <datc service>` printing both OutTempC and OutTempF raws cross-checks the scaling.
- HEV/hscan: on GEN3, hscan never fills `CR_Datc_OutTemp_C`. There's no HEV flag for it.

## 3. Speed, odometer, wheel speed

| Source | Call | Decode |
|---|---|---|
| GPM (what AA gets today) | `com.hkmc.micom.VehicleInfoManager` (ISystemService) → `VehicleInfoListener.onVehicleSpeedChanged(int)` → `sink/b/i` → `sink/b/l.nY` → `f.reportSpeedData(int, bool)` | int passed through unchanged. AA expects speed in m/s × 1000, so the MICOM value is presumably already mm/s (inferred). Poll: ISystemService `getVehicleSpeed` txn 0x21 |
| hscan | `getCarSpeed(byte unit)` 0xb; unit 0 = km/h, 1 = mph | `CONVERT_CF_Clu_DisplaySpeed` = raw × 0.5 in the cluster's unit (`CanGEN3Csm` ~795); ×1.609 or ×0.6214 to match the requested unit. **Display speed** (cluster-biased) |
| BCM | `getSpeed_KMHUnit()F` 0x2f / `getSpeed_MPHUnit()F` 0x30 | float |
| Odometer | hscan `getOdometer(byte)` 0x1 (`CONVERT_Odometer` = raw × 0.1); BCM `getOdometer(byte)` 0x27; HEV flag 0x1f (raw 3 bytes. HEVService itself uses raw/10 as the odometer, so ~0.1 km) | proven decodes |
| Trip | hscan `getSpeedDistKM()` 0x10 `[I`, `getAccelDistKM()` 0xe, `getTotalMilege()` 0xd4, `getEvMilege()` 0xd5; HEV `ADDED_DIST_DATC_OFF` 0x26 | not traced |
| Wheel speed | hscan internal `WHL_PUL_FL/FR/RL/RR` (pulse counts) | **not exposed** |

## 4. Power, drive mode, regen

- **Motor power available**: HEV `0x45`/`0x46 MCU_MG1/MG2PWRAVNKWVAL` and `0x25 MOTER_PWR_AVN`. These are "available" (limit) power, not actual output (inferred from the name). hscan `driveLineEleConsum` (0x4f) reads `CR_Mcu_MotPwrAvn_Kw`, which GEN3 doesn't fill, so it returns 0.
- **Actual battery power**: not exposed (see §1.5).
- **hscan climate/heater/electronics power getters** (`airConEleConsum` 0x50, `heaterEleConsum` 0x5c, `electricConsum` 0x51): their fields are only filled by the Can1/Can2 CSMs, so they're **0 on GEN3**.
- **Drive mode**: `IBCMService.getDriveMode()` (txn 0xc, void) triggers `IBCMListener.onDriveModeChanged(int)` with `BCMConstants$DriveMode`: 0 DEFAULT, 1 NORMAL, 2 SPORT, 3 ECO, 4 SNOW, −1 UNKNOWN. HEV `getLastDriveModeInfo()` 0xd `[I` and `IHEVListener.onEVDriveModeInfo([I)` hold the EV drive-mode settings. The EV app logs `mRegenLvlEco/Normal/Sport`, `mCliLimit*` and `mMaxSpd` from these, but they're settings, not live state.
- **i-Pedal / live regen level**: nothing found.
- HEV `0x08 ECO_LEVEL` and hscan `getEcoLevel` 0x52 (GEN3 doesn't fill `VCUCR_Vcu_EcoSco`) are driving-score style values.

## 5. HVAC / climate energy

- **Live**: HEV `0x4f DATC_CLMTPWRCNSMPTN_W`, pushed every ~100 ms (12k samples in 30 min, raw 0–56).
  - The EV app's `PwrConCli` (kW) read 0.48–0.74 while raw averaged ~26, so ~20 W/unit (inferred; the app may smooth or integrate).
  - Also `0x41 DATC_PTCPWRCNSMPTNVAL` (PTC heater), `0x2a PWR_E_COMPRESSOR`, `0x43` battery heater W.
  - The older `0x33`/`0x34` stay 0 on this CAN-FD car. That's why the earlier test saw zeros.
- **Per-trip sums**: `0x42`, `0x49`, `0x44` and `getDrivingHistory()` (§1.2). These feed the EV app's driving / climate / electronics / battery-care screen.
- **DATC state**: `IDATCService.getClimateInfo(int)` 0x26 (flag ints in `DATCFlag`), `IDATCListener.onDatcStatus(DatcStatus)` (push), and `onTemperatureInformation(FIFIFIFIFIFIFIFI)`. Cabin (in-car sensor) temp isn't decoded on GEN3. The set temp is in DatcStatus (driver set temp: hscan `CONVERT_CR_Datc_DrTempDispC` = raw × 0.5 + 14 °C on GEN3, internal only). hscan `getDATCStatus` 0x86 → `CF_Datc_OpSts`. A/C on/off: hscan `getAirConOnOffStatus` 0x1f.

## 6. Charging

- HEV flags:
  - `0x07 CHARGE_STATE` (3 = not charging; 0/1 seen during wake-up)
  - `0x1d CHG_STS`, `0x28 FAST_CHARGING`, `0x0d CHARGE_STATE_SOC_PC`
  - `0x22`/`0x23 TARGET_SOC_SLOW/QUICK`
  - remaining-time flags in minutes (capture: `0x0f` QUICK = 58, `0x10` ICCB US = 3120, `0x13` EVSE = 340, `0x15` integrated)
  - `0x52 UNIFICATION_CHARGING_CURRENT`, `0x53 OBC_ACPWRDIS`, `0x4e VCMS_CHRGINLETTYP`, `0x6c VCMS_PWRDIS`, `0x6e VCMS_CHGSTOP_STA`
  - `getChargingInfoGen4()` 0x1c
- hscan:
  - `getChargingStatus` 0x56; `getChargingFinish` 0x53
  - `getOBC_AcPwrDis()D` 0xe0 (×0.1, likely kW), `getVCMS_EvsePwrDis()D` 0xdf, `getCCM_EvseMainsPwr()D` 0xde
  - `getRemainChargingTime` 0x96, `quickRemainChargeTime` 0x4c
  - `addChargeStatusListener` 0x7f (push)
- GEN3 decodes `CF_OBC_DCChargingStat`/`ACChargingStat` (2-bit) internally.

## 7. Identity and versions

| Item | Source | Personal? |
|---|---|---|
| VIN | hscan `getVIN()` 0x2f (String); `requestVin(IVinListener)` 0x5e | **yes** |
| Model name | hscan `getVehicleModelName()` 0x2e; ISystemService `getVehicleTrimInfo` 0x5c | no |
| CAN generation | HEV `getCanDBGenerationInfo` 0x1b, ISystemService `getCANDBGeneration` 0x22 (nav logs "= 2", "3rd CAN") | no |
| Plug&Charge cert IDs | HEV `getCCM_PCID1..3` 0x4–0x6, flags 0x5f–0x61 | **yes** (seen −1 / 0) |
| Versions | `getprop ro.build.*`; nav packet `VCRM_SW_VER` (0); nav app logs `NaviApp[STDNW.NAM.HMC…]` | no |
| GPS lat/lon | hscan `getGpsInfo()` 0x62 `[D` (lat, lon only); altitude via Android `LocationManager` (GPM's `sink/b/g`) | **yes**. Don't log raw positions. The captures already contain a street name from nav logs |

## 8. Other

- Tire pressures: hscan `get*TirePressureStatus` 0xb0–0xb3 return warning status bytes. GEN3 only decodes TPMS warn lamps. No kPa.
- Pack max temp, cell voltages, inlet temp: decoded only by the older CSMs. Nothing on GEN3.
- Nav app (`com.mnsoft.navi`) EV inputs from the logs:
  - `getEcoEvCarInfo value[0..3]` = [display SOC raw, ~DTE km, 85/107–109 (unknown), 0]
  - `getEcoEVDistEmptyAvnKm` = DTE km
  - Pulling `/system/app/*Navi*` and decompiling `NaviFuelManager` would show how it uses `EV_AVLBL_ENRG` (the remaining unread consumer).

---

## Probe plan (read-only; run with the car on and parked unless noted)

First: `adb shell service list | grep -iE 'hkmc|hscan'` to confirm the service names. Each `service call` below is a getter. Floats/doubles come back as raw bits (decode the hex as IEEE-754).

| # | Command | Confirms if |
|---|---|---|
| P1 | `service call com.hkmc.micom.hev 3 i32 49` and log the nav packet at the same moment | ENERGYMAP equals nav `EV_AVLBL_ENRG` (expect 14462-ish tonight) |
| P2 | Charge session (L2 or DC): every 30 s log HEV 49, 25 (0x19), 88 (0x58), and hscan `service call tm_hscan_service 224` (OBC AC power) / 223 / 222, plus the charger's kWh | ΔENERGYMAP × 2 Wh ≈ charger kWh × ~0.95 → 2 Wh proven. ENERGYMAP×2/SOC at 80–100 % gives usable capacity. Tells whether 0x58 counts during charging |
| P3 | `dumpsys com.hkmc.micom.datc \| grep -i OutTemp` and the nav packet byte 6, vs the dash outside temp | `raw×0.5−40` matches the dash; the C/F pair agrees |
| P4 | `dumpsys com.hkmc.micom.hev` (whole HEVStatus, with DUMP), before and after a 10 km drive | trip sums 0x42/0x47/0x48/0x49/0x58/0x59/0x5a move consistently: Σ(motor) − Σ(regen) + climate + LDC ≈ 0x58 × 100 Wh; ENERGYMAP drop ≈ 0x58 change |
| P5 | `service call com.hkmc.micom.hev 44` (getDrivingHistory), `43` (getMyDrivingMode), `47` (getCO2Rdc), `46` (getPwrConsumption) | array contents match the EV app energy screen |
| P6 | `service call tm_hscan_service 214` (0xd6 SOH), `211` (0xd3 BatPwrSum float), `74` (0x4a remainBatteryPercentage), `137` (0x89 getBatSoc = 12 V SOC) | SOH 95; BatPwrSum = HEV 0x58 × 0.1; 0x4a likely 0 on GEN3 |
| P7 | `service call tm_hscan_service 11 i32 0` (km/h) while driving; `service call tm_hscan_service 1 i32 0` (odometer km) | speed = dash; odometer = dash ×1.609 if the dash is in miles |
| P8 | `service call com.hkmc.micom.hev 3 i32 79` (0x4f) with A/C off, fan only, and heat max | raw scales with load; ×20 W gives sane kW (compare the EV app's climate kW) |
| P9 | `service call com.hkmc.micom.hev 3 i32 34` / `35` (target SOC AC/DC) vs the EV settings screen | encoding (%, or 10 % steps) |
| P10 | `service call tm_hscan_service 47` (VIN). **Personal: don't commit the output** | VIN source works |

## Recommended logging changes (for `EvModelSender`)

*(All implemented in v0.2.0; see `EvModelSender` and [LOGGING.md](LOGGING.md). The trip sums are logged every 5 s row, not once a minute, and capacity uses a smoothed estimate preferring ≥ 60 % samples rather than a median.)*

1. Log `ENERGYMAP_1000MS` (HEV 0x31) every period. Treat 0xFFFF as invalid. Use `×2 Wh` for model `1.3`.
2. Fix the outside temp: `(d[4] & 0xff) * 0.5 − 40`.
3. Log HEV `0x58` / `0x42` / `0x49` / `0x44` / `0x47` / `0x48` / `0x59` / `0x5a` / `0x4f` once a minute (cheap ints), to build capacity and aux-power calibration data.
4. Learn the capacity as `median(ENERGYMAP×2 / (DISPLAY_SOC/200))` over samples with SOC ≥ 60 %, refreshed after charges. Keep 77.4 kWh only as the fallback. Drop the SOH-based capacity path.

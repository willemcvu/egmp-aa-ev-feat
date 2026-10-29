# v0.2.0

Real capacity, realistic charge times, automatic battery preconditioning and drive logs, on top of v0.1.1's EV routing. For the 2022 Ioniq 5 (US) with NEMESIS + HMG Leafia Plus v1.0.5.

## Highlights since v0.1.1

- **Real usable capacity.** Learned on the car from the BMS's remaining energy (HEV 0x31 × 2 Wh) ÷ the displayed SOC, and kept across sessions; the current energy sent to Maps comes straight from the BMS. On the owner's ~105k mi pack that's ~62.9 kWh instead of the nominal 77.4. No settings. BMS SOH is only logged (it's a health index, not a capacity).
- **Cold-weather model.** Outside temperature is decoded correctly (raw × 0.5 − 40 °C) and drives a heater power table plus a higher road load in the cold.
- **Realistic charge times.** The charge-time tables now use the format Google's server expects (energy still needed to full, seconds to full); before, Maps read our fast low-SOC bulk as the last stretch to 100 %. The top end matches the car's own estimate (80→90 % ≈ 8 min, 90→100 % ≈ 18 min on 350 kW), with no thermal pause, and the curve and tables are scaled to the learned capacity at runtime. Tesla Superchargers (the 250 kW row) charge at a flat 105 kW to 90 %, as this 800 V car does there.
- **Automatic battery preconditioning.** When the next stop on the Maps route is a charging stop above 50 kW and at most 60 minutes away, the head unit sends the car the same request its own navigation sends; the battery management decides when to heat. Cancelled on arrival, route end, Android Auto disconnect, READY lost, setting off, the car ending it, or car status unreadable for 5 minutes. Always on; the car's own **battery conditioning** EV setting is the off control. Commands are only sent on the exact car framework they were verified on (SHA-256 check).
- **Drive logs.** One CSV per Android Auto session (format v1: battery, temperatures, model values; no location, no full VIN, no times of day), capped at 32 MB on the head unit. Make an `egmp-aa-ev-logs` folder on a USB drive and plug it in: logs are copied and a pop-up says how many. Check, clean and submit them on the [drive log checker](https://willemcvu.github.io/egmp-aa-ev-feat/).
- Every logging and preconditioning call is wrapped so an error there can't stop Android Auto or navigation.

## Verified on the car

2022 Ioniq 5 (US), `NEEV.USA.0000.V015.012.260507`, Leafia v1.0.5, wired Android Auto, 2026-10-29:

- Installs and upgrades in place; reboots by itself. (Uninstall restoring Leafia byte for byte is covered by the package sandbox test.)
- Capacity learned from the BMS: ~62.9 kWh.
- Outside temperature matches the dash (48 °F).
- Maps' charge times look right to the owner.
- Preconditioning: REQUESTED → ACTIVE with the car's indicator on (short routes to a fast charger); ending the route cancels it and the car reads Off within ~2.5 s; on a 58-min route the request stays pending while the BMS waits.
- Drive logs copied to a USB drive (6 logs) with the pop-up.

## Known limitations and open questions

- **Capacity: 62.9 or ~71 kWh?** The BMS says ~62.9 kWh, but recent DC fast-charging sessions suggest ~70–71 kWh. A logged DC charge will settle it. If the real figure is higher, Maps is a little pessimistic on range and charge times.
- **Wireless Android Auto** uses the same code path but hasn't been verified with these features yet.
- **One car only:** Ioniq 5 (US) software 260507 with Leafia v1.0.5. The installer refuses anything else; preconditioning additionally checks the car framework.
- On this car the phone and the USB drive share the one data port, so logs are copied when the drive is plugged in after a session.
- Not yet tested: a cold-morning drive where the battery heats on the way, a real multi-stop route, Supercharger times.

## Upgrade

Install over v0.1.x as usual; it upgrades in place and keeps the original Leafia backup.

1. Copy the **contents** of `egmp-aa-ev-feat-v0.2.0-usb.zip` to the root of an empty FAT32/exFAT USB drive.
2. Car on, plug the drive into a USB data port, tap **Yes** when NEMESIS asks. The head unit restarts by itself; remove the drive while it restarts.
3. The first Android Auto start afterwards can take an extra minute while the head unit re-optimizes the app.

Over ADB: `adb push aa.ev.model.zip /data/local/tmp/` then `nemesis install --update /data/local/tmp/aa.ev.model.zip`.

The only runtime switch is `persist.gpm.ev.off=1` (stock behaviour without uninstalling). To uninstall: NEMESIS Manager (press NAV three times) → `aa.ev.model` → uninstall, then reboot. Uninstall this before updating or removing Leafia.

EV routing for Android Auto - aa.ev.model @VERSION@
https://github.com/willemcvu/egmp-aa-ev-feat   (built from commit @COMMIT@)

Makes Google Maps on Android Auto treat the car as an EV:
- battery % on arrival, charging stops, charge times and range, from the
  battery's live energy and its real (learned) usable capacity, with a
  cold-weather model driven by the live outside temperature;
- battery preconditioning: when the next stop on the Maps route is a fast
  charger (over 50 kW) at most 60 minutes away, the car is asked to warm
  the battery, the same way its own navigation does. Always on. To turn it
  off, switch off "battery conditioning" in the car's EV settings;
- drive logs: one small file per Android Auto session (battery, temperatures,
  model values; no location, no full VIN, no times of day), copied to a USB
  drive that has a folder named egmp-aa-ev-logs. Check them and share them
  at https://willemcvu.github.io/egmp-aa-ev-feat/

WARNING: this modifies the infotainment system's files. It checks every file
before changing anything and keeps a backup for uninstall, but you use it at
your own risk.

REQUIREMENTS (the installer refuses anything else and changes nothing)
- Hyundai Ioniq 5 (US), Gen5W head unit, software NEEV.USA.0000.V015.012.260507
- NEMESIS installed (https://github.com/xwtk/hmg_nemesis)
- HMG Leafia Plus v1.0.5 installed (https://github.com/VysseCodes/HMG-Leafia-Plus)

INSTALL (also upgrades an earlier version in place)
1. Copy the CONTENTS of this zip to the root of an empty FAT32/exFAT USB
   drive. Don't unzip packages/aa.ev.model.zip. No other NEMESIS.data may be
   on the drive.
       NEMESIS.data
       packages/aa.ev.model.zip
       README.txt
2. Car on, head unit fully started, plug the drive into a USB data port.
3. NEMESIS asks whether to run the item on the drive: tap Yes.
4. Wait: after a few seconds it says it's installed and the head unit
   restarts by itself. Remove the drive while it restarts. (It renames
   NEMESIS.data on the drive to NEMESIS.data.installed so a drive left in
   doesn't install again; rename it back to reinstall.) If it refuses, a
   message says so, nothing is changed and it doesn't restart.
5. Plug in Android Auto (the first time after installing it can take an
   extra minute). In Google Maps, battery % shows on routes and long trips
   add charging stops.

DRIVE LOGS
Make a folder named egmp-aa-ev-logs on a USB drive and plug it in after
driving (the phone and the drive may share the one USB data port). A
pop-up says how many logs were copied; then it's safe to remove.

UNINSTALL
NEMESIS Manager (press NAV three times) -> aa.ev.model -> uninstall, then
reboot. Leafia's original files are restored byte for byte. Uninstall this
BEFORE updating or uninstalling Leafia.

OFF SWITCH (keeps it installed, needs ADB)
  setprop persist.gpm.ev.off 1    then replug Android Auto (0 turns it back on)
Preconditioning has no switch here: use the car's battery conditioning setting.

TROUBLESHOOTING
- Log: /data/nemesis/aa.ev.model.log (also shown in NEMESIS Manager's
  aa.ev.model entry). Log copying: /data/nemesis/aa.ev.model.offload.log.
- "not installed" message: your head unit software or Leafia version isn't
  the one this build is for. Nothing was changed.
- Android Auto won't start after install: wait a minute after the first
  boot; if it still won't, uninstall as above.
- Wi-Fi shows an error after a restart: switch the car fully off and on.

WHAT'S IN THE PACKAGE
No Hyundai or Google files. packages/aa.ev.model.zip contains install
scripts, the log copy service, two small patch files (deltas), and lpatch
from HMG Leafia Plus (GPL-3.0, source in the repo under third_party/lpatch).
On the head unit, lpatch rebuilds the modified GPM app and Android Auto
library from the unit's own Leafia files, and only if those are
byte-for-byte the expected ones.

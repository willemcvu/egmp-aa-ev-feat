EV routing for Android Auto - aa.ev.model @VERSION@
https://github.com/willemcvu/egmp-aa-ev-feat   (built from commit @COMMIT@)

Makes Google Maps on Android Auto treat the car as an EV: battery % on
arrival, charging stops and range, from the car's live state of charge.

WARNING: this modifies the infotainment system's files. It checks every file
before changing anything and keeps a backup for uninstall, but you use it at
your own risk.

REQUIREMENTS (the installer refuses anything else and changes nothing)
- Hyundai Ioniq 5 (US), Gen5W head unit, software NEEV.USA.0000.V015.012.260507
- NEMESIS installed (https://github.com/xwtk/hmg_nemesis)
- HMG Leafia Plus v1.0.5 installed (https://github.com/VysseCodes/HMG-Leafia-Plus)

INSTALL
1. Copy the CONTENTS of this zip to the root of an empty FAT32/exFAT USB
   drive. Don't unzip packages/aa.ev.model.zip. No other NEMESIS.data may be
   on the drive.
       NEMESIS.data
       packages/aa.ev.model.zip
       README.txt
2. Car on, head unit fully started, plug the drive into a USB data port.
3. NEMESIS asks whether to run the item on the drive: tap Yes.
4. Wait for the result, then reboot the head unit (pinhole button) and
   remove the drive.
5. Plug in Android Auto. In Google Maps, battery % shows on routes and long
   trips add charging stops.

Running it again over an installed copy (or an earlier version) is fine.

UNINSTALL
NEMESIS Manager (press NAV three times) -> aa.ev.model -> uninstall, then
reboot. Leafia's original files are restored byte for byte. Uninstall this
BEFORE updating or uninstalling Leafia.

OFF SWITCH (keeps it installed, needs ADB)
  setprop persist.gpm.ev.off 1    then replug Android Auto (0 turns it back on)

TROUBLESHOOTING
- Log: /data/nemesis/aa.ev.model.log (also shown in NEMESIS Manager's
  aa.ev.model entry).
- "not installed" message: your head unit software or Leafia version isn't
  the one this build is for. Nothing was changed.
- Android Auto won't start after install: uninstall as above.

WHAT'S IN THE PACKAGE
No Hyundai or Google files. packages/aa.ev.model.zip contains install
scripts, two small patch files (deltas), and lpatch from HMG Leafia Plus
(GPL-3.0, source in the repo under third_party/lpatch). On the head unit,
lpatch rebuilds the modified GPM app and Android Auto library from the unit's
own Leafia files, and only if those are byte-for-byte the expected ones.

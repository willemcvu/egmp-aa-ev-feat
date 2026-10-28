#!/system/bin/sh
. /data/nemesis/apps/adb.home/scripts/common.sh

log "preuninstall adb.home"
_saved=""
[ -f "$STATE" ] && _saved=$(cat "$STATE")
if [ "$_saved" = 1 ]; then
    # Back to the stock switch: init restarts adbd and gives eth0 its address again.
    setprop persist.service.adb.tcp.enable 1
    log "restored persist.service.adb.tcp.enable=1"
fi
rm -f "$STATE"
log "preuninstall ok"
exit 0

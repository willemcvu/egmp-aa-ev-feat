#!/system/bin/sh
. /data/nemesis/apps/adb.home/scripts/common.sh

log "postinstall adb.home"
# Remember the stock switch so uninstall can put it back.
if [ ! -f "$STATE" ]; then
    echo "$(getprop persist.service.adb.tcp.enable)" > "$STATE"
    log "saved persist.service.adb.tcp.enable=$(cat "$STATE")"
fi
# Turning the switch off does not stop the running adbd (init only reacts to =1).
setprop persist.adb.tcp.port 5555
setprop persist.service.adb.tcp.enable 0
eth0_tidy
log "postinstall ok"
exit 0

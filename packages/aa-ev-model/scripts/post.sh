#!/system/bin/sh
. /data/nemesis/apps/aa.ev.model/scripts/common.sh

log "postinstall aa.ev.model"
trap '[ $? = 0 ] || { sys_ro; apps_release; }' EXIT

apps_hold
snapshot_all || exit 1
if ! sys_rw; then
    log "FAIL: remount /system rw"
    exit 1
fi

if ! put_lib "$FILES/HKMC_GPM.apk" "$GPM_APK"; then
    log "FAIL: install $GPM_APK"
    exit 1
fi
log "OK: $GPM_APK replaced"

rm -f "$GPM_ODEX" 2>/dev/null || true
if [ -f "$GPM_ODEX" ]; then
    log "FAIL: could not delete $GPM_ODEX"
    exit 1
fi
if ! write_odex "$GPM_APK" "$GPM_ODEX"; then
    rm -f "$GPM_ODEX" 2>/dev/null || true
    log "dexopt failed for HKMC_GPM; boot will optimize"
fi

if ! put_lib "$FILES/libautoreceiver_infobank.so" "$LIB_LIVE"; then
    log "FAIL: install $LIB_LIVE"
    exit 1
fi
log "OK: $LIB_LIVE replaced"

wipe_dalvik
prefill_dalvik "$GPM_ODEX"

sys_ro
apps_release
sync
log "done. Reboot the head unit, then plug in Android Auto."
log "off switch: setprop $OFF_PROP 1 (stock behaviour), back with setprop $OFF_PROP 0"
exit 0

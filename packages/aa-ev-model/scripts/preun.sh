#!/system/bin/sh
. /data/nemesis/apps/aa.ev.model/scripts/common.sh

log "preuninstall aa.ev.model"
trap '[ $? = 0 ] || { sys_ro; apps_release; }' EXIT

if [ ! -d "$BACKUP_DIR" ]; then
    log "no snapshot - nothing to restore"
    exit 0
fi

SUM_PAY_APK=$(manifest_sum HKMC_GPM.apk)
SUM_PAY_LIB=$(manifest_sum libautoreceiver_infobank.so)

apps_hold
if ! sys_rw; then
    log "FAIL: remount /system rw"
    exit 1
fi

# Restore each snapshotted file only if the live file is still ours. If Leafia
# was uninstalled in between, the live files are stock: leave them alone.
# The GPM APK also counts as ours when our library is in place and the APK
# isn't Leafia's: an earlier build of this package (its APK md5 differs).
_rc=0
_live_apk=$(file_md5 "$GPM_APK")
_live_lib=$(file_md5 "$LIB_LIVE")
if [ "$_live_apk" = "$SUM_PAY_APK" ] ||
   { [ "$_live_lib" = "$SUM_PAY_LIB" ] && [ "$_live_apk" != "$SUM_BASE_APK" ]; }; then
    if [ -s "$BACKUP_DIR/HKMC_GPM.apk" ] && [ -s "$BACKUP_DIR/HKMC_GPM.odex" ]; then
        put_lib "$BACKUP_DIR/HKMC_GPM.apk" "$GPM_APK" || _rc=1
        put_lib "$BACKUP_DIR/HKMC_GPM.odex" "$GPM_ODEX" || _rc=1
        log "restored HKMC_GPM apk+odex"
    else
        log "FAIL: incomplete GPM snapshot"
        _rc=1
    fi
else
    log "live HKMC_GPM.apk is not this package's - leaving it alone"
fi

if [ "$_live_lib" = "$SUM_PAY_LIB" ]; then
    if [ -s "$BACKUP_DIR/libautoreceiver_infobank.so" ]; then
        put_lib "$BACKUP_DIR/libautoreceiver_infobank.so" "$LIB_LIVE" || _rc=1
        log "restored libautoreceiver_infobank.so"
    else
        log "FAIL: missing library snapshot"
        _rc=1
    fi
else
    log "live libautoreceiver_infobank.so is not this package's - leaving it alone"
fi

wipe_dalvik
sys_ro
apps_release
sync
if [ "$_rc" = 0 ]; then
    rm -rf "$BACKUP_DIR"
    log "removed $BACKUP_DIR"
else
    log "keeping $BACKUP_DIR"
fi
exit "$_rc"

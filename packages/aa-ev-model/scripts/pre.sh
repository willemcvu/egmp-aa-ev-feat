#!/system/bin/sh
# NEMESIS runs preinstall from the staging dir, before the tree is copied to
# /data/nemesis/apps/aa.ev.model: source relatively and check the staged files.
FILES=./files
. ./scripts/common.sh

log "preinstall aa.ev.model"
if [ ! -x /system/xbin/nemesis ]; then
    log "FAIL: /system/xbin/nemesis missing"
    exit 1
fi

# Payloads must be present and match the manifest they were built with.
if [ ! -f "$FILES/MD5SUMS" ]; then
    log "FAIL: $FILES/MD5SUMS missing"
    exit 1
fi
SUM_PAY_APK=$(manifest_sum HKMC_GPM.apk) || { log "FAIL: no manifest entry for HKMC_GPM.apk"; exit 1; }
SUM_PAY_LIB=$(manifest_sum libautoreceiver_infobank.so) || { log "FAIL: no manifest entry for libautoreceiver_infobank.so"; exit 1; }
sum_is "$FILES/HKMC_GPM.apk" "$SUM_PAY_APK" || exit 1
sum_is "$FILES/libautoreceiver_infobank.so" "$SUM_PAY_LIB" || exit 1

# Live files must be either Leafia v1.0.5's (fresh install) or ours (reinstall).
_live_apk=$(file_md5 "$GPM_APK")
_live_odex=$(file_md5 "$GPM_ODEX")
_live_lib=$(file_md5 "$LIB_LIVE")
log "live: apk=$_live_apk odex=$_live_odex lib=$_live_lib"

case "$_live_apk:$_live_odex:$_live_lib" in
    "$SUM_BASE_APK:$SUM_BASE_ODEX:$SUM_BASE_LIB")
        log "live files are Leafia v1.0.5 - ok" ;;
    "$SUM_PAY_APK":*:"$SUM_PAY_LIB")
        # already ours: only safe with a complete snapshot to fall back to
        if [ -s "$BACKUP_DIR/HKMC_GPM.apk" ] && [ -s "$BACKUP_DIR/HKMC_GPM.odex" ] && [ -s "$BACKUP_DIR/libautoreceiver_infobank.so" ]; then
            log "live files are already this package's - reinstalling over it"
        else
            log "FAIL: live files are ours but there is no snapshot to uninstall to"
            exit 1
        fi ;;
    *)
        log "FAIL: live files are neither Leafia v1.0.5 nor this package's - refusing"
        toast "AA EV model not installed: unexpected HKMC_GPM/libautoreceiver versions. Uninstall other projection patches first."
        exit 1 ;;
esac

log "preinstall ok"
exit 0

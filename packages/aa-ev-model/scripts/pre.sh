#!/system/bin/sh
# NEMESIS runs preinstall from the staging dir, before the tree is copied to
# /data/nemesis/apps/aa.ev.model: source relatively and use the staged files.
FILES=./files
. ./scripts/common.sh

log "preinstall aa.ev.model"
trap '[ $? = 0 ] || clean_payload' EXIT
if [ ! -x /system/xbin/nemesis ]; then
    log "FAIL: /system/xbin/nemesis missing"
    exit 1
fi

for _f in MD5SUMS lpatch HKMC_GPM.apk.lpd libautoreceiver_infobank.so.lpd; do
    if [ ! -s "$FILES/$_f" ]; then
        log "FAIL: $FILES/$_f missing"
        exit 1
    fi
done
SUM_PAY_APK=$(manifest_sum HKMC_GPM.apk) || { log "FAIL: no manifest entry for HKMC_GPM.apk"; exit 1; }
SUM_PAY_LIB=$(manifest_sum libautoreceiver_infobank.so) || { log "FAIL: no manifest entry for libautoreceiver_infobank.so"; exit 1; }

# Live files must be either Leafia v1.0.5's (fresh install) or ours with a
# verified Leafia snapshot (reinstall, or upgrade from an earlier build: our
# library is unchanged between builds, the GPM APK is not).
_live_apk=$(file_md5 "$GPM_APK")
_live_odex=$(file_md5 "$GPM_ODEX")
_live_lib=$(file_md5 "$LIB_LIVE")
log "live: apk=$_live_apk odex=$_live_odex lib=$_live_lib"

if [ "$_live_apk:$_live_odex:$_live_lib" = "$SUM_BASE_APK:$SUM_BASE_ODEX:$SUM_BASE_LIB" ]; then
    log "live files are Leafia v1.0.5 - ok"
    _src_apk=$GPM_APK
    _src_lib=$LIB_LIVE
elif [ "$_live_lib" = "$SUM_PAY_LIB" ] && [ "$_live_apk" != "$SUM_BASE_APK" ]; then
    # already ours: only safe with a complete Leafia snapshot to fall back to
    if snapshot_is_base; then
        log "live files are this package's - reinstalling over them"
        _src_apk=$BACKUP_DIR/HKMC_GPM.apk
        _src_lib=$BACKUP_DIR/libautoreceiver_infobank.so
    else
        log "FAIL: live files are ours but there is no valid Leafia snapshot to uninstall to"
        toast "AA EV model not installed: no Leafia backup found. Uninstall aa.ev.model, reboot, and try again."
        exit 1
    fi
else
    log "FAIL: live files are neither Leafia v1.0.5 nor this package's - refusing"
    toast "AA EV model not installed: needs HMG Leafia Plus v1.0.5 on Ioniq 5 (US) 260507 firmware. Nothing was changed."
    exit 1
fi

if ! build_payloads "$_src_apk" "$_src_lib"; then
    toast "AA EV model not installed: could not rebuild its files from this head unit's. Nothing was changed."
    exit 1
fi

log "preinstall ok"
exit 0

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

if [ ! -s "$FILES/lpatch" ]; then
    log "FAIL: $FILES/lpatch missing"
    exit 1
fi
_cars=$(cars)
if [ -z "$_cars" ]; then
    log "FAIL: no car folders in $FILES"
    exit 1
fi
for _c in $_cars; do
    for _f in BASE MD5SUMS HKMC_GPM.apk.lpd libautoreceiver_infobank.so.lpd; do
        if [ ! -s "$FILES/$_c/$_f" ]; then
            log "FAIL: $FILES/$_c/$_f missing"
            exit 1
        fi
    done
done
log "supported: $(echo $_cars)"

# Live files must be either one car's Leafia v1.0.5 files (fresh install) or
# ours with a verified Leafia snapshot (reinstall, or upgrade from an earlier
# build: our library is unchanged between builds, the GPM APK is not).
_live_apk=$(file_md5 "$GPM_APK")
_live_odex=$(file_md5 "$GPM_ODEX")
_live_lib=$(file_md5 "$LIB_LIVE")
log "live: apk=$_live_apk odex=$_live_odex lib=$_live_lib"

if _car=$(car_for "$_live_apk" "$_live_odex" "$_live_lib"); then
    use_car "$_car" || exit 1
    log "live files are Leafia v1.0.5 on $CAR_NAME - ok"
    _src_apk=$GPM_APK
    _src_lib=$LIB_LIVE
elif any_sum MD5SUMS libautoreceiver_infobank.so "$_live_lib" &&
     ! any_sum BASE HKMC_GPM.apk "$_live_apk"; then
    # already ours: only safe with a complete Leafia snapshot to fall back to
    if _car=$(snapshot_car); then
        use_car "$_car" || exit 1
        log "live files are this package's on $CAR_NAME - reinstalling over them"
        _src_apk=$BACKUP_DIR/HKMC_GPM.apk
        _src_lib=$BACKUP_DIR/libautoreceiver_infobank.so
    else
        log "FAIL: live files are ours but there is no valid Leafia snapshot to uninstall to"
        toast "AA EV model not installed: no Leafia backup found. Uninstall aa.ev.model, reboot, and try again."
        exit 1
    fi
else
    log "FAIL: live files are neither Leafia v1.0.5 on a supported car nor this package's - refusing"
    toast "AA EV model not installed: needs HMG Leafia Plus v1.0.5 on a supported car and software (see README). Nothing was changed."
    exit 1
fi

if ! build_payloads "$_src_apk" "$_src_lib"; then
    toast "AA EV model not installed: could not rebuild its files from this head unit's. Nothing was changed."
    exit 1
fi

log "preinstall ok"
exit 0

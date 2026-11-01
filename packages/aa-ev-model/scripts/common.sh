#!/system/bin/sh
# aa.ev.model — advertise ELECTRIC + sensor 23 and send the energy model to AA.
#
# The package ships no Hyundai/Google binaries. files/ holds lpatch itself
# and, per supported car, lpatch deltas (*.lpd); preinstall rebuilds that
# car's payloads into $PAYLOAD from the unit's own Leafia v1.0.5 files (lpatch
# refuses any other source) and checks them against files/<car>/MD5SUMS.
# Install gates on the live files being exactly one car's Leafia v1.0.5 files (or this
# package's own, with a verified Leafia snapshot, for a reinstall/upgrade),
# snapshots them, and swaps in the payloads. Uninstall restores the snapshot
# byte for byte, but only for files that are still ours (if Leafia was
# uninstalled in between, its files are not put back).
#
# Off switch without uninstall: setprop persist.gpm.ev.off 1 (and replug AA).

export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib

PKG_DIR=/data/nemesis/apps/aa.ev.model
# preinstall runs from the staging dir before the tree exists at PKG_DIR;
# pre.sh sets FILES=./files before sourcing this file.
FILES=${FILES:-$PKG_DIR/files}
PAYLOAD=/data/nemesis/build/aa.ev.model
BACKUP_DIR=/data/nemesis/backup/aa.ev.model
LOG=/data/nemesis/aa.ev.model.log
HOLD_FILE=/data/nemesis/aa.ev.model.stopped
OFF_PROP=persist.gpm.ev.off

APP_DIR=/system/app
GPM_APK=$APP_DIR/HKMC_GPM.apk
GPM_ODEX=$APP_DIR/HKMC_GPM.odex
LIB_LIVE=/system/lib/libautoreceiver_infobank.so

# One folder per supported car under files/ (files/<car>/): BASE (md5 of
# that car's Leafia v1.0.5 GPM APK, odex and AA library), MD5SUMS (md5 of the
# payloads rebuilt from them), NAME, and the two deltas. Live files must
# match one car's BASE exactly, unless this package's own payload is already
# in place. use_car <car> loads one car's sums into the SUM_* variables.
CAR=
CAR_NAME=
SUM_BASE_APK=
SUM_BASE_ODEX=
SUM_BASE_LIB=

GPM_PKG=com.google.android.projection.sink

log() {
    local _msg="$1"
    echo "$_msg"
    mkdir -p /data/nemesis 2>/dev/null || true
    echo "$_msg" >> "$LOG" 2>/dev/null || true
}

toast() {
    am broadcast -a com.hkmc.intent.action.TOAST \
        -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" \
        --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1 || true
}

sys_rw() {
    /system/xbin/nemesis remount rw >/dev/null 2>&1
}

sys_ro() {
    /system/xbin/nemesis remount ro >/dev/null 2>&1 || true
}

file_md5() {
    local _line
    local _sum
    _line=$(md5 "$1" 2>/dev/null) || return 1
    set -- $_line
    _sum=$1
    [ -n "$_sum" ] || return 1
    echo "$_sum"
}

sum_is() {
    local _live="$1"
    local _want="$2"
    local _got
    if [ ! -s "$_live" ]; then
        log "FAIL: $_live missing"
        return 1
    fi
    _got=$(file_md5 "$_live") || {
        log "FAIL: md5 $_live"
        return 1
    }
    if [ "$_got" != "$_want" ]; then
        log "FAIL: $_live checksum $_got want $_want"
        return 1
    fi
    return 0
}

# sum_in <md5sum-format file> <name>: the md5 listed for name
sum_in() {
    local _line
    _line=$(grep "  $2\$" "$1" 2>/dev/null) || return 1
    set -- $_line
    [ -n "$1" ] || return 1
    echo "$1"
}

# payload md5 for the current car (use_car first)
manifest_sum() {
    sum_in "$FILES/$CAR/MD5SUMS" "$1"
}

# the supported cars: folders under files/ with a BASE file
cars() {
    local _d
    for _d in "$FILES"/*; do
        [ -f "$_d/BASE" ] && echo "${_d##*/}"
    done
    return 0
}

use_car() {
    CAR="$1"
    SUM_BASE_APK=$(sum_in "$FILES/$CAR/BASE" HKMC_GPM.apk) &&
    SUM_BASE_ODEX=$(sum_in "$FILES/$CAR/BASE" HKMC_GPM.odex) &&
    SUM_BASE_LIB=$(sum_in "$FILES/$CAR/BASE" libautoreceiver_infobank.so) || {
        log "FAIL: $FILES/$CAR/BASE incomplete"
        return 1
    }
    CAR_NAME=$(cat "$FILES/$CAR/NAME" 2>/dev/null)
    [ -n "$CAR_NAME" ] || CAR_NAME=$CAR
    return 0
}

# car_for <apk md5> <odex md5> <lib md5>: the car whose BASE these are
car_for() {
    local _c
    for _c in $(cars); do
        use_car "$_c" || continue
        if [ "$1:$2:$3" = "$SUM_BASE_APK:$SUM_BASE_ODEX:$SUM_BASE_LIB" ]; then
            echo "$_c"
            return 0
        fi
    done
    return 1
}

# snapshot_car: the car whose BASE the snapshot holds exactly
snapshot_car() {
    car_for "$(file_md5 "$BACKUP_DIR/HKMC_GPM.apk")" \
            "$(file_md5 "$BACKUP_DIR/HKMC_GPM.odex")" \
            "$(file_md5 "$BACKUP_DIR/libautoreceiver_infobank.so")"
}

# any_sum <file in each car folder> <name> <md5>: md5 is listed for some car
any_sum() {
    local _c
    [ -n "$3" ] || return 1
    for _c in $(cars); do
        [ "$(sum_in "$FILES/$_c/$1" "$2")" = "$3" ] && return 0
    done
    return 1
}

chown_system() {
    chown 0.0 "$1" 2>/dev/null || chown 0:0 "$1" 2>/dev/null || true
}


# NEMESIS 1.15 force-updates an installed package from a USB drive left in at
# boot, so with the reboot after install a drive left in would reinstall and
# reboot forever. Rename our descriptor (as Leafia does) so the next boot
# leaves it alone.
retire_usb_descriptor() {
    local _d
    for _d in /storage/usb0 /storage/usb1 /storage/usb2 /mnt/media_rw/usb0 /mnt/media_rw/usb1 /mnt/media_rw/usb2; do
        [ -f "$_d/NEMESIS.data" ] || continue
        grep -q '^type=install' "$_d/NEMESIS.data" 2>/dev/null || continue
        grep -q 'aa\.ev\.model\.zip' "$_d/NEMESIS.data" 2>/dev/null || continue
        if mv "$_d/NEMESIS.data" "$_d/NEMESIS.data.installed" 2>/dev/null; then
            log "renamed $_d/NEMESIS.data to NEMESIS.data.installed so the next boot does not reinstall"
        else
            log "WARN: could not rename $_d/NEMESIS.data - remove the USB drive before the head unit restarts"
        fi
    done
    return 0
}

clean_payload() {
    case "$PAYLOAD" in
        */data/nemesis/build/aa.ev.model) rm -rf "$PAYLOAD"; rmdir "${PAYLOAD%/*}" 2>/dev/null ;;
    esac
    return 0
}

# build_payloads <Leafia apk> <Leafia lib>: rebuild the current car's payloads
# into $PAYLOAD (use_car first). lpatch only writes a result when the source is
# the exact file the delta was made from; the results must then match the
# car's MD5SUMS. $PAYLOAD/CAR records the car for postinstall.
build_payloads() {
    local _n _src
    clean_payload
    mkdir -p "$PAYLOAD" || { log "FAIL: mkdir $PAYLOAD"; return 1; }
    chmod 755 "$FILES/lpatch" 2>/dev/null || true
    for _n in HKMC_GPM.apk libautoreceiver_infobank.so; do
        case "$_n" in
            *.apk) _src="$1" ;;
            *) _src="$2" ;;
        esac
        if ! "$FILES/lpatch" delta "$FILES/$CAR/$_n.lpd" "$_src" "$PAYLOAD/$_n" >> "$LOG" 2>&1; then
            log "FAIL: could not rebuild $_n from $_src"
            return 1
        fi
        sum_is "$PAYLOAD/$_n" "$(manifest_sum "$_n")" || return 1
    done
    echo "$CAR" > "$PAYLOAD/CAR" || { log "FAIL: write $PAYLOAD/CAR"; return 1; }
    log "rebuilt $CAR_NAME payloads from $1 and $2"
    return 0
}

# copy beside the target, then rename over it (new inode; a running app keeps
# the old mapping until it restarts)
put_lib() {
    local _src="$1"
    local _dst="$2"
    local _tmp="${_dst}.evtmp"
    rm -f "$_tmp" 2>/dev/null || true
    cp -f "$_src" "$_tmp" || { rm -f "$_tmp" 2>/dev/null; return 1; }
    if ! cmp "$_src" "$_tmp" >/dev/null 2>&1; then
        rm -f "$_tmp" 2>/dev/null || true
        return 1
    fi
    chmod 644 "$_tmp" 2>/dev/null || true
    chown_system "$_tmp"
    mv "$_tmp" "$_dst" || { rm -f "$_tmp" 2>/dev/null; return 1; }
    chmod 644 "$_dst" 2>/dev/null || true
    chown_system "$_dst"
    if [ -x /system/bin/restorecon ]; then
        restorecon "$_dst" 2>/dev/null || true
    fi
    cmp "$_src" "$_dst" >/dev/null 2>&1
}

android_up() { [ "$(getprop sys.boot_completed 2>/dev/null)" = 1 ]; }

apps_hold() {
    android_up || return 0
    touch "$HOLD_FILE" 2>/dev/null || { log "WARN: cannot record stopped apps; not stopping them"; return 0; }
    if pm path "$GPM_PKG" >/dev/null 2>&1; then
        case " $(cat "$HOLD_FILE" 2>/dev/null) " in
            *" $GPM_PKG "*) ;;
            *) echo "$GPM_PKG" >> "$HOLD_FILE" ;;
        esac
        if pm disable "$GPM_PKG" >/dev/null 2>&1; then
            log "stopped $GPM_PKG for the file swap"
        else
            log "WARN: could not stop $GPM_PKG"
        fi
    fi
    return 0
}

apps_release() {
    [ -f "$HOLD_FILE" ] || return 0
    if ! android_up; then
        log "WARN: apps stopped by an earlier run stay stopped until this runs with Android up"
        return 0
    fi
    if pm enable "$GPM_PKG" >/dev/null 2>&1; then
        log "started $GPM_PKG again"
        rm -f "$HOLD_FILE"
    else
        log "WARN: could not start $GPM_PKG again"
    fi
    return 0
}

snapshot_all() {
    mkdir -p "$BACKUP_DIR" 2>/dev/null || true
    local _f _bak
    for _f in "$GPM_APK" "$GPM_ODEX" "$LIB_LIVE"; do
        _bak="$BACKUP_DIR/${_f##*/}"
        if [ -s "$_bak" ]; then
            log "snapshot exists for ${_f##*/} - keeping original"
        else
            if [ ! -s "$_f" ]; then
                log "FAIL: $_f missing, cannot snapshot"
                return 1
            fi
            cp -f "$_f" "$_bak" || return 1
            chmod 644 "$_bak" 2>/dev/null || true
            log "backed up $_f"
        fi
    done
    return 0
}

write_odex() {
    local _apk="$1"
    local _odex="$2"
    if [ -z "$BOOTCLASSPATH" ]; then
        export BOOTCLASSPATH=/system/framework/core.jar:/system/framework/conscrypt.jar:/system/framework/okhttp.jar:/system/framework/core-junit.jar:/system/framework/bouncycastle.jar:/system/framework/ext.jar:/system/framework/framework.jar:/system/framework/framework2.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/mms-common.jar:/system/framework/android.policy.jar:/system/framework/services.jar:/system/framework/apache-xml.jar:/system/framework/webviewchromium.jar:/system/framework/automotive.jar
    fi
    if [ ! -x /system/bin/dexopt ]; then
        log "dexopt binary missing"
        return 1
    fi
    log "dexopt $_apk"
    /system/bin/dexopt --preopt "$_apk" "$_odex" "m=y,u=n" || return 1
    [ -s "$_odex" ] || return 1
    chmod 644 "$_odex" 2>/dev/null || true
    chown_system "$_odex"
    if [ -x /system/bin/restorecon ]; then
        restorecon "$_odex" 2>/dev/null || true
    fi
    return 0
}

wipe_dalvik() {
    rm -f /data/dalvik-cache/*HKMC_GPM* 2>/dev/null || true
    rm -f /data/dalvik-cache/arm/*HKMC_GPM* 2>/dev/null || true
    rm -f /cache/dalvik-cache/*HKMC_GPM* 2>/dev/null || true
}

# The APK carries classes.dex, so PackageManager looks in the dalvik cache and
# re-optimises at boot if empty. Prefill it with the odex (same file).
prefill_dalvik() {
    local _odex="$1"
    local _cache="/data/dalvik-cache/system@app@HKMC_GPM.apk@classes.dex"
    [ -s "$_odex" ] || return 0
    [ -d /data/dalvik-cache ] || return 0
    rm -f "${_cache}.tmp" 2>/dev/null || true
    if ! cp -f "$_odex" "${_cache}.tmp" 2>/dev/null || ! cmp "$_odex" "${_cache}.tmp" >/dev/null 2>&1; then
        rm -f "${_cache}.tmp" 2>/dev/null || true
        log "dalvik cache not filled; boot will optimize"
        return 0
    fi
    chmod 644 "${_cache}.tmp" 2>/dev/null || true
    chown 1000:1000 "${_cache}.tmp" 2>/dev/null || chown 1000.1000 "${_cache}.tmp" 2>/dev/null || true
    mv "${_cache}.tmp" "$_cache" 2>/dev/null || { rm -f "${_cache}.tmp" 2>/dev/null; return 0; }
    if [ -x /system/bin/restorecon ]; then
        restorecon "$_cache" 2>/dev/null || true
    fi
    log "dalvik cache filled for HKMC_GPM.apk"
    return 0
}

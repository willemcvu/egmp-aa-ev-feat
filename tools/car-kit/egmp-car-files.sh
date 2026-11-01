#!/system/bin/sh
# egmp car-files kit: copies what a build for a new car needs from the head
# unit to this USB drive (egmp-car-files/). Read-only on the car: it changes
# nothing there and writes only to the drive.
#
# Run by NEMESIS (NEMESIS.data type=execute) as root with cwd = the drive.
# Needs HMG Leafia Plus v1.0.5 installed (its files and its backup of the
# stock GPM APK are what the build patches). See docs/PORTING.md.
#
# The copies are Hyundai/Google files from your own car: keep them private.
# No VIN, location or personal data is collected.
export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib

OUT=./egmp-car-files
INFO=$OUT/info.txt

toast() {
    am broadcast -a com.hkmc.intent.action.TOAST \
        -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" \
        --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1 || true
}

sum() {
    _l=$(md5 "$1" 2>/dev/null) || _l=$(md5sum "$1" 2>/dev/null) || { echo "?"; return; }
    set -- $_l
    echo "$1"
}

FAILED=0
# grab <src> <dst relative to OUT>: copy with cat (no cp in some toolboxes),
# then check it arrived whole
grab() {
    if [ ! -f "$1" ]; then
        echo "missing  $1" >> "$INFO"
        FAILED=1
        return
    fi
    case "$2" in */*) mkdir -p "$OUT/${2%/*}" 2>/dev/null ;; esac
    cat "$1" > "$OUT/$2.tmp" && mv "$OUT/$2.tmp" "$OUT/$2"
    _a=$(sum "$1")
    _b=$(sum "$OUT/$2")
    if [ "$_a" = "$_b" ] && [ "$_a" != "?" ]; then
        echo "$_a  $2  ($1)" >> "$INFO"
    else
        echo "COPY FAILED  $1 ($_a vs $_b)" >> "$INFO"
        FAILED=1
    fi
}

toast "egmp: copying car files to the USB drive..."
rm -rf "$OUT"
mkdir -p "$OUT" || { toast "egmp: cannot write to the USB drive"; exit 1; }

{
    echo "# egmp car files $(date +%Y-%m-%d)"
    echo "ro.build.product=$(getprop ro.build.product)"
    echo "ro.product.device=$(getprop ro.product.device)"
    echo "ro.build.version.release=$(getprop ro.build.version.release)"
    echo "ro.build.display.id=$(getprop ro.build.display.id)"
    echo "nemesis packages:"
    ls /data/nemesis/apps 2>/dev/null
    echo "# md5  file  (source)"
} > "$INFO"

# Live files with Leafia v1.0.5 installed (what our package patches)
grab /system/app/HKMC_GPM.apk HKMC_GPM.apk
grab /system/app/HKMC_GPM.odex HKMC_GPM.odex
grab /system/lib/libautoreceiver_infobank.so libautoreceiver_infobank.so
# Leafia's snapshot of the stock GPM (the base our APK is assembled on). It
# only exists if Leafia's turn-by-turn part installed, which needs software
# Leafia supports (EV6 US: 260507).
if [ -f /data/nemesis/backup/projection.tbt/HKMC_GPM.apk ]; then
    grab /data/nemesis/backup/projection.tbt/HKMC_GPM.apk stock/HKMC_GPM.apk
    grab /data/nemesis/backup/projection.tbt/HKMC_GPM.odex stock/HKMC_GPM.odex
else
    echo "NOTE: no Leafia turn-by-turn backup: Leafia's Android Auto part is not installed (unsupported software?)" >> "$INFO"
    FAILED=1
fi
# NEMESIS, Leafia and package logs (no personal data), to diagnose installs
for _f in /data/nemesis/*.log /data/nemesis/logs/*.log /data/nemesis/logs/*/*.log \
          /data/nemesis/reports/*; do
    [ -f "$_f" ] || continue
    _n=${_f#/data/nemesis/}
    case "$_n" in */*) mkdir -p "$OUT/logs/${_n%/*}" 2>/dev/null ;; esac
    cat "$_f" > "$OUT/logs/$_n" 2>/dev/null
done
echo "logs copied to logs/" >> "$INFO"
# The car framework: fingerprints for preconditioning, and the AIDL
# interfaces whose transaction numbers EvFirmware resolves
for _f in automotive.jar automotive.odex automotive-service.jar automotive-service.odex; do
    [ -f "/system/framework/$_f" ] && grab "/system/framework/$_f" "framework/$_f"
done
for _f in /system/framework/*.odex /system/framework/*.jar; do
    case "$_f" in */automotive*) continue ;; esac
    if grep -q -e ITM_HSCANService -e IDATCService "$_f" 2>/dev/null; then
        grab "$_f" "framework/${_f##*/}"
    fi
done

sync
if [ "$FAILED" = 0 ]; then
    toast "egmp: car files copied to egmp-car-files on the USB drive. Safe to remove."
    exit 0
fi
toast "egmp: some files were missing or failed to copy - see egmp-car-files/info.txt"
exit 1

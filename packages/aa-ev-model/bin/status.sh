#!/system/bin/sh
# aa.ev.model status, for the NEMESIS manager.
. /data/nemesis/apps/aa.ev.model/scripts/common.sh

echo "== aa.ev.model =="
echo "off switch ($OFF_PROP): $(getprop $OFF_PROP)"
echo "live HKMC_GPM.apk:    $(file_md5 $GPM_APK)"
echo "live HKMC_GPM.odex:   $(file_md5 $GPM_ODEX)"
echo "live receiver lib:    $(file_md5 $LIB_LIVE)"
echo "base   (Leafia 1.0.5): apk $SUM_BASE_APK"
echo "                     : odex $SUM_BASE_ODEX"
echo "                     : lib $SUM_BASE_LIB"
if [ -f "$FILES/MD5SUMS" ]; then
    echo "payload:"
    cat "$FILES/MD5SUMS"
fi
if [ -d "$BACKUP_DIR" ]; then
    echo "snapshot: present in $BACKUP_DIR"
else
    echo "snapshot: none"
fi
echo "-- recent log --"
tail -n 15 "$LOG" 2>/dev/null || echo "(no log)"

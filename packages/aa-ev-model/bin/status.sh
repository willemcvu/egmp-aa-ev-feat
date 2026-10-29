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
echo "car: ${CAR_NAME:-unsupported} ($CAR)"
if [ -f "$FILES_CAR/MD5SUMS" ]; then
    echo "payload:"
    cat "$FILES_CAR/MD5SUMS"
fi
if [ -d "$BACKUP_DIR" ]; then
    echo "snapshot: present in $BACKUP_DIR"
else
    echo "snapshot: none"
fi
echo "-- recent log --"
# last 15 lines without tail (the unit has none): a ring of 15 variables
if [ -f "$LOG" ]; then
    _n=0
    while IFS= read -r _l; do
        eval "_r$((_n % 15))=\$_l"
        _n=$((_n + 1))
    done < "$LOG"
    _i=$((_n > 15 ? _n - 15 : 0))
    while [ "$_i" -lt "$_n" ]; do
        eval "echo \"\$_r$((_i % 15))\""
        _i=$((_i + 1))
    done
    echo "-- log copying --"
    _n=0
    while IFS= read -r _l; do
        eval "_r$((_n % 15))=\$_l"
        _n=$((_n + 1))
    done < /data/nemesis/aa.ev.model.offload.log 2>/dev/null
    _i=$((_n > 5 ? _n - 5 : 0))
    while [ "$_i" -lt "$_n" ]; do
        eval "echo \"\$_r$((_i % 15))\""
        _i=$((_i + 1))
    done
else
    echo "(no log)"
fi

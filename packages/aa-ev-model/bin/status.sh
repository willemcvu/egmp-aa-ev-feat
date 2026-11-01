#!/system/bin/sh
# aa.ev.model status, for the NEMESIS manager.
. /data/nemesis/apps/aa.ev.model/scripts/common.sh

echo "== aa.ev.model =="
echo "off switch ($OFF_PROP): $(getprop $OFF_PROP)"
echo "live HKMC_GPM.apk:    $(file_md5 $GPM_APK)"
echo "live HKMC_GPM.odex:   $(file_md5 $GPM_ODEX)"
echo "live receiver lib:    $(file_md5 $LIB_LIVE)"
for _c in $(cars); do
    use_car "$_c" || continue
    echo "car $_c: $CAR_NAME"
    echo "  base (Leafia 1.0.5): apk $SUM_BASE_APK"
    echo "                     : odex $SUM_BASE_ODEX"
    echo "                     : lib $SUM_BASE_LIB"
    echo "  payload:"
    cat "$FILES/$_c/MD5SUMS"
done
if [ -d "$BACKUP_DIR" ]; then
    if _c=$(snapshot_car); then
        use_car "$_c"
        echo "snapshot: present in $BACKUP_DIR ($CAR_NAME)"
    else
        echo "snapshot: present in $BACKUP_DIR (not a supported car's Leafia files)"
    fi
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

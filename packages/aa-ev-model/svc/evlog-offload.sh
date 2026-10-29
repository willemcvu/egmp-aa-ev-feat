#!/system/bin/sh
# aa.ev.model: copy drive logs to a USB drive (docs/LOGGING.md).
#
# GPM writes one CSV per Android Auto session into its own files dir (see
# EvLogger). Every POLL_S this copies any log that is new or has grown to
# <drive>/egmp-aa-ev-logs/ on every mounted USB drive that HAS that folder
# (the folder is the opt-in: drives without it are never written to). The
# copy goes to a .tmp name, is verified with cmp, then renamed, so a pulled
# drive never ends up with a half-written log. Logs stay on the head unit
# (capped by EvLogger) and are never deleted from here.
# EVLOG_ONCE=1 runs a single pass (host sandbox test).

export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin

SRC=/data/data/com.google.android.projection.sink/files/evlog
LOG=/data/nemesis/aa.ev.model.offload.log
POLL_S=20
DRIVES="/storage/usb0 /storage/usb1 /storage/usb2 /mnt/media_rw/usb0 /mnt/media_rw/usb1 /mnt/media_rw/usb2"

log() {
    echo "$(date) $1" >> "$LOG" 2>/dev/null
}

# file size via ls -l (no stat/wc on the unit); empty if missing or if the
# listing isn't the expected toolbox layout (perms owner group size ...)
size_of() {
    local _l
    _l=$(ls -l "$1" 2>/dev/null) || return 0
    set -- $_l
    case "$4" in
        ''|*[!0-9]*) ;;
        *) echo "$4" ;;
    esac
}

# 0 when the drive's copy is already current
up_to_date() {
    local _ss _ds
    _ss=$(size_of "$1")
    _ds=$(size_of "$2")
    if [ -n "$_ss" ] && [ -n "$_ds" ]; then
        [ "$_ss" = "$_ds" ]
        return
    fi
    [ -f "$2" ] && cmp "$1" "$2" >/dev/null 2>&1
}

offload_to() {
    local _dst="$1/egmp-aa-ev-logs" _f _n _tmp _done=0
    for _f in "$SRC"/*.csv; do
        [ -f "$_f" ] || continue
        _n=${_f##*/}
        up_to_date "$_f" "$_dst/$_n" && continue
        _tmp="$_dst/.$_n.tmp"
        rm -f "$_tmp" 2>/dev/null
        if cp "$_f" "$_tmp" 2>/dev/null && cmp "$_f" "$_tmp" >/dev/null 2>&1 \
                && mv "$_tmp" "$_dst/$_n" 2>/dev/null; then
            _done=$((_done + 1))
        else
            rm -f "$_tmp" 2>/dev/null
            log "copy failed: $_n -> $_dst"
        fi
    done
    if [ "$_done" -gt 0 ]; then
        sync
        log "copied $_done log(s) to $_dst"
    fi
}

log "evlog-offload started"
while true; do
    for _d in $DRIVES; do
        [ -d "$_d/egmp-aa-ev-logs" ] && offload_to "$_d"
    done
    [ -n "$EVLOG_ONCE" ] && exit 0
    sleep "$POLL_S"
done

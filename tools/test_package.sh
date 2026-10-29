#!/bin/bash
# Host-side sandbox test of the aa.ev.model NEMESIS package scripts.
#
# Copies packages/aa-ev-model into a fake head-unit filesystem, rewrites the
# absolute paths (/system, /data, /cache) into the sandbox, stubs the car
# commands (nemesis, md5, pm, dexopt, getprop, am), and runs the real
# pre/post/preun scripts through:
#   1. fresh install over Leafia v1.0.5 files
#   2. uninstall restores Leafia's files byte for byte
#   3. uninstall after Leafia was removed in between leaves stock files alone
#   4. reinstall over our own payloads
#   5. refusal on unknown live files
#   6. upgrade over an earlier build of this package (different GPM APK)
#   7. refusal when the Leafia snapshot is not intact
#   8. the package carries only deltas, lpatch and the manifest
#   9. drive-log offload to an opted-in USB drive
#
# files/lpatch is the real ARM binary; it runs here through qemu binfmt.
#
# Run from the repo root after packages/aa-ev-model/build.sh.

set -u
cd "$(dirname "$0")/.."
ROOT=$(pwd)
T=/tmp/opencode/pkgtest
rm -rf "$T"
mkdir -p "$T"

PRIV=private/car-current-leafia105
SUM_BASE_APK=0308224b94c7e1b81d372a8ea1535aed
SUM_BASE_ODEX=7d81a60aabf89efdd4ce12ba9787248e
SUM_BASE_LIB=c08410a8d32a615b1aa805cd8a243d52
SUM_PAY_APK=$(md5sum dist/HKMC_GPM.apk | cut -d' ' -f1)
SUM_PAY_LIB=$(md5sum dist/libautoreceiver_infobank.so | cut -d' ' -f1)

md5_of() { md5sum "$1" | cut -d' ' -f1; }

# Build a sandbox with a fresh "car" state: Leafia v1.0.5 live files.
setup_sandbox() {
    local S="$1"
    rm -rf "$S"
    mkdir -p "$S/system/app" "$S/system/lib" "$S/system/xbin" "$S/system/bin" \
             "$S/data/nemesis/apps/aa.ev.model" "$S/data/nemesis/bin" \
             "$S/data/dalvik-cache"
    cp "$PRIV/HKMC_GPM.apk" "$S/system/app/HKMC_GPM.apk"
    cp "$PRIV/HKMC_GPM.odex" "$S/system/app/HKMC_GPM.odex"
    cp "$PRIV/libautoreceiver_infobank.so" "$S/system/lib/libautoreceiver_infobank.so"

    # The package, with absolute paths rewritten into the sandbox. files/ holds
    # binary payloads: copy those verbatim, sed only the text files.
    ( cd packages/aa-ev-model && find . -type f -print0 ) | while IFS= read -r -d '' f; do
        mkdir -p "$S/data/nemesis/apps/aa.ev.model/$(dirname "$f")"
        case "$f" in
            ./files/*) cp "packages/aa-ev-model/$f" "$S/data/nemesis/apps/aa.ev.model/$f" ;;
            *) sed "s|/system/|$S/system/|g; s|/data/|$S/data/|g; s|/cache/|$S/cache/|g; s|/storage/|$S/storage/|g; s|/mnt/|$S/mnt/|g" \
                "packages/aa-ev-model/$f" > "$S/data/nemesis/apps/aa.ev.model/$f" ;;
        esac
    done

    # Stub car commands.
    cat > "$S/system/xbin/nemesis" <<'EOF'
#!/bin/bash
exit 0
EOF
    cat > "$S/data/nemesis/bin/md5" <<'EOF'
#!/bin/bash
md5sum "$1"
EOF
    cat > "$S/system/bin/pm" <<'EOF'
#!/bin/bash
# record calls so tests can assert hold/release ordering
echo "pm $*" >> "$PM_LOG"
[ "$1" = path ] && { echo "package:/system/app/x.apk"; exit 0; }
exit 0
EOF
    cat > "$S/system/bin/getprop" <<'EOF'
#!/bin/bash
[ "$1" = sys.boot_completed ] && echo 1
exit 0
EOF
    cat > "$S/system/bin/dexopt" <<'EOF'
#!/bin/bash
# --preopt <apk> <odex> <flags>: pretend to optimize
echo "FAKEODEX of $2" > "$3"
exit 0
EOF
    cat > "$S/system/bin/am" <<'EOF'
#!/bin/bash
exit 0
EOF
    cat > "$S/system/bin/reboot" <<'EOF'
#!/bin/bash
# record the reboot instead of doing it
echo reboot >> "$REBOOT_LOG"
exit 0
EOF
    cat > "$S/system/bin/sleep" <<'EOF'
#!/bin/bash
exit 0
EOF
    # toolbox-style `ls -l FILE`: perms owner group size date time name
    cat > "$S/system/bin/ls" <<'EOF'
#!/bin/bash
if [ "$1" = -l ] && [ -f "$2" ]; then
    echo "-rw-r--r-- root root $(/usr/bin/stat -c %s "$2") 2026-10-29 00:00 ${2##*/}"
    exit 0
fi
[ "$1" = -l ] && exit 1
exec /bin/ls "$@"
EOF
    chmod +x "$S/system/bin/ls"
    chmod +x "$S/system/xbin/nemesis" "$S/data/nemesis/bin/md5" "$S/system/bin/pm" \
             "$S/system/bin/getprop" "$S/system/bin/dexopt" "$S/system/bin/am" \
             "$S/system/bin/reboot" "$S/system/bin/sleep"
    # a USB drive holding this release's descriptor
    mkdir -p "$S/storage/usb0"
    cp release/NEMESIS.data "$S/storage/usb0/NEMESIS.data"

    # The scripts reset PATH to car dirs; give them the host coreutils they use.
    local _t
    for _t in cp mv rm rmdir mkdir chmod chown cmp grep tail touch cat sync md5sum dirname date; do
        ln -sf "$(command -v "$_t")" "$S/system/bin/$_t"
    done
}

run_script() {
    local S="$1" script="$2"
    # NEMESIS runs package hooks with cwd inside the (staging) package tree.
    ( cd "$S/data/nemesis/apps/aa.ev.model" && \
        PM_LOG="$S/pm.log" REBOOT_LOG="$S/reboot.log" \
        PATH="$S/system/xbin:$S/system/bin:$S/data/nemesis/bin:$PATH" \
        /bin/bash "scripts/$script" 2>&1 )
}

# last pm call on the package was "enable" (i.e. the app was released)
pm_released() {
    [ -f "$1/pm.log" ] && \
    [ "$(grep -E "pm (disable|enable) com.google.android.projection.sink" "$1/pm.log" | tail -1)" \
       = "pm enable com.google.android.projection.sink" ]
}

pass=0 fail=0
check() { # name condition
    if [ "$2" = 0 ]; then pass=$((pass+1)); echo "ok: $1";
    else fail=$((fail+1)); echo "FAIL: $1"; fi
}

echo "=== 1. fresh install over Leafia v1.0.5 ==="
S=$T/s1
setup_sandbox "$S"
run_script "$S" pre.sh > "$T/s1.pre.log" 2>&1
check "pre.sh accepts Leafia base" $?
run_script "$S" post.sh > "$T/s1.post.log" 2>&1
check "post.sh installs" $?
[ "$(md5_of "$S/system/app/HKMC_GPM.apk")" = "$SUM_PAY_APK" ]
check "live APK is our payload" $?
[ "$(md5_of "$S/system/lib/libautoreceiver_infobank.so")" = "$SUM_PAY_LIB" ]
check "live lib is our payload" $?
[ -s "$S/system/app/HKMC_GPM.odex" ]
check "odex regenerated" $?
[ -s "$S/data/dalvik-cache/system@app@HKMC_GPM.apk@classes.dex" ]
check "dalvik cache prefilled" $?
[ "$(md5_of "$S/data/nemesis/backup/aa.ev.model/HKMC_GPM.apk")" = "$SUM_BASE_APK" ]
check "snapshot has Leafia APK" $?
[ "$(md5_of "$S/data/nemesis/backup/aa.ev.model/libautoreceiver_infobank.so")" = "$SUM_BASE_LIB" ]
check "snapshot has Leafia lib" $?
pm_released "$S"
check "post.sh leaves GPM enabled (apps_release on success)" $?
[ "$(cat "$S/reboot.log" 2>/dev/null)" = reboot ]
check "post.sh reboots once at the end" $?
[ ! -f "$S/storage/usb0/NEMESIS.data" ] && [ -f "$S/storage/usb0/NEMESIS.data.installed" ]
check "USB descriptor retired so a drive left in does not reinstall" $?

echo "=== 2. uninstall restores byte for byte ==="
run_script "$S" preun.sh > "$T/s1.preun.log" 2>&1
check "preun.sh runs" $?
[ "$(md5_of "$S/system/app/HKMC_GPM.apk")" = "$SUM_BASE_APK" ]
check "APK restored byte for byte" $?
[ "$(md5_of "$S/system/app/HKMC_GPM.odex")" = "$SUM_BASE_ODEX" ]
check "odex restored byte for byte" $?
[ "$(md5_of "$S/system/lib/libautoreceiver_infobank.so")" = "$SUM_BASE_LIB" ]
check "lib restored byte for byte" $?
[ ! -d "$S/data/nemesis/backup/aa.ev.model" ]
check "snapshot removed" $?
pm_released "$S"
check "preun.sh leaves GPM enabled" $?

echo "=== 3. uninstall after Leafia was removed in between ==="
S=$T/s3
setup_sandbox "$S"
run_script "$S" pre.sh > /dev/null 2>&1 && run_script "$S" post.sh > /dev/null 2>&1
# Leafia uninstalled: live files revert to stock (different md5s).
echo "STOCK APK" > "$S/system/app/HKMC_GPM.apk"
echo "STOCK ODEX" > "$S/system/app/HKMC_GPM.odex"
echo "STOCK LIB" > "$S/system/lib/libautoreceiver_infobank.so"
run_script "$S" preun.sh > "$T/s3.preun.log" 2>&1
check "preun.sh runs" $?
[ "$(cat "$S/system/app/HKMC_GPM.apk")" = "STOCK APK" ]
check "stock APK left alone" $?
[ "$(cat "$S/system/lib/libautoreceiver_infobank.so")" = "STOCK LIB" ]
check "stock lib left alone" $?
[ ! -d "$S/data/nemesis/backup/aa.ev.model" ]
check "snapshot removed" $?

echo "=== 4. reinstall over our own payloads ==="
S=$T/s4
setup_sandbox "$S"
run_script "$S" pre.sh > /dev/null 2>&1 && run_script "$S" post.sh > /dev/null 2>&1
run_script "$S" pre.sh > /dev/null 2>&1 && run_script "$S" post.sh > "$T/s4.post.log" 2>&1
check "second install over ours succeeds" $?
run_script "$S" preun.sh > /dev/null 2>&1
[ "$(md5_of "$S/system/app/HKMC_GPM.apk")" = "$SUM_BASE_APK" ]
check "uninstall after reinstall still restores Leafia" $?

echo "=== 5. refusal on unknown live files ==="
S=$T/s5
setup_sandbox "$S"
echo "SOMETHING ELSE" > "$S/system/app/HKMC_GPM.apk"
run_script "$S" pre.sh > "$T/s5.pre.log" 2>&1
[ $? -ne 0 ]
check "pre.sh refuses unknown APK" $?
[ "$(md5_of "$S/system/app/HKMC_GPM.odex")" = "$SUM_BASE_ODEX" ]
check "odex untouched" $?

[ ! -d "$S/data/nemesis/build/aa.ev.model" ]
check "no payload left behind after refusal" $?
[ ! -f "$S/reboot.log" ]
check "no reboot after a refused install" $?

echo "=== 6. upgrade over an earlier build ==="
S=$T/s6
setup_sandbox "$S"
run_script "$S" pre.sh > /dev/null 2>&1 && run_script "$S" post.sh > /dev/null 2>&1
echo "EARLIER BUILD APK" > "$S/system/app/HKMC_GPM.apk"   # our lib stays in place
run_script "$S" pre.sh > "$T/s6.pre.log" 2>&1
check "pre.sh accepts an earlier build (rebuilds from the snapshot)" $?
run_script "$S" post.sh > "$T/s6.post.log" 2>&1
check "post.sh installs" $?
[ "$(md5_of "$S/system/app/HKMC_GPM.apk")" = "$SUM_PAY_APK" ]
check "live APK is the new payload" $?
[ ! -d "$S/data/nemesis/build/aa.ev.model" ]
check "payload build dir cleaned up" $?
echo "EARLIER BUILD APK" > "$S/system/app/HKMC_GPM.apk"
run_script "$S" preun.sh > "$T/s6.preun.log" 2>&1
[ "$(md5_of "$S/system/app/HKMC_GPM.apk")" = "$SUM_BASE_APK" ]
check "uninstall restores Leafia APK over an earlier build" $?
[ "$(md5_of "$S/system/lib/libautoreceiver_infobank.so")" = "$SUM_BASE_LIB" ]
check "uninstall restores Leafia lib" $?

echo "=== 7. refusal when the snapshot is not Leafia's ==="
S=$T/s7
setup_sandbox "$S"
run_script "$S" pre.sh > /dev/null 2>&1 && run_script "$S" post.sh > /dev/null 2>&1
_before=$(md5_of "$S/system/app/HKMC_GPM.apk")
echo "DAMAGED" > "$S/data/nemesis/backup/aa.ev.model/HKMC_GPM.odex"
run_script "$S" pre.sh > "$T/s7.pre.log" 2>&1
[ $? -ne 0 ]
check "pre.sh refuses with a damaged snapshot" $?
[ "$(md5_of "$S/system/app/HKMC_GPM.apk")" = "$_before" ]
check "live APK untouched" $?

echo "=== 8. package contents ==="
[ "$(cd packages/aa-ev-model/files && LC_ALL=C ls | LC_ALL=C sort | tr '\n' ' ')" = \
  "HKMC_GPM.apk.lpd MD5SUMS libautoreceiver_infobank.so.lpd lpatch " ]
check "files/ holds only deltas, lpatch and MD5SUMS" $?
! unzip -l dist/aa.ev.model.zip | grep -E '\.(apk|so|odex|dex)$' >/dev/null
check "package zip has no APK/so/odex/dex" $?

echo "=== 9. drive-log offload ==="
S=$T/s9
setup_sandbox "$S"
EV="$S/data/data/com.google.android.projection.sink/files/evlog"
mkdir -p "$EV" "$S/storage/usb1/egmp-aa-ev-logs"
printf '# egmp-aa-ev-log v1\nt_s,soc_half_pct\n0,92\n5,92\n' > "$EV/egmp-NEEV-20261029-001.csv"
run_offload() {
    ( cd "$S/data/nemesis/apps/aa.ev.model" && EVLOG_ONCE=1 \
        PATH="$S/system/xbin:$S/system/bin:$S/data/nemesis/bin:$PATH" \
        /bin/bash svc/evlog-offload.sh 2>&1 )
}
run_offload > "$T/s9.1.log"
cmp "$EV/egmp-NEEV-20261029-001.csv" "$S/storage/usb1/egmp-aa-ev-logs/egmp-NEEV-20261029-001.csv" >/dev/null 2>&1
check "log copied to the opted-in drive" $?
[ ! -e "$S/storage/usb0/egmp-aa-ev-logs" ] && [ "$(ls "$S/storage/usb0")" = NEMESIS.data ]
check "drive without egmp-aa-ev-logs/ untouched" $?
! ls -a "$S/storage/usb1/egmp-aa-ev-logs" | grep -q '\.tmp$'
check "no temp file left" $?
run_offload > "$T/s9.2.log"
[ "$(grep -c 'copied ' "$S/data/nemesis/aa.ev.model.offload.log")" = 1 ]
check "unchanged log not copied again" $?
echo "10,91" >> "$EV/egmp-NEEV-20261029-001.csv"
run_offload > "$T/s9.3.log"
cmp "$EV/egmp-NEEV-20261029-001.csv" "$S/storage/usb1/egmp-aa-ev-logs/egmp-NEEV-20261029-001.csv" >/dev/null 2>&1
check "grown log copied again" $?

echo
echo "passed: $pass, failed: $fail"
[ "$fail" = 0 ]

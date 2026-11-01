#!/bin/sh
# Full build of the aa.ev.model deliverables, for every car in tools/cars.txt
# whose files are in private/<car>/ (docs/PORTING.md):
#   1. build the energy-model template    -> dist/ev_batch.bin + EvModelTemplate.java
#   2. compile the GPM sender classes     -> gpm-ev/build/smali
#   per car:
#   3. patch the AA receiver library      -> dist/<car>/libautoreceiver_infobank.so
#   4. patch that car's GPM smali tree    -> build/cars/<car>/gpm-smali
#   5. assemble classes.dex, add it to the car's stock APK
#                                       -> dist/<car>/HKMC_GPM.classes.dex, HKMC_GPM.apk
#   6. build the NEMESIS package (deltas for every car) -> dist/aa.ev.model.zip
#
# One model and one set of classes serve every car: EvVehicle picks the car's
# road load at runtime, EvFirmware the binder transaction numbers.
#
# private/<car>/ holds HKMC_GPM.apk, HKMC_GPM.odex, libautoreceiver_infobank.so
# (the live files with Leafia v1.0.5 installed), stock/HKMC_GPM.apk (Leafia's
# backup of the stock APK) and optionally smali-gpm/ (baksmali of the Leafia
# APK's classes.dex; made here if missing). For the Ioniq 5 the original
# layout (private/car-current-leafia105/, private/car-neev-260507/) still works.
#
# Requires: ~/tools/env.sh (Android SDK), smali/baksmali, capstone and bsdiff4
# in PY (e.g. /tmp/opencode/ev-venv/bin/python), qemu-arm-static (runs the
# shipped ARM lpatch), unzip, protoc for checks. zip is optional.
#
# On Windows (Git Bash), point these at the tools instead, e.g.:
#   PY=.../python.exe LPATCH=/c/Tools/lpatch-host/lpatch.exe ANDROID_HOME=/c/Tools/android-sdk
#   SMALI_CMD="java -jar /c/Tools/smali/smali-2.5.2.jar"
#   BAKSMALI="java -jar /c/Tools/smali/baksmali-2.5.2.jar"
# (a host lpatch: build third_party/lpatch/lpatch.c with -DHOST; on Windows
# its files must be opened with O_BINARY, see docs/PORTING.md).
set -e
cd "$(dirname "$0")/.."
PY="${PY:-/tmp/opencode/ev-venv/bin/python}"
LPATCH="${LPATCH:-qemu-arm-static third_party/lpatch/lpatch}"
SMALI_CMD="${SMALI_CMD:-smali}"
BAKSMALI="${BAKSMALI:-baksmali}"
export PY LPATCH BAKSMALI
[ -z "$ANDROID_HOME" ] && . "$HOME/tools/env.sh"

echo "== 1. energy model template =="
"$PY" tools/build_ioniq5_model.py   # one model; EvVehicle patches road load per car
protoc -I reference --decode=vem.VehicleEnergyModel vehicle_energy_model.proto \
    < dist/ev_model.bin > /dev/null   # parse check

echo "== 2. compile sender classes =="
sh gpm-ev/build.sh

md5() { md5sum "$1" | cut -d' ' -f1; }

# stage_car <car>: copy the car's inputs to build/cars/<car>/{base,stock} and
# set SMALI to its Leafia smali tree. Returns 1 if private/ has no files for it.
stage_car() {
    _car=$1
    B=build/cars/$_car
    P=private/$_car
    rm -rf "$B"
    if [ ! -d "$P" ] && [ "$_car" = neev ] && [ -d private/car-current-leafia105 ]; then
        mkdir -p "$B/base" "$B/stock"
        cp private/car-current-leafia105/HKMC_GPM.apk private/car-current-leafia105/HKMC_GPM.odex \
            private/car-current-leafia105/libautoreceiver_infobank.so "$B/base/"
        cp private/car-neev-260507/LEAFIA/private/stock/app/HKMC_GPM.apk "$B/stock/"
        SMALI=private/car-current-leafia105/smali-gpm
        return 0
    fi
    [ -s "$P/HKMC_GPM.apk" ] || return 1
    mkdir -p "$B/base" "$B/stock"
    cp "$P/HKMC_GPM.apk" "$P/HKMC_GPM.odex" "$P/libautoreceiver_infobank.so" "$B/base/"
    cp "$P/stock/HKMC_GPM.apk" "$B/stock/"
    SMALI=$P/smali-gpm
    if [ ! -d "$SMALI" ]; then
        unzip -p "$P/HKMC_GPM.apk" classes.dex > "$B/leafia-classes.dex"
        $BAKSMALI d -o "$SMALI" "$B/leafia-classes.dex"
    fi
    return 0
}

# check_md5 <file> <want or -> <what>
check_md5() {
    [ "$2" = - ] && return 0
    _got=$(md5 "$1")
    [ "$_got" = "$2" ] || { echo "FATAL: $3 md5 $_got, want $2 (not Leafia v1.0.5's)" >&2; exit 1; }
}

built=""
while read -r car apk odex lib name <&3; do
    case "$car" in ''|'#'*) continue ;; esac
    if ! stage_car "$car"; then
        echo "-- $car ($name): no files in private/$car, skipped"
        continue
    fi
    echo "== $car: $name =="
    B=build/cars/$car
    D=dist/$car
    rm -rf "$D"
    mkdir -p "$D"
    check_md5 "$B/base/HKMC_GPM.apk" "$apk" "$car HKMC_GPM.apk"
    check_md5 "$B/base/HKMC_GPM.odex" "$odex" "$car HKMC_GPM.odex"
    check_md5 "$B/base/libautoreceiver_infobank.so" "$lib" "$car libautoreceiver_infobank.so"

    echo "-- 3. library patch"
    "$PY" tools/patch_library.py "$B/base/libautoreceiver_infobank.so" "$D/libautoreceiver_infobank.so"

    echo "-- 4. patch GPM smali"
    "$PY" tools/patch_gpm_smali.py "$SMALI" "$B/gpm-smali"

    echo "-- 5. assemble dex + APK"
    $SMALI_CMD a -j 1 --api 18 -o "$D/HKMC_GPM.classes.dex" "$B/gpm-smali"
    # Same construction as Leafia's GPM: the stock APK (no classes.dex) plus a
    # stored classes.dex, via lpatch zipadd. Deterministic, so the md5 the
    # package expects on the car is fixed.
    $LPATCH zipadd "$B/stock/HKMC_GPM.apk" "$D/HKMC_GPM.classes.dex" "$D/HKMC_GPM.apk"

    ( cd "$B/base" && md5sum -t HKMC_GPM.apk HKMC_GPM.odex libautoreceiver_infobank.so ) > "$D/BASE"
    echo "$name" > "$D/NAME"
    built="$built $car"
done 3< tools/cars.txt

[ -n "$built" ] || { echo "no car has files in private/ - see docs/PORTING.md" >&2; exit 1; }

echo "== 6. NEMESIS package:$built =="
sh packages/aa-ev-model/build.sh $built

echo
echo "built:"
for car in $built; do
    md5sum "dist/$car/libautoreceiver_infobank.so" "dist/$car/HKMC_GPM.apk"
done
md5sum dist/aa.ev.model.zip
echo
echo "verify the package scripts: bash tools/test_package.sh"

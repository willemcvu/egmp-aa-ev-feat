#!/bin/sh
# Compile the EvModelSender/EvModelTemplate, EV log (EvDriveLog, EvLogger,
# EvRowAgg, EvCar) and preconditioning (NavProto,
# PrecondCore, Precond) Java sources to smali in
# gpm-ev/build/smali, ready for tools/patch_gpm_smali.py.
# Needs ~/tools/env.sh (Android SDK with platforms/android-19 + build-tools/34).
set -e
cd "$(dirname "$0")"
[ -z "$ANDROID_HOME" ] && . "$HOME/tools/env.sh"

PKG=com/google/android/projection/protocol
rm -rf build/classes build/dex build/smali
mkdir -p build/classes build/dex

javac -source 8 -target 8 \
    -bootclasspath "$ANDROID_HOME/platforms/android-19/android.jar" \
    -d build/classes \
    src/$PKG/EvModelSender.java src/$PKG/EvModelTemplate.java src/$PKG/EvLogger.java \
    src/$PKG/EvCar.java src/$PKG/EvDriveLog.java src/$PKG/EvRowAgg.java \
    src/$PKG/NavProto.java src/$PKG/PrecondCore.java src/$PKG/Precond.java \
    stubs/$PKG/SensorSource.java \
    stubs/com/hkmc/cluster/NavigationCluster.java \
    stubs/com/hkmc/cluster/NavigationClusterListener.java

# Dex only our classes, never the stubs (the real ones are in the GPM tree /
# the car's automotive.jar).
"$ANDROID_HOME/build-tools/34.0.0/d8" --min-api 19 --output build/dex \
    build/classes/$PKG/EvModelSender*.class \
    "build/classes/$PKG/EvModelTemplate.class" \
    build/classes/$PKG/EvLogger*.class \
    build/classes/$PKG/EvCar*.class \
    build/classes/$PKG/EvDriveLog*.class \
    build/classes/$PKG/EvRowAgg*.class \
    build/classes/$PKG/NavProto*.class \
    build/classes/$PKG/Precond*.class

baksmali d build/dex/classes.dex -o build/smali

# Every compiled class except the stubs must be in the dex: a class left
# out of the d8 list above only fails on the car (NoClassDefFoundError).
missing=""
for c in $(cd build/classes && find . -name '*.class' | sed 's|^\./||; s|\.class$||' | sort); do
    case "$c" in
        "$PKG/SensorSource"|com/hkmc/*) continue ;;
    esac
    [ -f "build/smali/$c.smali" ] || missing="$missing $c"
done
if [ -n "$missing" ]; then
    echo "classes compiled but not dexed (add them to the d8 list):$missing" >&2
    exit 1
fi
echo "smali written to gpm-ev/build/smali"

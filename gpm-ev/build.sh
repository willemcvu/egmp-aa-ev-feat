#!/bin/sh
# Compile the EvModelSender/EvModelTemplate Java sources to smali in
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
    stubs/$PKG/SensorSource.java \
    stubs/com/hkmc/cluster/NavigationCluster.java \
    stubs/com/hkmc/cluster/NavigationClusterListener.java

# Dex only our classes, never the stubs (the real ones are in the GPM tree /
# the car's automotive.jar).
"$ANDROID_HOME/build-tools/34.0.0/d8" --min-api 19 --output build/dex \
    build/classes/$PKG/EvModelSender*.class \
    "build/classes/$PKG/EvModelTemplate.class"

baksmali d build/dex/classes.dex -o build/smali
echo "smali written to gpm-ev/build/smali"

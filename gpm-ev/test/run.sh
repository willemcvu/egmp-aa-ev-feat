#!/bin/sh
# Host tests: the preconditioning logic (NavProto + PrecondCore with a fake
# vehicle and journal, plain JVM), and EvModelSender's log row (android.jar
# plus small Log/SystemClock shims in test/shim), and the 4.1/8.1 capacity
# scaling (ScaleTest).
#   sh gpm-ev/test/run.sh          (VERBOSE=1 to see the EvPrecond log lines)
set -e
cd "$(dirname "$0")/.."
PKG=com/google/android/projection/protocol
OUT=build/test-classes
rm -rf "$OUT"
mkdir -p "$OUT"
javac -nowarn -d "$OUT" \
    src/$PKG/NavProto.java src/$PKG/PrecondCore.java test/PrecondTest.java
java -cp "$OUT" com.google.android.projection.protocol.PrecondTest

# EvModelSender.logRow on the JVM (car services absent -> all unknown), and
# the charge curve/table capacity scaling (scaleTemplate)
[ -z "$ANDROID_HOME" ] && . "$HOME/tools/env.sh"
SOUT=build/test-sender
rm -rf "$SOUT"
mkdir -p "$SOUT"
javac -nowarn -d "$SOUT" -cp "$ANDROID_HOME/platforms/android-19/android.jar" \
    test/shim/android/util/Log.java test/shim/android/os/SystemClock.java \
    src/$PKG/EvModelSender.java src/$PKG/EvModelTemplate.java src/$PKG/EvLogger.java \
    src/$PKG/NavProto.java src/$PKG/PrecondCore.java src/$PKG/Precond.java \
    stubs/$PKG/SensorSource.java stubs/com/hkmc/cluster/NavigationCluster.java \
    stubs/com/hkmc/cluster/NavigationClusterListener.java test/SenderRowTest.java \
    test/ScaleTest.java
java -cp "$SOUT:$ANDROID_HOME/platforms/android-19/android.jar" \
    com.google.android.projection.protocol.SenderRowTest
java -cp "$SOUT:$ANDROID_HOME/platforms/android-19/android.jar" \
    com.google.android.projection.protocol.ScaleTest

#!/bin/sh
# Host test for the preconditioning logic: NavProto + PrecondCore with a fake
# vehicle and journal. Plain JVM, no Android classes needed.
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

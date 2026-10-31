#!/usr/bin/env python3
"""Check aa.ev.model's raw binder transaction numbers against a car's own framework.

usage: check_framework_txns.py <smali root>   (baksmali x output of automotive, com.hkmc.ext.hcan, ...)

EvCar and Precond call car services by transaction number, taken from the Ioniq 5 US 260507
framework. A number is right on another car only if the same method has the same
TRANSACTION_ value in that car's AIDL stub.
"""
import os
import re
import sys

# interface, transaction used, expected method (EvCar/Precond and docs),
# what it does (R = read, W = write to the car, L = listener callback we receive)
EXPECTED = [
    ("com/hkmc/hev/IHEVService", 3, "getHEVStatus", "R", "SOC, BMS energy, flags 44/94"),
    ("com/hkmc/hev/IHEVService", 62, "setBattCondEvStation", "W", "precond: station event"),
    ("com/hkmc/hev/IHEVService", 65, "setBattCondRouteDis", "W", "precond: km to charger"),
    ("com/hkmc/hev/IHEVService", 66, "setBattCondRemainTime", "W", "precond: min to charger"),
    ("com/hkmc/bcm/IBCMService", 1, "registerBCMListener", "R", "drive-mode listener"),
    ("com/hkmc/bcm/IBCMService", 109, None, "R", "precond: conditioning indicator (name not documented)"),
    ("com/hkmc/bcm/IBCMListener", 0x11, "onDriveModeChanged", "L", "drive mode callback"),
    ("com/hkmc/datc/IDATCService", 0x06, "isUnitCelsius", "R", "climate unit"),
    ("com/hkmc/datc/IDATCService", 0x29, "getVRClimateStatus", "R", "climate state"),
    ("com/hkmc/telematics/hscan/tm_interface/ITM_HSCANService", 0x0b, "getCarSpeed", "R", "speed"),
    ("com/hkmc/telematics/hscan/tm_interface/ITM_HSCANService", 0x2f, "getVIN", "R", "VIN (model part only logged)"),
    ("com/hkmc/telematics/hscan/tm_interface/ITM_HSCANService", 0xd6, "getXEV_Bms_GDS_SOH", "R", "battery SOH"),
]

FIELD = re.compile(r"^\.field static final TRANSACTION_(\w+):I = (0x[0-9a-f]+|\d+)", re.M)


def find_stub(root, iface):
    for base, _, files in os.walk(root):
        for f in files:
            path = os.path.join(base, f).replace(os.sep, "/")
            if path.endswith(iface + "$Stub.smali"):
                return path
    return None


def table(path):
    by_name, by_num = {}, {}
    for name, num in FIELD.findall(open(path, encoding="utf-8").read()):
        n = int(num, 0)
        by_name[name] = n
        by_num[n] = name
    return by_name, by_num


def main():
    root = sys.argv[1]
    bad = 0
    for iface, txn, method, kind, what in EXPECTED:
        stub = find_stub(root, iface)
        short = iface.rsplit("/", 1)[1]
        if not stub:
            print(f"MISSING {short}: stub not found")
            bad += 1
            continue
        by_name, by_num = table(stub)
        at = by_num.get(txn)
        if method is None:
            print(f"CHECK   {short:18} {txn:>4} [{kind}] EV6 has {at!r} here ({what})")
            continue
        ev6 = by_name.get(method)
        ok = ev6 == txn
        bad += not ok
        print(f"{'OK     ' if ok else 'DIFFER '} {short:18} {txn:>4} [{kind}] {method}: EV6 {ev6} ({what})"
              + ("" if ok else f"; EV6 has {at!r} at {txn}"))
    print(f"\n{bad} mismatch(es)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())

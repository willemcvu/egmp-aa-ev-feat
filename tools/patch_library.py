#!/usr/bin/env python3
"""Patch libautoreceiver_infobank.so for Android Auto EV features.

Applies the three instruction patches designed in RESEARCH.md section 5d on top
of Leafia v1.0.5's patched library (md5 c08410a8d32a615b1aa805cd8a243d52):

  1. SensorType_IsValid (0x144ec2): cmp r0,#20 -> cmp r0,#22
     so a type-23 SensorRequest parses instead of being dropped.
  2. handleSensorRequest default branch (0xbb29e): mvn.w r7,#8 -> mov.w r7,#0
     so registered sensors outside 1..21 get a SUCCESS reply instead of -9.
  3. SensorSource::reportDiagnosticsData (0xbb638): push.w -> b.w 0xb1972
     tail-branch into ProtocolEndpointBase::queueOutgoing. The JNI wrapper
     nativeReportDiagnosticsData([B)I then sends whatever bytes Java passes
     as one whole AA message. Java must prepend the message type (80 03 for
     SensorBatch); the queue copies the data into frames before returning.

All sites are in the first PT_LOAD (file offset == virtual address) and are
byte-identical in stock and Leafia builds, so Leafia's own changes are
untouched.

Usage: patch_library.py [input.so] [output.so]
Defaults: private/car-current-leafia105/libautoreceiver_infobank.so ->
          dist/libautoreceiver_infobank.so

Refuses to run if the input md5 or the original bytes at any site differ.
Prints a byte diff of every changed region and disassembles each site before
and after so the result can be checked by eye.
"""

import hashlib
import struct
import sys
from pathlib import Path

from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB

BASE_MD5 = "c08410a8d32a615b1aa805cd8a243d52"  # Leafia v1.0.5 library

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_IN = ROOT / "private/car-current-leafia105/libautoreceiver_infobank.so"
DEFAULT_OUT = ROOT / "dist/libautoreceiver_infobank.so"


def encode_b_w(from_addr: int, to_addr: int) -> bytes:
    """Encode a Thumb-2 B.W (T4) at from_addr branching to to_addr."""
    offset = to_addr - (from_addr + 4)
    assert offset % 2 == 0 and -(1 << 24) <= offset < (1 << 24), hex(offset)
    x = (offset >> 1) & 0xFFFFFF  # 24-bit two's complement halfword offset
    s = (x >> 23) & 1
    i1, i2 = (x >> 22) & 1, (x >> 21) & 1
    j1, j2 = (i1 ^ s) ^ 1, (i2 ^ s) ^ 1
    hw1 = 0xF000 | (s << 10) | ((x >> 11) & 0x3FF)
    hw2 = 0x9000 | (j1 << 13) | (j2 << 11) | (x & 0x7FF)
    return struct.pack("<HH", hw1, hw2)


QUEUE_OUTGOING = 0xB1972  # ProtocolEndpointBase::queueOutgoing(this, data, len)

# (offset, original bytes, patched bytes, description)
PATCHES = [
    (
        0x144EC2,
        bytes.fromhex("1428"),  # cmp r0, #20
        bytes.fromhex("1628"),  # cmp r0, #22  -> types 1..23 valid
        "SensorType_IsValid: accept sensor types 1..23",
    ),
    (
        0xBB29E,
        bytes.fromhex("6ff00807"),  # mvn.w r7, #8   (status = -9)
        bytes.fromhex("4ff00007"),  # mov.w r7, #0   (status = SUCCESS)
        "handleSensorRequest default: reply SUCCESS, not -9",
    ),
    (
        0xBB638,
        bytes.fromhex("2de9f041"),  # push.w {r4-r8, lr}
        encode_b_w(0xBB638, QUEUE_OUTGOING),
        "reportDiagnosticsData: tail-branch into queueOutgoing",
    ),
]

# Regions to disassemble for the before/after dump: (start, length)
SHOW = [(0x144EC0, 12), (0xBB29A, 12), (0xBB638, 8), (QUEUE_OUTGOING, 24)]


def disasm(data: bytes, addr: int, length: int) -> list[str]:
    md = Cs(CS_ARCH_ARM, CS_MODE_THUMB)
    return [
        f"  {i.address:#08x}: {i.bytes.hex():<10} {i.mnemonic} {i.op_str}"
        for i in md.disasm(data[addr : addr + length], addr)
    ]


def main() -> int:
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_IN
    dst = Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_OUT

    data = bytearray(src.read_bytes())
    md5 = hashlib.md5(data).hexdigest()
    print(f"input : {src}\n        md5 {md5}")
    if md5 != BASE_MD5:
        print(f"FATAL: expected Leafia v1.0.5 base md5 {BASE_MD5}, refusing.")
        return 1

    for off, old, new, _desc in PATCHES:
        cur = bytes(data[off : off + len(old)])
        if cur != old:
            print(
                f"FATAL: bytes at {off:#x} are {cur.hex()}, expected {old.hex()}."
                " Base file changed?"
            )
            return 1

    for off, old, new, _desc in PATCHES:
        data[off : off + len(old)] = new

    # Verify: every byte that changed must lie inside one of the patch ranges
    # (bytes within a range may keep their value, e.g. the 0x28 in cmp r0,#n).
    orig = src.read_bytes()
    assert len(orig) == len(data), "patch must not change file size"
    allowed = {
        i for off, old, _new, _d in PATCHES for i in range(off, off + len(old))
    }
    diffs = [i for i in range(len(orig)) if data[i] != orig[i]]
    if not diffs or not set(diffs) <= allowed:
        print("FATAL: changed bytes are not exactly the intended sites.")
        return 1

    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_bytes(bytes(data))
    print(f"output: {dst}\n        md5 {hashlib.md5(data).hexdigest()}")
    print(f"changed {len(diffs)} bytes in {len(PATCHES)} sites, nothing else:\n")

    final = bytes(data)
    for off, old, new, desc in PATCHES:
        print(f"* {off:#x}: {old.hex()} -> {new.hex()}\n  {desc}")
    print("\nDisassembly after patching:")
    for addr, length in SHOW:
        print(f"  --- {addr:#x} ---")
        print("\n".join(disasm(final, addr, length)))

    # Sanity: the branch must decode to the queue function.
    md = Cs(CS_ARCH_ARM, CS_MODE_THUMB)
    insn = next(md.disasm(final[0xBB638 : 0xBB63C], 0xBB638))
    assert insn.mnemonic == "b.w" and insn.op_str == "#0xb1972", (
        insn.mnemonic,
        insn.op_str,
    )
    print("\nOK: reportDiagnosticsData now tail-branches to queueOutgoing.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

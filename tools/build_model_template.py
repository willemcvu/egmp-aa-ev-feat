#!/usr/bin/env python3
"""Build the EV energy-model template for the GPM sender (prototype stage).

Starts from the Ford sample (reference/ford_ev_model.bin, a canonical protobuf
per ENERGY-MODEL.md) and:

  * battery.capacity_wh (1.4)        := 77400  (Ioniq 5 LR, 2022 US)
  * battery.reserve_energy_wh (1.2)  := 2400   (~3% of capacity)
  * battery.current_energy_wh (1.3)  := PLACEHOLDER, re-encoded as a 5-byte
                                        (non-canonical but legal) varint so the
                                        Java sender can overwrite it in place
                                        with any value 0..2^35-1 at a fixed
                                        offset, with no length changes.

The result is wrapped as an Android Auto message:
  [0x80 0x03]            SensorBatch message type, big-endian
  [0xBA 0x01] + varint   SensorBatch field 23, length-delimited
  <model bytes>

Outputs:
  dist/ev_model.bin      bare VehicleEnergyModel (for protoc --decode checks)
  dist/ev_batch.bin      the full AA message the head unit will queue
  gpm-ev/src/com/google/android/projection/protocol/EvModelTemplate.java
                         base64 of ev_batch.bin + offset/width/placeholder

Run tools/check_model.sh afterwards to verify the round-trip with protoc.
"""

import base64
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FORD = ROOT / "reference/ford_ev_model.bin"
OUT_MODEL = ROOT / "dist/ev_model.bin"
OUT_BATCH = ROOT / "dist/ev_batch.bin"
OUT_JAVA = (
    ROOT
    / "gpm-ev/src/com/google/android/projection/protocol/EvModelTemplate.java"
)

CAPACITY_WH = 77400
RESERVE_WH = 2400
PLACEHOLDER_WH = 50000  # overwritten at runtime; also the no-signal default
ENERGY_WIDTH = 5


def read_varint(buf: bytes, off: int) -> tuple[int, int]:
    """Return (value, offset_after)."""
    value = 0
    shift = 0
    while True:
        b = buf[off]
        off += 1
        value |= (b & 0x7F) << shift
        if not b & 0x80:
            return value, off
        shift += 7
        assert shift < 70


def write_varint(value: int) -> bytes:
    out = bytearray()
    while True:
        b = value & 0x7F
        value >>= 7
        if value:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def fixed_varint(value: int, width: int) -> bytes:
    """Non-canonical varint of exactly width bytes (trailing zero groups)."""
    out = bytearray()
    for i in range(width):
        b = value & 0x7F
        value >>= 7
        out.append(b if i == width - 1 else b | 0x80)
    assert value == 0, "value does not fit"
    return bytes(out)


class Field:
    """A parsed length-delimited or varint field we may edit in place."""

    def __init__(self, buf, tag_off, val_off, val_len, wire):
        self.buf = buf
        self.tag_off = tag_off  # offset of the tag varint
        self.val_off = val_off  # offset of payload (after len varint for wt2)
        self.val_len = val_len
        self.wire = wire
        self.len_off = None  # set for wt2: offset of the length varint
        self.len_len = None


def parse_fields(buf: bytes, start: int, end: int) -> dict[int, Field]:
    fields = {}
    off = start
    while off < end:
        tag_off = off
        tag, off = read_varint(buf, off)
        num, wire = tag >> 3, tag & 7
        assert wire in (0, 1, 2, 5), f"unexpected wire type {wire} at {tag_off:#x}"
        f = Field(buf, tag_off, None, None, wire)
        if wire == 0:
            _, off2 = read_varint(buf, off)
            f.val_off, f.val_len = off, off2 - off
            off = off2
        elif wire == 1:
            f.val_off, f.val_len = off, 8
            off += 8
        elif wire == 5:
            f.val_off, f.val_len = off, 4
            off += 4
        else:
            f.len_off = off
            length, off = read_varint(buf, off)
            f.len_len = off - f.len_off
            f.val_off, f.val_len = off, length
            off += length
        fields[num] = f
    return fields


def replace_length_prefix_growth(data: bytearray, f: Field, delta: int) -> None:
    """Grow a wt2 field's length prefix by delta (must stay same varint size)."""
    assert f.len_len == 1 and f.val_len + delta < 0x80, "1-byte length assumed"
    data[f.len_off] = f.val_len + delta


def main() -> int:
    raw = FORD.read_bytes()  # SensorBatch{23: VehicleEnergyModel} (no msg header)
    outer = parse_fields(raw, 0, len(raw))
    assert set(outer) == {23}, f"unexpected outer fields {sorted(outer)}"
    batch_field = outer[23]
    assert batch_field.len_len == 2, "2-byte outer length assumed"
    mstart = batch_field.val_off
    model = bytearray(raw[mstart : mstart + batch_field.val_len])
    top = parse_fields(model, 0, len(model))
    assert set(top) == {1, 2, 3, 4, 5, 7, 8, 9}, f"unexpected top fields {sorted(top)}"
    batt = top[1]
    bf = parse_fields(model, batt.val_off, batt.val_off + batt.val_len)
    assert set(bf) == {2, 3, 4, 6, 7}, f"unexpected battery fields {sorted(bf)}"

    def quantity_varint(qf: Field) -> Field:
        qf_fields = parse_fields(model, qf.val_off, qf.val_off + qf.val_len)
        assert set(qf_fields) == {1}, "Quantity with only field 1 expected"
        return qf_fields[1]

    reserve = quantity_varint(bf[2])
    current = quantity_varint(bf[3])
    capacity = quantity_varint(bf[4])

    old_reserve, _ = read_varint(model, reserve.val_off)
    old_current, _ = read_varint(model, current.val_off)
    old_capacity, _ = read_varint(model, capacity.val_off)
    print(f"orig: reserve={old_reserve} current={old_current} capacity={old_capacity}")
    assert (old_reserve, old_current, old_capacity) == (3175, 68378, 92403)

    # Same-width replacements first (no length changes).
    for fld, new_val, name in (
        (reserve, RESERVE_WH, "reserve"),
        (capacity, CAPACITY_WH, "capacity"),
    ):
        enc = write_varint(new_val)
        assert len(enc) == fld.val_len, f"{name} varint width changed"
        model[fld.val_off : fld.val_off + fld.val_len] = enc

    # current_energy: widen 3-byte varint to ENERGY_WIDTH bytes in place.
    delta = ENERGY_WIDTH - current.val_len
    assert delta > 0
    enc = fixed_varint(PLACEHOLDER_WH, ENERGY_WIDTH)
    model[current.val_off : current.val_off + current.val_len] = enc
    # Fix the two enclosing 1-byte length prefixes: Quantity, then Battery.
    replace_length_prefix_growth(model, bf[3], delta)
    replace_length_prefix_growth(model, batt, delta)
    # Energy varint absolute offset within the model is unchanged by the splice.
    energy_off_in_model = current.val_off

    # Sanity: re-parse the edited model and check values.
    top2 = parse_fields(model, 0, len(model))
    batt2 = top2[1]
    bf2 = parse_fields(model, batt2.val_off, batt2.val_off + batt2.val_len)
    for num, want in ((2, RESERVE_WH), (3, PLACEHOLDER_WH), (4, CAPACITY_WH)):
        qf = parse_fields(model, bf2[num].val_off, bf2[num].val_off + bf2[num].val_len)[1]
        got, after = read_varint(model, qf.val_off)
        assert got == want and after == qf.val_off + qf.val_len, (num, got)
    print(
        f"new : reserve={RESERVE_WH} current={PLACEHOLDER_WH} (5-byte varint) "
        f"capacity={CAPACITY_WH}, model {len(model)} bytes"
    )

    # Re-wrap: [0x80 0x03] message type + SensorBatch field 23 + model.
    inner = write_varint(23 << 3 | 2) + write_varint(len(model)) + bytes(model)
    batch = b"\x80\x03" + inner
    assert len(write_varint(len(model))) == 2, "outer length must stay 2 bytes"
    energy_off = energy_off_in_model + (len(batch) - len(model))
    print(f"batch {len(batch)} bytes, energy varint at template offset {energy_off}")

    OUT_MODEL.write_bytes(bytes(model))
    OUT_BATCH.write_bytes(batch)

    b64 = base64.b64encode(batch).decode()
    lines = [b64[i : i + 100] for i in range(0, len(b64), 100)]
    concat = "\n".join(f'        + "{line}"' for line in lines[1:])
    OUT_JAVA.parent.mkdir(parents=True, exist_ok=True)
    OUT_JAVA.write_text(
        "// GENERATED by tools/build_model_template.py - do not edit.\n"
        "package com.google.android.projection.protocol;\n\n"
        "/** SensorBatch(23) template: Ford model, Ioniq 5 capacity, patchable energy. */\n"
        "final class EvModelTemplate {\n"
        "    static final int CAPACITY_WH = %d;\n"
        "    static final int ENERGY_OFFSET = %d;\n"
        "    static final int ENERGY_WIDTH = %d;\n"
        "    static final int ENERGY_PLACEHOLDER_WH = %d;\n"
        '    static final String TEMPLATE_B64 =\n'
        '        "%s"\n'
        "%s;\n\n"
        "    private EvModelTemplate() {}\n"
        "}\n"
        % (CAPACITY_WH, energy_off, ENERGY_WIDTH, PLACEHOLDER_WH, lines[0], concat)
    )
    print(f"wrote {OUT_MODEL.relative_to(ROOT)}, {OUT_BATCH.relative_to(ROOT)}, "
          f"{OUT_JAVA.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

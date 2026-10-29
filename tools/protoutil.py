#!/usr/bin/env python3
"""Minimal protobuf wire helpers shared by the model-template builders.

Only what the energy-model blobs need: varint read/write, fixed-width
(non-canonical) varints, fixed32/64 skipping, and a field walker that records
offsets so fields can be patched in place.
"""


def read_varint(buf, off):
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


def write_varint(value):
    out = bytearray()
    while True:
        b = value & 0x7F
        value >>= 7
        if value:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def fixed_varint(value, width):
    """Non-canonical varint of exactly width bytes (trailing zero groups)."""
    out = bytearray()
    for i in range(width):
        b = value & 0x7F
        value >>= 7
        out.append(b if i == width - 1 else b | 0x80)
    assert value == 0, "value does not fit"
    return bytes(out)


class Field:
    """A parsed field with offsets, for in-place edits."""

    def __init__(self, buf, tag_off, val_off, val_len, wire):
        self.buf = buf
        self.tag_off = tag_off
        self.val_off = val_off
        self.val_len = val_len
        self.wire = wire
        self.len_off = None  # wire type 2: offset of the length varint
        self.len_len = None


def iter_fields(buf, start, end):
    """Yield (num, Field) for buf[start:end] in wire order, repeats included."""
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
        yield num, f
    assert off == end, "message overruns its length"


def parse_fields(buf, start, end):
    """Map field number -> Field for a message spanning buf[start:end]
    (the last occurrence wins for repeated fields)."""
    return dict(iter_fields(buf, start, end))

#!/usr/bin/env python3
"""Check, clean and merge egmp-aa-ev-feat EV logs (formats v1 and v2, docs/LOGGING.md).

    python3 tools/evlog.py check FILE...      validate and summarise
    python3 tools/evlog.py clean IN OUT       allowlist filter, deterministic rewrite
    python3 tools/evlog.py merge DIR OUT.csv  clean every *.csv in DIR into one table

The allowlist lives in tools/evlog_schema.json. Standard library only.

check exit status: 0 clean, 1 cleaning would remove something, 2 unusable.
"""
import json
import os
import re
import sys

SCHEMA_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "evlog_schema.json")
NAME_RE = re.compile(r"^egmp-[A-Za-z0-9]+-[0-9]{8}-([0-9]+)\.csv$")
INT_RE = re.compile(r"^-?[0-9]+$")
BOOL_RE = re.compile(r"^[01]$")
# VIN model-year letters (character 10), 2010-2039
YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"
# Guess from the first two letters of the product string (Hyundai/Kia project codes).
CAR_GUESS = {"NE": "Ioniq 5", "CE": "Ioniq 6", "CV": "EV6", "MV": "EV9", "JW": "GV60"}


class LogError(Exception):
    """The file can't be used at all (wrong format, no t_s column)."""


def load_schema(path=SCHEMA_PATH):
    with open(path, encoding="utf-8") as f:
        s = json.load(f)
    s["_num"] = re.compile(s["number_pattern"])
    s["_hdr"] = {h["key"]: h for h in s["header"]}
    for h in s["header"]:
        if "pattern" in h:
            h["_re"] = re.compile(h["pattern"])
    s["_col"] = {c["name"]: c for c in s["columns"]}
    for c in s["columns"]:
        if "pattern" in c:
            c["_re"] = re.compile(c["pattern"])
    return s


def split_csv(line):
    """Split one CSV line (RFC 4180 quoting, no embedded newlines)."""
    out, cur, i, n, q = [], [], 0, len(line), False
    while i < n:
        ch = line[i]
        if q:
            if ch == '"':
                if i + 1 < n and line[i + 1] == '"':
                    cur.append('"')
                    i += 1
                else:
                    q = False
            else:
                cur.append(ch)
        elif ch == '"':
            q = True
        elif ch == ",":
            out.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
        i += 1
    out.append("".join(cur))
    return out


def valid_value(schema, spec, v):
    if "_re" in spec:
        return bool(spec["_re"].match(v))
    typ = spec["type"]
    if typ == "int":
        return bool(INT_RE.match(v))
    if typ == "bool":
        return bool(BOOL_RE.match(v))
    return bool(schema["_num"].match(v))


def parse(schema, text):
    """Parse and clean a log. Returns dict with header, columns, rows, removed (list of str)."""
    if text.startswith("﻿"):
        text = text[1:]
    lines = [l[:-1] if l.endswith("\r") else l for l in text.split("\n")]
    lines = [l for l in lines if l.strip() != ""]
    accept = schema.get("accept_magic", [schema["magic"]])
    if not lines or lines[0].rstrip() not in accept:
        raise LogError("first line is not one of: %s" % ", ".join("'%s'" % m for m in accept))
    magic = lines[0].rstrip()
    removed = []
    header = {}
    i = 1
    while i < len(lines) and lines[i].startswith("#"):
        line = lines[i]
        i += 1
        m = re.match(r"^# ([A-Za-z0-9_]+)=(.*)$", line)
        if not m:
            removed.append("header: comment/free-text line")
            continue
        key, val = m.group(1), m.group(2).strip()
        spec = schema["_hdr"].get(key)
        if spec is None:
            removed.append("header: key '%s' (not on allowlist)" % key)
            continue
        ok = schema["_num"].match(val) if spec["type"] == "number" else spec["_re"].match(val)
        if not ok:
            removed.append("header: value of '%s' (not a valid %s)" % (key, spec["type"]))
            continue
        if key in header:
            removed.append("header: duplicate '%s'" % key)
            continue
        header[key] = val
    if i >= len(lines):
        raise LogError("no column header row")
    names = [c.strip() for c in split_csv(lines[i])]
    i += 1
    src = {}  # column name -> source index
    for idx, name in enumerate(names):
        if name not in schema["_col"]:
            removed.append("column '%s' (not on allowlist)" % name)
        elif name in src:
            removed.append("column '%s' (duplicate)" % name)
        else:
            src[name] = idx
    if "t_s" not in src:
        raise LogError("no t_s column")
    cols = [c["name"] for c in schema["columns"] if c["name"] in src]
    rows = []
    bad = {}
    dropped = {"comment": 0, "malformed": 0, "t_s": 0, "order": 0}
    last_t = None
    for line in lines[i:]:
        if line.startswith("#"):
            dropped["comment"] += 1
            continue
        f = split_csv(line)
        if len(f) > len(names):
            dropped["malformed"] += 1
            continue
        f += [""] * (len(names) - len(f))
        row = []
        for c in cols:
            v = f[src[c]].strip()
            if v != "" and not valid_value(schema, schema["_col"][c], v):
                bad[c] = bad.get(c, 0) + 1
                v = ""
            row.append(v)
        t = row[0]  # t_s is always the first schema column
        if t == "":
            dropped["t_s"] += 1
            continue
        tf = float(t)
        if last_t is not None and tf <= last_t:
            dropped["order"] += 1
            continue
        last_t = tf
        rows.append(row)
    for c in cols:
        if c in bad:
            removed.append("column '%s': %d invalid value(s) blanked" % (c, bad[c]))
    msgs = {"comment": "comment line(s) in data", "malformed": "row(s) with too many fields",
            "t_s": "row(s) without a valid t_s", "order": "row(s) with t_s not increasing"}
    for k in ("comment", "malformed", "t_s", "order"):
        if dropped[k]:
            removed.append("%d %s dropped" % (dropped[k], msgs[k]))
    return {"magic": magic, "header": header, "columns": cols, "rows": rows, "removed": removed}


def serialize(schema, log):
    out = [log.get("magic", schema["magic"])]
    for h in schema["header"]:
        if h["key"] in log["header"]:
            out.append("# %s=%s" % (h["key"], log["header"][h["key"]]))
    out.append(",".join(log["columns"]))
    out.extend(",".join(r) for r in log["rows"])
    return "\n".join(out) + "\n"


def _col(log, name):
    if name not in log["columns"]:
        return []
    k = log["columns"].index(name)
    return [float(r[k]) for r in log["rows"] if r[k] != ""]


def fmt(x):
    return ("%.1f" % x).rstrip("0").rstrip(".")


def summary(log):
    h = log["header"]
    s = {}
    prod = h.get("product", "")
    s["car"] = CAR_GUESS.get(prod[:2].upper(), "unknown")
    vm = h.get("vin_model", "")
    if len(vm) == 9 and vm[8] in YEAR_CODES:
        s["model_year"] = 2010 + YEAR_CODES.index(vm[8])
    s["product"] = prod or "?"
    s["vin_model"] = vm or "?"
    s["date"] = h.get("date", "?")
    t = _col(log, "t_s")
    s["rows"] = len(log["rows"])
    s["duration_s"] = (t[-1] - t[0]) if t else 0
    ot = _col(log, "out_temp_c")
    if ot:
        s["out_temp_c"] = (min(ot), max(ot))
    soc = _col(log, "soc_half_pct")
    if soc:
        s["soc_pct"] = (soc[0] / 2, soc[-1] / 2)
    d = _col(log, "odo_delta_km")
    if d:
        s["distance_km"] = max(d)
    return s


def summary_lines(s):
    yr = " %d" % s["model_year"] if "model_year" in s else ""
    out = ["car:      %s%s (product %s, vin_model %s)" % (s["car"], yr, s["product"], s["vin_model"]),
           "date:     %s" % s["date"],
           "duration: %d min (%d rows)" % (round(s["duration_s"] / 60), s["rows"])]
    if "out_temp_c" in s:
        out.append("outside:  %s to %s °C" % tuple(fmt(x) for x in s["out_temp_c"]))
    if "soc_pct" in s:
        out.append("SOC:      %s%% -> %s%%" % tuple(fmt(x) for x in s["soc_pct"]))
    if "distance_km" in s:
        out.append("distance: %s km" % fmt(s["distance_km"]))
    return out


def read_text(path):
    with open(path, "rb") as f:
        return f.read().decode("utf-8", errors="replace")


def cmd_check(schema, files):
    worst = 0
    for path in files:
        print("== %s" % path)
        try:
            log = parse(schema, read_text(path))
        except (LogError, OSError) as e:
            print("  UNUSABLE: %s" % e)
            worst = 2
            continue
        for line in summary_lines(summary(log)):
            print("  " + line)
        if "t_s" in log["columns"] and not log["rows"]:
            print("  UNUSABLE: no valid data rows")
            worst = 2
            continue
        if log["removed"]:
            print("  clean would remove:")
            for r in log["removed"]:
                print("    - " + r)
            worst = max(worst, 1)
        else:
            print("  OK: nothing to remove")
    return worst


def clean_name(log, seq):
    short = re.sub(r"[^A-Za-z0-9]", "", log["header"].get("product", "").split(".")[0]) or "unknown"
    date = log["header"].get("date", "").replace("-", "") or "00000000"
    return "egmp-%s-%s-%03d.csv" % (short, date, seq)


def cmd_clean(schema, src, dst):
    try:
        log = parse(schema, read_text(src))
    except LogError as e:
        print("%s: UNUSABLE: %s" % (src, e), file=sys.stderr)
        return 2
    with open(dst, "w", encoding="utf-8", newline="\n") as f:
        f.write(serialize(schema, log))
    for r in log["removed"]:
        print("removed: " + r)
    print("wrote %s (%d rows, %d columns)" % (dst, len(log["rows"]), len(log["columns"])))
    return 0


def cmd_merge(schema, d, dst):
    names = sorted(n for n in os.listdir(d) if n.lower().endswith(".csv"))
    logs = []
    for n in names:
        try:
            log = parse(schema, read_text(os.path.join(d, n)))
        except LogError as e:
            print("skip %s: %s" % (n, e), file=sys.stderr)
            continue
        # Session id: the standard file name if it is one, else a counter (other
        # file names may contain anything, so they are not copied into the table).
        sid = n[:-4] if NAME_RE.match(n) else "session%d" % (len(logs) + 1)
        logs.append((sid, log))
    hkeys = [h["key"] for h in schema["header"]]
    cols = [c["name"] for c in schema["columns"] if any(c["name"] in l["columns"] for _, l in logs)]
    with open(dst, "w", encoding="utf-8", newline="\n") as f:
        f.write(",".join(["session"] + hkeys + cols) + "\n")
        total = 0
        for sid, log in logs:
            hv = [log["header"].get(k, "") for k in hkeys]
            idx = [log["columns"].index(c) if c in log["columns"] else None for c in cols]
            for r in log["rows"]:
                f.write(",".join([sid] + hv + [r[k] if k is not None else "" for k in idx]) + "\n")
                total += 1
    print("merged %d log(s), %d rows, %d columns -> %s" % (len(logs), total, 1 + len(hkeys) + len(cols), dst))
    return 0 if logs else 2


def main(argv):
    if len(argv) < 2 or argv[1] in ("-h", "--help"):
        print(__doc__.strip())
        return 0 if len(argv) >= 2 else 2
    schema = load_schema()
    cmd, args = argv[1], argv[2:]
    if cmd == "check" and args:
        return cmd_check(schema, args)
    if cmd == "clean" and len(args) == 2:
        return cmd_clean(schema, *args)
    if cmd == "merge" and len(args) == 2:
        return cmd_merge(schema, *args)
    print(__doc__.strip(), file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))

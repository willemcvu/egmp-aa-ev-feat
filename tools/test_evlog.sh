#!/bin/sh
# Tests for tools/evlog.py (and site/evlog.js when node is installed):
# check/clean on site/sample.csv and on a dirty variant with private data
# that must be removed. Run from anywhere: sh tools/test_evlog.sh
set -eu
cd "$(dirname "$0")/.."
PY=${PY:-python3}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fail() { echo "FAIL: $*"; exit 1; }
VIN=KM8KRDAF5NU012345
ADDR="1600 Amphitheatre Pkwy, Mountain View"

# 1. The sample is clean: check exits 0, clean is a byte-for-byte no-op.
$PY tools/evlog.py check site/sample.csv > "$T/check1.txt" || fail "sample check exit $?"
grep -q "OK: nothing to remove" "$T/check1.txt" || fail "sample not reported clean"
grep -q "Ioniq 5 2022" "$T/check1.txt" || fail "sample summary"
$PY tools/evlog.py clean site/sample.csv "$T/sample.clean.csv" > /dev/null
cmp -s site/sample.csv "$T/sample.clean.csv" || fail "cleaning the sample changed it"

# 2. Dirty variant: full VIN, unknown header keys, free-text comment, a lat/lon
#    column, an address in a numeric column, a non-numeric t_s, a time going
#    backwards, an overlong row and CRLF line endings.
$PY - "$VIN" "$ADDR" > "$T/dirty.csv" <<'EOF'
import sys
vin, addr = sys.argv[1], sys.argv[2]
lines = open("site/sample.csv").read().splitlines()
out = [lines[0], "# vin=" + vin, "# vin_model=" + vin, "# dest=" + addr,
       "# trip to grandma's house"] + [l for l in lines[1:9] if "vin_model" not in l]
cols = lines[9].split(",")
cols = cols[:3] + ["lat"] + cols[3:] + ["lon"]
out.append(",".join(cols))
for i, l in enumerate(lines[10:]):
    f = l.split(",")
    f = f[:3] + ["43.%04d" % (1000 + i)] + f[3:] + ["-76.%04d" % (2000 + i)]
    if i == 3:
        f[cols.index("out_temp_c")] = '"' + addr + '"'   # address in a numeric column
    if i == 4:
        f[cols.index("model_rev")] = "Main St"            # free text in the hex column
    if i == 5:
        f[0] = "12:34:56"                # wall-clock time as t_s
    out.append(",".join(f))
out.append(",".join(["7"] + f[1:]))      # t_s going backwards
out.append(",".join(["100"] + f[1:] + ["x"]))  # too many fields
out.append("# " + addr)
sys.stdout.write("\r\n".join(out) + "\r\n")
EOF

set +e
$PY tools/evlog.py check "$T/dirty.csv" > "$T/check2.txt"; rc=$?
set -e
[ "$rc" = 1 ] || fail "dirty check should exit 1, got $rc"
for want in "key 'vin' (not on allowlist)" "key 'dest'" "value of 'vin_model'" \
            "comment/free-text" "column 'lat'" "column 'lon'" "column 'out_temp_c': 1 invalid" "column 'model_rev': 1 invalid" \
            "without a valid t_s" "t_s not increasing" "too many fields" "comment line(s) in data"; do
  grep -qF "$want" "$T/check2.txt" || { cat "$T/check2.txt"; fail "check did not report: $want"; }
done

$PY tools/evlog.py clean "$T/dirty.csv" "$T/dirty.clean.csv" > /dev/null
C="$T/dirty.clean.csv"
for bad in "$VIN" "KM8KRDAF5" "vin=" "dest=" "Amphitheatre" "Mountain" "Main St" "grandma" "lat" "lon" \
           "43.1" "-76.2" "12:34" "$(printf '\r')"; do
  ! grep -qF -- "$bad" "$C" || fail "cleaned file still contains '$bad'"
done
head -1 "$C" | grep -qx "# egmp-aa-ev-log v1" || fail "magic line"
$PY tools/evlog.py check "$C" > "$T/check3.txt" || fail "cleaned file does not check clean"
grep -q "(19 rows)" "$T/check3.txt" || { cat "$T/check3.txt"; fail "expected 19 rows after cleaning"; }
grep -q "vin_model ?" "$T/check3.txt" || fail "full VIN in vin_model should be dropped"
# cleaning is idempotent
$PY tools/evlog.py clean "$C" "$T/again.csv" > /dev/null
cmp -s "$C" "$T/again.csv" || fail "clean is not idempotent"

# 3. Unusable files.
printf 't_s,lat\n1,43.1\n' > "$T/nomagic.csv"
set +e
$PY tools/evlog.py check "$T/nomagic.csv" > /dev/null; rc=$?
$PY tools/evlog.py clean "$T/nomagic.csv" "$T/x.csv" 2> /dev/null; rc2=$?
set -e
[ "$rc" = 2 ] && [ "$rc2" = 2 ] && [ ! -e "$T/x.csv" ] || fail "file without magic line accepted"

# 4. merge
mkdir "$T/logs"
cp site/sample.csv "$T/logs/egmp-NEEV-20260117-001.csv"
cp "$T/dirty.csv" "$T/logs/Trip to 1600 Amphitheatre.csv"
$PY tools/evlog.py merge "$T/logs" "$T/merged.csv" > /dev/null
[ "$(wc -l < "$T/merged.csv")" = 40 ] || fail "merged row count $(wc -l < "$T/merged.csv")"
head -1 "$T/merged.csv" | grep -q "^session,product,device,vin_model," || fail "merged header"
grep -q "^egmp-NEEV-20260117-001," "$T/merged.csv" || fail "session id from standard name"
grep -q "^session1," "$T/merged.csv" || fail "session id for nonstandard name"
! grep -qE "Amphitheatre|lat|$VIN" "$T/merged.csv" || fail "merged file leaks"

# 5. The website's cleaner (site/evlog.js) must give byte-identical output.
if command -v node > /dev/null 2>&1; then
  for f in site/sample.csv "$T/dirty.csv"; do
    node -e '
      const fs = require("fs"), E = require(process.argv[1]);
      const s = E.prepareSchema(JSON.parse(fs.readFileSync(process.argv[2], "utf8")));
      process.stdout.write(E.serialize(s, E.parse(s, fs.readFileSync(process.argv[3], "utf8"))));
    ' "$PWD/site/evlog.js" site/schema.json "$f" > "$T/js.csv"
    $PY tools/evlog.py clean "$f" "$T/py.csv" > /dev/null
    cmp -s "$T/js.csv" "$T/py.csv" || { diff "$T/py.csv" "$T/js.csv" | head; fail "evlog.js differs from evlog.py on $f"; }
  done
  echo "evlog.js matches evlog.py"
else
  echo "node not found: skipped evlog.js comparison"
fi

echo "PASS"

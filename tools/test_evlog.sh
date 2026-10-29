#!/bin/sh
# Tests for tools/evlog.py:
# check/clean on tools/testdata/sample-ev-log.csv and on a dirty variant with private data
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
$PY tools/evlog.py check tools/testdata/sample-ev-log.csv > "$T/check1.txt" || fail "sample check exit $?"
grep -q "OK: nothing to remove" "$T/check1.txt" || fail "sample not reported clean"
grep -q "Ioniq 5 2022" "$T/check1.txt" || fail "sample summary"
$PY tools/evlog.py clean tools/testdata/sample-ev-log.csv "$T/sample.clean.csv" > /dev/null
cmp -s tools/testdata/sample-ev-log.csv "$T/sample.clean.csv" || fail "cleaning the sample changed it"

# 2. Dirty variant: full VIN, unknown header keys, free-text comment, a lat/lon
#    column, an address in a numeric column, a non-numeric t_s, a time going
#    backwards, an overlong row and CRLF line endings.
$PY - "$VIN" "$ADDR" > "$T/dirty.csv" <<'EOF'
import sys
vin, addr = sys.argv[1], sys.argv[2]
lines = open("tools/testdata/sample-ev-log.csv").read().splitlines()
h = next(i for i, l in enumerate(lines) if not l.startswith("#"))  # column row
out = [lines[0], "# vin=" + vin, "# vin_model=" + vin, "# dest=" + addr,
       "# trip to grandma's house"] + [l for l in lines[1:h] if "vin_model" not in l]
cols = lines[h].split(",")
cols = cols[:3] + ["lat"] + cols[3:] + ["lon"]
out.append(",".join(cols))
for i, l in enumerate(lines[h + 1:]):
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
head -1 "$C" | grep -qx "# egmp-aa-ev-log v2" || fail "magic line"
$PY tools/evlog.py check "$C" > "$T/check3.txt" || fail "cleaned file does not check clean"
grep -q "(19 rows)" "$T/check3.txt" || { cat "$T/check3.txt"; fail "expected 19 rows after cleaning"; }
grep -q "vin_model ?" "$T/check3.txt" || fail "full VIN in vin_model should be dropped"
# cleaning is idempotent
$PY tools/evlog.py clean "$C" "$T/again.csv" > /dev/null
cmp -s "$C" "$T/again.csv" || fail "clean is not idempotent"

# 2b. Format v1 files (before always-on logging) still check and clean, and
#     keep their v1 magic line; a v3 file is not accepted.
printf '# egmp-aa-ev-log v1\n# date=2026-01-17\n# period_s=5\nt_s,soc_half_pct,speed_kph\n0,160,0\n5,160,29\n' > "$T/v1.csv"
$PY tools/evlog.py check "$T/v1.csv" > "$T/check_v1.txt" || { cat "$T/check_v1.txt"; fail "v1 file not clean"; }
$PY tools/evlog.py clean "$T/v1.csv" "$T/v1.clean.csv" > /dev/null
cmp -s "$T/v1.csv" "$T/v1.clean.csv" || fail "cleaning a clean v1 file changed it"
sed '1s/v1/v3/' "$T/v1.csv" > "$T/v3.csv"
set +e
$PY tools/evlog.py check "$T/v3.csv" > /dev/null; rc=$?
set -e
[ "$rc" = 2 ] || fail "unknown format version accepted"

# 3. Unusable files.
printf 't_s,lat\n1,43.1\n' > "$T/nomagic.csv"
set +e
$PY tools/evlog.py check "$T/nomagic.csv" > /dev/null; rc=$?
$PY tools/evlog.py clean "$T/nomagic.csv" "$T/x.csv" 2> /dev/null; rc2=$?
set -e
[ "$rc" = 2 ] && [ "$rc2" = 2 ] && [ ! -e "$T/x.csv" ] || fail "file without magic line accepted"

# 4. merge
mkdir "$T/logs"
cp tools/testdata/sample-ev-log.csv "$T/logs/egmp-NEEV-20260117-001.csv"
cp "$T/dirty.csv" "$T/logs/Trip to 1600 Amphitheatre.csv"
$PY tools/evlog.py merge "$T/logs" "$T/merged.csv" > /dev/null
[ "$(wc -l < "$T/merged.csv")" = 40 ] || fail "merged row count $(wc -l < "$T/merged.csv")"
head -1 "$T/merged.csv" | grep -q "^session,product,device,vin_model," || fail "merged header"
grep -q "^egmp-NEEV-20260117-001," "$T/merged.csv" || fail "session id from standard name"
grep -q "^session1," "$T/merged.csv" || fail "session id for nonstandard name"
! grep -qE "Amphitheatre|lat|$VIN" "$T/merged.csv" || fail "merged file leaks"


echo "PASS"

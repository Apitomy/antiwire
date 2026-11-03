#!/usr/bin/env bash
# TASK-39 dual-run output-identity suite.
#
# Reconciles the DualRunIdentityTest surefire report of the build this entry point already
# ran (the same evidence contract as security-corpus and parity-coverage --execution: the
# harness runs inside mvn verify, so a suite script that re-ran it would execute the corpus
# twice per verify.sh for no new evidence). Requires Failures 0, Errors 0, Skipped 0 and an
# invocation count equal to the corpus size enumerated from config/dual-run-corpus.tsv over
# BOTH pinned clones, with minimum floors of 150 upstream and 40 apicurio files. Every
# committed ledger row's doc_ref anchor must exist in its cited doc, so the
# compatibility-matrix entry cannot rot away from the assertion that enforces it.
#
# Contract with scripts/verify.sh: prints RESULT lines; exit 0 (PASS) / 1 (FAIL) / 3
# (NOT_RUN: the surefire report or a pinned clone is absent).
#
# --update: appends the divergences the last run observed (extracted from the report's
# failure messages, each carrying the exact row to record) to config/dual-run-divergences.tsv
# for review, so admission is a reviewed diff instead of hand-copying. --update never
# changes the verdict.
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
check_modules_consistency

REPORT="$ROOT/wire-protoc-compat-java/target/surefire-reports/TEST-com.squareup.wire.DualRunIdentityTest.xml"
UPDATE=0
if [ "${1:-}" = "--update" ]; then
  UPDATE=1
fi

for clone_var in ANTIWIRE_UPSTREAM ANTIWIRE_APICURIO; do :; done
# Corpus size per half, enumerated from the TSV over the clones the harness itself uses.
corpus_counts() {
  python3 - "$ROOT/config/dual-run-corpus.tsv" <<'PY'
import os, sys
tsv = sys.argv[1]
upstream = os.environ.get("ANTIWIRE_UPSTREAM") or "/tmp/wire"
apicurio = os.environ.get("ANTIWIRE_APICURIO") or "/tmp/apicurio-registry"
counts = {"upstream": 0, "apicurio": 0}
missing = []
with open(tsv) as f:
    lines = [l.rstrip("\n") for l in f if l.strip()]
for i, line in enumerate(lines):
    half, corpus_root = line.split("\t")
    if i == 0:
        if (half, corpus_root) != ("half", "root"):
            print("HEADER-MISMATCH", file=sys.stderr)
            sys.exit(2)
        continue
    base = {"upstream": upstream, "apicurio": apicurio}[half]
    path = os.path.join(base, corpus_root)
    if not os.path.isdir(path):
        missing.append(path)
        continue
    for dirpath, dirnames, filenames in os.walk(path):
        dirnames.sort()
        counts[half] += sum(1 for name in filenames if name.endswith(".proto"))
print("%d %d" % (counts["upstream"], counts["apicurio"]))
if missing:
    for path in missing:
        print("MISSING-CORPUS-ROOT %s" % path, file=sys.stderr)
    sys.exit(2)
PY
}

if [ ! -f "$REPORT" ]; then
  echo "RESULT dual-run-identity.status=NOT_RUN"
  echo "RESULT dual-run-identity.note=the DualRunIdentityTest surefire report is absent; run mvn verify first"
  exit "$EXIT_NOT_RUN"
fi

upstream_clone="${ANTIWIRE_UPSTREAM:-/tmp/wire}"
apicurio_clone="${ANTIWIRE_APICURIO:-/tmp/apicurio-registry}"
if [ ! -d "$upstream_clone/.git" ]; then
  echo "RESULT dual-run-identity.status=NOT_RUN"
  echo "RESULT dual-run-identity.note=the pinned upstream clone is absent at $upstream_clone; run scripts/fetch-upstream.sh"
  exit "$EXIT_NOT_RUN"
fi
if [ ! -d "$apicurio_clone/.git" ]; then
  echo "RESULT dual-run-identity.status=NOT_RUN"
  echo "RESULT dual-run-identity.note=the pinned apicurio clone is absent at $apicurio_clone; run scripts/fetch-apicurio.sh"
  exit "$EXIT_NOT_RUN"
fi

counts="$(corpus_counts)"
if [ $? -ne 0 ]; then
  echo "RESULT dual-run-identity.status=NOT_RUN"
  echo "RESULT dual-run-identity.note=the corpus TSV could not be enumerated over both clones (see MISSING-CORPUS-ROOT above)"
  exit "$EXIT_NOT_RUN"
fi
read -r upstream_count apicurio_count <<<"$counts"
floor_fail=""
if [ "$upstream_count" -lt 150 ]; then
  floor_fail=" upstream corpus $upstream_count below floor 150;"
fi
if [ "$apicurio_count" -lt 40 ]; then
  floor_fail="$floor_fail apicurio corpus $apicurio_count below floor 40;"
fi

# Report reconciliation and doc_ref anchor checks in one python pass.
verdict="$(python3 - "$REPORT" "$ROOT/config/dual-run-divergences.tsv" "$ROOT" "$UPDATE" <<'PY'
import os, re, sys
report, ledger, root, update = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]

import xml.etree.ElementTree as ET
suite = ET.parse(report).getroot()
tests = int(suite.get("tests", "0"))
failures = int(suite.get("failures", "0"))
errors = int(suite.get("errors", "0"))
skipped = int(suite.get("skipped", "0"))
invocations = 0
problem_texts = []
for tc in suite.iter("testcase"):
    if tc.get("name", "").startswith("identity("):
        invocations += 1
    for tag in ("failure", "error"):
        for node in tc.findall(tag):
            text = node.text or ""
            problem_texts.append(text)

problems = []
if failures or errors or skipped:
    problems.append("Failures: %d, Errors: %d, Skipped: %d" % (failures, errors, skipped))

rows = []
with open(ledger) as f:
    lines = [l.rstrip("\n") for l in f if l.strip()]
for i, line in enumerate(lines):
    if i == 0:
        if line.split("\t") != ["half","path","op","port_shape","upstream_shape","reason","doc_ref"]:
            print("LEDGER-HEADER-MISMATCH", file=sys.stderr)
            sys.exit(2)
        continue
    fields = line.split("\t")
    if len(fields) != 7:
        print("LEDGER-ROW-FIELD-COUNT %s" % line, file=sys.stderr)
        sys.exit(2)
    rows.append(fields)

for fields in rows:
    doc_ref = fields[6]
    if ":" not in doc_ref:
        print("BAD-DOC-REF %s" % doc_ref, file=sys.stderr)
        sys.exit(2)
    doc, anchor = doc_ref.split(":", 1)
    doc = doc.strip()
    anchor = anchor.strip()
    path = os.path.join(root, doc)
    if not os.path.isfile(path) or anchor not in open(path).read():
        problems.append("doc_ref anchor missing: %s (%s)" % (doc_ref, anchor))

print("%d %d %d %d" % (tests, invocations, failures + errors + skipped, len(problems)))
for p in problems:
    print("PROBLEM %s" % p)
if update == "1":
    for text in problem_texts:
        for line in text.splitlines():
            if re.match(r"^(upstream|apicurio)\t[^\t]+\t[^\t]+\t[^\t]+\t[^\t]+\t[^\t]+\t[^\t]+$", line):
                print("ROW " + line)
PY
)" || { echo "RESULT dual-run-identity.status=FAIL"; echo "RESULT dual-run-identity.note=the surefire report or the divergence ledger could not be parsed"; exit "$EXIT_FAIL"; }

tests=$(printf '%s\n' "$verdict" | head -n 1 | awk '{print $1}')
invocations=$(printf '%s\n' "$verdict" | head -n 1 | awk '{print $2}')
bad=$(printf '%s\n' "$verdict" | head -n 1 | awk '{print $3}')
nproblems=$(printf '%s\n' "$verdict" | head -n 1 | awk '{print $4}')
printf '%s\n' "$verdict" | grep '^PROBLEM ' | sed 's/^PROBLEM //'
corpus_total=$((upstream_count + apicurio_count))

if [ "$UPDATE" -eq 1 ]; then
  printf '%s\n' "$verdict" | grep '^ROW ' | sed 's/^ROW //' >> "$ROOT/config/dual-run-divergences.tsv"
  appended=$(printf '%s\n' "$verdict" | grep -c '^ROW ' || true)
  echo "--update: appended $appended observed divergence row(s) to config/dual-run-divergences.tsv for review"
fi

note="DualRunIdentityTest: $tests tests, $invocations identity invocations vs corpus size $corpus_total (upstream $upstream_count, apicurio $apicurio_count), Failures/Errors/Skipped: $bad; ledger rows all observed, doc_ref anchors present"
if [ -n "$floor_fail" ] || [ "$invocations" -ne "$corpus_total" ] || [ "$bad" -ne 0 ] || [ "$nproblems" -ne 0 ]; then
  echo "RESULT dual-run-identity.status=FAIL"
  echo "RESULT dual-run-identity.note=$floor_fail$note"
  exit "$EXIT_FAIL"
fi
echo "RESULT dual-run-identity.status=PASS"
echo "RESULT dual-run-identity.note=$note"
exit "$EXIT_OK"

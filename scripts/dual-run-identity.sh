#!/usr/bin/env bash
# TASK-39 dual-run output-identity suite.
#
# Reconciles the DualRunIdentityTest surefire report of the build this entry point already
# ran (the same evidence contract as security-corpus and parity-coverage --execution: the
# harness runs inside mvn verify, so a suite script that re-ran it would execute the corpus
# twice per verify.sh for no new evidence). Requires Failures 0, Errors 0, Skipped 0,
# per-half identity invocation counts equal to the corpus enumerated from
# config/dual-run-corpus.tsv over BOTH pinned clones (a stale report from a same-total
# corpus cannot pass), and per-half both-LOADED coverage from the harness's census line at
# or above the floors below, so green can never mean empty coverage. Every committed
# ledger row's doc_ref anchor must exist in its cited doc, so the compatibility-matrix
# entry cannot rot away from the assertion that enforces it.
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
# Enumerated-corpus floors (files per half) and both-LOADED coverage floors (files per half
# that both sides loaded through the staged roots), all measured over the pinned corpora
# (wire 9f62097dfe4995b5709d001ca0187e30ca0530ef, apicurio
# 23a09ab4e128e8a308efbcc333c5318734126b55) on 2026-11-03: exact measurements, not margins.
MIN_CORPUS_UPSTREAM=150
MIN_CORPUS_APICURIO=40
MIN_LOADED_UPSTREAM=141
MIN_LOADED_APICURIO=31
UPDATE=0
if [ "${1:-}" = "--update" ]; then
  UPDATE=1
fi

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
if [ "$upstream_count" -lt "$MIN_CORPUS_UPSTREAM" ]; then
  floor_fail=" upstream corpus $upstream_count below floor $MIN_CORPUS_UPSTREAM;"
fi
if [ "$apicurio_count" -lt "$MIN_CORPUS_APICURIO" ]; then
  floor_fail="$floor_fail apicurio corpus $apicurio_count below floor $MIN_CORPUS_APICURIO;"
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
identity_testcases = 0
problem_texts = []
coverage = None
for tc in suite.iter("testcase"):
    if tc.get("name", "").startswith("identity("):
        identity_testcases += 1
    for tag in ("failure", "error"):
        for node in tc.findall(tag):
            text = node.text or ""
            problem_texts.append(text)
    for out in tc.findall("system-out"):
        match = re.search(r"DUAL-RUN-COVERAGE invocationsUpstream=(\d+) invocationsApicurio=(\d+)"
                          r" loadedUpstream=(\d+) loadedApicurio=(\d+)"
                          r" threwUpstream=(\d+) threwApicurio=(\d+)"
                          r" divergentUpstream=(\d+) divergentApicurio=(\d+)",
                          out.text or "")
        if match:
            coverage = tuple(int(g) for g in match.groups())

problems = []
if failures or errors or skipped:
    problems.append("Failures: %d, Errors: %d, Skipped: %d" % (failures, errors, skipped))
if coverage is None:
    problems.append("the DUAL-RUN-COVERAGE census line is absent from the report")

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

print("%d %d %d %d" % (tests, identity_testcases, failures + errors + skipped, len(problems)))
if coverage is not None:
    print("COVERAGE " + " ".join(str(c) for c in coverage))
for p in problems:
    print("PROBLEM %s" % p)
if update == "1":
    for text in problem_texts:
        for line in text.splitlines():
            if re.match(r"^(upstream|apicurio)\t[^\t]+\t[^\t]+\t[^\t]+\t[^\t]+\t[^\t]+\t[^\t]+$", line):
                print("ROW " + line)
PY
)" || { echo "RESULT dual-run-identity.status=FAIL"; echo "RESULT dual-run-identity.note=the surefire report or the divergence ledger could not be parsed"; exit "$EXIT_FAIL"; }

read -r tests invocations_total bad nproblems _ <<<"$(printf '%s\n' "$verdict" | head -n 1)"
printf '%s\n' "$verdict" | grep '^PROBLEM ' | sed 's/^PROBLEM //'
coverage_line="$(printf '%s\n' "$verdict" | grep '^COVERAGE ' | sed 's/^COVERAGE //')"
if [ -z "$coverage_line" ]; then
  # The census line is already a PROBLEM; zero the numbers so the invocation and floor
  # checks fail cleanly instead of comparing against an empty string.
  coverage_line="0 0 0 0 0 0 0 0"
fi
read -r inv_upstream inv_apicurio loaded_upstream loaded_apicurio \
  threw_upstream threw_apicurio divergent_upstream divergent_apicurio <<<"$coverage_line"
if [ "$loaded_upstream" -lt "$MIN_LOADED_UPSTREAM" ]; then
  floor_fail="$floor_fail upstream both-LOADED coverage ${loaded_upstream:-absent} below floor $MIN_LOADED_UPSTREAM;"
fi
if [ "$loaded_apicurio" -lt "$MIN_LOADED_APICURIO" ]; then
  floor_fail="$floor_fail apicurio both-LOADED coverage ${loaded_apicurio:-absent} below floor $MIN_LOADED_APICURIO;"
fi

if [ "$UPDATE" -eq 1 ]; then
  printf '%s\n' "$verdict" | grep '^ROW ' | sed 's/^ROW //' >> "$ROOT/config/dual-run-divergences.tsv"
  appended=$(printf '%s\n' "$verdict" | grep -c '^ROW ' || true)
  echo "--update: appended $appended observed divergence row(s) to config/dual-run-divergences.tsv for review"
fi

corpus_total=$((upstream_count + apicurio_count))
note="DualRunIdentityTest: $tests tests, $invocations_total identity testcases; census invocations upstream $inv_upstream vs corpus $upstream_count, apicurio $inv_apicurio vs corpus $apicurio_count (total $corpus_total); both-LOADED coverage upstream $loaded_upstream, apicurio $loaded_apicurio (both-THREW $threw_upstream/$threw_apicurio, divergent $divergent_upstream/$divergent_apicurio); Failures/Errors/Skipped: $bad; ledger rows all observed, doc_ref anchors present"
if [ -n "$floor_fail" ] || [ "$invocations_total" -ne "$corpus_total" ] \
    || [ "$inv_upstream" -ne "$upstream_count" ] || [ "$inv_apicurio" -ne "$apicurio_count" ] \
    || [ "$bad" -ne 0 ] || [ "$nproblems" -ne 0 ]; then
  echo "RESULT dual-run-identity.status=FAIL"
  echo "RESULT dual-run-identity.note=$floor_fail$note"
  exit "$EXIT_FAIL"
fi
echo "RESULT dual-run-identity.status=PASS"
echo "RESULT dual-run-identity.note=$note"
exit "$EXIT_OK"

#!/usr/bin/env bash
# Single verification entry point for antiwire (TASK-2 AC#2).
#
# This is the only command CI calls from its build job, and the place later tasks extend:
# a suite becomes ACTIVE when its owning task flips its status in config/verify-suites.json
# and, when needed, adds the step that runs it here. PENDING suites are listed but never
# run and never printed as passed. A suite is PASS only when its script printed
# result=PASS AND exited EXIT_OK; any other outcome (FAIL, MISSING, NOT_RUN) is recorded
# with the suite's exit code and fails the run. A passing entry point means exactly this:
# the ACTIVE build checks passed. Empty suites are not evidence of parity.
#
# Stale-tree guard: when mvn verify fails, the suites that consume build artifacts are
# skipped, never executed against leftovers from an earlier build; each is recorded
# MISSING with a not-attributable note (the overall verdict stays FAIL). dependency-policy
# is the one exception: when the enforcer's bannedDependencies rule is what failed, it is
# recorded FAIL with that attribution, because the enforcer verdict IS attributable.
#
# Clone prerequisite (TASK-14.1): the clone-dependent tests (TestFiles.upstreamClone in
# SchemaEncoderInteropTest and the golden corpora) run INSIDE mvn verify, so the pinned
# upstream sources must exist and be validated before the build starts. Before this step
# existed the fetch lived only in the parity-coverage suite after a green build, so a
# clean checkout without the clone failed six SchemaEncoderInteropTest static
# initializers and never reached the fetch. The step reuses an existing correct clone,
# rejects a wrong or modified one, and fails fast before the long build.
set -uo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
check_modules_consistency

MANIFEST="$ROOT/config/verify-suites.json"
RESULTS="$(mktemp)"
LOG="$(mktemp)"
KEEP_LOG=0
trap 'rm -f "$RESULTS"; [ -n "${WATCH_LOG:-}" ] && rm -f "$WATCH_LOG"; [ "$KEEP_LOG" -eq 1 ] || rm -f "$LOG"' EXIT

res() { printf 'RESULT %s\n' "$1" >> "$RESULTS"; }

# Record one suite script's verdict after reconciling its RESULT lines with its exit code:
# PASS only when it printed result=PASS AND exited EXIT_OK; a nonzero exit (including
# EXIT_NOT_RUN) is recorded as FAIL or MISSING with the exit code in the note, so output
# alone can never make a suite pass.
record_suite() { # <suite-name> <exit-code> <log-file>
  local name="$1" rc="$2" log="$3"
  local printed note
  printed="$(grep "^RESULT ${name}\.status=" "$log" | head -n 1 | sed 's/^[^=]*=//')"
  note="$(grep "^RESULT ${name}\.note=" "$log" | head -n 1 | sed 's/^[^=]*=//')"
  if [ "$rc" -eq "$EXIT_OK" ] && [ "$printed" = "PASS" ]; then
    res "${name}.status=PASS"
    res "${name}.note=${note:-passed; the suite script recorded no note}"
  elif [ -z "$printed" ]; then
    res "${name}.status=MISSING"
    res "${name}.note=no result recorded by the suite script (exit code $rc); see its output above"
  elif [ "$printed" = "NOT_RUN" ]; then
    res "${name}.status=MISSING"
    res "${name}.note=could not run (exit code $rc): ${note:-no note recorded}"
  elif [ "$printed" = "PASS" ]; then
    res "${name}.status=FAIL"
    res "${name}.note=the suite script printed PASS but exited with code $rc: ${note:-no note recorded}"
  else
    res "${name}.status=FAIL"
    res "${name}.note=${note:-failed; no note recorded} (exit code $rc)"
  fi
}

run_suite() { # <suite-name> <script-relative-path>
  local name="$1" script="$2" rc
  echo
  echo "--- suite: $name ($script) ---"
  bash "$ROOT/$script" >"$LOG" 2>&1
  rc=$?
  cat "$LOG"
  record_suite "$name" "$rc" "$LOG"
}

# Stale-tree guard: record an artifact-consuming suite as not run when the build failed,
# so no stale artifact can produce a PASS for this revision.
skip_suite() { # <suite-name> <script-relative-path>
  local name="$1" script="$2"
  echo
  echo "--- suite: $name ($script): SKIPPED, mvn verify failed (see the build log) ---"
  res "$name.status=MISSING"
  res "$name.note=build failed; artifact results not attributable to the current revision"
}

if command -v java >/dev/null 2>&1; then
  build_jvm="$(java -version 2>&1 | head -n 1)"
else
  build_jvm=""
  echo "WARNING: no java executable on PATH; mvn verify relies on JAVA_HOME alone." >&2
fi

echo "=== antiwire verification entry point: scripts/verify.sh ==="
echo "build toolchain JVM: ${build_jvm:-(none found: no java executable on PATH)}"

echo
echo "--- prerequisite: pinned upstream sources (scripts/fetch-upstream.sh) ---"
if ! bash "$ROOT/scripts/fetch-upstream.sh" >"$LOG" 2>&1; then
  cat "$LOG"
  {
    echo "FATAL: prerequisite failed: the pinned upstream sources could not be obtained and validated."
    echo "       Clone-dependent tests initialize against the clone inside mvn verify, so no build"
    echo "       was started. Fix the fetch (network, pin, clone path; docs/parity-runner.md) and"
    echo "       re-run. ANTIWIRE_UPSTREAM overrides the clone location."
  } >&2
  exit "$EXIT_FAIL"
fi
cat "$LOG"

echo
echo "--- prerequisite: pinned apicurio corpus sources (scripts/fetch-apicurio.sh) ---"
if ! bash "$ROOT/scripts/fetch-apicurio.sh" >"$LOG" 2>&1; then
  cat "$LOG"
  {
    echo "FATAL: prerequisite failed: the pinned apicurio corpus sources could not be obtained and validated."
    echo "       The dual-run identity harness reads the apicurio half of its corpus from the clone inside"
    echo "       mvn verify, so no build was started. Fix the fetch (network, pin, clone path;"
    echo "       docs/parity-runner.md) and re-run. ANTIWIRE_APICURIO overrides the clone location."
  } >&2
  exit "$EXIT_FAIL"
fi
cat "$LOG"

echo
echo "--- suite: build (mvn verify; includes the dependency-policy enforcer rules) ---"
build_ok=1
if mvn -B -ntp -f "$ROOT/pom.xml" verify >"$LOG" 2>&1; then
  tail -n 40 "$LOG"
  res "build.status=PASS"
  res "build.note=mvn verify green on: ${build_jvm:-unreported JVM}"
  res "dependency-policy.status=PASS"
  res "dependency-policy.note=enforcer rules ran inside mvn verify: Maven and JDK 17+ floors, no Kotlin or Kotlin-backed artifact in production scope"
else
  build_ok=0
  cat "$LOG"
  # Keep the mvn log around for debugging; it is removed again on a green run.
  KEEP_LOG=1
  res "build.status=FAIL"
  res "build.note=mvn verify failed, see log above; full mvn output kept at $LOG"
  if grep -q "bannedDependencies failed" "$LOG"; then
    # The dependency policy is the one suite whose verdict is attributable from the mvn
    # log alone; toolchain rule failures (requireMavenVersion/requireJavaVersion) must
    # not claim it, so the match stays scoped to the bannedDependencies rule.
    res "dependency-policy.status=FAIL"
    res "dependency-policy.note=enforcer bannedDependencies rule tripped: a banned artifact sits in production scope (DEC-4); see log above and $LOG"
  else
    res "dependency-policy.status=MISSING"
    res "dependency-policy.note=build failed; artifact results not attributable to the current revision (log: $LOG)"
  fi
fi

# TASK-23 upstream watch: informational and artifact-independent, so it runs whichever way
# the build went. This block deliberately does not reuse run_suite: run_suite binds $LOG,
# which still holds the mvn output the module-summary suites below read, so the watch gets
# its own log file (cleaned by the EXIT trap). PASS here means the watch RAN AND REPORTED,
# with state=CURRENT/BEHIND or PIN_MOVED in the note; an upstream release must not fail the
# build. Only a watch that could not run (network, malformed pins) records NOT_RUN, which
# fails the run as MISSING, exactly like any other suite that never executed.
WATCH_LOG="$(mktemp)"
echo
echo "--- suite: upstream-watch (scripts/check-upstream.sh --informational) ---"
bash "$ROOT/scripts/check-upstream.sh" --informational >"$WATCH_LOG" 2>&1
watch_rc=$?
cat "$WATCH_LOG"
record_suite upstream-watch "$watch_rc" "$WATCH_LOG"
rm -f "$WATCH_LOG"

# Module test suites (runtime-tests TASK-9, schema-tests TASK-13): their evidence is the
# per-module surefire summary inside the green mvn verify log above. The summary is an
# aggregate: the per-case IDENTITY reconciliation (every mapped case must run, and every
# skip must carry a recorded disposition) is the parity-coverage suite's --execution
# pass below, because aggregate module totals cannot express it (port-only extras,
# parameterized invocations and unmapped port-only classes all shift the counts).
module_test_summary() { # <artifactId>
  awk -v prefix="] Building antiwire $1 " '
    /] Building antiwire / {
      inmod = index($0, prefix) > 0
      if (inmod) last = ""
    }
    inmod && /^\[(INFO|WARNING)\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+/ { last = $0 }
    END { print last }
  ' "$LOG"
}

surefire_count() { # <surefire-summary-line>: the Tests run: N value
  printf '%s\n' "$1" | sed -n 's/^.*Tests run: \([0-9][0-9]*\),.*$/\1/p'
}

module_tests_suite() { # <suite> <artifactId>
  local suite="$1" artifact="$2" summary count
  summary="$(module_test_summary "$artifact")"
  count="$(surefire_count "$summary")"
  case "$summary" in
    *"Failures: 0, Errors: 0"*) zero_failures=1 ;;
    *) zero_failures=0 ;;
  esac
  skipped="$(printf '%s\n' "$summary" | sed -n 's/^.*Skipped: \([0-9][0-9]*\)$/\1/p')"
  if [ -n "$count" ] && [ "$zero_failures" -eq 1 ]; then
    res "$suite.status=PASS"
    res "$suite.note=$count $artifact cases inside mvn verify, $skipped skipped (Failures: 0, Errors: 0); skips reconciled by identity in the parity-coverage suite"
  else
    KEEP_LOG=1
    res "$suite.status=FAIL"
    res "$suite.note=missing or failing surefire summary for $artifact; mvn log kept at $LOG"
  fi
}

# TASK-17 security corpus: the regression cases for docs/security-regression-inventory.md run
# inside mvn verify as SecurityCorpusTest classes in three modules; this suite reconciles their
# surefire summaries out of the same build log, so it cannot pass on a build that did not
# execute them. Each class must appear, be green, and carry at least its floor of cases; the
# floor is the count the inventory doc's rows pin to the class, so thinning the corpus below
# the registry fails the suite. Growing a class needs no script change: floors are minimums.
security_corpus_suite() {
  local required_classes=(
      com.squareup.wire.RuntimeSecurityCorpusTest:10
      com.squareup.wire.schema.SchemaSecurityCorpusTest:10
      com.squareup.wire.java.JavaGeneratorSecurityCorpusTest:4
  )
  local problems="" entry class floor summary count skipped
  for entry in "${required_classes[@]}"; do
    class="${entry%:*}"
    floor="${entry##*:}"
    summary="$(grep -E "^\[INFO\] Tests run: .* -- in ${class}$" "$LOG" | head -n 1)"
    count="$(surefire_count "$summary")"
    skipped="$(printf '%s\n' "$summary" | sed -n 's/^.*Skipped: \([0-9][0-9]*\).*$/\1/p')"
    case "$summary" in
      *"Failures: 0, Errors: 0"*)
        if [ -z "$count" ] || [ "$((count - skipped))" -lt "$floor" ]; then
          problems="$problems ${class}(ran $count cases, $skipped skipped, floor is $floor)"
        fi
        ;;
      "")
        problems="$problems ${class}(absent from the build)"
        ;;
      *)
        problems="$problems ${class}(failing)"
        ;;
    esac
  done
  if [ -z "$problems" ]; then
    res "security-corpus.status=PASS"
    res "security-corpus.note=SecurityCorpusTest classes green inside mvn verify (runtime, schema, generator); registry docs/security-regression-inventory.md"
  else
    KEEP_LOG=1
    res "security-corpus.status=FAIL"
    res "security-corpus.note=corpus classes not all green:$problems; mvn log kept at $LOG"
  fi
}

if [ "$build_ok" -eq 1 ]; then
  module_tests_suite runtime-tests wire-runtime-java
  module_tests_suite schema-tests wire-schema-java
  module_tests_suite protoc-oracle wire-protoc-compat-java
  module_tests_suite compiler-tests wire-java-generator

  security_corpus_suite

  run_suite parity-coverage scripts/parity-coverage.sh
  run_suite duplicate-class-check scripts/check-classpath.sh
  run_suite bytecode-java11 scripts/check-java11-bytecode.sh
  run_suite java11-consumer scripts/consumer-check-java11.sh
  run_suite surface-check scripts/surface-check.sh
  run_suite dual-run-identity scripts/dual-run-identity.sh
else
  skip_suite duplicate-class-check scripts/check-classpath.sh
  skip_suite bytecode-java11 scripts/check-java11-bytecode.sh
  skip_suite java11-consumer scripts/consumer-check-java11.sh
  skip_suite surface-check scripts/surface-check.sh
  skip_suite dual-run-identity scripts/dual-run-identity.sh
fi

echo
python3 - "$MANIFEST" "$RESULTS" <<'PY'
import json
import sys

manifest_path, results_path = sys.argv[1], sys.argv[2]
results = {}
for line in open(results_path):
    line = line.strip()
    if not line.startswith("RESULT "):
        continue
    key, _, value = line[len("RESULT "):].partition("=")
    results[key] = value

with open(manifest_path) as f:
    data = json.load(f)

names = {s["name"] for s in data["suites"]}
unknown = sorted({k.split(".")[0] for k in results} - names)
if unknown:
    print("INTERNAL ERROR: results for suites absent from the manifest: %s"
          % ", ".join(unknown))
    sys.exit(2)

rows = []
failures = []
active = 0
pending = []
for s in data["suites"]:
    name = s["name"]
    if s["status"] == "ACTIVE":
        active += 1
        status = results.get(name + ".status", "MISSING")
        note = results.get(name + ".note", "no result recorded by the entry point")
        if status != "PASS":
            failures.append(name)
    else:
        pending.append("%s (%s)" % (name, s["owner"]))
        status = "PENDING"
        note = "planned; not run and not passed"
    rows.append((name, s["status"], s["owner"], status, note))

width = max(len(r[0]) for r in rows)
print("Suite registry from %s:" % manifest_path)
for name, declared, owner, status, note in rows:
    print("  %-*s  declared=%-7s owner=%-7s result=%-7s %s"
          % (width, name, declared, owner, status, note))
print()
if failures:
    print("VERDICT: FAIL. ACTIVE suites not passed: %s" % ", ".join(failures))
    sys.exit(1)
print("VERDICT: all %d ACTIVE suites passed." % active)
if pending:
    print("PENDING suites: %s." % ", ".join(pending))
    print("They have not run. This run claims no parity, compatibility, or completeness.")
sys.exit(0)
PY
exit $?

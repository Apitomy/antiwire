#!/usr/bin/env bash
# TASK-14.1: offline regression check for the verify.sh clone prerequisite.
#
# Proves, with a stubbed mvn (no real build) and a synthetic local upstream repository,
# that scripts/verify.sh obtains and validates the pinned upstream sources BEFORE the mvn
# build suite, and that every broken clone state fails fast with an explicit prerequisite
# message instead of reaching the build:
#
#   absent      no clone at the configured path: verify.sh clones it, validates the pin,
#               and mvn starts only with the clone already at the pin;
#   valid       a correct clone at the pin: reused (no re-clone), same ordering;
#   modified    a clone with local worktree modifications: rejected before mvn;
#   wrong       a clone of a different upstream whose tag resolves elsewhere: rejected
#               before mvn with the pin-mismatch error;
#   unreachable no clone and an unreachable repository URL: explicit prerequisite failure
#               before mvn.
#
# The stub mvn records its invocation and, at that moment, whether the clone is at the
# pinned commit with a clean worktree; that record is the ordering proof. Everything runs
# against local git repositories, so the check needs no network.
set -euo pipefail

REAL_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d /tmp/antiwire-bootstrap-test.XXXXXX)"
FAILED=0
trap 'if [ "$FAILED" -eq 0 ]; then rm -rf "$WORK"; else echo "scratch kept at $WORK" >&2; fi' EXIT

# The synthetic pinned tag and its identity, shared by every scratch tree.
TAG="7.1.0"

fail() { # <state> <message>
  echo "FAIL [$1]: $2" >&2
  FAILED=1
}

pass() { # <state> <message>
  echo "PASS [$1]: $2"
}

make_upstream_repo() { # <dir> <file-content>: a one-commit repo with annotated tag $TAG
  local dir="$1" content="$2"
  mkdir -p "$dir"
  git -C "$dir" init -q -b main
  git -C "$dir" config user.email test@antiwire.local
  git -C "$dir" config user.name "antiwire bootstrap test"
  printf '%s\n' "$content" >"$dir/README.md"
  git -C "$dir" add README.md
  git -C "$dir" commit -qm "synthetic upstream"
  git -C "$dir" tag -a "$TAG" -m "synthetic pin"
}

make_apicurio_repo() { # <dir>: a one-commit repo whose HEAD is the bare-commit pin
  local dir="$1"
  mkdir -p "$dir"
  git -C "$dir" init -q -b main
  git -C "$dir" config user.email test@antiwire.local
  git -C "$dir" config user.name "antiwire bootstrap test"
  printf 'synthetic apicurio corpus\n' >"$dir/README.md"
  git -C "$dir" add README.md
  git -C "$dir" commit -qm "synthetic apicurio pin"
}

make_scratch_tree() { # <dir> <repo-path> <tag-object> <commit> <apicurio-repo>
  local dir="$1" repo="$2" tag_object="$3" commit="$4" apicurio_repo="$5"
  mkdir -p "$dir"
  cp "$REAL_ROOT/pom.xml" "$dir/pom.xml"
  cp -r "$REAL_ROOT/scripts" "$dir/scripts"
  cp -r "$REAL_ROOT/config" "$dir/config"
  python3 - "$dir/config/parity-pins.json" "$repo" "$TAG" "$tag_object" "$commit" \
      "$apicurio_repo" "$APICURIO_COMMIT" <<'PY'
import json, sys
path, repo, tag, tag_object, commit, apicurio_repo, apicurio_commit = sys.argv[1:8]
with open(path) as f:
    pins = json.load(f)
pins["upstream"] = {"repository": repo, "tag": tag,
                    "annotated_tag_object": tag_object, "resolved_commit": commit}
pins["apicurio_registry"]["repository"] = apicurio_repo
pins["apicurio_registry"]["commit"] = apicurio_commit
with open(path, "w") as f:
    json.dump(pins, f, indent=2)
    f.write("\n")
PY
}

make_stub_mvn() { # <bindir>: writes the mvn stub recording clone state at invocation
  local bindir="$1"
  mkdir -p "$bindir"
  cat >"$bindir/mvn" <<'STUB'
#!/usr/bin/env bash
# test-bootstrap.sh stub: no build runs. It records that mvn started and whether the
# upstream clone was already validated at the pin at that moment.
{
  echo "mvn-invoked"
  head="$(git -C "$CLONE" rev-parse HEAD 2>/dev/null || true)"
  dirty="$(git -C "$CLONE" status --porcelain 2>/dev/null || true)"
  if [ "$head" = "$PIN_COMMIT" ] && [ -z "$dirty" ]; then
    echo "clone-at-pin: yes"
  else
    echo "clone-at-pin: no (head=${head:-none} dirty=${dirty:+yes})"
  fi
} >>"$MVN_MARKER"
exit 0
STUB
  chmod +x "$bindir/mvn"
}

# run_state <name> <state-dir>: runs the scratch verify.sh with the stubbed mvn and the
# clone at <state-dir>/clone; leaves combined output in <state-dir>/out.log and the exit
# code in <state-dir>/rc. mvn never sees a real pom: the stub ignores its arguments.
run_state() { # <name> <state-dir>
  local name="$1" dir="$2"
  local stubbin="$dir/stubbin"
  make_stub_mvn "$stubbin"
  : >"$dir/mvn.marker"
  set +e
  env PATH="$stubbin:$PATH" \
      ANTIWIRE_UPSTREAM="$dir/clone" ANTIWIRE_APICURIO="$dir/apicurio-clone" \
      CLONE="$dir/clone" PIN_COMMIT="$PIN_COMMIT" MVN_MARKER="$dir/mvn.marker" \
      bash "$dir/tree/scripts/verify.sh" >"$dir/out.log" 2>&1
  echo $? >"$dir/rc"
  set -e
}

mvn_invocations() { # <state-dir>: how many times the stub ran (mvn verify, plus any
# artifact-consuming suite that falls back to a build, e.g. java11-consumer)
  grep -c "^mvn-invoked$" "$1/mvn.marker" 2>/dev/null || true
}

mvn_saw_pin() { # <state-dir>: the stub's ordering observation
  grep -q "^clone-at-pin: yes$" "$1/mvn.marker" 2>/dev/null
}

line_number() { # <file> <pattern>: first matching line number, or 0
  grep -n "$2" "$1" 2>/dev/null | head -n 1 | cut -d: -f1 || true
}

# Shared synthetic upstream: the pinned repo, plus a second repo whose same-named tag
# resolves to a different identity (the wrong-clone state).
UPSTREAM="$WORK/upstream"
make_upstream_repo "$UPSTREAM" "pinned upstream sources"
PIN_COMMIT="$(git -C "$UPSTREAM" rev-parse "$TAG^{commit}")"
PIN_OBJECT="$(git -C "$UPSTREAM" rev-parse "$TAG^{tag}")"
OTHER_UPSTREAM="$WORK/upstream-other"
make_upstream_repo "$OTHER_UPSTREAM" "a different upstream"

# Shared synthetic apicurio corpus: the bare-commit pin fetch-apicurio.sh verifies.
APICURIO_REPO="$WORK/apicurio"
make_apicurio_repo "$APICURIO_REPO"
APICURIO_COMMIT="$(git -C "$APICURIO_REPO" rev-parse HEAD)"

new_state() { # <name> -> creates $WORK/<name>/{tree}; prints its dir
  local dir="$WORK/$1"
  mkdir -p "$dir"
  make_scratch_tree "$dir/tree" "$UPSTREAM" "$PIN_OBJECT" "$PIN_COMMIT" "$APICURIO_REPO"
  printf '%s\n' "$dir"
}

echo "=== bootstrap prerequisite check (stubbed mvn, synthetic upstream $PIN_COMMIT) ==="

# --- absent: the entry point must bootstrap the clone before mvn -------------------------
dir="$(new_state absent)"
run_state absent "$dir"
out="$dir/out.log"
if [ "$(cat "$dir/rc")" -ne 0 ] && grep -q "FATAL: prerequisite failed" "$out"; then
  fail absent "verify.sh failed at the prerequisite step, but the sources were fetchable"
fi
pin_line="$(line_number "$out" 'upstream pin verified')"
build_line="$(line_number "$out" 'suite: build')"
if [ -z "$pin_line" ] || [ -z "$build_line" ] || [ "$pin_line" -ge "$build_line" ]; then
  fail absent "pin verification (line ${pin_line:-none}) must precede the build suite (line ${build_line:-none})"
elif ! grep -q "Cloning into" "$out"; then
  fail absent "an absent clone was not cloned by the prerequisite step"
elif ! grep -q "apicurio pin verified" "$out"; then
  fail absent "the apicurio corpus clone was not bootstrapped and pin-verified"
elif [ "$(mvn_invocations "$dir")" -lt 1 ] || ! mvn_saw_pin "$dir"; then
  fail absent "mvn ran $(mvn_invocations "$dir") time(s) and did not observe a clean clone at the pin"
else
  pass absent "clone bootstrapped and pin verified before the first mvn invocation"
fi

# --- valid: an existing correct clone is reused, not replaced ---------------------------
dir="$(new_state valid)"
git clone -q --branch "$TAG" "$UPSTREAM" "$dir/clone"
git clone -q "$APICURIO_REPO" "$dir/apicurio-clone"
clone_inode_before="$(stat -c %i "$dir/clone/.git")"
run_state valid "$dir"
out="$dir/out.log"
if [ "$(cat "$dir/rc")" -ne 0 ] && grep -q "FATAL: prerequisite failed" "$out"; then
  fail valid "verify.sh failed at the prerequisite step with a valid clone present"
fi
if grep -q "Cloning into" "$out"; then
  fail valid "an existing correct clone was re-cloned (destructive replacement)"
elif [ "$(mvn_invocations "$dir")" -lt 1 ] || ! mvn_saw_pin "$dir"; then
  fail valid "mvn ran $(mvn_invocations "$dir") time(s) and did not observe a clean clone at the pin"
elif [ "$(stat -c %i "$dir/clone/.git")" != "$clone_inode_before" ]; then
  fail valid "the clone directory was replaced rather than reused"
elif ! grep -q "upstream pin verified" "$out"; then
  fail valid "the reused clone was not pin-verified"
else
  pass valid "existing clone reused, pin verified, mvn started with the clone at the pin"
fi

# --- modified: a dirty upstream worktree is rejected before mvn --------------------------
dir="$(new_state modified)"
git clone -q --branch "$TAG" "$UPSTREAM" "$dir/clone"
echo "local edit" >>"$dir/clone/README.md"
run_state modified "$dir"
out="$dir/out.log"
if [ "$(cat "$dir/rc")" -eq 0 ]; then
  fail modified "verify.sh exited 0 despite a modified clone"
elif ! grep -q "has local modifications" "$out"; then
  fail modified "the rejection does not name the local modifications"
elif ! grep -q "FATAL: prerequisite failed" "$out"; then
  fail modified "no explicit prerequisite failure message"
elif [ "$(mvn_invocations "$dir")" -ne 0 ]; then
  fail modified "mvn ran $(mvn_invocations "$dir") time(s) despite the modified clone"
else
  pass modified "modified clone rejected with an actionable error before the build"
fi

# --- wrong: a clone whose tag resolves to a different identity ---------------------------
dir="$(new_state wrong)"
git clone -q --branch "$TAG" "$OTHER_UPSTREAM" "$dir/clone"
run_state wrong "$dir"
out="$dir/out.log"
if [ "$(cat "$dir/rc")" -eq 0 ]; then
  fail wrong "verify.sh exited 0 despite a wrong clone"
elif ! grep -q "does not match the pinned upstream identity" "$out"; then
  fail wrong "the rejection does not name the pin mismatch"
elif ! grep -q "FATAL: prerequisite failed" "$out"; then
  fail wrong "no explicit prerequisite failure message"
elif [ "$(mvn_invocations "$dir")" -ne 0 ]; then
  fail wrong "mvn ran $(mvn_invocations "$dir") time(s) despite the wrong clone"
else
  pass wrong "wrong clone rejected with the pin mismatch before the build"
fi

# --- unreachable: no clone and a repository URL that cannot be fetched -------------------
dir="$(new_state unreachable)"
make_scratch_tree "$dir/tree" "$WORK/no-such-repository" "$PIN_OBJECT" "$PIN_COMMIT" "$APICURIO_REPO"
run_state unreachable "$dir"
out="$dir/out.log"
if [ "$(cat "$dir/rc")" -eq 0 ]; then
  fail unreachable "verify.sh exited 0 despite unreachable sources"
elif ! grep -q "FATAL: prerequisite failed" "$out"; then
  fail unreachable "no explicit prerequisite failure message"
elif ! grep -q "no build" "$out"; then
  fail unreachable "the failure does not state that the build was not started"
elif [ "$(mvn_invocations "$dir")" -ne 0 ]; then
  fail unreachable "mvn ran $(mvn_invocations "$dir") time(s) despite unreachable sources"
else
  pass unreachable "unreachable sources fail fast with an explicit message before the build"
fi

# --- apicurio-modified: a dirty apicurio corpus worktree is rejected before mvn ----------
dir="$(new_state apicurio-modified)"
git clone -q "$APICURIO_REPO" "$dir/apicurio-clone"
echo "local edit" >>"$dir/apicurio-clone/README.md"
run_state apicurio-modified "$dir"
out="$dir/out.log"
if [ "$(cat "$dir/rc")" -eq 0 ]; then
  fail apicurio-modified "verify.sh exited 0 despite a modified apicurio corpus clone"
elif ! grep -q "has local modifications" "$out"; then
  fail apicurio-modified "the rejection does not name the local modifications"
elif ! grep -q "FATAL: prerequisite failed" "$out"; then
  fail apicurio-modified "no explicit prerequisite failure message"
elif [ "$(mvn_invocations "$dir")" -ne 0 ]; then
  fail apicurio-modified "mvn ran $(mvn_invocations "$dir") time(s) despite the modified corpus clone"
else
  pass apicurio-modified "modified apicurio corpus clone rejected with an actionable error before the build"
fi

echo
if [ "$FAILED" -ne 0 ]; then
  echo "RESULT bootstrap-check.status=FAIL"
  exit 1
fi
echo "RESULT bootstrap-check.status=PASS"
echo "bootstrap prerequisite check: all six clone states behaved as specified"

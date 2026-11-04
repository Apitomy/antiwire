#!/usr/bin/env bash
# TASK-39: retrieve the pinned Apicurio Registry corpus clone and verify its provenance.
#
# Usage: scripts/fetch-apicurio.sh [clone-path]
# The clone path defaults to $ANTIWIRE_APICURIO or /tmp/apicurio-registry. The script is
# idempotent: an existing clone is reused after the pin check passes. Mirror of
# scripts/fetch-upstream.sh with a bare-commit pin (the commit the compatibility matrix
# cites) instead of a tag: the pin is verified by git rev-parse and the working tree is
# put AT the pin with a detached checkout, rejecting a dirty tree for the same reason.
set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PINS="$ROOT/config/parity-pins.json"

PINS_LINE="$(python3 - "$PINS" <<'PY'
import json, sys
pins = json.load(open(sys.argv[1]))
a = pins["apicurio_registry"]
print(a["repository"], a["commit"], a["clone_path_default"])
PY
)"
read -r REPO COMMIT CLONE_DEFAULT <<<"$PINS_LINE"

CLONE_PATH="${1:-${ANTIWIRE_APICURIO:-$CLONE_DEFAULT}}"

verify_pin() {
  # HEAD equality is guaranteed by the checkout at the pin that precedes this call (the
  # head != COMMIT branch was unreachable after it); what stays checked is that the
  # pinned object is exactly what the clone carries.
  if ! git -C "$CLONE_PATH" cat-file -e "$COMMIT^{commit}" 2>/dev/null; then
    echo "FAIL: $CLONE_PATH does not contain the pinned apicurio commit $COMMIT." >&2
    echo "  re-run with a fresh clone path or update config/parity-pins.json per docs/parity-runner.md" >&2
    return 1
  fi
  echo "apicurio pin verified: $COMMIT at $CLONE_PATH"
}

if [ ! -d "$CLONE_PATH/.git" ]; then
  mkdir -p "$(dirname "$CLONE_PATH")"
  git clone --filter=blob:none "$REPO" "$CLONE_PATH"
fi

# The pinned commit may predate or postdate the default branch the clone was made from.
# The 2026-11-03 pin (23a09ab4) is on origin/main, so a fresh GitHub clone carries it and
# the fallbacks below never run. They stay for a future pin that again lives off GitHub
# (the first TASK-39 pin, 48e2742, existed only on a local branch of the maintainer's
# registry clone and no cache-cold CI run could fetch it; deviation recorded in the
# design's implementation reconciliation): $ANTIWIRE_APICURIO_SOURCE names a local clone
# to fetch the object from. Identity is still the hash: nothing is trusted from the
# source beyond carrying the pinned object.
if ! git -C "$CLONE_PATH" cat-file -e "$COMMIT^{commit}" 2>/dev/null; then
  git -C "$CLONE_PATH" fetch origin --force || true
fi
if ! git -C "$CLONE_PATH" cat-file -e "$COMMIT^{commit}" 2>/dev/null; then
  # GitHub PR heads are not fetched by a plain clone; the pin could ride one.
  git -C "$CLONE_PATH" fetch origin '+refs/pull/*/head:refs/remotes/pull/*' --force >/dev/null 2>&1 || true
fi
if ! git -C "$CLONE_PATH" cat-file -e "$COMMIT^{commit}" 2>/dev/null; then
  if [ -z "${ANTIWIRE_APICURIO_SOURCE:-}" ]; then
    echo "FAIL: the pinned apicurio commit $COMMIT is not on any ref of $REPO" >&2
    echo "  (branch heads and refs/pull were both fetched). Point ANTIWIRE_APICURIO_SOURCE" >&2
    echo "  at a local clone that carries the commit, or update config/parity-pins.json to a" >&2
    echo "  commit on origin/main per docs/parity-runner.md." >&2
    exit 1
  fi
  git -C "$CLONE_PATH" fetch "$ANTIWIRE_APICURIO_SOURCE" "$COMMIT" --force
fi
# The pin is a bare commit and the corpus harness reads the working tree, so the tree
# goes AT the pin and a dirty tree is rejected (lib.sh tail, shared with
# fetch-upstream.sh); a fresh clone or cache refill starts on a branch head.
checkout_at_pin_and_require_clean "$CLONE_PATH" "$COMMIT" "the corpus harness"
verify_pin

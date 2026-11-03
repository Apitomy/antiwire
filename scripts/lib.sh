#!/usr/bin/env bash
# Shared library for the antiwire scripts (TASK-2 build tooling).
#
# Single source of truth for the repo root, the module list, module jar discovery and the
# exit-code contract. Every consumer script sources this file and runs
# check_modules_consistency at startup, so a module added to one place but not the other
# fails loudly at the top of the run instead of silently checking the wrong set.

# Repo root, resolved from this file's own location so the scripts work from any cwd.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The build modules, defined once here; the parent pom.xml <modules> block must match
# (enforced by check_modules_consistency, which every consumer script runs at startup).
# wire-upstream-shaded first: parity fixture packages before its consumers (see parent pom).
MODULES=(wire-upstream-shaded wire-runtime-java wire-schema-java wire-tests-java wire-protoc-compat-java wire-java-generator)
# wire-tests-java is a test-only module (never published) and is checked alongside the
# shipped set for classpath and bytecode hygiene
# checks: its generated fixtures are compiled with the port's runtime and must stay clean.
SHIPPED_MODULES=(wire-runtime-java wire-schema-java wire-java-generator)
# Shipping modules only: the fixture is never-published test tooling with no target/classes,
# so class-origin, bytecode and consumer checks must not iterate it.
SHIPPED_MODULES=(wire-runtime-java wire-schema-java wire-java-generator)

# A check ran and passed.
# shellcheck disable=SC2034  # consumed by the scripts that source this file
EXIT_OK=0
# A check ran and failed, or a precondition is broken.
# shellcheck disable=SC2034  # consumed by the scripts that source this file
EXIT_FAIL=1
# A suite could not run at all: missing toolchain or missing build artifacts.
# shellcheck disable=SC2034  # consumed by the scripts that source this file
EXIT_NOT_RUN=3

# Fail loudly (clear message, nonzero exit) when the <modules> block of the parent pom.xml
# and the MODULES list above disagree.
check_modules_consistency() {
  local pom_modules expected actual
  pom_modules="$(sed -n '/<modules>/,/<\/modules>/p' "$ROOT/pom.xml" \
    | sed -n 's:.*<module>\(.*\)</module>.*:\1:p' | sed '/^[[:space:]]*$/d')"
  expected="$(printf '%s\n' "${MODULES[@]}" | sort)"
  actual="$(printf '%s\n' "$pom_modules" | sort)"
  if [ "$expected" != "$actual" ]; then
    {
      echo "FATAL: module list mismatch between scripts/lib.sh and $ROOT/pom.xml"
      echo "  scripts/lib.sh MODULES:"
      printf '    %s\n' "${MODULES[@]}"
      echo "  $ROOT/pom.xml <modules> block:"
      printf '    %s\n' "$pom_modules"
    } >&2
    exit "$EXIT_FAIL"
  fi
}

# Print the parent pom's project version, read from the <version> directly under <project>
# (the modules inherit it, so target/<module>-<version>.jar is the exact artifact name).
# Same structural read as check_modules_consistency above: a sed pass over the parent pom,
# failing closed when the element cannot be found.
project_version() {
  local version
  version="$(sed -n 's:^  <version>\(.*\)</version>[[:space:]]*$:\1:p' "$ROOT/pom.xml" \
    | sed '/^[[:space:]]*$/d' | head -n 1)"
  if [ -z "$version" ]; then
    echo "FATAL: could not read the project <version> directly under <project> in $ROOT/pom.xml" >&2
    return "$EXIT_FAIL"
  fi
  printf '%s\n' "$version"
}

# Print the module's packaged jar path, exactly target/<module>-<project version>.jar. The
# jar is resolved against the parent pom's version instead of picking the first
# target/<module>-*.jar alphabetically: after a version bump without mvn clean the old
# version's jar still sits in target/ and an alphabetical glob would hand out the stale
# artifact. Fail closed when the exact jar is absent or unexpected jars shadow it; prints
# nothing on stdout plus a stderr FATAL in those cases, and callers must handle the empty
# result explicitly (they report NOT_RUN).
find_module_jar() {
  local module="$1" version jar
  version="$(project_version)" || return "$EXIT_FAIL"
  local exact="$ROOT/$module/target/$module-$version.jar"
  local -a candidates=()
  for jar in "$ROOT/$module/target/$module"-*.jar; do
    [ -f "$jar" ] || continue
    case "$jar" in
      *-sources.jar|*-javadoc.jar) continue ;;
      # Test-classifier jars (wire-schema-java's shared test-utils, TASK-16) are test
      # artifacts, not the module's production jar; exclude them from the production pick.
      *-tests.jar) continue ;;
    esac
    candidates+=("$jar")
  done
  if [ "${#candidates[@]}" -eq 1 ] && [ "${candidates[0]}" = "$exact" ]; then
    printf '%s\n' "$exact"
    return "$EXIT_OK"
  fi
  {
    echo "FATAL: no unambiguous packaged jar for $module under $ROOT/$module/target"
    echo "  expected exactly: $exact"
    echo "  found ${#candidates[@]} non-sources/non-javadoc jar(s):"
    if [ "${#candidates[@]}" -gt 0 ]; then
      printf '    %s\n' "${candidates[@]}"
    fi
    echo "  stale or unexpected jars are present; run mvn clean (or remove the stray jars) and rebuild"
  } >&2
  return "$EXIT_FAIL"
}

# Shared tail of the pinned-clone fetch scripts (fetch-upstream.sh, fetch-apicurio.sh):
# put the working tree AT the pinned commit, then reject a dirty tree, because the parity
# suites read files, not refs, so a modified clone would silently reconcile or compare
# against edited sources. Prints the restore commands on rejection.
# <clone-path> <pinned-commit> <what-reads-the-tree>
checkout_at_pin_and_require_clean() {
  git -C "$1" checkout --quiet --detach "$2"
  if [ -n "$(git -C "$1" status --porcelain)" ]; then
    {
      echo "FAIL: $1 has local modifications; $3 reads the working tree,"
      echo "  so a modified clone is rejected. Restore it with:"
      echo "    git -C $1 restore ."
      echo "    git -C $1 checkout --quiet --detach $2"
      echo "    git -C $1 clean -fd"
    } >&2
    return "$EXIT_FAIL"
  fi
}

# Downloads the pinned upstream wire-compiler 7.1.0 toolchain jars into $1 and prints the
# classpath string for `java -cp` (shared by the fixture generators; DEC-5 build-time tools).
fetch_wire_compiler_jars() { # <dir>
  local dir="$1"
  (
    cd "$dir"
    fetch_maven() {
      curl -sL -o "$2" "https://repo1.maven.org/maven2/$1"
    }
    fetch_maven com/squareup/wire/wire-compiler/7.1.0/wire-compiler-7.1.0.jar wire-compiler.jar
    fetch_maven com/squareup/wire/wire-schema-jvm/7.1.0/wire-schema-jvm-7.1.0.jar wire-schema.jar
    fetch_maven com/squareup/wire/wire-runtime-jvm/7.1.0/wire-runtime-jvm-7.1.0.jar wire-runtime.jar
    fetch_maven com/squareup/wire/wire-kotlin-generator/7.1.0/wire-kotlin-generator-7.1.0.jar wire-kotlin-generator.jar
    fetch_maven com/squareup/wire/wire-java-generator/7.1.0/wire-java-generator-7.1.0.jar wire-java-generator.jar
    fetch_maven com/squareup/wire/wire-swift-generator/7.1.0/wire-swift-generator-7.1.0.jar wire-swift-generator.jar
    fetch_maven com/squareup/wire/wire-grpc-client-jvm/7.1.0/wire-grpc-client-jvm-7.1.0.jar wire-grpc-client.jar
    fetch_maven com/squareup/kotlinpoet/kotlinpoet-jvm/2.3.0/kotlinpoet-jvm-2.3.0.jar kotlinpoet.jar
    fetch_maven com/squareup/okio/okio-jvm/3.18.2/okio-jvm-3.18.2.jar okio.jar
    fetch_maven org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.jar kotlin-stdlib.jar
    fetch_maven com/google/guava/guava/33.7.1-jre/guava-33.7.1-jre.jar guava.jar
    fetch_maven com/palantir/javapoet/javapoet/0.19.0/javapoet-0.19.0.jar javapoet.jar
    fetch_maven com/google/guava/failureaccess/1.0.3/failureaccess-1.0.3.jar failureaccess.jar
  )
  echo "wire-compiler.jar:wire-schema.jar:wire-runtime.jar:wire-kotlin-generator.jar:wire-java-generator.jar:wire-swift-generator.jar:wire-grpc-client.jar:kotlinpoet.jar:okio.jar:kotlin-stdlib.jar:guava.jar:javapoet.jar:failureaccess.jar"
}

# Building antiwire

The repository holds the implemented pure-Java Wire port. Claims of parity or compatibility
are bounded by the ACTIVE suites in `config/verify-suites.json` that `scripts/verify.sh` runs
and reconciles; evidence beyond them lives in the `docs/` ledgers. The research and plan are
in `docs/`, the decision record in [docs/decisions.md](docs/decisions.md).

## Toolchain

- JDK 17 or later to build (Maven enforcer rejects older). Production compilation targets
  Java 11 via `--release 11` (DEC-3).
- Maven 3.6.3 or later. No wrapper is committed; the version floor is enforced instead.
- A JDK 11 for the consumer smoke is optional locally (see below) and always provisioned in CI.

## Known invocation constraint: `mvn test` with the shaded fixture in the reactor

`mvn test -pl <module> -am` fails in `wire-runtime-java` with `TestEngine with ID
'junit-vintage' failed to discover tests` (root cause: `NoClassDefFoundError` on a relocated
`io.apitomy.antiwire.parity.*` class). The shaded fixture's relocation output is
produced by the shade plugin in the `package` phase, so a `test`-scoped reactor run resolves
the `wire-upstream-shaded` dependency to its empty `target/classes` instead of a jar, and the
JUnit 4 parity tests cannot link. This predates the generator work (surefire dumps from
2026-10-02 00:07 onward) and does not affect the entry point: `mvn verify` and
`scripts/verify.sh` run the `package` phase and are green. Until the fixture owns a fix (for
example a test-phase-aware jar or surefire exclusion), use `mvn verify`, not `mvn test`, for
any reactor-scoped run.

## Verification entry point

`scripts/verify.sh` is the single entry point and the only command CI's build job runs. It:

1. bootstraps the pinned upstream clone: `scripts/fetch-upstream.sh` runs BEFORE the build,
   because clone-dependent tests (`TestFiles.upstreamClone`, the golden corpora) initialize
   inside `mvn verify`. The clone path defaults to `/tmp/wire` and is overridden with
   `ANTIWIRE_UPSTREAM` (CI passes its cached `~/antiwire-upstream` this way, so local and
   CI runs share one contract). An existing correct clone is reused, never re-cloned; a
   clone whose tag resolves elsewhere, or whose worktree carries local modifications, is
   rejected with an actionable error; when the sources cannot be obtained (network, pin),
   the run fails fast with an explicit prerequisite message and no build is started.
   `scripts/test-bootstrap.sh` is the offline regression check for these states (stubbed
   mvn, synthetic upstream repository);
1b. bootstraps the pinned Apicurio Registry corpus clone (TASK-39): `scripts/fetch-apicurio.sh`
   runs right after the upstream fetch with the same fail-closed shape; the dual-run identity
   harness reads the apicurio half of its corpus from this clone inside `mvn verify`. The
   clone path defaults to `/tmp/apicurio-registry` and is overridden with `ANTIWIRE_APICURIO`
   (CI passes its cached `~/antiwire-apicurio` this way);
2. runs `mvn verify` (build, enforcer dependency policy, module smoke tests, classpath exports);
3. runs `scripts/check-classpath.sh`: per module, reports where classes under the retained
   prefixes load from and fails if one class name resolves from two artifacts. The retained
   prefixes live in `config/retained-prefixes.txt` (one per line), the single source for the
   check and the retained-namespace list resolved under OPEN-1; update it if namespaces move. The check fails closed when that file is missing or empty;
4. runs `scripts/check-java11-bytecode.sh`: module jars and production dependency jars must
   expose only class files Java 11 can select (major version 55 or lower, multi-release aware);
5. runs `scripts/consumer-check-java11.sh`: compiles and runs the consumer (ProtoWriter and loading-layer smokes)
   on an actual Java 11 JVM against the module jars;
6. runs `scripts/surface-check.sh` (suite `surface-check`, TASK-34): the source-compatibility
   surface check described below. The dual-run identity suite (TASK-39, PENDING at the time
   of writing) reconciles the DualRunIdentityTest surefire report of step 2: corpus floors
   (150 upstream, 40 apicurio files), Failures/Errors/Skipped 0, and every divergence-ledger
   row's doc anchor present. The full list of suites, 13 ACTIVE at the time of writing, is
   the registry in `config/verify-suites.json`; this numbered list is a summary of the steps.

### Source-compatibility surface check

Compatibility target (maintainer decision 2026-10-09): source compatibility for every public
upstream member that Java can express without Kotlin types (DEC-4) and without okio in the
signature (DEC-14). Binary compatibility is not promised (DEC-2).

`scripts/surface-check.py` compares every public and protected member of the real Wire 7.1.0 jars
(`wire-runtime-jvm`, `wire-schema-jvm`, `wire-java-generator`, `wire-compiler`) with the port's
module jars, by full signature. The upstream jars are fetched from Maven Central into
`target/surface-check/jars` and verified against the SHA-256 pinned in `config/parity-pins.json`
and the SHA-1 Maven Central publishes; without a verified copy the suite is `NOT_RUN`, never
skipped. Each member is MATCH (counted only), GAP (to fix or consciously accept, with an owner
task) or EXCLUDED (Kotlin type, okio type, Kotlin internal, out-of-scope feature, data-class
bridge). Checked-exception differences are a separate `THROWS` category. The small consumer
programs in `scripts/surface-consumer/` are compiled against the real jars (must compile) and
against the port (must compile, unless the header records `surface-expect: port=fail owner=...
reason=...`).

The ledger `config/surface-baseline.tsv` must equal the computed result: a new difference, or a
gap that was fixed and is now stale, fails the suite. After a reviewed change, regenerate it with
`scripts/surface-check.sh --update` (add `--allow-new` to accept new rows) and review the diff.

`scripts/scan-consumer-jars.py --port-jar <module jar> ... <consumer jar> ...` reads the constant pool of compiled
consumer jars (for example Confluent's kafka-protobuf-provider) and lists every `com.squareup.wire.*`
member they reference that the port jars do not provide; it reports and does not fail.

`scripts/footprint-tree-sizes.py --repo <m2 root> --module <artifactId> <base tree> [<after tree>]`
recomputes the classpath sums of `docs/footprint.md` from `mvn dependency:tree -DoutputFile=...`
output: test, provided and system subtrees pruned, the module's own artifact left out, each jar
sized from the given local repositories; with two trees it prints the net change, the jars
removed and added, and the jars whose version changed.

Stale build outputs: after switching revisions, deleted or renamed test classes can survive
as stale `.class` files under a module's `target/test-classes`, and surefire runs them (or
fails discovery) against sources that no longer exist. The supported recovery is a clean
build of the affected module (`mvn clean verify`, or remove that module's `target/`); no
test exclusion ever papers over stale outputs.

The suite registry it prints comes from `config/verify-suites.json`. Each suite carries a
declared status, `ACTIVE` or `PENDING`; only the owning task flips one. PENDING suites are
never run and never printed as passed. The entry point records one result per suite:

- `PASS`: the suite printed `result=PASS` and exited `EXIT_OK`;
- `FAIL`: the suite ran and failed, or printed `PASS` while exiting nonzero (the exit code
  is part of the recorded note);
- `MISSING`: the suite could not produce a verdict; recorded with the suite script's exit
  code (a `NOT_RUN` reported by the suite script lands here), and synthesized by the entry
  point when a suite records no result at all;
- `NOT_RUN`: the status a suite script prints when it cannot run, for example when no Java
  11 toolchain exists locally.

Any ACTIVE suite whose result is not `PASS` fails the whole run. A green `verify.sh` means
exactly: every ACTIVE suite recorded `PASS`, nothing more.

Exit codes are defined once in `scripts/lib.sh`: `EXIT_OK=0` (a check ran and passed),
`EXIT_FAIL=1` (a check ran and failed, or a precondition is broken), `EXIT_NOT_RUN=3` (a
suite could not run at all). Every script sources `lib.sh`, which fails loudly at startup
when the module list there and the parent `pom.xml` `<modules>` block disagree.

`scripts/verify.sh` requires `python3` on PATH: the final registry reconciliation is an
embedded Python script.

The Java 11 toolchain for step 5 (the consumer smoke) is found via `$JAVA11_HOME`, `$JAVA_HOME_11_X64` (set by
CI), `$JAVA_HOME` when itself 11, or sdkman and common system locations. Without one
locally, the suite reports `NOT_RUN` and the run fails. CI has a single job: Maven runs on
Temurin 17 while Temurin 11 is provisioned as `JAVA_HOME_11_X64`, and step 5 executes on
that real JDK 11 in the same job; there is no second consumer job and no artifact
upload or download.

## Coordinates

The groupId is `io.apitomy` (DEC-19, 2026-11-03; `io.apicurio` before it, resolved by
maintainer directive on 2026-10-02 and reconfirmed 2026-10-06, DEC-8). Authorization to publish a release is a separate, still-closed gate:
deployment is disabled (`maven.deploy.skip` is true in the parent pom) and a release must not
be published before TASK-21's release gates pass and the maintainer approves.

### Publishing snapshots (DEC-18)

`0.1.0-SNAPSHOT` builds may be published to the Central Portal snapshot repository with the
`publish-snapshots` profile:

    mvn -B -P publish-snapshots -DskipTests deploy

Credentials come from a `settings.xml` server with id `central-portal-snapshots` (a Central
Portal user token). To check the published set without credentials, deploy to a local
directory:
`mvn -P publish-snapshots -DskipTests -Dmaven.install.skip=true -DaltDeploymentRepository=local::file:///tmp/snapshot-check deploy`.
What is published, the guards against publishing a release, and the `publish-snapshot` CI job
are described in DEC-18 (`docs/decisions.md`).

## Kotlin policy

Kotlin is wired for test compilation only (`src/test/kotlin`, test-scoped stdlib, no
production execution of the Kotlin plugin). Maven enforcer bans `org.jetbrains.kotlin:*`,
`org.jetbrains.kotlinx:*` and `com.squareup.okio:*` from production scope, including
transitives (DEC-4).

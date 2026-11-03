# Parity runner (TASK-14)

One reproducible command verifies the port against the pinned upstream: `./scripts/verify.sh`.
Its first step bootstraps the pinned upstream clone (before the build: the clone-dependent
tests initialize inside `mvn verify`), and its `parity-coverage` suite proves provenance and
reconciles the upstream test inventory against the port artifacts, blocking on lost cases
and unrecorded drift.

## Pins and provenance

`config/parity-pins.json` stores the upstream identity: repository, tag `7.1.0`, the
annotated tag object (`da24c33ee1fe772a7a04617018087f46f26d1708`), and the commit it must
resolve to (`9f62097dfe4995b5709d001ca0187e30ca0530ef`), plus the build-time fixture pins
(wire-compiler jars from Maven Central, protobuf-java test oracle 4.36.1).
`scripts/fetch-upstream.sh` retrieves the clone (default `/tmp/wire`, override with
`$ANTIWIRE_UPSTREAM` or the first argument), fetches the tag into an existing clone when
missing, and refuses to proceed when the resolved identity differs from the pins or when
the worktree carries local modifications: reconciliation reads the working tree, so a
modified clone is not the pinned sources. `scripts/verify.sh` runs this fetch as a
prerequisite before `mvn verify` (fail fast with an explicit message, no build), and
`scripts/test-bootstrap.sh` is the offline regression check covering absent, valid,
modified, wrong-pin and unreachable clone states with a stubbed mvn.

## Reconciliation

`scripts/check-parity-coverage.py` walks the in-scope upstream modules
(wire-runtime commonTest; wire-schema commonTest + jvmTest; wire-tests jvm-java-kotlin and
jvm-kotlin-proto-reader-32; the protoc compatibility suites; the Java generator and
compiler suites), extracts `@Test` method names from every upstream file and from each
mapped port file, and enforces per `config/upstream-case-map.json`:

- `adopted`/`partial`: every upstream case must exist in the mapped port file, after the
  file's `renames` map and minus its `missing` entries (each of which carries a reason
  naming the non-ported feature). Port-only extra cases are reported, not fatal.
- `skipped` dispositions: a port case that is `@Disabled`/`@Ignore` is lawful only when
  the pinned upstream ignores the same case (a mirrored skip, read from the clone; the
  ten currently mirrored skips are PrunerTest's four map-variant cases, ParseTest's two,
  InteropTest `boxOneOfsJava`, DynamicSerializationTest `mapTest`, SchemaTest
  `linkExtendTypeInOuterMessage`, and TestAllTypes `testUnknownFieldsTypeMismatch`) or
  the map records the skip with a reason and exactly one of:
  `exclusion` naming a DEC (a declared non-ported feature, DEC-6 today) or `owner` naming
  the TASK that owes the case (none remain open: the generated-model Empty case closed
  with TASK-16.1 and the two ProtoTarget cases closed with TASK-16.2's port). Any other
  skip fails with the case
  identity; the annotation's reason string alone is never a disposition. Class-level
  disables need a `skipped_class` record.
- `fixture`: a fixture entry must carry no `@Test` methods on either side; an executable
  file cannot leave the inventory through the fixture flag.
- `deferred`: an owning task must be recorded (currently none; see the deferred table in
  [task13-case-accounting.md](task13-case-accounting.md)).
- `excluded`: a reason naming the excluded feature must be recorded (DEC-6 JSON adapters,
  the manual ParsingTester harness, the shared Assertions helper).

Fixture directories and non-executable upstream modules are distinguished in the map's
`excluded_upstream_modules` (fixtures feed `scripts/generate-java-fixtures.sh` and
`wire-tests-java`; they are not suites). An upstream test file inside the in-scope modules
that is absent from the map fails the run, as does a map entry whose upstream file
disappeared: both directions of drift are caught.

### Execution reconciliation

Source-presence is not execution. With `--execution` (added by `scripts/parity-coverage.sh`
after the green build), the checker parses the per-class surefire XML reports of the five
test modules and reconciles case IDENTITIES: every `@Test` method declared in a mapped
executable port file must appear in its class's report, every runtime skip must carry the
lawful disposition validated above (an annotation-free skip, e.g. a JUnit assumption,
needs a record too), and a report case absent from the port source means stale compiled
tests ran (clean build required). Aggregate module totals deliberately play no gate role:
port-only extra classes (the okio suites, the security corpus, parity locks), parameterized
invocations (`name[1]`, `name[Buffer]`) and port-only extras all shift counts without
saying anything about coverage, which is exactly why the reconciliation is per-case
identity. The `runtime-tests`/`schema-tests`/`protoc-oracle`/`compiler-tests` suites keep
reporting their module totals, with skips reconciled by identity here.

`scripts/test-parity-gate.sh` is the offline regression check: deletion, unrecorded
method-level skip, class-level skip and false-fixture mutations of a scratch tree must
each fail for the intended reason, mirrored and recorded skips must pass, an unrecorded
extra skip in a replayed module summary must be caught by the identity reconciliation, and
release validation must reject owner-recorded skips while accepting DEC-approved
exclusions.

Case ledgers (the human-readable counterparts of the map):
[task9-case-accounting.md](task9-case-accounting.md) for the runtime suites,
[task13-case-accounting.md](task13-case-accounting.md) for the schema suites.

## Release validation

`python3 scripts/check-parity-coverage.py --require-complete` additionally fails while any
deferred case remains and while any skip disposition still carries an open `owner` task,
so required cases that are skipped pending their owner block release, while DEC-approved
feature exclusions do not. TASK-21's release procedure must run it; partial M2/M3 coverage
is never release parity.

## Extension contract

- **TASK-15 (protoc oracle)** adds a `protoc-oracle` entry to `config/verify-suites.json`
  and a blocking job in `.github/workflows/ci.yml`; its cases extend the map with a new
  in-scope module section pointing at the protoc corpus, same statuses and reasons.
- **TASK-16 (compiler, generator, profiles)** closes the deferred entries by porting their
  suites (flipping map entries to `adopted`) and adds blocking compiler/profile and
  generated-code comparison jobs. The `compiler-tests` suite flips ACTIVE the way
  `schema-tests` did.
- **TASK-17 (security corpus)** verifies the final required suite and may add its own map
  section for corpus cases.
- **TASK-39 (dual-run identity)** registers the `dual-run-identity` suite: the harness
  (`DualRunIdentityTest`, `wire-protoc-compat-java`) stages every corpus `.proto` the way a
  consumer like Apicurio Registry stages it (per-file temp root, package-mirroring path,
  staged import closure) and loads it through real Wire 7.1.0 (the relocated fixture) and
  the port in one JVM, asserting identical load outcomes, root-cause failure chains, raw
  descriptor bytes and canonical text. Its ledger is
  `config/dual-run-divergences.tsv`; every observed difference must land there as
  a committed row citing a `docs/compatibility-matrix.md` anchor (subsection
  task-39-dual-run-divergences), and a committed row nothing observes any more fails the
  stale-row census. Corpus bump procedure: move the pin (upstream in `upstream`, registry in
  `apicurio_registry`), re-run `scripts/fetch-upstream.sh` and `scripts/fetch-apicurio.sh`
  with fresh clone paths, re-run the harness, and account for every delta as a port fix or
  a ledger row with its doc anchor; floors in `scripts/dual-run-identity.sh` (corpus counts
  and both-LOADED coverage per half) catch silent corpus shrinkage and silent coverage
  loss. `bash scripts/dual-run-identity.sh --update` appends the observed
  divergences to the ledger for a reviewed diff.

## Bump procedure

1. Update `upstream` in `config/parity-pins.json` to the new tag, annotated tag object,
   and resolved commit (`git rev-parse <tag>^{tag}` and `git rev-parse <tag>^{commit}`
   inside a fresh clone).
2. Run `./scripts/fetch-upstream.sh` with a fresh clone path; the pin check must pass.
3. Run `python3 scripts/check-parity-coverage.py`. Expect failures where the new upstream
   tag added, renamed, or removed cases; update `config/upstream-case-map.json` and the
   two case ledgers to account for every delta (a case is ported, recorded missing with a
   reason, deferred with an owner, or excluded with a reason; nothing else).
4. Port or disposition the new cases, then run `./scripts/verify.sh`; all ACTIVE suites
   must pass.

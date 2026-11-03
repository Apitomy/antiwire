# antiwire decision record

Status: approved planning baseline, 2026-09-29. This record carries the maintainer decisions taken after the external plan review (see `docs/plan-review.md` for the disposition of every review item) and the open technical decisions assigned to M0. Implementation later proceeded under milestones M0-M5 and the publication gate (DEC-8, TASK-21) remains closed; as written on 2026-09-29 it authorized planning only: no implementation, no publication, no Apicurio source change, and no repository or organization transfer follows from this document. Rationale and evidence live in `docs/research-wire-java-port-2026-09-29.md`; each entry below names the section that carries it.

## Settled decisions

### DEC-1 Semantic target: Wire 7.1.0

The port reproduces Wire **7.1.0** semantics. Pinned identity: annotated tag object `da24c33ee1fe772a7a04617018087f46f26d1708` pointing at commit `9f62097dfe4995b5709d001ca0187e30ca0530ef` (release date 2026-09-28). Both hashes are recorded because they name different git object kinds, the tag object and the peeled commit, and reviews of this plan have cited both. There is no 6.4.0-equivalence mode. The behavioral delta between Apicurio's current 6.4.0 pin and 7.1.0 is captured upstream-versus-upstream (TASK-5 baseline capture, TASK-18 delta-aware acceptance), never absorbed silently by the port. Rationale: research report F1, F4; consumer-slice verification.

### DEC-2 Compatibility contract: functional and source compatibility, documented migration sites only

The port guarantees functional compatibility and source compatibility for intended consumers, except at documented, individually justified migration sites. The initial set of migration sites is the limited Apicurio source migration approved as project scope: `ProtobufSchemaLoader.java`, `ProtoContent.java`, `FileDescriptorUtils.java`, `ProtobufFile.java`, and the `utils/protobuf-schema-utilities` POM, an observed minimum verified against the local Apicurio clone at commit `48e2742acc68fae456fe252cc8b08243e376ebfc` (clone dated 2026-09-24), not a guaranteed-exhaustive or current-master list; TASK-18 re-inventories before editing. There is no precompiled ABI guarantee: classes compiled against Wire 6 or Wire 7 artifacts are not promised to link against the port. Public types keep `com.squareup.wire` names where feasible; every exception is recorded in the TASK-1 compatibility matrix with its owner and migration requirement. Deliberate duplicate-class coexistence (the port and an upstream Wire artifact on one application classpath) is excluded, and the layout rules must make accidental coexistence detectable. Rationale: research report F3, F5.

### DEC-3 Platform and build: Java 11 bytecode, Maven, JDK 17+

Artifacts compile with `--release 11`; building requires JDK 17 or later; the build tool is Maven, matching Apicurio's consumption model. `--release 11` governs the port's own compilation only: every retained production dependency's bytecode and API usage must also run on Java 11, and a real Java 11 consumer smoke exercises the runtime and schema artifacts (the optional generator as well if it is published at the same baseline). TASK-2 owns the automated enforcement and the smoke runner (TASK-2 AC#3 and AC#5); TASK-5 exercises it on the spike (TASK-5 AC#7); TASK-21 runs it on the final release candidate and the published artifacts (TASK-21 AC#6). Test and build tooling is not forced to Java 11, and JDK 17 or later remains the build baseline. Java 11 forbids records and sealed classes in production sources; local-variable `var` is available and is not prohibited. Rationale: research report F6, plan section.

### DEC-4 Dependency policy: no Kotlin in production, Java dependencies allowed

Production artifacts and their transitive dependencies contain no Kotlin: no kotlin-stdlib, no Kotlin-backed library, no Kotlin compiler plugin in production scope. Small pure-Java third-party dependencies are allowed; each one must justify itself with a measured footprint contribution and a license review, must satisfy DEC-3's Java 11 runtime constraint, and the maintainer accepts the measured footprint before release. Kotlin remains permitted for build and test tooling, including test compilation and pinned upstream generators running as isolated build-time fixture tools. The earlier "zero non-JDK dependencies" framing is dropped and is not a requirement. Rationale: research report F2, F3.

### DEC-5 Test scope: every relevant case mandatory, tracked mechanical adaptations

Every relevant upstream test case is mandatory, including full runtime and reflection coverage. Relevance is determined by the behavior a case covers, not by which upstream module or source set hosts it: runtime behavior tests living outside the wire-runtime module, including the shared wire-tests JVM suites (jvm-java-kotlin, with serialization, unknown-field retention, and redaction cases) and relevant cases inside mixed suites, are part of the mandatory scope, while fixture-only code is not itself coverage. Upstream Kotlin tests stay Kotlin and run against the Java production artifacts with mechanical adaptations only: named-argument call forms rewritten, omitted-default call sites rewritten or served by Java overload bridges as determined per site, package-level and member-extension imports rewritten, fixtures regenerated by pinned build-time tools (upstream fixture generation avoids any dependency on the port's own generator). The pinned original sources are preserved for drift comparison; every adaptation is individually recorded and reviewed, preserving inputs, scenarios, expected values, and error paths. Exclusions are allowed only for declared non-ported functionality (DEC-6), never for a failing or inconvenient test. Mixed compiler suites are not excluded wholesale: their Java-target cases are retained or ported, and only cases that exclusively cover excluded features are omitted. TASK-9 AC#1 and AC#2 build the source-derived inventory across wire-runtime commonTest and the applicable cross-module runtime suites; TASK-14 AC#1 runs the cross-module runtime inventory and its AC#6 reconciles source and build inventories, rejecting missing relevant cases. Rationale: research report F4; upstream-slice verification of the Kotlin language restriction.

### DEC-6 Excluded functionality (initial release)

JSON adapters (gson-support, moshi-adapter), the gRPC client, the gRPC reflection product `wire-reflector` and its grpcurl interop suite, Kotlin and Swift generator products, the Gradle plugin, editions support (upstream itself rejects editions at the pinned tag), and non-JVM targets are excluded from the initial release. The wire-reflector exclusion is a product exclusion only: the runtime's own JVM reflection machinery is not gRPC reflection and stays mandatory test scope under DEC-5. Upstream generators may still run as isolated build-time fixture tools under DEC-4. Consequence: the 7.1.0 JSON null-element rejection fix lives in the excluded adapters and is recorded as out of scope, not silently dropped. Rationale: research report F1, F4.

**DEC-6 scope note, 2026-10-06 (maintainer decision):** ProtoTarget `.proto` emission is retained in the initial release scope. DEC-6 itself is unchanged because its exclusion list never named ProtoTarget or `.proto` emission; TASK-16's delivery framing around the Java-target compiler did not by itself narrow the retained compiler surface. TASK-16.2 therefore ports `ProtoTarget` to the optional generator module and revives its two upstream cases (`protoOnly`, `protoTargetNeverEmitsGoogleProtobufDescriptor`).

### DEC-7 Consumer priority: Apicurio first, Kafka deferred

Apicurio Registry is the initial release acceptance target. Apache Kafka is a later adoption target and does not gate the first release. The concrete Kafka use case is a product decision to be named when that phase starts. Rationale: research report F5, F6.

### DEC-8 Publication and organization boundaries (superseded by DEC-19)

The Maven groupId was `io.apicurio` (now `io.apitomy` per DEC-19, 2026-11-03). The namespace use is authorized by the maintainer (directive 2026-10-02, reconfirmed 2026-10-06); authorization to publish artifacts is a separate gate that stays closed until TASK-21's release gates pass and the maintainer approves the release explicitly (TASK-21 AC#1). Defining the plan can precede implementation, but hosting or transferring the repository under an organization requires agreed scope, namespace permission, and maintenance arrangements. Rationale: consumer-slice governance verification.

### DEC-9 Performance: measure and approve before release

The first release requires measured runtime benchmarks (encoding and decoding against upstream Wire 7.1.0) and measured Apicurio schema-operation benchmarks (parsing, linking, loading, descriptor conversion). Any regression requires explicit maintainer acceptance; unresolved or unmeasured results block release. Numeric thresholds are set only after baseline and variance measurement, not in advance. Measurement and acceptance records identify the measured candidate's artifact checksums, build revision, and resolved dependency set; a relevant code, dependency, or packaging change invalidates them and requires remeasurement and renewed acceptance. TASK-20 owns the evidence (AC#5); TASK-21 refuses stale records (AC#3). Rationale: research report F8.

### DEC-10 Footprint: measured, clean-consumer versus marginal

Footprint claims come from resolved dependency trees and actual artifact sizes for a clean minimal consumer and for the Apicurio integration branch, using real coordinates and POMs. Clean-consumer totals (where the upstream `wire-schema` POM makes guava, javapoet, and kotlinpoet mandatory `api` dependencies) are distinguished from marginal totals (dependencies a consumer such as Apicurio already has). No port jar-size or savings figure is promised in advance; the port's size is a measurement outcome. Footprint and acceptance records identify the measured candidate's artifact checksums, build revision, and resolved dependency set; a relevant code, dependency, or packaging change invalidates them and requires remeasurement and renewed acceptance. TASK-19 owns the evidence (AC#4); TASK-21 refuses stale records (AC#3). Rationale: research report F2.

### DEC-11 Upstream synchronization: ready before first release

The upstream tracking and re-sync procedure must exist and be demonstrated before the first release and is a release prerequisite. Its worked example replays an already-published applicable upstream change that includes at least one security fix, and rehearses locally, using the port's own verification tools: detection, applicability triage, owner routing with simulated escalation, source-diff extraction, implementation and test adaptation, regression detection, and a full parity rerun on isolated classpaths. The exercise is recorded locally; no real incident notifications are sent. A dry run against the actual next upstream release happens when it ships. Rationale: research report risk register, TASK-23.

### DEC-12 Licensing and provenance

The port is Apache 2.0 overall and preserves upstream attribution verbatim, per file, including notices embedded in file bodies, not only leading headers. Five upstream files carry BSD-style notices: `ProtoReader.kt`, `ProtoReader32.kt`, `ByteArrayProtoReader32.kt`, `MathMethods.kt` (R8), and `ProtoWriter.kt`, which carries a Square Apache-2.0 header followed by a Google Nano BSD-style notice in its body. The JetBrains `ArrayList` files are Apache-2.0. The NOTICE file carries the BSD-style notices verbatim plus separate Apache-2.0 attribution for any vendored or adapted okio code and for every retained third-party dependency. Legal approval is not claimed. Rationale: research report F3.

### DEC-13 Release prerequisites

The release task (TASK-21) depends on the footprint report (TASK-19), the performance report (TASK-20), and the upstream sync demonstration (TASK-23). The transitive closure of those three covers the execution tasks TASK-1 through TASK-20 and TASK-23; the Kafka path (TASK-22) is outside the release gate by DEC-7. M5 therefore carries explicit release gates: measured footprint accepted, measured performance accepted or regressions explicitly accepted, sync procedure demonstrated, full required CI suite green with no pending required case, a release-time recheck that published dependency metadata contains no Kotlin, the Java 11 consumer smoke of DEC-3 run on the final candidate, and freshness checks that every measurement and acceptance record identifies the final candidate's artifact checksums, build revision, and resolved dependency set, with records invalidated by later relevant changes refused.

**DEC-8 resolved 2026-10-02 (maintainer directive):** the provisional `io.github.paoloantinori` coordinates are replaced by `io.apicurio` everywhere (group IDs, the wire-upstream-shaded parity relocation package `io.apicurio.antiwire.parity`, BUILD/docs references). The maintainer's message authorized the namespace use only; publishing artifacts stays a separate gate (TASK-21 AC#1). Measurement docs (footprint, performance) carry dated coordinate-update notes; class and resource bytes are unchanged by a coordinate switch, but whole-jar SHA-256 values change with the embedded META-INF/maven groupId path, so whole-jar checksum records were re-issued (footprint.md section 10, performance.md coordinate note). **Superseded by DEC-19 (2026-11-03):** the groupId is now `io.apitomy` and the repository moved to the Apitomy organization; every publish gate set here carries over unchanged. Unlike the DEC-8 switch, the checksum records were not re-issued for DEC-19: nothing was published under either namespace, and the already-required remeasurement (TASK-21) re-issues them.

## Open technical decisions assigned to M0 (not settled here)

These are recorded as open so that no document pretends they are decided. TASK-4 owns the evaluation, TASK-5 validates it, and the results land back in this file as M0 outputs.

### OPEN-1 I/O strategy, namespaces, and the type bridge (resolved 2026-10-01; see "OPEN-1, buffer half: resolved" and DEC-14)

The exact I/O route is an M0 spike outcome. Candidates: reviewed pure-Java dependencies, a translated okio subset (under original or relocated package names), a scoped `java.nio` replacement, or a bounded combination. The inventory that any route must cover: ByteString and its companion operations, Buffer, Source/Sink and buffered variants, UTF-8 handling, exception types (okio IOException, EOFException; okio ProtocolException appears at the pin only in test scope, per the compatibility matrix), Closeable, utf8Size, FileSystem, Path, FileHandle, in-memory sources, classpath resources, and ZIP loading (the loader's compression-relevant concern is ZIP, not gzip). No forced drop-in assumption: modern okio is Kotlin and barred from production scope; okio 1.x ended at 1.17.6 and its source purity is unverified, so it is not assumed to be a drop-in replacement. The chosen public namespaces constrain upstream test adaptation, generated code, and the Apicurio migration sites; all three consequences are recorded with the choice.

### OPEN-2 Publishing combination (module boundaries are fixed; resolved 2026-10-01, see "OPEN-2: resolved")

The development module boundaries are settled, not open: runtime and schema are separate modules that stay independently consumable, and the Java generator is optional, so no consumer of runtime or schema pulls generator dependencies (TASK-1 AC#3, TASK-16 AC#5). What remains an M0 result is only the publishing combination: separate runtime and schema artifacts with a transitive edge (matching upstream), a single core jar, or another reviewed grouping. TASK-2 creates the provisional module shells so M0 experiments are not blocked; TASK-1 records the provisional grouping (AC#3); TASK-4 finalizes the grouping alongside the I/O route using spike evidence (AC#6), within the fixed boundaries of runtime and schema independent consumability and the optional generator; TASK-5 validates the finalized combination before M1 (AC#7). The duplicate-class prevention rules of DEC-2 apply throughout.

### OPEN-3 Public API compatibility matrix

TASK-1 inventories the actual public surface (Wire types, okio types embedded in it, parser ranges, profile APIs, generated-code APIs) against observed Apicurio call sites, using upstream `.api` dumps as a boundary checklist rather than a full-ABI gate, consistent with DEC-2. The matrix lives at `docs/compatibility-matrix.md` and is updated whenever a keep-name exception, namespace choice, or migration site changes.

### OPEN-4 Golden-output exactness versus namespace choice

Where the compatibility contract preserves a surface, generated output is byte-identical to upstream. If the M0 namespace choice requires a bounded mechanical mapping in generated code, that mapping is documented with its rationale, reviewed, checked automatically against pinned upstream goldens, and the generated output must compile and run. Owner: TASK-16, constrained by the OPEN-1 choice.

### Later-milestone technical decisions (owners named, not M0)

Public Java range type for reserved and extension ranges, with endpoint conventions and the consumer migration contract: TASK-10. Final loading API surface and FakeFileSystem handling (patch Apicurio's usage versus vendor a Java equivalent): TASK-12 implementation on the OPEN-1 boundary, TASK-18 at integration. Numeric performance thresholds: TASK-20, after baseline measurement per DEC-9.

## Estimate status

The 4-to-6-month, single-engineer estimate remains a provisional MEDIUM-confidence hypothesis. It is re-examined at M0 exit against measured I/O subset scope, one-slice translation velocity, and mechanically recounted suite sizes. No milestone or document may present it as proven.

## M0 spike outputs (2026-10-01, execution branch merged)

Recorded per the OPEN-1/OPEN-2 protocol above: TASK-4 evaluates, TASK-5 validates, results
land here. Full mapping and measured effort: docs/m0-execution-ledger-2026-10-01.md.

### OPEN-1, buffer half: resolved

Route: a translated okio subset vendored inside wire-runtime-java under the original
`okio` package names, based on okio 1.17.6 sources. Evidence: the vendored files were
verified byte-identical to the pinned 1.17.6 sources except two recorded annotation strips
(closing this record's "source purity unverified" caveat for the buffer layer), and okio's
own 1.17.6 JVM test suite runs verbatim against them: 732 tests, 0 failures, 4 upstream
skips. Namespace consequences, as required by OPEN-1: upstream test sources import `okio.*`
untouched; generated code stays source-compatible; no okio artifact ships, so the DEC-4
production-Kotlin ban holds trivially for this layer. The gzip and zlib family is omitted
with an import-inventory justification (the loader's compression-relevant concern is ZIP,
not gzip). The loading surface, previously open, is now implemented and inventoried:
okio.Path, okio.FileSystem (SYSTEM over java.nio, openZip over the JDK zip filesystem,
asResourceFileSystem for classpath reads) and okio.FileMetadata, original code with the okio
3 API shape; the pinned-source map with consequences and justified exclusions is
docs/loading-api-inventory.md, and FakeFileSystem was ultimately not ported (the schema suites that used it run on real temp directories, docs/task13-case-accounting.md; Apicurio usage is served by asResourceFileSystem behind JdkSchemaLoader), with the verbatim schema
tests.

### OPEN-2: resolved

The publication grouping is separate artifacts with a transitive edge, matching upstream:
`wire-runtime-java` and `wire-schema-java` are independently consumable, schema declares a
compile dependency on runtime exactly as upstream `wire-schema` declares `wire-runtime`
(api), and `wire-java-generator` is a separate optional artifact so no runtime or schema
consumer pulls generator dependencies. The reactor additionally carries
`wire-upstream-shaded`, a never-published test fixture (maven.deploy.skip, enforcer exempt,
relocation into the provisional namespace) holding the relocated upstream oracle; it is test
tooling, not a published grouping choice. Spike evidence: the whole M0 execution ran on this
layout, and the java11-consumer suite compiled and ran a real consumer against exactly these
module jars on Temurin 11.

### Loader boundary and duplicate-class policy (TASK-4 AC#4 fold)

The loader boundary is `okio.FileSystem` at the loading edge: translated wire-schema code
takes a FileSystem (the JVM constructor also accepts `java.nio.file.FileSystem` via
`asOkioFileSystem`), reads through `source` plus `metadataOrNull`, opens ZIP protoPath roots
through the closeable `openZip` handle, and loads classpath descriptors through the read-only
`asResourceFileSystem`; no okio artifact and no Kotlin type appears in production scope.
Duplicate-class coexistence stays excluded per DEC-2, enforced mechanically: the vendored
`okio` and `com.squareup.wire` classes exist only in the port's jars, the
`duplicate-class-check` suite proves no retained-prefix class resolves from two artifacts on
any module test classpath, and the parity fixture's in-reactor exclusion (its original pom
declares the upstream dependency the dependency-reduced pom removes once installed) is
recorded in wire-runtime-java's pom. Generated-code golden reconciliation under OPEN-4 is
unaffected by the namespace choice: namespaces are unchanged from upstream, so byte-identical
goldens remain the goal there.

### TASK-5 partial validation

Encoding-parity evidence: ProtoReader and ProtoWriter translated at the pinned tag; a live
relocated oracle asserts byte-for-byte output over 500 seeded writer sequences and identical
read transcripts over 200 structured messages, packed replay, group skipping, the recursion
limit, and six malformed-input vectors with exact exception messages, including
GHSA-7xpr-hc2w-34m9 on both applicable paths. Total suite 747 tests, 0 failures (spike-time count; the wire-runtime-java module now runs 909). The
remaining TASK-5 criteria (upstream test compilation against the slice, pinned fixture
generation, the 6.4.0-versus-7.1.0 baseline corpus, the null-boundary example suite, the
Java 11 consumer validation) stay open and are mapped per criterion in the execution ledger (closed 2026-10-02: TASK-5 is Done, every criterion met).

### Supersessions from the execution branch

The execution branch's earlier artifacts are superseded where they conflict with this
record: its own decision list (D1 through D8) is subsumed by DEC-1 through DEC-13 above
and by this section, except D5a, which the maintainer confirmed on 2026-10-06 and which is
recorded below as DEC-14; its zero-dependency enforcer and its separate CI dependency-list
assertion are dropped in favor of the reviewed dependency policy and the scripts/verify.sh
suite registry. Its Maven coordinates are `io.apitomy` (DEC-19; `io.apicurio` between
2026-10-02 and 2026-11-03 by DEC-8, authorized as final at its time): the interim
`io.github.paoloantinori` placeholder is gone everywhere including the parity relocation
packages, and `io.apicurio` remains only in dated records and in Apicurio's own artifacts.

### DEC-14 Consumer-facing API is JDK-typed; okio stays an internal engine (confirmed 2026-10-06)

The consumer-facing Java API of the port exposes no okio types. Wire-owned `Bytes` and
JDK-typed canonical members are the public forms for adapters, messages, `AnyMessage`,
`FieldEncoding` and the schema loading facade (`JdkSchemaLoader`). okio remains legal only as
the internal engine layer vendored inside `wire-runtime-java` under its original package
names, with no okio artifact on any production classpath.

Two kinds of okio-typed members remain, and they are different in kind:

1. `@Deprecated` compatibility bridges on the consumer-facing classes. They exist because the
   pinned-upstream fixtures (wire-tests-java, wire-protoc-compat-java) are generated by the
   unmodified upstream compiler and reference `okio.ByteString` (DEC-5). The port's own
   generator emits the `Bytes` forms, and the golden corpus is compared under the documented
   mapping (OPEN-4).
2. Engine surfaces that are deliberately NOT deprecated and are not consumer-facing:
   `ProtoReader`, `ProtoWriter`, `ReverseProtoWriter`, `ProtoReader32`,
   `ByteArrayProtoReader32` and `ProtoReader32AsProtoReader` (`readBytes()` returns okio),
   `SchemaLoader`'s okio-typed constructor, `SchemaEncoder.encode(ProtoFile)`, and the
   `com.squareup.wire.internal.*` packages. [api-surface.md](api-surface.md) lists them under
   "What remains okio-typed and why". A consumer who uses these classes directly is on the
   engine API and is not covered by the no-okio statement.

Enforcement is mechanical but bounded. `ConsumerApiSurfaceTest` checks the public
non-deprecated members of four root classes (`ProtoAdapter`, `Message`, `AnyMessage`,
`FieldEncoding`) and their public nested classes, and `JdkSchemaLoaderValidationTest`
(`noOkioTypesInPublicSignatures`) checks `JdkSchemaLoader`. Both fail closed on a new
okio-typed member on those classes, even a deprecated one. They do not cover other public
classes: a new okio-typed public member elsewhere is not caught by them and is a review
item. This refines DEC-2: source compatibility for upstream's okio-typed members is not a
goal; it is preserved through the deprecated bridge only where the DEC-5 fixtures require it.

### Provenance mechanism (decided 2026-10-07, TASK-30)

The per-file "Translated from" header that `docs/translation-conventions.md` section 7.2 proposed on 2026-09-30 is not adopted. The audit of 2026-10-06 found it on none of the 310 production files (161 in the three shipped modules, the rest in the never-published test modules), and adding it would change the class files and sources jars of the shipped modules and re-fire the DEC-13 invalidation rule without adding information the existing records do not carry. Provenance is carried by the retained package and class names, the pin in `config/parity-pins.json` (DEC-1), the verbatim upstream notices (DEC-12), `docs/license-inventory.md`, the adapted-test ledgers and case-accounting documents, `config/upstream-case-map.json` reconciled by the `parity-coverage` suite, and the task and commit history. Section 7.2 now states this mechanism.

### Accessor names follow upstream (decided 2026-10-09, TASK-33.1)

Kotlin properties are exposed with the Java names the real Wire 7.1.0 jars use: `getX()`, or `isX()` for boolean properties named `isX`. The port does not carry plain `x()` accessors or public fields next to them, so there is one name for each thing. Maintainer decision of 2026-10-09 in reply to the first user feedback (`NoSuchMethodError` on `ProtoFileElement.getPackageName()` and `NoSuchFieldError` on `ProtoParser.Companion`, TASK-33): `getName()` must not become `name()`. Members that upstream itself names without a prefix through `@JvmName` (for example `Service.type()`, `MessageType.fields()`, `Message.adapter()`) stay as they are. 426 upstream getters are pinned by `AccessorNameParityTest` against a baseline generated from the real jars (`scripts/gen-upstream-getter-baseline.py`; 406 and 55 gaps at the end of TASK-33.1, 426 and 35 after TASK-33.2 added classes and getters); 35 are recorded gaps with a reason each (Kotlin types such as `KClass`, upstream `Void` returns, classes the port keeps package-private, WireCompiler options of features outside the port). The Companion form (`ProtoParser.Companion`), missing constructors, absent classes and the `throws IOException` differences are not part of this decision and stay open under TASK-33. The measured candidates of docs/footprint.md and docs/performance.md predate this rename and are stale under DEC-13.

### Compatibility target (decided 2026-10-09, TASK-34)

The compatibility target is source compatibility for every public upstream Wire member that Java can express without Kotlin types (DEC-4) and without okio in the signature (DEC-14); binary compatibility is not promised (DEC-2). Members that cannot be expressed that way are listed as EXCLUDED with their reason in `config/surface-baseline.tsv`; every other difference is a GAP with an owner task, and the `surface-check` suite fails when the computed surface differs from that ledger, so a regression and a stale gap are both visible.

### Companion binary shape and the public surface (decided 2026-10-10, TASK-33.2)

Binary compatibility stays outside the promise of DEC-2 in general, with one exception recorded here: the Kotlin `Companion` form. A third-party jar compiled against Wire (Confluent kafka-schema-registry-client 8.0.0, `ProtobufSchema.toProtoFile`) reads the static field `ProtoParser.Companion` and calls `ProtoParser$Companion.parse`, so every upstream companion exists in the port as a `public static final Companion` field of a nested public `Companion` class whose public methods carry the upstream signatures and delegate to the existing statics. Everything else is source compatibility for members expressible without Kotlin (DEC-4) and without okio (DEC-14), enforced by the `surface-check` suite and its ledger `config/surface-baseline.tsv`. Poet-typed signatures (Palantir JavaPoet upstream, Square JavaPoet 1.13.0 here, DEC-3) and Guava-typed signatures are recorded as excluded. `scripts/scan-consumer-jars.py` scans compiled consumer jars for Wire references the port lacks; on 2026-10-10 Confluent kafka-protobuf-provider, kafka-schema-registry-client and kafka-protobuf-serializer 8.0.0 resolve 111 of 111 references, and Apicurio's protobuf modules at 3.3.4-SNAPSHOT 118 of 119 (the one miss is the okio-typed `Schema.protoFile(okio.Path)`).

### DEC-15 Range elements are `int[]` pairs, not `kotlin.ranges.IntRange` (decided 2026-10-10, open to reconsideration)

`ReservedElement.getValues()` and `ExtensionsElement.getValues()` return a `List<Object>` whose
entries are `String` names, `Integer` tags, or `int[]` pairs `{start, endInclusive}`. Upstream Wire
7.1.0 returns a `kotlin.ranges.IntRange` for a range. The port cannot do that without a Kotlin
type in its public API, which DEC-4 forbids.

**Consequence, measured on 2026-10-10 with the real Confluent jar.** Code compiled against Wire
that tests `instanceof kotlin.ranges.IntRange` on those values does not recognise a range from
the port. Confluent's `kafka-protobuf-provider` 8.0.0 does exactly that (18 references in
`ProtobufSchema`, and `ProtobufSchemaUtils.toString`): parsing and printing a schema work, but
`canonicalString()` on a proto2 schema with `extensions 100 to 199;` or `reserved 5 to 9;` throws
`IllegalArgumentException` (`ProtobufSchemaUtils.java:726`). The same input prints correctly with
real Wire 7.1.0. Source consumers (Apicurio's own code) adapt the `instanceof IntRange` branches
to `int[]` and are unaffected; the migration is recorded on the Apicurio branch
`antiwire-integration` (ANTIWIRE_MIGRATION.md).

**Decision.** Accept and document. The port keeps the Kotlin-free `int[]` form.

**What is not decided, and how to reconsider.** The alternative is an optional, separate
artifact that ships a hand-written Java class named `kotlin.ranges.IntRange` (a pair of
`Integer` accessors `getStart()` and `getEndInclusive()` and the `ClosedRange` members) so
binary-compiled consumers resolve it. Its costs: it puts a class in a package the project does
not own, it collides with the real `kotlin-stdlib` on any classpath that already has it
(duplicate-class check, DEC-4 boundary), and `ReservedElement.getValues()` would have to return
that type instead of `int[]`, a second public form to maintain. Reconsider when one of these
holds: a real consumer reports a failure through this path and cannot change its code; the
Apicurio Registry app tests that exercise Confluent's printer (`ProtobufSerdeTest.testSerdeMix`,
`ConfluentClientTest.testSerdeProtobufSchema`) fail on it; or a stable way appears to have the
shim and `kotlin-stdlib` coexist (for example a relocation-free marker interface both implement).
Record any new decision as DEC-16 and update this section.

### DEC-16 Checked exceptions follow upstream's `@Throws`, with two forms of surfacing (decided 2026-10-09, implemented 2026-10-10)

Wire is Kotlin and has no checked exceptions: a function declares `IOException` in bytecode only
where it carries `@Throws(IOException::class)` (45 functions). The port had declared checked
exceptions on 79 more methods, so Java code that compiles against Wire without a `try/catch`
(`message.encode()`, `schemaLoader.loadSchema()`, `anyMessage.unpack(adapter)`) did not compile
against the port. The maintainer decided on 2026-10-09 ("Unchecked") to remove them.

**Rule.** Every public or protected method keeps `throws IOException` exactly where upstream
declares it (verified member by member with `javap` on the 7.1.0 jars; the surface check pins it)
and declares no checked exception where upstream does not.

**Two ways a failure surfaces on a method that no longer declares it.**

1. Top-level convenience methods a consumer calls from plain code (`Message.encode()`,
   `Message.encodeByteString()`, `ProtoAdapter.encode(E)`, `encodeByteString(E)`,
   `AnyMessage.pack`/`unpack`/`unpackOrNull`, `SchemaLoader.initRoots`/`loadSchema`/`loadProfile`,
   `WireRun.execute`, `DryRunFileSystem.sink`/`appendingSink`, `ReverseProtoWriter.emitCurrentSegment`)
   wrap the `IOException` in `java.io.UncheckedIOException` with the original as cause.
2. Helpers that generated code and runtime adapters call from inside a method upstream does declare
   with `@Throws` (`Internal.decodeMessageOrMerge`, `Internal.decodePrimitive_*`,
   `ProtoReader.readUnknownField`/`addUnknownField`/`nextFieldMinLengthInBytes`,
   `ByteArrayProtoReader32` and `ProtoReader32` equivalents, `OneOf.encodeWithTag`,
   `RuntimeMessageAdapter.encode`/`decode`) rethrow the original exception unchanged through
   `com.squareup.wire.internal.Rethrow`. This is what Kotlin does, and it is required: upstream's
   own tests (`ParseTest.overRecursionLimitThrowsIOException`, `ProtoReaderTest`,
   `ProtoReader32Test`) assert that a failing `decode` surfaces a plain `IOException` such as "Wire
   recursion limit exceeded". Wrapping there would turn every generated message's `decode` failure
   into an `UncheckedIOException` and break those tests.

**Cost, accepted.** A Java caller that writes `catch (IOException e)` around one of these methods
gets the compile error "exception IOException is never thrown in the body of the corresponding try
statement". Real Wire has the same property, so code written against Wire already avoids it.

**Not changed.** `WireCompiler.main` keeps `throws IOException` (upstream declares `@Throws` there);
`EnumAdapter.encode`/`decode` and the whole reader/writer/adapter `encode(writer, value)` /
`decode(reader)` family keep it too. Compiler-generated bridge methods (erased return type) are
not compared: Kotlin emits them without a throws clause and javac copies the source method's.
Enforcement: `config/surface-baseline.tsv` has no row owned by TASK-33.3 and the TASK-34 suite
fails on a new one; `UncheckedExceptionContractTest` pins both halves by reflection and by
behavior.


### DEC-17 JdkSchemaLoader keeps its checked IOException (decided 2026-10-11, confirmed 2026-10-29)

`JdkSchemaLoader` is antiwire's own facade, with no Wire counterpart, so DEC-16's rule (copy
upstream's `throws` member by member) does not apply to it. It declares `throws IOException` on
`forClasspath`, `initRoots`, `loadSchema` and `close`, and keeps doing so.

Rationale: the class does real file I/O, and a caller has to deal with failure. This was the
recommendation of 2026-10-10, MikeEdgar's vote on GitHub issue #8 (2026-10-11, option A), and
the maintainer's confirmation of 2026-10-29. The Apicurio migration branch needed no change from
DEC-16 precisely because its `IOException` handlers guard calls on this class.

### DEC-18 -SNAPSHOT builds may be published before the first release (decided 2026-10-29)

The parent pom and the three shipped modules may be published as `0.1.0-SNAPSHOT` to the Central
Portal snapshot repository (`https://central.sonatype.com/repository/maven-snapshots/`) before
the first release. Releases, including pre-release qualifiers such as `-alpha` or `-rc` (which
Maven and Central treat as releases: immutable and permanent), stay gated by DEC-8 and TASK-21.

Rationale (maintainer, 2026-10-29): consumers such as Apicurio can try a current build without
installing antiwire locally, while nothing permanent is published. A -SNAPSHOT resolves only
from the snapshot repository, which a consumer must declare explicitly (Maven Central's release
repository does not serve it); tooling that filters out SNAPSHOT versions skips it; and Central
deletes it after 90 days. A snapshot is still a binary distribution, so the main jars and the
tests jar carry `META-INF/LICENSE` and `META-INF/NOTICE` and the poms declare their licenses;
the sources jars keep the per-file headers (the sources and javadoc jars without the two files
are a TASK-21 release-audit item).

Mechanism: the `publish-snapshots` profile of the parent pom lifts `maven.deploy.skip` and has
three guards. The enforcer rejects a non-SNAPSHOT version; only a `snapshotRepository` is
configured, so a release has nowhere to go; the test-only modules (`wire-upstream-shaded`,
`wire-tests-java`, `wire-protoc-compat-java`) keep their own `maven.deploy.skip`. What is
published: the parent pom, each shipped module's jar, sources, javadoc and pom, plus
`wire-schema-java`'s `tests` jar (Java test utilities and tests, no Kotlin; it is not part of
the TASK-21 release set nor of the per-file audit in `docs/license-inventory.md`). Uploads
happen in one final step (`deployAtEnd`): a run cancelled or failed during the build uploads
nothing, so only that last step can leave a mix of old and new modules. The `publish-snapshot` CI job republishes after a green build on every push to
`main` and weekly; GitHub disables the weekly schedule after 60 days without repository
activity, so a dormant repository can let the snapshot expire.

Not done by the repository: the io.apitomy namespace's (DEC-19) SNAPSHOT publishing must be enabled in
the Central Portal by its namespace administrator, and a Central Portal user token must be
stored as the `CENTRAL_TOKEN_USERNAME` and `CENTRAL_TOKEN_PASSWORD` repository secrets. Until
both exist, the CI job only prints a notice and nothing is published.

### DEC-19 groupId becomes io.apitomy and the repository moves to the Apitomy organization (decided 2026-11-03)

Supersedes DEC-8's `io.apicurio`. The maintainer decided, following jsenko's 2026-11-03
comment on issue #9, that antiwire moves to the Apitomy organization and publishes under
`io.apitomy`. Apicurio now follows CNCF governance, which constrains publishing; Apitomy does
not, and the `io.apitomy` namespace already exists on Maven Central (apitomy-data-models and
related artifacts). Nothing had been published under `io.apicurio` (no release, no snapshot),
so the rename has no published-artifact cost.

Changed on 2026-11-03: the groupId of the parent and every module, the wire-upstream-shaded
parity relocation packages (`io.apitomy.antiwire.parity.*`), and the consumer-facing
references (README, BUILD.md, the migration guide, the Kafka proposal). Java class packages
stay `com.squareup.wire.*`, as they have since DEC-8: that decision changed Maven coordinates
only, and so does this one. Measurement docs (footprint, performance) keep their recorded
`io.apicurio` paths with dated notes; whole-jar checksums embed the old META-INF/maven path
and are re-issued by the already-required remeasurement (TASK-21). DEC-18 snapshot
publishing, when enabled, happens under `io.apitomy`: enabling snapshots on that namespace
and storing the `CENTRAL_TOKEN_*` secrets are done by the Apitomy namespace administrators.

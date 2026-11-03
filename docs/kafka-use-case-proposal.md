# Kafka use case proposal (TASK-22 decision package)

Status: decision package prepared 2026-10-02, awaiting maintainer decision on AC#1 (named use
case, target module, users, end-to-end acceptance criteria). Nothing has been implemented, no
Kafka checkout has been modified, and nothing has been posted or proposed to any upstream
community. This document is research plus a recommendation; every Kafka fact in it was read
from the pinned commit cited below, and every port fact from this repository at `62e98c5` or
the Apicurio integration branch cited below.

## 1. Pinned Kafka target

apache/kafka was shallow-cloned read-only for research on 2026-10-02:

```
git clone --depth 1 https://github.com/apache/kafka /tmp/kafka
```

The clone resolved to commit `4c7bc0e4cebaa1fb9e26bde350594926586780a2`
("KAFKA-18201: testGroupMetadataMessageFormatter fails for new consumer protocol (#18198)",
authored 2026-10-02 17:13:46 +0800). `gradle.properties` at that commit declares
`version=4.5.0-SNAPSHOT` and `scalaVersion=2.13.18`. The Scala version is recorded for
completeness only: every scenario proposed below is client-side Java and does not touch Scala
code. The checkout was used read-only (greps and file reads); nothing in it was modified.

## 2. Kafka requirements found at the pin (facts, not policy)

### 2.1 Java baseline

`build.gradle` lines 53 through 55:

```groovy
  minClientJavaVersion = 11
  minNonClientJavaVersion = 17
  modulesNeedingJava11 = [":clients", ":generator", ":streams", ":streams:test-utils", ":streams:examples", ":streams:streams-scala", ":test-common:test-common-util"]
```

Compilation applies `options.release` per module (`build.gradle` lines 122 through 129): the
modules in `modulesNeedingJava11` compile with `--release 11`, every other module with
`--release 17`. Two consequences for a POC:

- Anything that consumes `kafka-clients` as a library lives exactly at the port's own Java 11
  floor (`maven.compiler.release=11`, DEC-3). A Java 11 client POC is at Kafka's own client
  baseline, not below it.
- The broker itself is Java 17 territory. A POC harness that starts a broker from the pinned
  checkout runs it under JDK 17+, while the client side stays on 11. That split satisfies
  TASK-22 AC#2's dual condition (compiles at the pinned Kafka target's baseline; client use
  retains Java 11).

### 2.2 Protobuf presence in Kafka itself

- `gradle/dependencies.gradle` lines 119 and 120 pin `opentelemetryProto: "1.3.2-alpha"` and
  `protobuf: "3.25.5", // a dependency of opentelemetryProto`; lines 227 and 228 map them to
  `io.opentelemetry.proto:opentelemetry-proto` and `com.google.protobuf:protobuf-java`.
- The `:clients` project declares both (`build.gradle` lines 2027 and 2028) solely for client
  telemetry (KIP-714). The consuming code imports precompiled classes only:
  `clients/src/main/java/org/apache/kafka/common/telemetry/internals/ClientTelemetryReporter.java`
  lines 62 through 65 import `io.opentelemetry.proto.metrics.v1.{Metric,MetricsData,
  ResourceMetrics,ScopeMetrics}`. No runtime schema parsing exists anywhere in `:clients`
  (no `Descriptors.`, `DynamicMessage`, or `FileDescriptorProto` use under
  `common/telemetry/`).
- Both are relocated into `kafka-clients` under
  `org.apache.kafka.shaded.*` (`build.gradle` lines 2096 through 2099), so the published
  client jar never exposes protobuf-java to consumers. The only other `com.google.protobuf`
  import in the tree is a JMH benchmark (`jmh-benchmarks/.../ByteUtilsBenchmark.java`),
  which is not shipped.

### 2.3 How Kafka defines schemas (candidate (a) evidence)

- There are zero `.proto` files in the entire tree (`find /tmp/kafka -name "*.proto"` outside
  `.git`: no results). Kafka's RPC protocol and KRaft metadata records are defined as JSON
  consumed at build time by the `:generator` module: 203 JSON definitions under
  `clients/src/main/resources/common/message/`, 27 under
  `metadata/src/main/resources/common/metadata/`, plus 44 group-coordinator, 4
  share-coordinator, 2 transaction-coordinator, and 1 raft definitions.
- There are no Rust sources on trunk at this commit (no `Cargo.toml`, no `.rs` files), so the
  "Rust broker metadata" question has no trunk referent today.
- Kafka has no Wire dependency: `grep -rn "squareup"` across `*.java`, `*.gradle`,
  `*.properties` returns nothing. The only textual near-match for "wire schema" is a prose
  comment (`group-coordinator/group-coordinator-api/.../StreamsGroupTopologyDescription.java`
  line 36), not code.

## 3. What the port already serves in the Apicurio serdes stack (candidate (b) evidence)

The Apicurio integration branch (`/tmp/apicurio`, branch `antiwire-integration`, head
`97f1b06`, "chore(antiwire): re-point wire artifacts to io.apicurio coordinates (DEC-8)")
already consumes the port in the modules a Kafka-facing story would build on:

- `serdes/generic/serde-common-protobuf/pom.xml` depends on `io.apicurio:wire-schema-java`
  (the port as the branch has it; Java package names stay `com.squareup.wire.*`, and DEC-8
  changed Maven coordinates only. antiwire's coordinates are `io.apitomy` since DEC-19,
  2026-11-03, so the branch must be re-pointed when it is revalidated). `ProtobufSchemaParser.parseSchema`
  (`src/main/java/io/apicurio/registry/serde/protobuf/ProtobufSchemaParser.java` line 46)
  parses raw `.proto` text dynamically with `ProtoParser.parse(...)`, resolves references into
  a dependency map, and falls back to binary `FileDescriptorProto` parsing when text parsing
  cannot resolve imports (lines 79 through 85).
- `MessageIndexesUtil.getMessageIndexes` (same package, lines 118 through 138) walks the
  port's `ProtoFileElement.types` and nested types to compute the message-index path written
  into the wire envelope for Confluent interop; `ProtobufSerializer.java` lines 174 and 193
  use it plus `toProtoFileElement` to extract a schema from a live message at produce time.
  `ProtobufDeserializer` builds `DynamicMessage` instances from the schema fetched through the
  resolver (lines 204 through 250).
- `utils/protobuf-schema-utilities/src/main/java/io/apicurio/registry/utils/protobuf/schema/FileDescriptorUtils.java`
  (1860 lines; imports `com.squareup.wire.schema.*` and `...internal.parser.*` at lines 10
  through 16) performs the `ProtoFileElement` to protobuf-java `FileDescriptor` conversion in
  both directions.
- `schema-util/protobuf` runs the port inside registry rules: content canonicalizer,
  dereferencer, reference finder, FQN conflict detector, validity validators, and the
  compatibility checker library (all import `com.squareup.wire.*`).
- Kafka Connect is covered too: `utils/converter/.../ProtobufConverter.java` (a
  `SerdeBasedConverter`) plus `utils/converter/.../protobuf/ProtobufData.java`, which converts
  between Connect `SchemaAndValue` and generic `DynamicMessage` with documented fidelity
  rules.
- `examples/protobuf-validation` demonstrates registry-backed protobuf validation
  (`ProtobufValidator` resolving an artifact by reference), but it requires a running Apicurio
  Registry at `localhost:8080`.

## 4. Candidate scenarios

### C1 (RECOMMENDED): registry-free protobuf client stack on vanilla kafka-clients

**Motivation.** A Kafka application that wants protobuf today needs either compiled message
classes (no dynamic schema handling at all) or the Confluent serializer stack, which presumes
a Confluent-compatible schema registry and drags its client dependency chain. The port plus
protobuf-java can do what that stack does around the schema itself: parse `.proto` text at
runtime, resolve imports, convert to descriptors, drive `DynamicMessage` both directions, and
check schema compatibility between versions. None of it needs a registry server, and none of
it needs Kotlin anywhere on the classpath. That is also the story the maintainer set as the
port's goal from the start ("to start using it in the projects apicurio-registry and
apache-kafka", README.md line 9).

**Users.** Kafka application developers and platform teams who picked protobuf and want
schema-driven serialization without adopting Confluent's registry client; Apicurio users who
want the schema machinery with a lighter client story; and, internally, this port, which needs
one concrete Kafka-shaped demonstration for its adoption record.

**Target module.** A new optional example module in this repository (proposed name
`wire-kafka-example`), modeled on how `wire-java-generator` is optional: it changes nothing in
the released artifact set or the port's dependency policy (DEC-4), because example modules are
not production artifacts. It would depend on `io.apitomy:wire-schema-java`,
`com.google.protobuf:protobuf-java`, `org.apache.kafka:kafka-clients`, and, for descriptor
conversion, `io.apicurio:apicurio-registry-protobuf-schema-utilities` installed locally from
the Apicurio branch at `97f1b06` (reusing the already-migrated conversion rather than writing
a second one; recorded as an Apicurio-branch dependency, not hidden). That utility brings its
own compile-scope dependencies, all pure Java: `proto-google-common-protos`, `guava`, and
`icu4j` (its pom notes the port itself carries no guava dependency). The port's own artifacts
stay clean: `wire-schema-java`'s only production dependency is `wire-runtime-java`
(protobuf-java is test-scoped there), and `wire-runtime-java` has no production third-party
dependency at all.

**Acceptance evidence.** End-to-end against the unmodified pinned checkout: format storage
with `bin/kafka-storage.sh`, start single-node KRaft with `bin/kafka-server-start.sh
config/server.properties` (both exist at the pin; the checkout is used as-is, never edited);
run the example producer that loads a `.proto` from the classpath at runtime (no compiled
message classes in the payload path, one import resolved), builds descriptors, and produces
records as `DynamicMessage`; run the consumer that re-parses the same schema and decodes every
record, asserting round-trip equality; plus a negative case where an incompatible edited
schema version is rejected before any produce. Full draft criteria in section 5.

**Effort.** Small: roughly one to two focused days for the module, the broker harness script,
and the recorded evidence. The schema parsing, descriptor conversion, and envelope pieces all
exist already; the POC wires them to `kafka-clients` and scripts the broker.

**Risk.** Low. The main caveats: the POC depends on the Apicurio branch's utilities until the
port grows or vendors an equivalent (stated, not hidden); `DynamicMessage` paths are slower
than compiled classes, and the POC makes no performance claim (DEC-9 discipline); and
"registry-free" demonstrates capability, not a product commitment.

### C2: Apicurio serdes and Connect converter demonstrated against the pinned broker

**Motivation.** Close the loop port, serdes, real Kafka: produce and consume protobuf through
`apicurio-registry-serde-common-protobuf` and run a Connect worker with `ProtobufConverter`
against the broker started from the pin. Apache Kafka itself ships only three data converters
(`ByteArrayConverter` in `connect/runtime`, `JsonConverter` in `connect/json`, `StringConverter`
in `connect/api`), so a working protobuf converter is a real ecosystem gap the Apicurio stack
already fills on top of the port.

**Users.** Kafka Connect users needing protobuf; existing Apicurio Registry users on Kafka.

**Target module.** No new port module; this is an integration run using Apicurio branch
artifacts, so its natural home is the Apicurio integration branch's own test material, not
this repository.

**Acceptance evidence.** A `connect-standalone` worker with `ProtobufConverter` moving records
through a file connector, plus serdes produce/consume, against the pinned broker and a running
Apicurio Registry.

**Effort.** Medium: two to four days, mostly standing up the registry and worker harness with
locally installed branch jars.

**Risk.** Medium, and mostly misplaced: the outcome speaks to Apicurio's stack more than to
the port, overlaps TASK-18's integration scope, a failure would more likely implicate
Apicurio wiring than the port, and it cannot run until the Apicurio side's artifacts are
installed anyway.

### C3: serving Kafka's own protocol or metadata schema needs (rejected at this pin)

**Motivation (investigated and found empty).** The hypothesis was that Kafka's broker
metadata or a Rust broker component might parse `.proto` schemas where a Wire-shaped library
could serve. The pin says otherwise, per section 2.3: no `.proto` files exist, the protocol
and metadata are JSON-defined and compiled by `:generator` at build time, protobuf appears
only as precompiled telemetry classes, and no Rust sources exist on trunk. Making Kafka
itself consume any schema library would be a Kafka-side design change on the scale of a KIP,
owned by the Kafka community, not by this port.

**Users.** None reachable without upstream changes, which TASK-22 explicitly does not
authorize.

**Effort and risk.** Not estimable from this side; rejected for this phase. Re-evaluate only
if trunk later introduces runtime `.proto` parsing (for example if a protobuf-based metadata
surface lands from the Rust broker line of work).

### Ranking rationale

C1 is recommended because it is the only candidate that is simultaneously concrete today (no
upstream change needed), additive (C2 mostly re-demonstrates TASK-18's work; C3 has no hook),
and shaped exactly like TASK-22's acceptance criteria (pinned Kafka baseline, Java 11 client,
recorded classpath with no Kotlin). C2 remains valuable as a later follow-up once the Apicurio
branch is released, and C3 is closed at this pin.

## 5. Proposed end-to-end acceptance criteria for the recommended scenario (C1)

Draft for the maintainer to approve or edit; approving a version of this list is what closes
AC#1.

1. The broker runs from the unmodified pinned checkout `4c7bc0e4`: `bin/kafka-storage.sh
   format` and `bin/kafka-server-start.sh config/server.properties` under JDK 17+, with the
   exact commands and outputs recorded in the evidence ledger. If the maintainer prefers a
   released Kafka over the snapshot pin, the closest released 4.x client is substituted and
   recorded, and the Java facts of section 2 are re-checked against that release tag.
2. The example module compiles with `maven.compiler.release=11` on the port's JDK 17+
   toolchain and its producer and consumer run against that broker; the producer sends at
   least 1000 records built as `DynamicMessage`; the consumer decodes all of them and asserts
   round-trip equality with the sent set.
3. Schema dynamics are proven inside the run: the `.proto` is loaded at runtime from the
   classpath, at least one non-trivial import is resolved through the port's loader, and no
   compiled protobuf message class participates in the payload path.
4. A negative case is recorded: an edited, incompatible schema version is rejected by the
   compatibility check before any record is produced, with the rejection output archived.
5. The full runtime classpath is recorded (`mvn dependency:list` archived) and contains no
   Kotlin and no Kotlin-backed dependency (no `kotlin-stdlib`, no `okio`, no `kotlinpoet`);
   `guava`, `icu4j`, and `proto-google-common-protos` are expected to appear through the
   Apicurio descriptor-conversion utility and are labeled as such, not hidden; every retained
   dependency (kafka-clients, protobuf-java, the port artifacts, the Apicurio utility and its
   three Java dependencies) is listed with version and license. This is the AC#3 record.
6. A Java 11 runtime smoke runs the consumer on a provisioned Java 11 JVM, per the DEC-3
   pattern.
7. The evidence ledger names the Kafka pin commit, the port revision and artifact checksums,
   and the Apicurio branch head it was built against.

## 6. What the POC would and would not do

Would: build the `wire-kafka-example` module in this repository; script the pinned broker
lifecycle; produce, consume, and verify protobuf records with runtime-parsed schemas; run the
incompatible-schema rejection; record classpath, versions, checksums, and outputs in a ledger
document; keep all of it local.

Would not: modify any Kafka source or file anything with the Kafka community (no KIP, no
issue, no discussion post; TASK-22 grants no such authorization); modify or push to the
Apicurio branch; add any production dependency or code to the port's released modules; make
any performance claim (benchmarks stay under DEC-9's separate measurement discipline); test
Confluent wire-format interop (the Confluent-style message-index envelope already exists in
the Apicurio serdes and is not on the POC's critical path; interop is a possible later
extension, not a claim); or tick any TASK-22 acceptance criterion, which happens only after
the maintainer approves.

## 7. Explicit non-claims

- No Kafka community approval, review, or awareness exists or is implied by this document.
- No Kafka dependency policy is asserted. The port's research report already records
  (README.md line 13) that trunk practice suggests the Kafka community would not accept the
  Kotlin dependency chain, while no primary written policy supporting that conclusion was
  found; that remains the state of knowledge at this pin, and nothing here upgrades the
  suggestion to policy.
- The maintainer has not yet approved any scope; this package is the input to that decision
  (AC#1 is open on purpose).
- Every Kafka fact above is valid for commit `4c7bc0e4` only; trunk moves daily and the facts
  must be re-checked if the pin moves.
- Nothing here modifies the port's decisions: DEC-4 (no Kotlin in production), DEC-6
  (exclusions), DEC-7 (Kafka deferred, non-gating), and DEC-13 (TASK-22 outside the release
  gate) all stand unchanged.

## Appendix A: adoption document skeleton (for the later community-facing document)

This is the skeleton TASK-22 AC#4's document will grow from; bracketed items are placeholders
filled only with measured results.

1. **Motivation.** Why a pure-Java Wire exists: schema-heavy Kafka and Apicurio work without a
   Kotlin toolchain or Kotlin-backed transitive dependencies; minimal footprint; Apache 2.0.
2. **The port in one page.** Coordinates `io.apitomy` (DEC-19; `io.apicurio` before it, DEC-8); modules wire-runtime-java,
   wire-schema-java, optional wire-java-generator; Java 11 bytecode built on JDK 17+; source
   and functional compatibility contract per DEC-2.
3. **Measured results.** [PLACEHOLDER: encoding and decoding benchmarks versus upstream Wire
   7.1.0; schema-operation benchmarks; footprint as clean-consumer versus marginal numbers;
   the Kafka POC evidence ledger with commands, outputs, and checksums.]
4. **Licensing and provenance.** Apache 2.0 overall, upstream attribution preserved verbatim
   per file including in-body notices; the five upstream files carrying BSD-style notices
   (`ProtoReader.kt`, `ProtoReader32.kt`, `ByteArrayProtoReader32.kt`, `MathMethods.kt`,
   `ProtoWriter.kt`) and the NOTICE file contents per DEC-12; legal approval not claimed.
5. **AI-assisted translation methodology.** Summary of
   [translation-conventions.md](translation-conventions.md): pinned upstream identity (tag
   7.1.0, both hashes), mechanical nullability and exception conventions, upstream tests kept
   as Kotlin under individually recorded adaptations, the single verification entry point
   `./scripts/verify.sh`, and the upstream synchronization procedure
   ([upstream-sync.md](upstream-sync.md)).
6. **Compatibility and exclusions.** The DEC-2 compatibility contract and the DEC-6 exclusion
   list (JSON adapters, the gRPC client and wire-reflector, Kotlin and Swift generators, the
   Gradle plugin, editions, non-JVM targets), stated as exclusions rather than silently
   missing features.
7. **Status and disclaimers.** Independent port; not affiliated with or endorsed by Square or
   the Apache Software Foundation; no Kafka community approval claimed; feedback channels
   [PLACEHOLDER].

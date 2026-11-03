# antiwire: status and direction (2026-10-01)

A note for Apicurio engineers on where the pure-Java Wire port stands, what has been verified so far, and where it is going. Repository: https://github.com/Apitomy/antiwire. This note was written mid-reconciliation on 2026-10-01: the reviewed planning baseline landed on `main` (decision record DEC-1 through DEC-13, plan-review ledger, re-scoped backlog) while the execution branch `m0-spikes` carried the first code; the two are merged, and docs/m0-execution-ledger-2026-10-01.md maps execution onto the reviewed criteria. Long-form background: `docs/research-wire-java-port-2026-09-29.md`.

## Why this project exists

Apicurio Registry consumes exactly one Wire artifact today, `com.squareup.wire:wire-schema` (pinned at 6.4.0), for proto parsing, the Schema model, and descriptor conversion. That one artifact drags in a chain of non-JDK runtime dependencies: `wire-runtime-jvm`, `okio` (Kotlin-compiled), `kotlin-stdlib` (1.7 MB), `kotlinpoet`, and `guava`. For Apache Kafka the same chain is a non-starter under its dependency policy. The goal of antiwire is a functional, dependency-free, pure-Java port of the parts of Wire that Apicurio (and later Kafka) actually use, tracked at a pinned upstream tag (currently 7.1.0) and verified by running Wire's own test material against the port.

The scope is a functional port, not a byte-for-byte API freeze. Where keeping upstream's exact signatures helps pass the same test suites we keep them; where a JDK type serves Apicurio better, the public API gets the JDK type (decision D5a). The okio buffer library is vendored inside the port as an internal engine, so no okio artifact ships at all.

## What is done (milestone M0, complete)

The porting spikes are finished and all evidence below comes from real builds (`mvn clean verify`, JDK 17, bytecode target Java 11), not from inspection alone.

1. The reviewed decision record (DEC-1 through DEC-13 in docs/decisions.md) settles the contract: functional and source compatibility with a documented, limited Apicurio source migration; no Kotlin in production with small reviewed pure-Java dependencies allowed; Java 11 bytecode, Maven, JDK 17 build with a real Java 11 consumer check; provisional coordinates pending Apicurio namespace authorization (DEC-8). The M0 execution resolved the buffer half of the open I/O decision: a vendored okio subset under original package names.
2. Build skeleton, CI and verification entry point: provisional parent coordinates with three module shells plus the never-published parity fixture; the reviewed dependency-policy enforcer (no Kotlin in production scope, transitives included) and the scripts/verify.sh suite registry with a real JDK 11 consumer smoke. The execution branch had separately built and negative-tested its own zero-dependency gates; those are superseded by the reviewed policy, and the negative-battery discipline (inject a violation, watch the gate fire) carries forward.
3. Vendored okio subset, pure Java: the buffer layer (ByteString, Buffer, Segment, BufferedSource/Sink, and 19 more classes) copied 1:1 from okio 1.17.6, the last Java-era release, with okio's own unit tests vendored verbatim: 732 tests, 0 failures. Gzip and zlib classes are omitted; the wire 7.1.0 sources import none of them.
4. Encoding core parity: ProtoReader and ProtoWriter translated from wire 7.1.0 Kotlin, with FieldEncoding, Syntax, and ProtocolException. Parity is checked against a live oracle, not golden files: a test fixture (`wire-upstream-shaded`, never published) relocates the real upstream `wire-runtime-jvm` 7.1.0 jar into a private package so both implementations run side by side in the same test. 500 seeded writer sequences produce byte-identical output on both sides; 200 structured messages produce identical read transcripts; packed-scalar replay, nested group skipping, the recursion limit, and six malformed-input cases produce identical exceptions and messages. The 2026 group-skip security regression (GHSA-7xpr-hc2w-34m9) is covered on both paths where it applies. Total suite: 747 tests, 0 failures.

Under the reviewed scope, M0's planning tasks are done (TASK-1 through TASK-3 on main) and the two spike tasks are In Progress with substantial cores delivered: TASK-4 has the buffer route proven and the loading surface open; TASK-5 has encoding parity proven and the upstream-test-compilation and baseline-capture criteria open. The per-criterion mapping is docs/m0-execution-ledger-2026-10-01.md.

## What remains

- M1 (runtime implementation): the rest of wire-runtime in Java: ProtoAdapter and the adapter zoo, the reflection machinery, Message and well-known types. The members deferred from M0 (rawProtoAdapter, unknown-field handling, forEachTag) land here. Gate: wire-runtime's own JVM test suite, adopted verbatim, green.
- M2 (schema parity): wire-schema in Java: the proto parser element model (the exact surface Apicurio imports), the linker and Schema model, and SchemaEncoder. This is the milestone that matters most to Apicurio. It also includes the JDK-typed consumer API work (task 25, re-pointed at the reviewed milestones): schema loading on `java.nio.file.Path` with a classpath helper, no `okio.*` types in consumer-facing signatures. Gate: wire-schema's own test suite green, plus a CI parity harness that re-extracts upstream tests at the pinned tag so drift is caught mechanically.
- M3 (external oracle): the external oracle, ported as-is: wire's protoc-compatibility suite (protoc 4.36.1), the compiler CLI, and the Java code generator validated byte-identical against upstream's golden files. The security regression corpus becomes permanent tests.
- M4 (Apicurio migration): an Apicurio Registry branch swapping `wire-schema` for the port artifact, running the protobuf-carrying module tests and a serde subset, plus a measured before/after footprint report. This is where we expect the ProtobufSchemaLoader's FakeFileSystem choreography to disappear.
- M5 (measured release): JMH comparison against upstream and protobuf-java with an agreed budget, a 0.1.0 release to Maven Central with full attribution (Square, Google, JetBrains), a Kafka-side POC with a KIP-grade document, and the upstream re-sync procedure.

Estimate, to be treated as an estimate: M1 and M2 are the bulk, roughly two to three months of focused work; the whole plan was sized at four to six months end to end in the original research.

## What we would like from the Apicurio team

The README's second-opinion section now separates what M0 already settled (okio vendoring, namespaces, module boundaries, the functional-port stance) from what is genuinely open, each item with its owning task. Two open items matter most to this audience: the public API compatibility matrix (which surfaces stay upstream-shaped and which get JDK types is designed in task 25, before the Apicurio integration in task 18), and whether the JDK-typed loading API fits how the Registry wants to load descriptors (today's loader juggles okio FileSystem, Path, and FakeFileSystem). Concrete API wishes from the team are cheapest to incorporate now, before M2 freezes the surface.

Feedback as GitHub issues on Apitomy/antiwire (github.com/Apicurio/antiwire at the time of writing), or directly to Paolo.

## Honest caveats

Nothing of wire-schema itself is translated yet; today the port covers the encoding core and the vendored buffer engine only. The estimates above are estimates. Upstream Wire still rejects protobuf editions, so the port does too; parity with Wire is the contract, not parity with protoc. And the branch with all of this is not pushed yet, so the links above show the research and backlog state until it lands.

## Correction, 2026-10-06

This note is kept as the record of 2026-10-01. Two statements in it are out of date. The
`io.apicurio` namespace is now authorized by the maintainer (DEC-8, resolved 2026-10-02 and
reconfirmed 2026-10-06), so the coordinates are no longer provisional; publishing artifacts
stays a separate gate (TASK-21 AC#1). The decision record now runs to DEC-14, which records
the confirmed JDK-typed public API with okio as an internal engine only. See
[decisions.md](decisions.md).

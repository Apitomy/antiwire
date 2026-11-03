# antiwire status update, 2026-10-05

> Status banner (2026-10-06): dated postscript: the empty-generated-message gap was closed by TASK-16.1 and TASK-21.1 is Done; the footprint renewal at 0f860ff awaits maintainer signature; TASK-16.2.1 remains open.

For context: antiwire is a pure-Java port of Square Wire (Square's protobuf library), with no Kotlin in production scope, built for Apicurio Registry first and Kafka later. The original is written in Kotlin and drags kotlin-stdlib, okio, and other heavy dependencies into every consumer.

## What exists on main today (113 commits at `295ef5f`)

The library works: runtime, .proto parser, schema linker, loader (files, directories, zip/jar archives), command-line compiler, and the Java code generator. All of it on Java 11. One known gap: protos using `google.protobuf.Empty` generate code that needs Kotlin and does not compile against the port runtime; the gap is recorded and owned (TASK-16.1).

### Footprint vs upstream

| Surface | Upstream 6.4.0 | Upstream 7.1.0 | antiwire | Reduction |
|---|---|---|---|---|
| Runtime jars | 4 jars, 2.30 MiB | 4 jars, 2.33 MiB | **1 jar, 0.24 MiB** | 90% |
| Schema jars | 15 jars, 9.33 MiB | 14 jars, 9.70 MiB | **2 jars, 0.55 MiB** | 94% |
| Optional generator | 15 jars, 10.2 MiB | 15 jars, 10.2 MiB | **4 jars, 0.70 MiB** | 93% |
| Apicurio marginal | 10.5 MiB of jars removed by the migration (Kotlin, okio, kotlinpoet, guava), nothing comparable added | | | |

### Speed vs upstream 7.1.0

| Operation (the Apicurio path) | Result |
|---|---|
| Parse .proto files | 1.12x faster (initial 2026-10-02 matrix) |
| Link + load schemas | 1.08x faster (initial 2026-10-02 matrix) |
| Descriptor conversion | 1.16x faster, 22% fewer allocations (initial 2026-10-02 matrix) |
| Message encode (4 workloads, forward and reverse paths) | Every cell inside its oracle band; best cell 2.2x faster (UTF-8 fast path) |
| Message decode (4 workloads) | Every cell inside its oracle band; the extra copy on bytes fields is gone (allocation within 0.2% of upstream) |

Message cells: session-5 matrix on candidate `82c3624` (docs/performance.md). The three schema-operation cells: initial 2026-10-02 matrix on a superseded candidate, to be renewed on the final candidate before release acceptance.

### Evidence

| Proof | Scale |
|---|---|
| Upstream test suite adopted, reconciled by case name against the pinned 7.1.0 sources | 967 case names: 963 matched by name, 4 recorded missing; matching by name does not show a case executes (the audit found disabled ones counted as matched, see TASK-14.2) |
| Interoperability tests against protoc 4.36.1 (Google's compiler), byte-level | 122 recorded cases, 50 of them skipped (72 executed) |
| Security regression tests covering the library's historical advisories | 24 cases |
| Generated code byte-identical to the original compiler after the documented Bytes mapping (docs/api-surface.md) | the pinned upstream Java golden corpus, `all_types_proto3` |
| CI suites, all green on every push (tests, parity reconciliation, bytecode, classpath, upstream watch) | 12 suites |

An automated check reconciles the name of every upstream test case against the pinned 7.1.0 sources at every build, so a case that is lost or unaccounted for by name fails the run; a case that is present but disabled is a separate gap tracked in TASK-14.2. It compares names, not test bodies: whether each ported case still asserts what upstream asserts was reviewed one by one during the port and recorded in the case ledgers (docs/task9-case-accounting.md, docs/task13-case-accounting.md, docs/task16-case-accounting.md); no automated check proves the semantic equivalence of the test bodies. The build also enforces the zero-Kotlin rule in production scope on every run.

### Apicurio integration

| Aspect | State |
|---|---|
| Migration surface | 23 files across 4 modules, on a local branch (never pushed, by design) |
| Tests | Same counts as the pre-migration baseline, zero failures |
| Descriptors produced | Byte-identical to the wire 6.4.0 baseline, 20/20 corpus files |
| Dependency graph | Zero Kotlin, okio, or guava attributable to the migration |

### Maintenance and API

| Aspect | State |
|---|---|
| Upstream release tracking | Documented procedure plus an automated tag watch, rehearsed on a real advisory |
| Public API | No okio types in public signatures; our own `Bytes` type end-to-end, old forms kept as deprecated bridges so original-compiler output still compiles |

## What is still open

| Item | Status | Blocking on |
|---|---|---|
| [0.1.0 release](https://github.com/Apitomy/antiwire/issues/3) | Measurements accepted on their recorded candidates (footprint `c713e9a`, performance `82c3624`); renewal on the final candidate and the release-builder guard update still to do | Maintainer's final go, final-candidate remeasurement (TASK-21, TASK-21.1), and who uploads the artifacts. Deferred as not urgent |
| [Kafka adoption](https://github.com/Apitomy/antiwire/issues) (TASK-22) | Proposal C1 written up, waiting | Deferred by the maintainer |
| [`Bytes` name-collision question](https://github.com/Apitomy/antiwire/issues/2) | Original claim refuted for the tested top-level shapes (audit 2026-10-06); TASK-27 pins the verified behavior and dispositions the remaining shapes | Low priority; the remaining shapes are unverified, not confirmed defects |
| Apicurio PR | Branch ready locally | Maintainer's call on whether and when |

In short: the measured work is done and recorded against its candidates, and the verification battery is green at `295ef5f`. What remains: remeasurement on the final candidate, the 2026-10-06 audit follow-ups, and the maintainer decisions (publish, Kafka, PR).

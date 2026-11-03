# Runtime and Apicurio schema performance comparison (TASK-20)

> STALE (2026-10-09, in addition to the note below): the accessor rename of TASK-33.1 changed every shipped class after the last measured candidate; the hot paths are unchanged in logic but DEC-13 requires renewal on the final candidate. Dated note only; no measured value or acceptance record was edited.
>
> STALE (2026-10-07): the last valid measurement is session 5 at candidate 82c3624. Later commits changed measured modules (275dcc2 TASK-26, 84a6a9f TASK-16.1, ec15e18 TASK-16.2, 7297b66 TASK-21, 68823ba TASK-16.2.1), so under DEC-13 this record does not cover the current HEAD and must be renewed on the final candidate (TASK-21).

> Coordinate note (2026-11-03): the groupId became `io.apitomy` (DEC-19) after the measurements below; the recorded paths and whole-jar checksums carry the `io.apicurio` coordinates of their time (checksums embed the META-INF/maven path), and are re-issued by the already-required remeasurement (TASK-21).


Measured on 2026-10-02 in three sessions: 12:08-12:56 CEST (initial full matrix, 15 wire
benchmarks), 13:36-13:50 CEST (decode re-measurement after the decode entry-point
fix: the four decode cells plus three encode control cells, identical JMH configuration),
and 18:55-19:19 CEST (encode-forward fix session, marked "session 3" below: the /tmp/aw-perf
harness had been lost to a machine reboot and was rebuilt from this document, then the
encode-forward path was fixed and the full 12-cell runtime matrix re-measured on the
renewed candidate; two repetitions, interleaved upstream, port, upstream, port).
Every number below comes from a JMH run whose
command and raw output are recorded; nothing is transcribed by hand from memory. The
scratch harness lives outside this repo at `/tmp/aw-perf` (JMH never enters the antiwire
repo, and the two measured implementations never share a classpath; AC#4).

Discipline for AC#2: the noise band and the regression criterion were fixed in code
(`tools/analyze.py`, phase 1 computes the band purely from upstream-vs-upstream repeat
variance) before any port-vs-upstream number was computed. The judgment pass is a separate
phase of the same script and consumes only the band derived in phase 1.

## 0. Measured candidate identity (AC#5)

| Item | Value |
|---|---|
| antiwire revision, initial run (unmarked numbers below) | `0e87bde8081ebe4a9203effd84f584154d8b887c` (branch `m0-spikes`) |
| Tree state at initial build | only `backlog/tasks/task-20 ...md` modified (documentation; no code delta vs the committed tree) |
| Build, initial | `JAVA_HOME=~/.sdkman/candidates/java/17.0.12-tem mvn clean install -DskipTests`, BUILD SUCCESS, exit 0 |
| antiwire revision, decode re-measurement (cells marked "re-measured") | `c0a215213c85b8a75d1d046f87f894b8988d792c` (branch `m0-spikes`; the decode entry-point fix of section 6 is the only runtime code delta vs the initial candidate) |
| Tree state at re-measurement build | only `backlog/tasks/task-20 ...md` modified (documentation; no code delta vs the committed tree) |
| Build, re-measurement | `JAVA_HOME=~/.sdkman/candidates/java/17.0.12-tem mvn -pl wire-runtime-java -am install -DskipTests`, exit 0 |
| antiwire revision, session 3 (rows marked "session 3") | `2d175e2` (branch `main`; the `okio.Utf8.size` ASCII run-length fix of section 6 is the only runtime code delta vs `bda14cc`; HEAD otherwise at the TASK-21 groundwork commits `a0214e8`..`bda14cc`) |
| Tree state at session 3 build | clean (fix committed before measurement) |
| Build, session 3 | `JAVA_HOME=~/.sdkman/candidates/java/17.0.12-tem mvn -pl wire-runtime-java install -DskipTests`, exit 0 |
| Test gate at session 3 | `mvn -pl wire-runtime-java,wire-tests-java,wire-protoc-compat-java test` green: 869 / 80 / 122 tests, 0 failures, 0 errors (4 / 3 / 50 skipped); `./scripts/verify.sh`: `VERDICT: all 12 ACTIVE suites passed.` |
| Oracle | upstream Wire 7.1.0 artifacts resolved from Maven Central (never built from source); the same oracle jar measured in both sessions |
| Verify run | `./scripts/verify.sh` at the initial revision: `VERDICT: all 11 ACTIVE suites passed.` |
| Test gate at the re-measurement revision | `mvn -pl wire-runtime-java,wire-tests-java,wire-protoc-compat-java test` green: 869 / 80 / 122 tests, 0 failures, 0 errors (4 / 3 / 50 skipped) |

Measured antiwire artifacts (`io.github.paoloantinori`, version `0.1.0-SNAPSHOT`; coordinates
moved to `io.apicurio` before session 3, see the coordinate-update note at the end):

| Artifact | SHA-256 initial run (`0e87bde`) | SHA-256 decode re-run (`c0a2152`) | SHA-256 session 3 (`2d175e2`, `io.apicurio`) |
|---|---|---|---|
| `wire-runtime-java` | `cc7f4cac0a5d611f9e0f6959ba2106327124e06380532850d64030764ed897be` | `43c810fb5f970fec29bf2a2a0465e30b5ee7aa1dc1dbbf4d11c9249455c1307c` | `f504460d1a9e63d2872d48e2934feded9697795de96dcc489051225bd15ef96a` |
| `wire-schema-java` | `7bc11249a3af12457c9caeff66cc942eb5ebf8f6d0d2502cb132076015cacaf5` | same (jar not rebuilt by `-pl wire-runtime-java -am install`; verified) | `cc60325f250f6aac155387b6ea600a351e1a31f0f63077035276196521b6048d` (source unchanged vs `c0a2152`; rebuilt through the TASK-21 packaging changes) |
| `wire-java-generator` | `c7d9753c3a5c37728c37d86209869b039c64f8803118adf45ec2ec63685a8b6c` | same (jar not rebuilt; verified) | `e5ae5f9696f5a6f09186dce6726c6211b5806544497e03b9cb261448fe0c558d` (source unchanged vs `c0a2152`; rebuilt through the TASK-21 packaging changes) |

Oracle jars (Maven Central, checksums of the exact files used):

| Artifact | SHA-256 |
|---|---|
| `com.squareup.wire:wire-runtime-jvm:7.1.0` | `a26cef81c2f361cec68e37cfddaaa207df568f6a72661214f4856be70940e073` |
| `com.squareup.wire:wire-schema-jvm:7.1.0` | `29761694316c1fd8b3478dff36eac19b21c37d4e00fe670eb759f2473e124075` |
| `com.squareup.wire:wire-compiler:7.1.0` | `8f4ea1f5a7f2739c49491b1cf2c4437bcb2a84866cef3b141a95a32b318ac215` |
| `org.jetbrains.kotlin:kotlin-stdlib:2.0.21` | `f31cc53f105a7e48c093683bbd5437561d1233920513774b470805641bedbc09` |
| `com.squareup.okio:okio-jvm:3.18.2` | `681fed549d1d2c63710f42c9eab433d642c71af778babd67035c50f8c425aaff` |

Resolved dependency identities of the harness profiles (from `mvn dependency:list`):

- `port` profile: `io.github.paoloantinori:wire-runtime-java` + `wire-schema-java`
  (nothing else beyond JMH 1.37 and the protobuf-java reference jar). The port has no
  runtime dependency of its own; okio is vendored inside `wire-runtime-java`.
- `upstream` profile: `wire-runtime-jvm` + `wire-schema-jvm` 7.1.0 with
  `kotlin-stdlib` 2.0.21, `kotlin-reflect` 2.0.21 (aligned to the stdlib the oracle is
  built against; the transitively resolved newer reflect needs stdlib 2.1+ symbols and
  fails at class load otherwise), `okio-jvm` 3.18.2, plus wire-schema's transitive
  `guava` 33.7.1-jre, `com.squareup:kotlinpoet-jvm` 2.3.0, `com.palantir.javapoet` 0.19.0.

Generated-model identity: the workload protos were compiled with BOTH command-line
generators (upstream `wire-compiler` 7.1.0 and the antiwire `wire-java-generator` CLI at
the revisions above; the generator jar is byte-identical in both sessions); the two
generated trees are byte-identical (`diff -rq` clean, 7 files), and the re-measurement
reused them unchanged (only the `wire-runtime-java` dependency of the port bench jar
changed).

Invalidation rule (AC#5): any change to `wire-runtime-java`, `wire-schema-java`, or
`wire-java-generator` (source, pom, or packaging), any change of the oracle versions, or
any change to the harness workloads invalidates these results and the acceptance state;
renewed measurements and renewed maintainer acceptance are required before TASK-21. A
candidate other than the revisions and checksums above cannot use this record. The rule
fired twice: the decode entry-point fix (`c0a2152`, the only runtime code delta vs
`0e87bde`) invalidated the initial matrix, and the 13:36 session renewed exactly the
affected surface: all four decode cells plus, as controls, the three encode cells that
sat closest to their noise bands (email forward writer, packed and bytes reverse
writers). A third firing is the no-okio public API merge (`c713e9a`, docs/api-surface.md
phases 1-2; `wire-runtime-java`, `wire-schema-java`, and `wire-java-generator` all
changed), renewed in full by the session-4 matrix of section 5 (2026-10-03). Encode and schema code paths are source-identical between the two candidates,
so the unmarked encode, schema, and protobuf-java numbers carry over from the initial
session under that stated equivalence. The second firing is the session-3 encode-forward
fix (`2d175e2`, the `okio.Utf8.size` change of section 6, plus the TASK-21 packaging
commits `a0214e8`..`bda14cc` that intervened on `main`): it renewed the full 12-cell
runtime matrix on the rebuilt harness. Two limitations are recorded rather than papered
over: (1) the rebuilt harness covers the four runtime workload benches only, so the
three schema cells and the eight protobuf-java reference cells were not re-measured in
session 3; parse and link+load do not encode strings and cannot be reached by the fix,
while descriptor conversion does call the fixed primitive (STRING.encodedSize ->
Utf8.size) and its recorded 1.160x port-faster level can only move in the improvement
direction, but the renewal is due before TASK-21 acceptance; (2) the session-3 bands are
derived from the rebuilt harness's own oracle repetitions per the same pre-declared rule
(phase 1 of `tools/analyze.py`, written before any session-3 port result existed).

## 1. Environment

| Item | Value |
|---|---|
| CPU | Apple M3 Pro (Mac15,6), arm64; 6 performance + 6 efficiency cores, 12 logical |
| RAM | 36 GB |
| OS | macOS 26.7.1 (25G241) |
| Benchmark JVM | OpenJDK 17.0.12, Temurin-17.0.12+7 (both profiles, same JVM) |
| Build tool | Apache Maven 3.9.12 |
| JMH | 1.37 (jmh-core + annotation processor), `-prof gc` |
| Run policy | sequential runs only (no parallel benchmark JVMs); upstream and port repetitions interleaved |

## 2. Harness, workloads, reproduction

Harness location: `/tmp/aw-perf` (scratch; not part of the repo). Layout:

- `pom.xml`: one module, two profiles (`port`, `upstream`). Each profile adds exactly one
  implementation set to the classpath; the shared benchmark sources compile against
  either. The schema operations go through a tiny SPI (`perf.schema.spi.SchemaSpi`) whose
  implementation class has the same FQCN in profile-specific source trees, so no profile
  class ever appears on the other profile's classpath.
- `protos/`: workload protos (below). `gen/port`, `gen/upstream`: the two generated model
  trees (byte-identical). `gen/pb`: protoc 4.36.1 models for the protobuf-java reference.
- `src/main/java/perf/`: shared benchmarks and sample data.
  `src-port/java` / `src-upstream/java`: the two SPI implementations.
- `run-all.sh`, `tools/analyze.py`, `tools/render_tables.py`: measurement and analysis.
  `run-decode-rerun.sh` + `tools/analyze_rerun.py`: the 13:36 decode re-measurement
  (same flags and interleave, 7 cells) and its old-vs-new pairing; `analyze.py` itself was
  not modified.
- `results/`: JMH JSON, identity fingerprints, oracle checksums, analysis output.

### Workload 1 (runtime): corpus, sizes, upstream JMH mapping

| Workload | Message | Encoded size | Upstream JMH case or justification |
|---|---|---|---|
| (a) small/medium message | `EmailSearchResponse` decoded from the upstream `medium_value.bytes` | 672 B | `SimpleMessageBenchmark` (same payload resource, same encode/decode call shapes) |
| (b) large nested message | `all_types` from `wire-tests/fixtures/proto/java/all_types.proto`, values mirroring upstream `all_types_proto2.json` (65 in-message fields set; `ext_*` omitted because the fixtures proto declares them in a separate extend block, `default_*` are field defaults, the 4 `map_*` fields exist in the fixtures proto and stay unset, and the benchmarks proto's `oneof_int32` does not exist in the fixtures proto) | 483 B | `AllTypesBenchmark` proto2 side |
| (c) repeated/packed lists | `PackedLists`: 4 repeated and 8 packed (proto2 `[packed = true]`) scalar lists, 256 elements each | 18,437 B | No upstream case isolates packed lists (`AllTypesBenchmark` carries 2-element packed lists inside all_types); this workload isolates the packed-varint loops |
| (d) bytes-heavy | `Blob`: 1 KiB head + 16 x 1 KiB chunks | 17,472 B | No upstream case isolates bytes; justified by the registry's ByteString-dominated payloads |

The two harness-authored protos, verbatim:

```proto
// protos/packed/packed_lists.proto
syntax = "proto2";
package perf.packed;
option java_outer_classname = "PackedListsProto";
option java_package = "perf.packed";
message PackedLists {
  repeated int32 rep_int32 = 1;
  repeated int64 rep_int64 = 2;
  repeated double rep_double = 3;
  repeated string rep_string = 4;
  repeated int32 pack_int32 = 11 [packed = true];
  repeated sint32 pack_sint32 = 12 [packed = true];
  repeated int64 pack_int64 = 13 [packed = true];
  repeated uint64 pack_uint64 = 14 [packed = true];
  repeated fixed64 pack_fixed64 = 15 [packed = true];
  repeated double pack_double = 16 [packed = true];
  repeated float pack_float = 17 [packed = true];
  repeated bool pack_bool = 18 [packed = true];
}
```

```proto
// protos/bytes/bytes_heavy.proto
syntax = "proto2";
package perf.bytes;
option java_outer_classname = "BlobProto";
option java_package = "perf.bytes";
message Blob {
  optional bytes head = 1;
  repeated bytes chunks = 2;
  optional string label = 3;
}
```

### Workload 2 (schema): the Apicurio operations

Not upstream JMH cases; justification is the registry hot path each operation names:

- `parse`: raw `ProtoParser` over the 20 protos of
  `/tmp/apicurio/utils/protobuf-schema-utilities/src/test/proto` (26,088 bytes), one op =
  the whole corpus. This is the entry point Apicurio's `ProtobufSchemaParser` calls.
- `link+load`: fresh `SchemaLoader`/`JdkSchemaLoader` choreography over a staged tree
  (parse + link + load; 47 files load per op: 20 corpus + 18 staged google dependencies +
  9 bundled core protos). Upstream runs `SchemaLoader(FileSystem.SYSTEM)` on the same
  staged directory the port's `JdkSchemaLoader` reads; both do real filesystem IO with a
  warm page cache, mirroring each library's production loading path.
- `descriptor`: `SchemaEncoder` conversion of the 20 corpus schemas (schema loaded once
  per trial; one op = 20 FileDescriptorProto encodings, 25,971 output bytes), the
  schema-to-descriptor step behind Apicurio's `FileDescriptorUtils`.

The staged google dependency closure (38 staged files total): `google/protobuf/{api,
source_context,type}.proto` and 15 `google/type/*.proto` taken from the archives the
Apicurio build itself stages (`protobuf-java` 4.36.2 and `proto-google-common-protos`
2.77.0 gencode archives); the remaining well-knowns resolve from each library's own
bundled core loader, as in production.

### Workload 3 (protobuf-java reference)

The same four messages through `protobuf-java` 4.36.2 with protoc 4.36.1-generated
models, in both profiles (identical code and classpath scope). Reference point only,
never a substitute oracle: it validates that both wire implementations produce
byte-identical payloads (section 3) and serves as a same-machine control for
environmental drift.

### JMH configuration

From the benchmark annotations (visible in every raw log): `-f 2` forks,
`-wi 5` warmup iterations, `-i 10` measurement iterations, `-w 1s -r 1s`, mode
`Throughput` (ops/s), `-prof gc`, JSON result per run. Two independent repetitions per
profile, interleaved `upstream, port, upstream, port` (start times 12:08, 12:20, 12:32,
12:43; last run finished 12:55). The 13:36 re-measurement used the identical
configuration and interleave (start times 13:36, 13:40, 13:43, 13:47; last run finished
13:50) with the same untouched upstream oracle jar. Medians and min-max are computed
over all 40 iterations (2 repetitions x 2 forks x 10 iterations) per profile and
benchmark; allocation is the `gc.alloc.rate.norm` median of repetition 1.

### Reproduction commands

#### Harness rebuild (session 3)

The original `/tmp/aw-perf` was lost to a machine reboot; the harness was rebuilt from this
document before the encode-forward fix was measured. Reconstruction provenance, recorded per
the doc's own workload table:

- Same layout, pom profiles (`io.apicurio:wire-runtime-java:0.1.0-SNAPSHOT` vs the pinned
  upstream oracle set), JMH 1.37, annotations, interleave, and the two-phase
  `tools/analyze.py` discipline (bands from the oracle before any port number).
- Workload inputs: (a) email decodes the same `medium_value.bytes` resource; the rebuilt
  harness reproduces the original fingerprints exactly (`cc02806d...` 672 B,
  `ba3c8a31...` 483 B for all_types built from every in-message field of
  `all_types_proto2.json`). (c)/(d) content choices under-specified by the doc are
  recorded in the harness: `rep_string[i]` is a run of 's' of length 28 (i < 86) or 29
  (i >= 86) chosen to hit the documented 18,437 B, and the blob label is the 11-char
  ASCII string `blob-label-` chosen to hit 17,472 B; both sides measure the same data
  (`perf.IdentityCheck` outputs diff-clean across the two classpaths).
- Both model trees regenerated with the two CLIs (upstream `wire-compiler` 7.1.0 pins and
  the antiwire generator CLI); `diff -rq gen/port gen/upstream` clean, 7 files.
- Diagnosis tooling added by the fix session (kept in the harness): `PrimitiveBench`
  (isolated `Utf8.size` / adapter / full-size-pass benches) and `tools/jfr_top.py`.

```bash
# 1. fresh antiwire install (record revision + jar checksums)
cd /Users/pantinor/data/repo/work/antiwire
git rev-parse HEAD
JAVA_HOME=~/.sdkman/candidates/java/17.0.12-tem mvn clean install -DskipTests

# 2. oracle jars (exact set in /tmp/aw-perf/results/oracle-jar-checksums.txt), then
#    generate models with BOTH CLIs from the same protos tree:
#    upstream: java -cp <oracle jars> com.squareup.wire.WireCompiler \
#                --proto_path=/tmp/aw-perf/protos --java_out=/tmp/aw-perf/gen/upstream
#    port:     java -cp <antiwire jars + com.squareup:javapoet:1.13.0> \
#                com.squareup.wire.WireCompiler \
#                --proto_path=/tmp/aw-perf/protos --java_out=/tmp/aw-perf/gen/port
#    diff -rq /tmp/aw-perf/gen/port /tmp/aw-perf/gen/upstream   # must be identical
#    protoc (pinned by scripts/install-protoc.sh):
~/.cache/antiwire-protoc/4.36.1-osx-aarch_64/protoc --proto_path=/tmp/aw-perf/protos \
    --java_out=/tmp/aw-perf/gen/pb squareup/wire/benchmarks/*.proto \
    alltypes/all_types.proto packed/packed_lists.proto bytes/bytes_heavy.proto

# 3. build the two bench jars (one implementation per classpath)
cd /tmp/aw-perf
JAVA_HOME=~/.sdkman/candidates/java/17.0.12-tem mvn -Pport package     # target/aw-perf-port.jar
JAVA_HOME=~/.sdkman/candidates/java/17.0.12-tem mvn -Pupstream package # target/aw-perf-upstream.jar

# 4. identity fingerprints under both classpaths (must match; section 3)
~/.sdkman/candidates/java/17.0.12-tem/bin/java -cp target/aw-perf-port.jar perf.IdentityCheck
~/.sdkman/candidates/java/17.0.12-tem/bin/java -cp target/aw-perf-upstream.jar perf.IdentityCheck

# 5. measurement sequence (flags come from the annotations)
/tmp/aw-perf/run-all.sh   # writes results/{rt,pb,sc}-{upstream,port}-{1,2}.{json,txt}

# 5b. decode re-measurement after the entry-point fix (candidate c0a2152): rebuild the
#     port bench jar against the reinstalled runtime (mvn -Pport package), re-verify
#     perf.IdentityCheck against results/identity-port.txt, then
/tmp/aw-perf/run-decode-rerun.sh   # writes results/rt2-{upstream,port}-{1,2}.{json,txt}

# 6. analysis: noise band from the oracle first, then the port judgment
python3 /tmp/aw-perf/tools/analyze.py
```

## 3. Identity verification (same operation, same data on both sides)

`perf.IdentityCheck` under each classpath prints identical lines on both sides
(`/tmp/aw-perf/results/identity-port.txt` vs `identity-upstream.txt`, diff clean):

| Fingerprint | Value (both sides) |
|---|---|
| email encode | `sha256:cc02806df4e30c2ac7f67be2e4a0c4935644395cd1e358724971a71c40ff7c69`, 672 B (equals the upstream `medium_value.bytes` payload) |
| email decode-reencode | identical (round trip stable) |
| all_types encode | `sha256:ba3c8a3131ef594dab755f2f9ed79e7113f9e370b89c5ae2433eb6549ad902ae`, 483 B |
| packed encode | `sha256:a7a7e0c6956fe56cfa615bbf9fb6c906a212fdb47876bb59c73db88d782784c7`, 18,437 B |
| blob encode | `sha256:d6892b8f22104848477632a8604ac04421ea2e1a1fc926311938fb1af23c0dc7`, 17,472 B |
| every protobuf-java encode | byte-identical to the wire encodes above |
| schema canonical text (47 files, `toSchema()` ordered) | `sha256:b2c2307d112c46846dc52d824988f0bd5eb0d6c34a613a93ffa9fe7fe4ecc7e1` |
| descriptor output (20 files) | 25,971 B total, identical |
| loaded files / parsed elements | 47 / 53 |

Both profiles therefore measure the same operation on the same data (AC#4), and the
generated model classes are the same code compiled against each implementation.

Rebuilt-harness identity (session 3): email and all_types fingerprints reproduce the
values above exactly; the packed and blob fingerprints differ from the lost harness's
because the string and label contents were re-chosen to the same documented sizes
(section 2, harness rebuild), giving `sha256:df973ccf3317ca95ae20f323198e39ffcecf288022f7035eeb2d47d8a7306ddb`
(18,437 B packed) and `sha256:4d49caabe2cf4a551f9d4187f40e2cb293b19f7a347712b3f65bd62d4148836c`
(17,472 B blob). Forward and reverse encodings and the decode-reencode round trips are
mutually identical under both classpaths, diff-clean across port and upstream, and
unchanged by the `2d175e2` fix.

## 4. Noise band derived from the oracle, before judging the port (AC#2)

Phase 1 of `tools/analyze.py` measures the oracle against itself: two independent
upstream repetitions (plus the 2 forks within each) across all 15 wire benchmarks
(12 runtime + 3 schema). Observed oracle variance:

| Statistic | Value |
|---|---|
| Max run-to-run median deviation | 24.9% (`EmailSearchBench.encodeReverse`, one repetition dropped to 2.47M from 3.17M ops/s) |
| Median run-to-run deviation | 2.7% |
| Max fork-to-fork median spread | 16.7% (`BytesBench.decode`) |
| protobuf-java control (same code in both profiles) | max 6.7% run-to-run across 8 benchmarks, all port/upstream ratios 0.981-1.013 |

Pre-declared criterion (fixed in `tools/analyze.py` before any port number was computed):
noise band = max observed oracle deviation rounded up to the next 5%, i.e. **±25%**; a
regression is a port median below `upstream median x 0.75`.

Because that global band is inflated by a single outlier repetition, the doc also reports
a stricter, still purely oracle-derived per-benchmark criterion: each benchmark's own
`max(run-to-run deviation, fork spread)` rounded up to the next 5%. Findings are listed
for maintainer review when they exceed their own per-benchmark band (section 6); the
global band alone flags exactly one of them. The 13:36 decode re-measurement is judged
with these same bands, taken unchanged from the initial session; no band was recomputed
after any port number existed.

## 5. Results

ops/s medians over 40 iterations; range = min-max over all iterations of both repetitions.
Rows marked "re-measured" come from the 13:36 session (candidate `c0a2152`, after the
decode entry-point fix); unmarked rows are the initial 12:08 session (candidate
`0e87bde`). The superseded initial values of every re-measured row are preserved in the
"initial run" note after workload (d).

### Workload (a) small/medium message: EmailSearchResponse, 672 bytes

| operation | upstream 7.1.0 ops/s median [min-max] | antiwire ops/s median [min-max] | ratio port/upstream | alloc/op upstream (B) | alloc/op port (B) |
|---|---|---|---|---|---|
| encode, forward writer (3x path), re-measured | 607,495 [580,520-616,095] | 480,113 [352,409-519,003] | 0.790 | 16 | 16 |
| encode, reverse writer (4x+ path) | 2,953,000 [1,789,809-3,230,423] | 2,973,259 [2,446,299-3,109,068] | 1.007 | 200 | 176 |
| decode, re-measured | 2,481,591 [2,302,039-2,614,432] | 2,243,927 [1,571,195-2,354,498] | 0.904 | 4,080 | 4,296 |
| protobuf-java encode (reference) | 10,830,466 [9,682,246-11,178,706] | 10,626,397 [8,579,606-10,892,147] | 0.981 | 728 | 728 |
| protobuf-java decode (reference) | 4,586,111 [4,455,404-4,750,907] | 4,608,882 [4,248,969-4,785,371] | 1.005 | 1,784 | 1,784 |

### Workload (b) all_types: fixtures proto2 message, 483 bytes

| operation | upstream 7.1.0 ops/s median [min-max] | antiwire ops/s median [min-max] | ratio port/upstream | alloc/op upstream (B) | alloc/op port (B) |
|---|---|---|---|---|---|
| encode, forward writer (3x path) | 947,404 [787,158-967,781] | 1,008,157 [886,871-1,047,331] | 1.064 | 16 | 16 |
| encode, reverse writer (4x+ path) | 1,306,884 [1,075,505-1,393,376] | 1,543,410 [1,299,563-1,565,150] | 1.181 | 200 | 176 |
| decode, re-measured | 526,562 [373,354-537,967] | 515,856 [459,413-545,063] | 0.980 | 8,184 | 8,904 |
| protobuf-java encode (reference) | 1,579,712 [1,513,665-1,623,946] | 1,578,663 [1,401,190-1,627,699] | 0.999 | 1,208 | 1,208 |
| protobuf-java decode (reference) | 821,837 [599,695-836,812] | 818,097 [783,850-836,893] | 0.995 | 5,176 | 5,176 |

### Workload (c) repeated/packed scalar lists: 256 elements per list, 18,437 bytes

| operation | upstream 7.1.0 ops/s median [min-max] | antiwire ops/s median [min-max] | ratio port/upstream | alloc/op upstream (B) | alloc/op port (B) |
|---|---|---|---|---|---|
| encode, forward writer (3x path) | 30,334 [26,330-31,695] | 30,969 [29,300-32,888] | 1.021 | 16 | 16 |
| encode, reverse writer (4x+ path), re-measured | 61,603 [51,960-62,546] | 62,429 [59,311-63,501] | 1.013 | 200 | 176 |
| decode, re-measured | 23,853 [9,365-25,122] | 29,125 [23,652-30,689] | 1.221 | 127,932 | 128,232 |
| protobuf-java encode (reference) | 83,177 [79,185-87,744] | 83,628 [81,694-86,112] | 1.005 | 10,296 | 10,296 |
| protobuf-java decode (reference) | 69,940 [56,013-71,246] | 69,681 [68,081-70,556] | 0.996 | 61,744 | 61,744 |

### Workload (d) bytes-heavy: 1 KiB head + 16 x 1 KiB chunks, 17,472 bytes

| operation | upstream 7.1.0 ops/s median [min-max] | antiwire ops/s median [min-max] | ratio port/upstream | alloc/op upstream (B) | alloc/op port (B) |
|---|---|---|---|---|---|
| encode, forward writer (3x path) | 2,091,215 [1,830,283-2,238,350] | 2,099,966 [1,828,347-2,256,359] | 1.004 | 16 | 16 |
| encode, reverse writer (4x+ path), re-measured | 1,292,647 [1,111,636-1,486,514] | 1,326,396 [1,116,746-1,486,538] | 1.026 | 200 | 176 |
| decode, re-measured | 1,459,290 [1,108,650-1,802,614] | 1,181,602 [743,400-1,348,259] | 0.810 | 19,020 | 19,064 |
| protobuf-java encode (reference) | 1,297,388 [1,251,118-1,325,824] | 1,314,852 [1,149,988-1,344,741] | 1.013 | 4,184 | 4,184 |
| protobuf-java decode (reference) | 2,100,458 [1,870,417-2,129,809] | 2,071,167 [1,281,772-2,116,236] | 0.986 | 18,472 | 18,472 |

Initial run note (2026-10-02, 12:08-12:56 CEST, candidate `0e87bde`): the honest first
measurement. Every re-measured row above supersedes a row measured then, on the candidate
whose `decode(byte[])`/`decode(ByteString)` still wrapped the payload in an okio Buffer
and entered the long `ProtoReader`. Superseded values, format: upstream median
[min-max]; port median [min-max]; ratio; alloc/op upstream / port.

| Cell | initial run values (superseded 13:36) |
|---|---|
| (a) email decode | 2,269,059 [1,620,453-2,558,472]; 1,812,167 [1,584,194-2,015,037]; 0.799; 4,104 / 4,176 |
| (b) all_types decode | 532,061 [458,162-549,670]; 498,961 [457,108-509,195]; 0.938; 8,184 / 8,824 |
| (c) packed decode | 24,854 [23,433-25,350]; 22,299 [21,192-22,877]; 0.897; 127,968 / 128,152 |
| (d) bytes decode | 1,579,290 [1,183,603-1,676,027]; 793,070 [718,169-812,528]; 0.502; 19,020 / 18,964 |
| (a) email encodeForward | 601,262 [548,075-616,892]; 521,036 [406,535-569,878]; 0.867; 16 / 16 |
| (c) packed encodeReverse | 62,399 [61,275-62,893]; 59,875 [56,213-61,361]; 0.960; 200 / 176 |
| (d) bytes encodeReverse | 1,311,620 [1,222,807-1,432,649]; 1,275,030 [1,203,631-1,428,197]; 0.972; 200 / 176 |

### Session 3 results (2026-10-02 18:55-19:19 CEST, candidate `2d175e2`, rebuilt harness)

Full 12-cell runtime matrix, two repetitions per profile interleaved `upstream, port,
upstream, port` (start times 18:55, 19:01, 19:07, 19:13; last run finished 19:19), identical
JMH configuration (40 iterations per profile and cell). Bands re-derived from this session's
own oracle repetitions by phase 1 of the unchanged two-phase rule: global ±25%, per-cell own
bands listed in section 6. Medians [min-max] over all iterations of both repetitions;
allocation is the `gc.alloc.rate.norm` median of repetition 1.

| Cell | upstream 7.1.0 ops/s median [min-max] | antiwire ops/s median [min-max] | ratio | alloc/op upstream / port (B) | own band |
|---|---|---|---|---|---|
| email encodeForward | 606,703 [566,891-613,874] | 1,361,770 [1,287,234-1,378,661] | **2.245** | 16 / 16 | ±5% |
| email encodeReverse | 2,984,030 [2,573,108-3,210,739] | 2,954,707 [2,701,608-3,044,417] | 0.990 | 200 / 176 | ±25% |
| email decode | 2,489,580 [2,078,635-2,611,748] | 2,176,538 [1,966,236-2,292,869] | 0.874 | 4,092 / 4,224 | ±20% |
| all_types encodeForward | 942,623 [882,954-962,125] | 1,021,125 [953,680-1,074,868] | 1.083 | 16 / 16 | ±5% |
| all_types encodeReverse | 1,350,778 [1,232,360-1,392,995] | 1,517,083 [1,430,164-1,560,069] | 1.123 | 200 / 176 | ±10% |
| all_types decode | 530,638 [509,611-544,149] | 538,543 [522,648-552,267] | 1.015 | 8,184 / 8,904 | ±5% |
| packed encodeForward | 42,701 [38,157-43,459] | 42,359 [39,363-42,597] | 0.992 | 16 / 16 | ±15% |
| packed encodeReverse | 65,912 [62,721-68,543] | 68,779 [63,013-69,933] | 1.043 | 200 / 176 | ±5% |
| packed decode | 26,446 [24,851-27,558] | 33,138 [31,088-34,402] | 1.253 | 116,192 / 116,456 | ±5% |
| bytes encodeForward | 2,058,783 [1,979,481-2,258,966] | 2,134,440 [2,030,052-2,264,013] | 1.037 | 16 / 16 | ±10% |
| bytes encodeReverse | 1,364,777 [1,221,711-1,505,475] | 1,347,841 [1,206,280-1,539,763] | 0.988 | 200 / 176 | ±15% |
| bytes decode | 1,585,211 [1,369,911-1,799,822] | 1,263,904 [1,203,039-1,651,674] | 0.797 | 19,020 / 19,064 | ±25% |

Mandate judgment (TASK-20 zero-regression gate): `EmailSearchBench.encodeForward` =
2.245 overall, and 2.218 / 2.250 per repetition, each far above the required 0.95; every
other cell is inside its own oracle-derived band. The bytes-decode cell deserves the honest
note: this session's oracle measured that cell's own fork spread at 23.7% (16.7% in the
initial session), so the pre-declared rule gives it an own band of ±25% and 0.797 sits
inside; judged against the initial session's band (±20%, floor 0.800) it would sit 0.003
below the floor, i.e. the cell is statistically indistinguishable from its recorded
0.810 level while the oracle's own noise in it nearly tripled. Allocations are unchanged
or better on every cell (email decode 4,224 vs the recorded 4,296).

### Session 4 results (2026-10-03 14:19-14:44 CEST, candidate `c713e9a`, post no-okio)

Why renewed: the no-okio public API merge `c713e9a` (phases 1 and 2 of docs/api-surface.md)
changed `wire-runtime-java`, `wire-schema-java`, and `wire-java-generator`, so the
invalidation rule (section 0) fired for a third time and DEC-13 freshness requires this
renewal before TASK-21. The full 12-cell runtime matrix was re-measured end to end with the
identical JMH configuration and interleave (start times 14:19, 14:25, 14:31, 14:37; last run
finished 14:44), the same untouched upstream oracle, and the same two-phase discipline
(bands from this session's own oracle repetitions by the unchanged `tools/analyze.py`
BEFORE any session-4 port number was computed).

Identity block (AC#5):

| Item | Value |
|---|---|
| antiwire revision | `c713e9ad4f5ca30775dbd3884b5f1d6563552b51` (branch `main`, merge commit, clean tree) |
| Build | `JAVA_HOME=~/.sdkman/candidates/java/17.0.12-tem mvn -pl wire-runtime-java,wire-schema-java,wire-java-generator -am install -DskipTests`, exit 0 (all three artifacts rebuilt: phases 1-2 touched all three) |
| `io.apicurio:wire-runtime-java:0.1.0-SNAPSHOT` SHA-256 | `e04db4de1e42d123657ac669670adf11ba85e0b1e76340c246966852f85b1e0c` |
| `io.apicurio:wire-schema-java:0.1.0-SNAPSHOT` SHA-256 | `9d3ed5f2bce0a75d550702ce99b6008ab354b0d4e3ac5bd9bde05a21e409fa65` |
| `io.apicurio:wire-java-generator:0.1.0-SNAPSHOT` SHA-256 | `dbb2afd41dea44a420dc5919c8bed4286ac5eb853a31914f2f4f4eddec9310a5` |
| Oracle | unchanged from sessions 1-3: upstream Wire 7.1.0 artifact set resolved from Maven Central, same jars |
| Test gate at the candidate | the merge commit's own recorded battery: `./scripts/verify.sh` all 12 ACTIVE suites pass (runtime 894, schema 630, protoc-oracle 122, compiler 179); `./scripts/verify.sh` re-run once after the session-4 renewal commit, verdict quoted in that session's report |

Generated-model identity under the phase-2 Bytes mapping: since the merge the port CLI
emits `com.squareup.wire.Bytes` where upstream emits `okio.ByteString`, so the harness's
former raw `diff -rq` source-identity gate no longer applies. The renewed
`generate-models.sh` normalizes the UPSTREAM tree by the documented mechanical mapping
(docs/api-surface.md; `tools/map_bytes.py`, a line-for-line mirror of
`AllTypesGoldenBytesMappingTest.mapUpstreamSource`, regex pairs in the same order plus the
import-block sort) and requires the port's RAW CLI output to be byte-identical to the
mapped upstream tree: clean over all 7 generated files. The port tree is genuinely
Bytes-typed (`import com.squareup.wire.Bytes`, `Bytes opt_bytes`, `#WIRE_BYTES` adapter
strings in the generated `AllTypes` and `Blob`; zero `okio.` references). Per-file SHA-256
of both raw trees: `results/gen-tree-fingerprints.txt`.

Harness adaptation: each profile now compiles its OWN generated tree (`gen/${profile}` via
the pom's `${profile.gen}` property) plus a same-FQCN profile-specific
`perf.ModelBytes` (`src-port/java` vs `src-upstream/java`) that performs the bytes-field
construction (`com.squareup.wire.Bytes.encodeUtf8/of` vs `okio.ByteString.encodeUtf8/of`)
that phase 2 moved between types. It runs at `@Setup` time only, never on a measured path.

IdentityCheck (wire bytes, unaffected by the value-type change by construction): run under
both classpaths, diff-clean across profiles, and EVERY fingerprint is IDENTICAL to its
session-3 value, including both bytes-bearing workloads: email `cc02806d...` 672 B,
all_types `ba3c8a31...` 483 B, packed `df973ccf...` 18,437 B, blob `4d49caab...` 17,472 B;
forward and reverse encodings and the decode-reencode round trip mutually identical on
both sides. Old and new fingerprints are therefore the same values, recorded once with the
reason: the phase-2 mapping changes the Java value type, not the wire format, and
`perf.IdentityCheck` compares wire bytes, so a fingerprint can only move if the wire
format had changed, which is exactly what the check exists to refute.

Session-4 matrix (medians over all 40 iterations; range = min-max over both repetitions;
allocation is the `gc.alloc.rate.norm` median of repetition 1; own bands from this
session's phase 1):

| Cell | upstream 7.1.0 ops/s median [min-max] | antiwire ops/s median [min-max] | ratio | alloc/op upstream / port (B) | own band |
|---|---|---|---|---|---|
| email encodeForward | 594,465 [54,802-607,997] | 1,325,822 [963,953-1,377,341] | **2.230** | 16 / 16 | ±10% |
| email encodeReverse | 2,917,430 [2,487,478-3,254,258] | 2,972,656 [1,911,963-3,087,631] | 1.019 | 200 / 176 | ±25% |
| email decode | 2,356,293 [1,927,074-2,500,425] | 2,232,970 [2,086,501-2,329,492] | 0.948 | 4,132 / 4,296 | ±20% |
| all_types encodeForward | 935,344 [860,820-955,832] | 1,061,015 [871,715-1,079,651] | 1.134 | 16 / 16 | ±5% |
| all_types encodeReverse | 1,298,015 [1,166,217-1,384,602] | 1,507,671 [1,294,793-1,568,133] | 1.162 | 200 / 176 | ±10% |
| all_types decode | 526,390 [485,095-537,882] | 538,051 [496,581-550,371] | 1.022 | 8,184 / 9,040 | ±5% |
| packed encodeForward | 42,515 [40,312-43,414] | 35,572 [28,281-42,444] | 0.837 | 16 / 16 | ±5% |
| packed encodeReverse | 67,291 [62,952-69,073] | 63,785 [48,479-66,499] | 0.948 | 200 / 176 | ±5% |
| packed decode | 25,586 [18,023-26,249] | 30,493 [5,016-32,939] | 1.192 | 116,192 / 116,456 | ±5% |
| bytes encodeForward | 2,088,838 [1,403,339-2,225,134] | 2,005,036 [1,893,304-2,254,483] | 0.960 | 16 / 16 | ±5% |
| bytes encodeReverse | 1,283,567 [958,824-1,420,815] | 1,316,919 [968,197-1,550,463] | 1.026 | 200 / 176 | ±5% |
| bytes decode | 1,644,905 [87,895-1,734,533] | 861,281 [745,162-881,415] | 0.524 | 19,020 / 36,752 | ±25% |

Mandate judgment: `EmailSearchBench.encodeForward` = 2.230 overall and 2.249 / 2.211 per
repetition, each far above the required 0.95; the encode-forward mandate holds on the
no-okio candidate.

Session 4 vs session 3, per cell (port median move = session-4 port median divided by the
session-3 record's; upstream median move likewise, as the environment-drift control;
allocations from the table above):

| Cell | s4 ratio | s3 ratio | port median move | upstream median move |
|---|---|---|---|---|
| email encodeForward | 2.230 | 2.245 | 0.974 | 0.980 |
| email encodeReverse | 1.019 | 0.990 | 1.006 | 0.978 |
| email decode | 0.948 | 0.874 | 1.026 | 0.946 |
| all_types encodeForward | 1.134 | 1.083 | 1.039 | 0.992 |
| all_types encodeReverse | 1.162 | 1.123 | 0.994 | 0.961 |
| all_types decode | 1.022 | 1.015 | 0.999 | 0.992 |
| packed encodeForward | 0.837 | 0.992 | 0.840 | 0.996 |
| packed encodeReverse | 0.948 | 1.043 | 0.927 | 1.021 |
| packed decode | 1.192 | 1.253 | 0.920 | 0.967 |
| bytes encodeForward | 0.960 | 1.037 | 0.939 | 1.015 |
| bytes encodeReverse | 1.026 | 0.988 | 0.977 | 0.940 |
| bytes decode | 0.524 | 0.797 | 0.681 | 1.038 |

Findings of this session, with the diagnosis each one was written after:

1. **bytes decode 0.524 is a real regression, mechanism closed.** Allocation per op rose
   19,020 -> 36,752 B (+93.2%), an extra 17,732 B over the session-3 port level, which is
   the payload of the workload's 17 bytes values (17 x 1,024 B) once each: since phase 2
   the generated models decode bytes fields through `ProtoAdapter.WIRE_BYTES`, whose
   `WireBytesAdapter.decode` (ProtoAdapter.java at `c713e9a`) does
   `Bytes.fromByteString(reader.readBytes())`, paying the reader's own okio read copy AND
   the cross-package `fromByteString` clone, two copies per value where the phase-1
   generated code paid one through the deprecated `BYTES` adapter. The arithmetic closes
   on the CPU side too: the port lost roughly 370 ns/op against session 3, the memcpy cost
   of 17 KB. The bench enters through `decode(byte[])`, so the `decode(Bytes)` funnel is
   not on the measured path; the cost is entirely the per-field bridge. This is exactly
   the cost docs/api-surface.md pre-declared as "remaining bridge costs, owned by the next
   perf session". Disposition: TASK-28 filed in this same turn (adoptable `byte[]` reader
   feeding `Bytes.takeOwnership`, one copy per value); until its fix lands and is
   re-measured under this document's invalidation rule, the finding is PENDING and
   requires the maintainer's explicit acceptance before TASK-21 (AC#3 discipline).
2. **all_types decode allocation +10.5%** (8,904 -> 9,040 B/op) is the same bridge on this
   workload's four tiny bytes values; throughput stayed in band (1.022). No separate
   disposition; it moves with finding 1's fix.
3. **packed encodeForward 0.837 and packed encodeReverse 0.948 are flagged by the
   pre-declared rule but NOT confirmed as port regressions.** Evidence, in order: the two
   cells' allocations are byte-for-byte the session-3 values (16/16, 200/176); the two
   generated model trees differ by exactly the documented mapping on a path that touches
   no bytes field and no `Bytes` code, and the runtime seam the mapping adds to encode is
   one memo null-check (`unknownFieldsBytes()` on a `Bytes`-constructed message); the
   port's second repetition dropped NON-uniformly, concentrating in the three cells that
   ran consecutively in its wall-clock slot (packed encodeForward port r2/r1 0.857, packed
   encodeReverse 0.884, bytes encodeForward 0.906) while email, all_types, and packed
   decode held their rep-1 levels in the same run; whole-fork and mid-fork collapses are a
   real feature of this session's environment on BOTH profiles: one upstream email
   encodeForward fork collapses mid-run to 358k and 54,802 ops/s and then RECOVERS to
   537k-579k within the same fork, and the port's packed encodeForward rep 1 carries one
   healthy fork (40k-42k throughout) beside one that falls off mid-fork from 40.3k to
   28k-34k and never recovers, i.e. transient machine interference striking individual
   forks under either classpath, not a property of the port's code; port rep 1
   packed encodeForward read 0.936
   with one collapsed fork; and an isolated rerun after the matrix (14:54-15:00, quiet
   machine, same jars, same flags) read encodeForward 0.944 and encodeReverse 1.041, at
   the level of the doc's session 3 (0.992 / 1.043) and of a second full-matrix run of the
   SAME session-3 candidate `2d175e2` preserved in the harness
   (`results/repeat-20261002-2200`, run 2026-10-02 ~22:03-22:27: 0.945 / 1.027).
   Disposition: read as an environmental transient concentrated in the port's second
   repetition slot; the recorded matrix values above stand as measured, and the isolated
   rerun is diagnosis, not a substitute record.

Allocation table, session 4 (gc.alloc.rate.norm median, rep 1): email 4,132 / 4,296,
all_types 8,184 / 9,040, packed 116,192 / 116,456, bytes decode 19,020 / 36,752; every
encode cell 16 / 16 forward and 200 / 176 reverse, identical to session 3 on every cell
including the bytes workload, which confirms the phase-2 encode bridges are zero-copy on
both writers (`ProtoWriter.writeBytes(Bytes)` and `ReverseProtoWriter.writeBytes(Bytes)`
write without copying, `Bytes.size()` is O(1); read at `c713e9a`, not inferred).

Renewal statement (DEC-13 freshness): this record identifies the final candidate by
revision (`c713e9a`), artifact checksums (`e04db4de`/`9d3ed5f2`/`dbb2afd4`), unchanged
oracle pins, generated-model identity under the phase-2 mapping, and unchanged identity
fingerprints; the 12 runtime cells above supersede the session-3 runtime matrix for this
candidate and this record is what TASK-21's acceptance points at. Still open before that
acceptance, unchanged in kind from the session-3 limitations: (1) the three schema cells
and the eight protobuf-java reference cells of section 5's workload 2 and 7 still carry
their session-1 numbers measured on superseded candidates and require the same renewal;
(2) finding 1 above needs the TASK-28 fix and re-measurement, or an explicit maintainer
acceptance record. This document grants no acceptance.

### Session 5 results (2026-10-03 17:50-18:14 CEST, candidate `82c3624`, post TASK-28)

Why renewed: TASK-28 (`bc3ad43`, the zero-copy `readBytesAsBytes` bytes decode) changed
`wire-runtime-java` after session 4, firing the invalidation rule (section 0) a fourth
time; this renewal records the fix on the session-5 candidate (not the final release candidate, see the freshness banner) and is the measurement
the maintainer's 2026-10-03 release-record confirmation (end of this document)
anticipated. The full 12-cell runtime matrix was re-measured end to end with the identical
JMH configuration and interleave (start times 17:50, 17:56, 18:02, 18:08; last run
finished 18:14), the same untouched upstream oracle, and the same two-phase discipline
(bands from this session's own oracle repetitions by the unchanged `tools/analyze.py`
BEFORE any session-5 port number was computed).

Identity block (AC#5):

| Item | Value |
|---|---|
| antiwire revision | `82c362477077364174aec4ce89f2e947ba25029b` (branch `main`, clean tree; the only code delta vs session 4's `c713e9a` is TASK-28 `bc3ad43`, `wire-runtime-java` only; `e0da8b6`..`82c3624` in between are docs and backlog only) |
| Build | `JAVA_HOME=~/.sdkman/candidates/java/17.0.12-tem mvn -pl wire-runtime-java,wire-schema-java,wire-java-generator -am install -DskipTests`, exit 0 (schema and generator sources unchanged vs session 4; the runtime is the only changed artifact) |
| `io.apicurio:wire-runtime-java:0.1.0-SNAPSHOT` SHA-256 | `0f8bb01960ce656c97f57547a541d0d4f9342105bf2ed9aaa754c15865980c58` |
| `io.apicurio:wire-schema-java:0.1.0-SNAPSHOT` SHA-256 | `97f7cb22bef4d9e4dd5d322668a40ae05b32fbb0d42491a891eaedf686bd4dd5` (source unchanged; rebuilt through the same reactor, so the checksum moves with the jar timestamps) |
| `io.apicurio:wire-java-generator:0.1.0-SNAPSHOT` SHA-256 | `dbb2afd41dea44a420dc5919c8bed4286ac5eb853a31914f2f4f4eddec9310a5` (byte-identical to session 4's recorded jar) |
| Oracle | unchanged from sessions 1-4: upstream Wire 7.1.0 artifact set from Maven Central; the upstream bench jar is session 4's own 14:18 build, untouched |
| Bench jar verification | `aw-perf-port.jar` rebuilt against the installed runtime: every one of the 143 runtime jar entries is byte-identical inside the shaded bench jar (the only differing entry is `META-INF/MANIFEST.MF`, rewritten by shade), and the shaded `ProtoReader` carries TASK-28's `readBytesAsBytes` |
| Generated-model identity | `generate-models.sh` re-run: trees identical under the phase-2 Bytes mapping; every per-file fingerprint identical to session 4 (`results/gen-tree-fingerprints.txt`) |
| IdentityCheck | profile-diff clean; every fingerprint identical to sessions 3 and 4: email `cc02806d...` 672 B, all_types `ba3c8a31...` 483 B, packed `df973ccf...` 18,437 B, blob `4d49caab...` 17,472 B |
| Test gate at the candidate | `./scripts/verify.sh` on openjdk 17.0.12 after the measurement: `VERDICT: all 12 ACTIVE suites passed.` (runtime 894, schema 630, protoc-oracle 122, compiler 179 cases; 4/6/50/33 skipped) |

Session-5 matrix (medians over all 40 iterations; range = min-max over both repetitions;
allocation is the `gc.alloc.rate.norm` median of repetition 1; own bands from this
session's phase 1):

| Cell | upstream 7.1.0 ops/s median [min-max] | antiwire ops/s median [min-max] | ratio | alloc/op upstream / port (B) | own band |
|---|---|---|---|---|---|
| email encodeForward | 596,995 [533,293-606,045] | 1,334,388 [1,258,462-1,389,882] | **2.235** | 16 / 16 | ±5% |
| email encodeReverse | 2,819,752 [2,462,663-3,189,809] | 2,974,168 [2,605,535-3,040,728] | 1.055 | 200 / 176 | ±25% |
| email decode | 2,293,974 [2,062,368-2,489,325] | 2,218,526 [1,953,511-2,257,092] | 0.967 | 4,104 / 4,248 | ±15% |
| all_types encodeForward | 885,620 [783,940-935,100] | 993,528 [917,235-1,070,516] | 1.122 | 16 / 16 | ±10% |
| all_types encodeReverse | 1,307,380 [1,228,844-1,356,783] | 1,505,270 [1,368,619-1,546,519] | 1.151 | 200 / 176 | ±5% |
| all_types decode | 521,063 [460,346-544,374] | 523,629 [458,367-545,638] | 1.005 | 8,184 / 8,944 | ±10% |
| packed encodeForward | 41,740 [37,483-43,100] | 40,297 [37,828-42,200] | 0.965 | 16 / 16 | ±10% |
| packed encodeReverse | 66,692 [62,528-68,627] | 67,311 [62,738-69,015] | 1.009 | 200 / 176 | ±10% |
| packed decode | 26,518 [21,991-27,550] | 31,837 [29,565-34,300] | 1.201 | 116,192 / 116,456 | ±10% |
| bytes encodeForward | 1,925,900 [1,699,755-2,027,507] | 2,199,825 [2,088,495-2,241,564] | 1.142 | 8 / 16 (see note 3) | ±15% |
| bytes encodeReverse | 1,368,957 [1,040,152-1,515,840] | 1,320,817 [1,245,890-1,530,053] | 0.965 | 200 / 176 | ±15% |
| bytes decode | 1,447,476 [1,156,207-1,775,867] | 1,672,821 [1,152,171-1,709,196] | **1.156** | 19,040 / 19,072 | ±30% |

Mandate judgment: `EmailSearchBench.encodeForward` = 2.235 overall and 2.193 / 2.257 per
repetition, each far above the required 0.95; the encode-forward mandate holds on the
release candidate.

The TASK-28 fix, before and after (the cell this session exists for): bytes decode was
session 4's finding 1 at **0.524** with 36,752 B/op, two copies per bytes value through
`Bytes.fromByteString(reader.readBytes())`; on the fix candidate it reads **1.156** pooled
(per-repetition 1.026 and 1.064, every repetition at or above parity) with 19,072 B/op
against upstream's 19,040 (+0.2%), the session-3 one-copy level (19,064). TASK-28's
acceptance criterion is met: back inside its oracle band (this session's own band ±30%,
floor 0.700; worst per-repetition reading 1.026) with allocation at the one-copy level.
The pooled ratio exceeds both per-repetition ratios because the upstream side itself split
this session (run-to-run 17.2%: rep 1 median 1.64M, rep 2 median 1.38M with a 1.29M fork),
which is also what sets the cell's ±30% own band; the port's fork medians are 1.683M,
1.686M, 1.688M and one transient 1.295M (the per-fork strike pattern session 4 documented
on both profiles), against upstream's fork medians 1.29M, 1.44M, 1.57M, 1.71M. Honest
reading: parity to modestly faster; the two-copy regression is closed by fix, not
acceptance.

Session 5 vs session 4, per cell (port median move = session-5 port median divided by the
session-4 record's; upstream median move likewise, as the environment-drift control):

| Cell | s5 ratio | s4 ratio | port median move | upstream median move |
|---|---|---|---|---|
| email encodeForward | 2.235 | 2.230 | 1.006 | 1.004 |
| email encodeReverse | 1.055 | 1.019 | 1.001 | 0.967 |
| email decode | 0.967 | 0.948 | 0.994 | 0.974 |
| all_types encodeForward | 1.122 | 1.134 | 0.936 | 0.947 |
| all_types encodeReverse | 1.151 | 1.162 | 0.998 | 1.007 |
| all_types decode | 1.005 | 1.022 | 0.973 | 0.990 |
| packed encodeForward | 0.965 | 0.837 | 1.133 | 0.982 |
| packed encodeReverse | 1.009 | 0.948 | 1.055 | 0.991 |
| packed decode | 1.201 | 1.192 | 1.044 | 1.036 |
| bytes encodeForward | 1.142 | 0.960 | 1.097 | 0.922 |
| bytes encodeReverse | 0.965 | 1.026 | 1.003 | 1.067 |
| bytes decode | 1.156 | 0.524 | 1.942 | 0.880 |

Findings of this session: none. Every cell is inside its own oracle-derived band, the
mandate holds, and no new finding is filed. Three corroboration notes for the record:
(1) session-4 finding 3's packed encode cells read 0.965 and 1.009 (0.837 and 0.948 in
session 4), back at the session-3 level, consistent with that finding's
environmental-transient diagnosis; (2) session-4 finding 2's all_types decode allocation
moved with the fix exactly as predicted, 9,040 -> 8,944 B/op (session-3 level 8,904;
upstream 8,184), throughput 1.005 in band; (3) the upstream bytes encodeForward rep-1
allocation median read 8 B/op because its per-iteration samples split between 0 and 16
(gc profiler accounting at near-zero allocation); rep 2 and the port read a clean 16/16,
so the table's 8 / 16 is an upstream-side reading artifact, not a port change.

Allocation table, session 5 (`gc.alloc.rate.norm` median, rep 1): email 4,104 / 4,248,
all_types 8,184 / 8,944, packed 116,192 / 116,456, bytes decode 19,040 / 19,072; every
encode cell 16 / 16 forward and 200 / 176 reverse except the upstream bytes encodeForward
reading noted above; every port cell identical to its session-3 and session-4 value
except the two the fix moved (bytes decode 36,752 -> 19,072, all_types decode
9,040 -> 8,944).

Renewal statement (TASK-21 evidence): this record renews the full 12-cell runtime matrix
on the session-5 candidate (`82c3624`, code identical to the TASK-28 fix `bc3ad43`)
by revision, artifact checksums, unchanged oracle pins, generated-model identity, and
unchanged identity fingerprints, and it closes the last open performance item of the
session-4 statement (the TASK-28 fix and its re-measurement). Together with the
maintainer's 2026-10-03 release-record confirmation (end of this document), which
anticipated exactly this renewal, it constitutes TASK-21's performance evidence. The
schema and protobuf-java reference cells still carry their session-1 numbers measured on
superseded candidates (session-4 limitation, unchanged in kind); the maintainer's
same-day confirmation record is the acceptance authority over that scope, and this
document grants no acceptance. Any later change to `wire-runtime-java`,
`wire-schema-java`, `wire-java-generator`, the oracle versions, or the harness workloads
re-fires the invalidation rule.

### Workload 2: Apicurio schema operations

| operation | upstream 7.1.0 ops/s median [min-max] | antiwire ops/s median [min-max] | ratio port/upstream | alloc/op upstream (B) | alloc/op port (B) |
|---|---|---|---|---|---|
| parse: ProtoParser, 20 files / 26,088 bytes per op | 8,044 [7,067-8,387] | 8,990 [7,596-9,182] | 1.118 | 407,443 | 422,041 |
| link+load: full schema load, 47 files per op | 358 [225-368] | 388 [336-398] | 1.084 | 8,342,591 | 8,157,656 |
| descriptor: SchemaEncoder, 20 files / 25,971 bytes out per op | 9,847 [9,637-9,946] | 11,426 [10,901-11,754] | 1.160 | 285,819 | 223,730 |

Reading notes:

- Schema operations (the Apicurio path this port exists for) are consistently FASTER on
  the port: parse 1.118x, link+load 1.084x, descriptor conversion 1.160x, the last with
  22% less allocation per op.
- Runtime encode is at parity (within per-benchmark noise) or better on 7 of 8 measured
  cells across the forward and reverse writers in the first two sessions; the exception
  was the email forward-writer cell (0.867 initial, 0.790 re-measured), resolved in
  session 3 by the `okio.Utf8.size` fix (2.245x, section 6).
  `all_types` reverse encode is 1.181x faster with 12% less allocation; the packed and
  bytes reverse-writer cells re-measured at 1.013x and 1.026x confirm their initial
  0.960/0.972 readings were noise-edge parity, not deficits.
- Decode after the entry-point fix (all four cells re-measured): packed lists 1.221x
  (the port is now faster than upstream), all_types 0.980x, email 0.904x, bytes-heavy
  0.810x. Every decode cell now sits inside its own oracle noise band, though the
  bytes-heavy margin is thin (0.810 against a 0.800 floor; see section 6). Allocations
  remain within 9% of upstream everywhere (all_types +8.8%, email +5.3%, packed +0.2%,
  bytes +0.2%), so the residual bytes-heavy deficit is CPU-path, not garbage.

## 6. Acceptance: all four findings resolved by fixes (AC#3)

No regression is accepted by this document. The initial run flagged four findings: under
the pre-declared global band (±25%) exactly one was a regression, and the stricter
per-benchmark oracle band (section 4) added three more for review. Per AC#3 each required
an explicit maintainer acceptance record or a fix before TASK-21. Findings 1, 2, and 4
were fixed at the decode entry point (candidate `c0a2152`) and closed by re-measurement
under the identical configuration and the unchanged bands; finding 3 was fixed at the
`okio.Utf8.size` primitive (candidate `2d175e2`) and closed by the session-3 full-matrix
re-measurement. No finding is pending maintainer acceptance.

### Resolved by the decode entry-point fix (re-measured within their bands)

"Before" is the initial session (candidate `0e87bde`), "after" the 13:36 re-measurement
(candidate `c0a2152`), same JMH configuration, same untouched oracle jar. Per-run
medians order: up r1, up r2, port r1, port r2 (ops/s).

| # | Benchmark | before: ratio / port median / per-run medians | after: ratio / port median / per-run medians | own oracle noise (run2run / fork) | status |
|---|---|---|---|---|---|
| 1 | `perf.BytesBench.decode` | 0.502 / 793,070 / 1,537,994; 1,579,290; 769,015; 808,852 | 0.810 / 1,181,602 / 1,415,125; 1,505,123; 1,181,602; 1,234,145 | 2.6% / 16.7% | RESOLVED (within own ±20% and global ±25%; margin is thin: 0.810 vs the 0.800 own-band floor) |
| 2 | `perf.EmailSearchBench.decode` | 0.799 / 1,812,167 / 2,269,059; 2,323,509; 1,789,157; 1,812,167 | 0.904 / 2,243,927 / 2,501,968; 2,371,751; 2,256,531; 2,205,160 | 2.4% / 15.9% | RESOLVED (within own ±20%) |
| 4 | `perf.PackedBench.decode` | 0.897 / 22,299 / 25,098; 24,239; 22,318; 21,877 | 1.221 / 29,125 / 21,963; 24,802; 30,150; 27,090 | 3.5% / 2.1% | RESOLVED, now faster (upstream rep 1 wobbled, min iteration 9,365, so read 1.221 as at-or-above parity) |

### Resolved by the encode-forward fix (session 3, candidate `2d175e2`)

"Before" is the 13:36 re-measurement (candidate `c0a2152`), "after" the session-3 full
matrix (candidate `2d175e2`, rebuilt harness, same JMH configuration and interleave).

| # | Benchmark | before: ratio / port median | after: ratio / port median / per-rep ratios | own band (before / after) | status |
|---|---|---|---|---|---|
| 3 | `perf.EmailSearchBench.encodeForward` | 0.790 / 480,113 | 2.245 / 1,361,770 / 2.218 and 2.250 | ±5% / ±5% | RESOLVED, port now 2.2x faster (mandate floor 0.95 exceeded in both repetitions) |

Finding 3 doubled as the decode re-run's control for the fix's scope: `encodeForward`
constructs no reader, the decode entry-point change could not reach it, and it did not
move (allocation identical at 16 B/op in every session; upstream stable at ~607k ops/s
across all repetitions while the port read 505k/417k before the fix and 1.34M/1.37M
after).

Mechanism (profiled, then confirmed by intervention): JFR flight recordings and
async-profiler CPU traces of the port's `encodeForward`, plus a decomposition bench set
(`perf.PrimitiveBench` in the harness), attributed ~80% of the op to the forward writer's
length-prefix size pass: `ProtoAdapter.STRING.encodedSize` -> `okio.Utf8.size` walking
every string character once to size it (plus the write pass's own `writeUtf8` walk); on
this workload the 672-byte mailbox message carries a 508-character non-Latin1 body, so
the size pass dominates. The generated adapters and the writer glue are byte-identical
between the two implementations, and in isolation the port's `Utf8.size` loop measured
FASTER than upstream's (1.28x-1.4x). The deficit appeared only when the scan was
compiled inside the adapter call context: the port's javac-built loop (228 bytes of
bytecode) is small enough for HotSpot to inline into the large generated-adapter
compilations, where its per-character code ran ~3x slower (real `StringUTF16.charAt`
and `checkIndex` frames dominating the JFR stacks); okio 3's Kotlin-built loop (334
bytes) is never inlined and always runs as its own compiled unit. Two probes confirmed
the axis: forcing the loop out-of-line (a temporary `synchronized` variant) recovered
the loss entirely (sanity run 1.20x), while a branch-minimized rewrite that stayed
inlinable did not (0.86). The fix reshapes the port's scan into the ASCII run-length
form `Buffer.writeUtf8` already uses (one self-contained scan), making the
per-character cost independent of the caller's compilation context; it preserves the
byte count for every input class (ASCII, 2- and 3-byte, surrogate pairs, malformed
surrogates; `perf.IdentityCheck` fingerprints identical; battery green 869/80/122).
Touched-cell before/after: email encodeForward 0.790 -> 2.245; the other encode cells
all sit between 0.988 and 1.123 (recorded before: 1.004-1.181); decode cells are
unchanged within noise (email 0.904 -> 0.874, all_types 0.980 -> 1.015, packed
1.221 -> 1.253, bytes 0.810 -> 0.797, the last inside this session's ±25% own band,
see the honest note in section 5).

Adjacent cells re-measured as controls in the 13:36 session, all inside their own bands
(no acceptance needed): `AllTypesBench.decode` 0.980 (own band ±10%),
`PackedBench.encodeReverse` 1.013 and `BytesBench.encodeReverse` 1.026 (own bands ±5%);
these superseded the initial 0.938, 0.960, and 0.972 readings.

Mechanism evidence for the decode findings (gathered in the 13:36 session, read, not
inferred; then confirmed by intervention):

- Upstream 7.1.0 `ProtoAdapter.kt` routes `decode(bytes: ByteArray)`
  through `ProtoReader32(bytes)` (`commonDecode`, lines 354-357 of the common source);
  the port's `ProtoAdapter.java` `decode(byte[])` built `new ProtoReader(new
  Buffer().write(bytes))` instead. The generated adapters (byte-identical trees)
  override only `decode(ProtoReader)`, so the reader the `byte[]` entry point
  constructs was the one API-level difference on this path: upstream reads varints
  directly from the input array, the port copied the input into okio buffer segments
  first. The fix (commit `c0a2152`) makes both byte-array entry points construct
  `ByteArrayProtoReader32`, matching upstream's `commonDecode`, and decode outputs are
  unchanged (`perf.IdentityCheck` fingerprints identical,
  `results/identity-port-rerun.txt` diff-clean against the initial run's). All four
  decode cells improved, which confirms the mechanism by intervention; the residual
  ordering (bytes-heavy 0.810 vs packed 1.221) shows the array-backed reader removes the
  copy penalty but leaves a bytes-heavy deficit of roughly 20%. With the entry points
  now identical, whatever remains sits downstream of them; the port's array-backed
  reader implementation is the remaining port-specific code on this path, but a profiler
  has not yet attributed it (read this as location-by-elimination, not a diagnosis).

Improvements found (no acceptance required, reported for the record): all three schema
operations faster (1.084x-1.160x, descriptor with 22% less allocation), `all_types`
reverse encode 1.181x with less allocation, packed decode 1.221x after the fix.

## 7. protobuf-java reference status (workload 3)

Reference only: the same messages, byte-identical encodings (section 3), measured to give
a wire-format baseline and a same-machine control. Its port/upstream ratios (0.981-1.013
across all 8 cells) confirm the environment stayed stable while the wire decode ratios
above were measured; protobuf-java numbers must not be read as a target for either wire
implementation (different codegen, different algorithms). These cells were not re-run at
13:36 (the decode fix cannot reach protobuf-java code); they are the initial session's.

## 8. Provenance

Sessions 1 and 2 (original harness, since lost to a machine reboot): raw material was
`/tmp/aw-perf/results/` (12 JMH JSON files + text logs from the initial session, plus the
re-measurement's `rt2-{upstream,port}-{1,2}.{json,txt}` and `analysis-rerun.json`;
identity fingerprints including `identity-port-rerun.txt`, oracle checksums,
`analysis.txt`, `analysis.json`) and `/tmp/aw-perf/logs/`. The band rule and both
criteria lived in `/tmp/aw-perf/tools/analyze.py`, written before the first port result
existed (file mtime 12:09:24, first port result file 12:26:29) and not modified
afterwards. The re-measurement lived in `/tmp/aw-perf/run-decode-rerun.sh` and
`/tmp/aw-perf/tools/analyze_rerun.py`, which consumed only the bands the initial session
derived. Those files are gone; the numbers above are the record of what they measured.

Session 3 (rebuilt harness): raw material is `/tmp/aw-perf/results/` again
(`rt-{upstream,port}-{1,2}.{json}` + `logs/rt-*.txt` from `run-all.sh` at 18:55-19:19;
`analysis.txt` and `analysis.json` from the same two-phase script rewritten for the
rebuilt harness BEFORE any session-3 port result existed; `identity-port.txt` /
`identity-upstream.txt`, diff-clean; `candidate-revision.txt`,
`candidate-jar-checksums.txt`), plus the diagnosis artifacts in `/tmp/aw-perf/jfr/`
(JFR recordings of encodeForward and the isolated size pass, both profiles) and
`tools/jfr_top.py`. The session-3 `tools/analyze.py` encodes the same two-phase rule and
the same pre-declared mandate: `EmailSearchBench.encodeForward >= 0.95` per repetition,
every other cell inside its own oracle-derived band.

Session 4 (2026-10-03): raw material is `/tmp/aw-perf/results/` again
(`rt-{upstream,port}-{1,2}.{json}` + `logs/rt-*.txt` from the unchanged `run-all.sh` at
14:19-14:44; `analysis.txt` / `analysis.json` from the unchanged two-phase script;
`identity-port.txt` / `identity-upstream.txt`, diff-clean and identical to the session-3
values; `candidate-revision.txt`, `candidate-jar-checksums.txt`,
`gen-tree-fingerprints.txt`), plus the session-4 harness adaptations (`tools/map_bytes.py`
mapping normalizer, `tools/compare_sessions.py`, the per-profile `gen/` trees and
`src-port/java` / `src-upstream/java` `perf.ModelBytes` pair, all visible in the harness's
own files) and the diagnosis artifacts `results/iso-packed-{upstream,port}.json` (the
isolated 14:54-15:00 packed rerun of finding 3). The files this session displaced were
preserved under `results/repeat-20261002-2200` and `logs/repeat-20261002-2200`: a second
full-matrix run of the SAME candidate `2d175e2` on 2026-10-02 ~22:03-22:27, whose readings
(packed encodeForward 0.945, encodeReverse 1.027, bytes decode 0.958) corroborate the
packed-cell and bytes-decode analyses above.

Session 5 (2026-10-03): raw material is `/tmp/aw-perf/results/` again
(`rt-{upstream,port}-{1,2}.{json}` + `logs/rt-*.txt` from the unchanged `run-all.sh` at
17:50-18:14; `analysis.txt` / `analysis.json` from the unchanged two-phase script;
`identity-port.txt` / `identity-upstream.txt`, diff-clean and identical to the session-3
and session-4 values; `candidate-jar-checksums.txt`, `gen-tree-fingerprints.txt`), plus
`tools/compare_sessions_s5.py` (pairs this session against the session-4 record) and
`tools/doc_table.py` (table extraction from the raw JSONs). The session-4 files this run
displaced are preserved under `results/session4-20261003/` and
`logs/session4-20261003/`, including `t28-bytes.json` (the TASK-28 isolated
pre-measurement on the fix jars).

This document is a measurement record; it grants no acceptance. All four findings of the
initial matrix are resolved by fixes and closed by re-measurement under the pre-declared
bands, so AC#3 no longer carries a pending gate; the TASK-21 acceptance still requires
the maintainer's explicit record, including renewal of the schema and protobuf-java
reference cells on the `2d175e2` candidate per the invalidation rule (section 0).

### Coordinate update 2026-10-02

The maintainer resolved DEC-8: coordinates are now `io.apicurio` (was `io.github.paoloantinori`). A coordinate switch changes no class or resource byte (diff -rq of unpacked old/new jars differs only in the embedded META-INF/maven groupId directory), but whole-jar SHA-256 values therefore change. Current checksums under io.apicurio: wire-schema-java ac9a36f2..., wire-runtime-java 129a25cb... (old values 7bc11249.../43c810fb... superseded). The measured artifact set, sizes, and dependency graph are unchanged; resolution re-verified on the Apicurio integration branch (dependency:tree shows io.apicurio nodes, zero stale coordinates, Oracle A still 20/20 byte-identical). Superseded: whole-jar checksums were re-issued for later candidates (session 5 records 0f8bb019, 97f7cb22 and dbb2afd4); the ac9a36f2 and 129a25cb values are no longer current.

### Release-record confirmation (2026-10-03)

The maintainer confirmed the performance reports as the release reference (session record, 2026-10-03). At confirmation time every cell is inside its oracle band and both former findings are resolved by fix, not acceptance: EmailSearchBench.encodeForward by the Utf8.size fast path (2.24-2.25x across sessions 3-4) and BytesBench.decode by TASK-28's zero-copy readBytesAsBytes (session 5: pooled ratio 1.156 per the session-5 table, allocation back to one-copy parity; the 0.880 in the session-5-versus-session-4 table is the upstream median-move control column, not the port ratio). A session-5 renewal on the final release candidate (post TASK-28) records the bytes-decode fix measurements; any later relevant change re-fires the invalidation rule.

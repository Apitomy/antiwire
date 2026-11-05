---
id: TASK-21
title: Publish 0.1.0 with attribution and docs
status: To Do
assignee: []
created_date: 2026-09-29 09:23
updated_date: '2026-11-05T08:05:00Z'
labels: []
milestone: m-11
dependencies:
  - TASK-19
  - TASK-20
  - TASK-23
  - TASK-14.1
  - TASK-14.2
  - TASK-16.1
  - TASK-16.2
  - TASK-21.1
references:
  - https://github.com/Apitomy/antiwire/issues/3
documentation:
  - docs/decisions.md
  - >-
    backlog/tasks/task-41 -
    Profile-and-close-the-bytes-decode-residual-deficit-session-6-finding-1.md
priority: high
ordinal: 21000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Publish the first release only after the approved compatibility, complete applicable test coverage, Apicurio integration, measured footprint/performance and demonstrated maintenance gates pass. Use the io.apitomy groupId (DEC-19, 2026-11-03; io.apicurio until then per DEC-8) only with namespace/publication authorization explicitly recorded; project planning is not authorization to publish or transfer. Include sources, javadoc, reproducible build instructions, pinned upstream provenance and a per-file license inventory covering headers and embedded notices. Preserve Google Nano BSD-style notices in the reader family and ProtoWriter, R8 notices, JetBrains notices, mixed Square Apache notices and notices for any vendored dependencies. Coordinate the final external publish action with the maintainer.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Explicit maintainer release approval for publishing artifacts is recorded before anything is published; the io.apitomy namespace (DEC-19, 2026-11-03; io.apicurio until then per DEC-8) itself is already authorized. Sources, javadoc and POM metadata accompany a reproducible release build.
- [ ] #2 The final release candidate passes all applicable runtime/schema/protoc/compiler/profile/golden checks with no required case pending, and the recorded Apicurio integration evidence matches the same candidate.
- [ ] #3 Footprint/performance reports and their acceptance records identify the released candidate by build revision, artifact checksums and resolved dependency identities. Relevant changes require remeasurement and renewed acceptance; stale records block release. TASK-23's demonstrated synchronization procedure is available.
- [ ] #4 The candidate POMs and resolved consumer classpaths contain no Kotlin, Kotlin-backed production dependencies, leaked test/build tools or duplicate upstream/port classes. Production bytecode and API use selected on Java 11 satisfy that baseline, including multi-release dependencies.
- [ ] #5 License/header/in-file notice auditing covers the actual derived files and dependencies; README documents the source-migration contract, upstream pin, supported features and excluded functionality.
- [ ] #6 Using the final candidate artifacts and resolved production dependencies, actual Java 11 consumer runs exercise runtime encoding/decoding, schema parsing/loading and the optional Java generator. The same checks run against published artifacts before release completion; JDK 17-only runs cannot satisfy this gate.
- [ ] #7 Every production source and resource shipped in the final candidate is listed in docs/license-inventory.md with a verified header, including files added after the 2026-10-06 audit (baseline 295ef5f).
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
2026-10-06 status for the release prerequisites. The audit follow-ups are all closed and merged on main. TASK-14.1, TASK-14.2, TASK-16.1, TASK-16.2 and TASK-21.1 are Done, and the verification battery passes on main (scripts/verify.sh, all 12 ACTIVE suites, run by me at 0f860ff). The parity gate reports no skipped case owned by an open task.

Renewed since the audit, not yet accepted:
- docs/license-inventory.md was re-audited at 0f860ff: 161 shipped sources and 9 resources, with Bytes.java and ProtoTarget.java classified. The audit found that ProtoReader32AsProtoReader.java is translated from a Square 2024 file but carried the antiwire header; its header was corrected to the upstream Square notice. That is a comment-only change that alters the jar bytes of wire-runtime-java.
- docs/footprint.md section 10 renews the footprint measurement for 0f860ff on Linux with the Temurin 17 baseline: nine artifacts with checksums, three clean-consumer probes, zero Kotlin, okio or Guava nodes. Its acceptance row reads PENDING maintainer signature. The Apicurio marginal was NOT renewed on this host and is not claimed.

Still open and owned here:
1. Maintainer signature on the section 10 footprint, after the final candidate is fixed. The checksums bind to 0f860ff and differ after the license-header correction, so the footprint must be measured on the final candidate.
2. Performance remeasurement on the final candidate. The JMH harness was lost with the Mac scratch directory and the benchmark must run on a quiet machine to stay comparable with sessions 1 to 5, so it was not run.
3. Same-candidate Apicurio revalidation and the Apicurio marginal. The original TASK-18 integration branch was lost (wiped from the Mac /tmp, see the dated notes below); the integration was redone on a pushed branch, `antiwire-integration` on `paoloantinori/apicurio-registry` (TASK-33.4), which must be rerun against the final candidate.
4. Regeneration of docs/release-candidate.md for the final candidate, using the repaired builder (scripts/release-build.sh).
5. The published-artifact Java 11 check, and the release-time check that published dependency metadata contains no Kotlin.
6. Explicit maintainer approval of publication and of the io.apitomy namespace use (DEC-19; io.apicurio until 2026-11-03).

Historical record, recovered verbatim from commit 295ef5f0e7a1e45937417b9f621044d8139df6f5 (the Backlog MCP rewrite had dropped it): the 2026-10-02 17:50 release-groundwork comment. It describes the state on its original date, not the current approval state.

Mechanical release groundwork landed (no publish, no tag, no deploy): docs/license-inventory.md (per-file notice audit, all 159 production sources plus 9 resources, zero missing notices, three working assumptions corrected: IntArrayList family is JetBrains not R8, ProtoReader32AsProtoReader carries the antiwire header not a Nano notice, okio/Base64 carries the ASF header it vendored with; open discrepancy flagged: NOTICE attributes the ProtoReader family and MathMethods to Google under Apache 2.0 while the files carry BSD-style notices, and DEC-12 says NOTICE carries the BSD texts verbatim); README release-contract section; scripts/release-build.sh producing target/release/ (9 jars + MANIFEST.sha256) and the generated docs/release-candidate.md identifying candidate a45b0f0 with the two open gates (footprint signature with the guava pin observation, encodeForward) auto-filled from the live acceptance rows plus the DEC-13 freshness caveat; pom.xml header comment corrected for the resolved DEC-8 (comment-only, effective model identical). verify.sh on JDK 17.0.12: all 12 ACTIVE suites PASS. No AC ticked: publication approval, final-candidate freshness, and the Java 11 published-artifact smoke remain.

GitHub tracking issue: https://github.com/Apitomy/antiwire/issues/3

2026-10-06 maintainer confirmations, recorded in docs/decisions.md. (1) The io.apicurio namespace is confirmed for use (DEC-8 reconfirmed). This covers the namespace only: AC#1's explicit release approval for publishing artifacts remains open and is the maintainer's gate, so no publish, tag or deploy follows from this note. (2) The JDK-typed public API without okio is confirmed as the intended design (new DEC-14, backed by ConsumerApiSurfaceTest and JdkSchemaLoaderValidationTest).

2026-10-07 audit note: the NOTICE discrepancy mentioned in the 2026-10-02 groundwork note (Apache attribution over BSD-only files) is now resolved: NOTICE reproduces the BSD notices verbatim without the Apache sentence and carries a JavaPoet 1.13.0 attribution (see docs/license-inventory.md, 2026-10-07). The 159-source count there was the a45b0f0 figure; HEAD has 161 (docs/license-inventory.md).

2026-10-09 correction: open item 3 above says the integration branch existed only on the Mac. It is not on the Mac either (a11d7cfa and 6ce5582c are invalid objects in the Apicurio clones on both hosts, no antiwire-integration branch anywhere checked). Same-candidate Apicurio revalidation and the Apicurio footprint marginal now also require locating or redoing the TASK-18 migration; see TASK-33.

2026-10-09 verified finding (supersedes the earlier 2026-10-09 correction): the integration branch was on the Mac at /tmp/apicurio and was wiped (4058 empty directories, 0 files, no git objects; Mac rebooted 2026-10-02 16:51, the last cleanup most likely macOS /tmp housekeeping, mechanism unconfirmed). It is absent from bird as well. Open item 3 therefore means redoing the TASK-18 migration on a fresh Apicurio branch and keeping it in a persistent location (pushed to a branch of a fork or an antiwire-owned repo), not /tmp. Same-candidate Apicurio revalidation and the Apicurio footprint marginal depend on it. See TASK-33 and TASK-18.

2026-10-29 (DEC-18 snapshot publishing review): the parent pom now copies LICENSE and NOTICE into META-INF of every main and test jar and declares Apache-2.0 plus BSD-3-Clause `<licenses>` (verified on a local file deploy: all four published binary jars carry both files, NOTICE byte-identical to the root file). Open for the release audit (AC#5): the -sources and -javadoc jars do not carry LICENSE or NOTICE (sources keep the per-file headers; the javadoc jar has neither), and the poms still lack the `<url>`, `<scm>` and `<developers>` elements Central requires for a release.

2026-10-29 /simplify on the DEC-18 change, finding not applied there: the LICENSE/NOTICE copy uses ${project.basedir}/.. and copy-resources silently copies nothing when its includes match no file, so a future module not sitting directly under the repository root would ship jars without the notices and the build would still pass. Guard to add before the release: a check that every shipped jar (main and tests) contains META-INF/LICENSE and META-INF/NOTICE, either an enforcer requireFilesExist per jar module or a jar-content assertion in scripts/release-build.sh or scripts/test-release-gates.sh.

2026-11-03, DEC-19: groupId is now io.apitomy (was io.apicurio, DEC-8) and the repository moved to the Apitomy organization. Consequences for the open items: docs/release-candidate.md regeneration and the published-artifact checks (items 4 and 5) must use io.apitomy coordinates; the Apicurio revalidation (item 3) must re-point the fork branch antiwire-integration, whose poms still depend on io.apicurio:wire-schema-java; SNAPSHOT publishing (DEC-18) now needs the io.apitomy namespace's snapshots enabled and the CENTRAL_TOKEN_* secrets stored by the Apitomy namespace admins. The recorded io.apicurio checksums in footprint/performance stay as measured, with dated coordinate notes added.

2026-11-03 correction (from the DEC-19 code review): the earlier text naming io.apicurio as the namespace to authorize is superseded by DEC-19; AC#1 and the description above now say io.apitomy, and open item 6's "approval of the io.apicurio namespace use" reads approval of the io.apitomy namespace use, as does the m-11 milestone line.
<!-- SECTION:NOTES:END -->

## Comments

<!-- COMMENTS:BEGIN -->
author: TASK-41 closure
created: 2026-11-05T08:05:00Z

---

2026-11-05 (TASK-41 closure): the performance gate of this task's AC#3 has one pending item, now investigated to mechanism level in TASK-41 (docs/performance.md session 6, finding 1 outcome): the bytes-decode cell reads 0.802 with a confirmed JIT inline-shape mechanism and a +19% intervention bound; the contained fix was measured neutral and reverted. This task's gate closes when the maintainer explicitly accepts that cell as measured, or funds the varint-path divergence as future work; until then the release record's performance section is complete except for that acceptance.

---
<!-- COMMENTS:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /code-review at high effort on the final diff and resolve or explicitly disposition every finding before marking Done
<!-- DOD:END -->

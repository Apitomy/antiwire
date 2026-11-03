---
id: TASK-2
title: Set up Maven multi-module build skeleton and CI
status: Done
assignee:
  - assistant
created_date: '2026-09-29 09:22'
updated_date: '2026-10-06 18:40'
labels: []
milestone: m-6
dependencies:
  - TASK-1
documentation:
  - docs/decisions.md
priority: high
ordinal: 2000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Create only the minimum Maven structure needed for M0 experiments and later modules. Provide runtime, schema and optional Java-generator module shells using the provisional layout from TASK-1. Build production Java with --release 11 on JDK 17 or later. Kotlin compiler plugins and libraries may be used for test compilation or build-time fixture generation, never as production dependencies. Establish a single verification entry point that later tasks extend, with upstream implementations isolated from port-under-test classpaths. TASK-5 supplies the representative adapted-test compilation proof; empty suites are not evidence of parity.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 The runtime, schema and optional Java-generator module shells build production Java with --release 11 on JDK 17; no functionality or parity is claimed from an empty build.
- [x] #2 CI runs the build and a shared verification entry point; the entry point reports which planned suites are active and cannot label absent suites as passed.
- [x] #3 Production dependency checks reject Kotlin and Kotlin-backed transitives and require Java 11-compatible bytecode and API usage in the classes selected on Java 11, including multi-release jars. Published metadata excludes test/build tools.
- [x] #4 The build supports Kotlin test compilation and pinned upstream fixture generation in isolated scopes, with an executable check of where port-under-test classes load from; the check fails when the same com.squareup.wire or retained okio class name resolves from two different artifacts, reporting both origins (duplicate-class prevention rules in docs/compatibility-matrix.md).
- [x] #5 CI provisions an actual Java 11 JVM for consumer execution checks, separate from the JDK 17+ build toolchain. TASK-5 exercises the spike and TASK-21 checks the final published artifact set; empty module checks cannot certify runtime compatibility.
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Implementation delegated to a subagent; simplify and code-review findings fixed by a second worker; orchestrator re-verified build and entry point before commit 2a60bed. The push triggers the first CI run; its result should be checked before TASK-5 relies on the workflow.

CI evidence recorded post-completion: run 36664501304 on Apicurio/antiwire (commit 2a60bed) completed with success in 59s, verifying AC#5's CI execution empirically. Process note: AC#5 was checked minutes before that run finished; the success evidence closes the gap, and future AC checks wait for the named evidence before being marked.

2026-10-06 update: the placeholder groupId noted in the Final Summary is superseded. io.apicurio is the final namespace (DEC-8, authorized by the maintainer on 2026-10-02 and reconfirmed on 2026-10-06); publication stays gated by TASK-21 AC#1 and maven.deploy.skip. The Final Summary above records the state on 2026-09-30.
2026-11-03 correction: io.apicurio is itself superseded by DEC-19; the namespace is io.apitomy and the repository moved to the Apitomy organization. See docs/decisions.md.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Set up the Maven multi-module build skeleton and CI.

Parent aggregator plus wire-runtime-java, wire-schema-java and the optional wire-java-generator shells (runtime standalone, schema on runtime, generator on schema, per OPEN-2). Production Java at --release 11 built on Temurin 21; Kotlin compiled test-scope only, proven by a Kotlin smoke test; maven-enforcer bans Kotlin and Kotlin-backed artifacts in production scope (negative-tested both directions, including the enforcer grammar trap where scope-qualified patterns are inert).

scripts/verify.sh is the shared verification entry point: a suite registry from config/verify-suites.json printing ACTIVE/PENDING with owners, fail-closed semantics (PASS requires exit 0 plus result=PASS; absent JDK 11 or failed build record MISSING, never PASS), no PASS from stale artifacts after a failed build, and enforcer failures attributed to the dependency-policy suite. check-classpath.sh reports class origins per module and fails on duplicate com.squareup.wire/okio classes across artifacts, multi-release aware; check-java11-bytecode.sh gates Java 11-selectable bytecode including multi-release jars; consumer-check-java11.sh compiles and runs a placeholder consumer on a real JDK 11 (sdkman Temurin 11 locally, CI-provisioned in the workflow) and certifies nothing. CI: one build job, JDK 17 primary plus setup-java 11 with JAVA_HOME_11_X64; first run triggered by the push of commit 2a60bed.

GroupId io.github.paoloantinori is a recorded placeholder with maven.deploy.skip=true; io.apicurio awaits DEC-8 authorization. Shared scripts/lib.sh owns the module list (consistency-checked against the pom), jar discovery (exact-version match, fails on stale/ambiguous jars), and exit-code constants.

Gates: pa:simplify (3 checks, findings fixed); code-review high (7 findings resolved with negative-test evidence, including two empirically confirmed: stale-jar selection and unattributable PASS rows); mvn verify green; verify.sh green; shellcheck clean. Unproven: the GitHub Actions run itself (awaits the pushed workflow execution).
<!-- SECTION:FINAL_SUMMARY:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Run /code-review at high effort on the final diff and resolve or explicitly disposition every finding before marking Done
<!-- DOD:END -->

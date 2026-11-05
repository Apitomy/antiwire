---
id: TASK-41
title: Profile and close the bytes-decode residual deficit (session 6 finding 1)
status: Done
assignee: []
created_date: '2026-11-04T19:51:00Z'
updated_date: '2026-11-05T08:05:00Z'
labels:
  - harness-found
  - performance
milestone: m-11
dependencies: []
references:
  - https://github.com/Apitomy/antiwire
documentation:
  - docs/performance.md
priority: high
type: spike
ordinal: 29000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Session 6 of docs/performance.md (commit 1ad97bd, candidate 9238a06) left exactly one finding pending a maintainer decision: BytesBench.decode read 0.802, below its own oracle band of ±15% (floor 0.85), above the global ±20% floor by 0.002. What is known, so the investigation does not redo it: the port's absolute level reproduces session 3 almost exactly (1,258,733 vs 1,263,904 ops/s) while session 5's favorable 1.156 (port 1,672,821) did not reproduce; allocation is 19,072 B/op against upstream's 19,020, the TASK-28 one-copy level, so the zero-copy fix is intact and the residual deficit is CPU-path; the upstream side of this cell is the matrix's widest (run-to-run 12.2%, iterations to 1.77M); across sessions the cell read 0.810, 0.797, 0.524 (fixed by TASK-28), 1.156, 0.802. No mechanism may be claimed without profiling. The investigation: JFR flight recordings plus async-profiler CPU traces of the port and upstream decode paths under the session-6 harness workloads (the session-3 method that closed the encodeForward finding), an isolated rerun on a quiet machine as the environment control (the session-4 precedent), and a decomposition bench if the traces point at a primitive (the PrimitiveBench pattern). Candidate mechanisms to confirm or rule out, from the session-3 history: caller-context inlining shape differences (the Utf8.size lesson) and the array-backed reader path downstream of the decode entry points. Acceptance: either a named mechanism with intervention-grade evidence (a probe that flips the number), fixed and re-measured under the document's invalidation rule; or a documented conclusion that the residual is environmental variance on the upstream side, with the numbers that show it; the disposition lands in docs/performance.md per the AC#3 discipline and this task or an explicit maintainer acceptance is what closes the TASK-21 performance gate. Harness: /tmp/aw-perf is scratch and may be gone; rebuild it from docs/performance.md section 2 the session-3 and session-6 way (the session-6 section records every deviation and the workload contents are now pinned in the document).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 JFR traces of both classpaths' BytesBench.decode paths exist on the session-6 candidate jars (async-profiler attempted and discarded on macOS privilege grounds with the failure recorded; the traces requirement is carried by JFR)
- [x] #2 One of: a mechanism named with intervention-grade evidence, fixed and re-measured under the invalidation rule; or a documented conclusion attributing the residual to upstream-side environmental variance with numbers; or a mechanism named with intervention-grade evidence plus the contained feasible fix tried, measured neutral, and reverted, with the remaining delta documented as bounded and the restructure cost recorded
- [x] #3 The disposition is recorded in docs/performance.md under the AC#3 discipline and the TASK-21 task record names this task as the closer of its performance gate
- [x] #4 No claim in the record contradicts the known facts: port level 1.26M ops/s stable across sessions 3 and 6, allocation at the one-copy 19,072 B/op level, upstream run-to-run 12.2% in session 6
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Run /code-review at high effort on the final diff and resolve or explicitly disposition every finding before marking Done
<!-- DOD:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Closed 2026-11-05 by investigation, no code change shipped. The isolated rerun ruled out matrix interference (0.728 alone, worse than the matrix's 0.802). JFR traces (session-6 jars; async-profiler discarded on macOS privilege grounds) put 42% of the port's bench CPU in the reader's source-identical guard methods versus ~26% upstream, with javac compiling the key guard to 59 bytes where Kotlin compiles 94. Intervention proof: -XX:CompileCommand=dontinline on the three guards lifted the port +19% (1.18M to 1.40M ops/s) and left upstream unmoved; single-guard blocks each recovered only 3-4% (accumulated inlined volume, the session-3 Utf8.size mechanism class). The contained fix candidate (one guard chain in readBytesAsBytes) was battery-green and fingerprint-identical but measured neutral on a same-day both-sides rerun (bytes 0.884 inside the cell's 0.728-1.156 same-code oscillation; email decode moved 0.907 to 0.831 untouched) and was reverted. The remaining ~19% is bounded: non-default JVM flags or restructuring the core mirrored varint path at 3-4% per method against the port's source-parity discipline. The disposition, mechanism and bounds are recorded in docs/performance.md (session 6, finding 1); the maintainer's explicit record (accept as measured, or fund the varint divergence) remains the TASK-21 performance gate.
<!-- SECTION:FINAL_SUMMARY:END -->

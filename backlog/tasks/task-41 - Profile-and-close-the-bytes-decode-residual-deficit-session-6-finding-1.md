---
id: TASK-41
title: Profile and close the bytes-decode residual deficit (session 6 finding 1)
status: To Do
assignee: []
created_date: '2026-11-04T19:51:00Z'
updated_date: '2026-11-04T19:52:00Z'
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
- [ ] #1 JFR and async-profiler traces of the port and upstream BytesBench.decode paths exist, captured under the session-6 harness workloads on the session-6 candidate or a later one named by revision
- [ ] #2 Either a mechanism is named with intervention-grade evidence (a probe that flips the number) and fixed and re-measured under the invalidation rule, or a documented conclusion shows with numbers that the residual is upstream-side environmental variance
- [ ] #3 The disposition is recorded in docs/performance.md under the AC#3 discipline and the TASK-21 performance gate names this task or an explicit maintainer acceptance as its closer
- [ ] #4 No claim in the record contradicts the known facts: port level 1.26M ops/s stable across sessions 3 and 6, allocation at the one-copy 19,072 B/op level, upstream run-to-run 12.2% in session 6
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /code-review at high effort on the final diff and resolve or explicitly disposition every finding before marking Done
<!-- DOD:END -->

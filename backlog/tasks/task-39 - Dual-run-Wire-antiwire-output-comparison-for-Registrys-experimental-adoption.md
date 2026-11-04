---
id: TASK-39
title: Dual-run Wire/antiwire output comparison for Registry's experimental adoption
status: Done
assignee: []
created_date: '2026-11-03 16:29'
updated_date: '2026-11-04 00:34'
labels:
  - design
dependencies: []
references:
  - 'https://github.com/Apitomy/antiwire/issues/9#issuecomment-6605890289'
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Proposal from @jsenko, 2026-11-03, issue #9 (https://github.com/Apitomy/antiwire/issues/9#issuecomment-6605890289). For Registry to take antiwire behind apicurio.features.experimental.enabled there must be a way back: if antiwire normalizes or prints a schema even slightly differently from Wire, content hashes, de-duplication and compatibility results written while antiwire was active would not match after switching back. His idea: an opt-in mode that runs Wire and antiwire side by side, compares the output of every operation, and fails (or falls back to Wire) on any difference; evaluation over a corpus of real-world schemas (the upstream test suites and the schemas in the Confluent compatibility checks).

Constraints and known facts to carry in:
- Wire and antiwire both ship com.squareup.wire classes, so a same-classpath dual-run is impossible without relocating one side; the wire-upstream-shaded fixture is the in-repo precedent for shading real Wire.
- DEC-15 is a known divergence of exactly this class: Confluent 8.0.0 ProtobufSchema.canonicalString() throws IllegalArgumentException on a proto2 schema with reserved or extension ranges (int[] pairs instead of kotlin.ranges.IntRange).
- Any difference found by the dual-run must land in config/surface-baseline.tsv or the compatibility docs, not just be fixed silently.
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /code-review at high effort on the final diff and resolve or explicitly disposition every finding before marking Done
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
2026-11-03, maintainer on jsenko's comment: "capisco la preoccupazione, probabilmente dobbiamo fare dei test estensivi prima". Extensive output-identity testing precedes any proposal of Registry adoption. Related: DEC-19 (same day) renamed the groupId to io.apitomy and moved the repository to the Apitomy organization; the dual-run fixture, if built, relocates upstream Wire under io.apitomy.antiwire.parity.* now.

2026-11-04 closed. Merged to main as 0830d39 + review-fix commits, pushed cbb44fe, CI run 41747766847. Suite dual-run-identity ACTIVE since eeb6f3b; my batteries: 14/14 ACTIVE at eeb6f3b, at 10074f2, and at the final state. The harness found TASK-40 on its first run (by design: the ledger held the divergence instead of a silent fix). Follow-ups recorded: TASK-40 owns the port fix (its fix makes the ledger row stale and forces removal); the apicurio corpus pin is a moving origin/main head and the bump procedure in docs/parity-runner.md governs re-pins.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
The dual-run output-identity harness is merged (merge 0830d39 plus review-fix cbb44fe) and the suite dual-run-identity is ACTIVE: real Wire 7.1.0 (relocated fixture, now carrying wire-schema-jvm with widened relocations) and the port load 213 pinned corpus files (171 upstream roots + 42 Apicurio Registry at origin/main 23a09ab4, GitHub-reachable proven cache-cold) staged per package, and assert identical descriptor bytes, canonical text, load outcomes and DEC-15 element types. Coverage floors pin both-LOADED at the measured 141/31 (172 of 213; 40 both-THREW documented per cause in docs/compatibility-matrix.md), so green cannot mean empty coverage. The run found one real port defect, filed as TASK-40 (bytes-typed option CCE at encode), ledgered and documented, never silenced. Gates: three /code-review high rounds on the contribution (10 findings drove rounds 2-3; the final delta review found 7 more, all applied: report-freshness on the verdict path, PENDING-ADMISSION sentinel, blob:none clone, floor-comment scoping, trailing newline, corpus-row validation, pin-at-head standalone checks) plus two /simplify rounds; tests: my full batteries 14/14 ACTIVE suites before merge and before push. DoD /code-review: satisfied on the final diff, every finding applied or explicitly dispositioned in the worktree .audit design reconciliation.
<!-- SECTION:FINAL_SUMMARY:END -->

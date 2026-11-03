---
id: TASK-39
title: Dual-run Wire/antiwire output comparison for Registry's experimental adoption
status: To Do
assignee: []
created_date: '2026-11-03 16:29'
updated_date: '2026-11-03 16:43'
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
<!-- SECTION:NOTES:END -->

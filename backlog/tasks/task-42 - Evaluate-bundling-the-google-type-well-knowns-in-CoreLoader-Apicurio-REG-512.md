---
id: TASK-42
title: Evaluate bundling the google/type well-knowns in CoreLoader (Apicurio REG-512)
status: To Do
assignee: []
created_date: '2026-11-04T21:24:00Z'
updated_date: '2026-11-04T21:24:00Z'
labels:
  - field-report
milestone: m-11
dependencies: []
references:
  - https://github.com/Apitomy/antiwire
documentation:
  - docs/decisions.md
priority: low
type: enhancement
ordinal: 30000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Field report from the Apicurio integration agent (REG-512, 2026-11-04): every consumer that loads schemas with google-type imports must stage google/type/*.proto (15 files) plus api, source_context, type, field_mask, struct, metadata and decimal on every call, because antiwire's CoreLoader does not bundle them the way it bundles descriptor.proto and the other well-knowns. The Apicurio ProtobufSchemaLoader stages this set per call on its integration branch; the performance harness of docs/performance.md stages the same 18 files for the same reason. field_mask and struct are already bundled today, so a staged copy overlaps the bundled one (today harmless because the staged tree wins resolution, but the overlap should be resolved deliberately, not by accident). The proposal: CoreLoader resolves the google/type family and the remaining well-knowns from its bundled resources, so consumers drop the per-call staging; weigh the jar-size cost of bundling 15+ schemas against the consumer friction it removes before committing to it. Evaluation inputs: the Apicurio agent measured its Jimfs staging route at 1.00-1.04 ms per full loadSchema versus 2.48-3.57 ms with temp-directory staging (branch antiwire-integration-jimfs, ANTIWIRE_MIGRATION.md section REG-511), so the staging cost is already small once in-memory; the remaining argument for bundling is consumer simplicity, not speed. If adopted, the harness of docs/performance.md must re-derive its staged-closure identity (the invalidation rule covers harness-workload changes) and the compatibility surface check must cover the new bundled resources.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 A recorded decision says yes or no, with the measured jar-size delta of bundling the google/type family plus the remaining extras against the consumer friction it removes
- [ ] #2 If yes: CoreLoader resolves the new well-knowns from bundled resources, the field_mask and struct overlap is resolved deliberately, the compatibility surface covers the new resources, and the docs/performance.md harness re-derives its staged-closure identity under the invalidation rule
- [ ] #3 If no: the decision records the reason and the consumer staging recipe stays the documented path
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /code-review at high effort on the final diff and resolve or explicitly disposition every finding before marking Done
<!-- DOD:END -->

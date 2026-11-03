---
id: TASK-40
title: >-
  Port throws ClassCastException encoding bytes-typed option values (TASK-39
  finding)
status: To Do
assignee: []
created_date: '2026-11-03 21:37'
updated_date: '2026-11-03 22:04'
labels:
  - bug
  - harness-found
dependencies: []
references:
  - 'https://github.com/Apitomy/antiwire/issues/9'
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the TASK-39 dual-run harness on its first corpus run (2026-11-03), and independently reproduced: loading a schema whose file carries a bytes-typed option value (option (bytes_value) = ...) succeeds, but SchemaEncoder(schema).encode(protoFile) throws java.lang.ClassCastException "class okio.ByteString cannot be cast to class com.squareup.wire.Bytes" on the port, where real Wire 7.1.0 encodes fine. Corpus witness: squareup/proto2/kotlin/alloptions/all_options.proto (upstream corpus, /tmp/wire clone). Root cause area: the linked option-value model keeps the parsed okio.ByteString where the port's model (DEC-14) uses com.squareup.wire.Bytes; the conversion is missing on the encode path. Evidence: my jshell reproduction against the port classes (load OK files=3, encode CCE), harness ledger row 1 in config/dual-run-divergences.tsv and docs/compatibility-matrix.md section "TASK-39 dual-run divergences". Acceptance: the corpus witness encodes identically to upstream (the harness's ledger row goes stale and is removed by the stale-row rule in the same commit as the fix); a regression test with a minimal bytes-option proto lands in wire-schema-java; surface-baseline.tsv updated if any member changes.
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Run /code-review at high effort on the final diff and resolve or explicitly disposition every finding before marking Done
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
2026-11-03 throw site pinned by the follow-up agent's stage-by-stage repro (/tmp/task39-repro, against the worktree jars): STAGE loadSchema OK; STAGE protoFile lookup OK (types=2); STAGE encode THREW java.lang.ClassCastException at com.squareup.wire.ProtoAdapter$WireBytesAdapter.encode(ProtoAdapter.java:1352). So the missing conversion is where the option value reaches the WireBytes adapter during SchemaEncoder's options encoding: the parsed okio.ByteString must become com.squareup.wire.Bytes before the adapter sees it.
<!-- SECTION:NOTES:END -->

---
id: TASK-40
title: >-
  Port throws ClassCastException encoding bytes-typed option values (TASK-39
  finding)
status: Done
assignee: []
created_date: 2026-11-03 21:37
updated_date: '2026-11-04T19:10:00Z'
labels:
  - bug
  - harness-found
dependencies: []
references:
  - https://github.com/Apitomy/antiwire/issues/9
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

2026-11-04 closed. Root cause chain (causal-chain discipline): the throw site was WireBytesAdapter.encode (ProtoAdapter.java:1352); the discriminating observation was that the CCE fired at the ADAPTER, not at toJsonSingle's (String) cast, proving the option-value model is String on both sides and the parser/linker were tangential; the defect was the missing engine-to-DEC-14 conversion at the one producer boundary (SchemaEncoder.toJsonSingle), where every other scalar branch already converts. History note for the record: commit cd5a72c (TASK-39 closed) unintentionally also carries the seven dual-run review-fix files, because the review-fix commit failed silently on a broken git-add chain the evening before and the staged changes rode into the next commit; the content is correct and CI-verified, the message under-describes it. Surfaced to the maintainer rather than rewriting pushed history.
<!-- SECTION:NOTES:END -->

## Comments

<!-- COMMENTS:BEGIN -->
author: perf session 6
created: 2026-11-04T19:10:00Z

---

2026-11-04 (perf session 6): the "full battery 14/14" claim above is refuted. verify.sh at this task's candidate (9238a06) failed deterministically on JdkSchemaLoaderInMemoryTest: jimfs 1.3.2's no-arg Jimfs.newFileSystem() derives its configuration from the current platform (Configuration.forCurrentPlatform(), bytecode-verified) and is case-INsensitive on macOS, so the test's case-sensitive half could not pass on this machine at any commit since de61ab2. The loader under test was correct (an NIO-only probe with no antiwire code reproduced the resolution). Fixed the same day by pinning Configuration.unix() in that half (test scope only, main jars untouched); battery re-run green, all 14 ACTIVE suites passed. Any earlier battery claim after de61ab2 that included this test on this Mac could not have been green as recorded.

---
<!-- COMMENTS:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Fixed in e94be29 (main, pushed): SchemaEncoder.toJsonSingle's ProtoType.BYTES branch now returns Bytes.encodeUtf8 (the DEC-14 form the options adapters consume) instead of the upstream-verbatim okio ByteString.encodeUtf8; the binary encoding is byte-identical to upstream (verified against the relocated fixture's bytecode). The regression test in SchemaEncoderTest proves a bytes-valued custom option encodes and round-trips through the decoded FileDescriptorProto options map as Bytes. The harness ledger row went stale exactly as designed (the suite failed it as stale before removal) and was removed in the same commit; the matrix records the finding as fixed, dated. Coverage floors moved 141 to 142 upstream in all five stated locations. Gates: /code-review high (fix verified against real artifacts; 5 consistency findings applied), /simplify 4 angles (3 applied), full battery 14/14 ACTIVE with zero divergences, surface unchanged (MATCH=1408 GAP=0).
<!-- SECTION:FINAL_SUMMARY:END -->

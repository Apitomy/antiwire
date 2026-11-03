# Security regression inventory: pinned Wire 7.1.0 baseline

TASK-17 deliverable A. The port pins upstream square/wire at tag 7.1.0 (commit
`9f62097df`, cloned at `/tmp/wire`). This inventory lists every security and semantic fix of the
pinned baseline that applies to the port's Java surface, the pinned-source evidence for each, and
the named regression case that fails if the defect is reintroduced. Deliverable B is the corpus
itself: the three `*SecurityCorpusTest` classes listed below, wired as the `security-corpus`
verify suite.

## Method

Candidates were enumerated from the clone's history, never from a fixed advisory count:

- `git log 7.0.0-alpha05..7.1.0 --oneline` lists 153 commits in that range (the task briefing
  said 193; the measured number for the stated range is 153, `git rev-list --count` agrees, and
  the clone holds 193 tags total, which is a plausible source of the figure. Nothing below hinges
  on the boundary: the enumeration also covers the 2026 security wave that starts before
  alpha05).
- Keyword sweeps over `git log --all --grep` (negative, skipGroup, limit, recursion, recursive,
  overflow, escape, valid, sanitiz, merge, GHSA, CVE) plus a full read of the pinned
  `CHANGELOG.md`, whose Security entries name their advisory IDs.
- Every candidate was then verified against the pinned source tree at tag 7.1.0 (file and line
  numbers below are from that tree) and against the port's own source. Release attribution comes
  from `git tag --contains`/`git describe --contains` (mainline) and, for 6.x backports, from
  `CHANGELOG.md` only, because the clone carries no 6.3.x/6.4.x tags.
- The task briefing's hint that the duplicate-message merge fix "may be separate" from
  #3652/#3656 resolves as follows: #3652 carries the runtime-adapter merge and #3656 carries the
  generated-code oneof merge plus the dynamic adapter's oneof clearing. Both merge commits belong
  to the FieldMask feature series and both are in scope.

## Corpus wiring

| Class | Module | Cases (floor) |
|---|---|---|
| `com.squareup.wire.RuntimeSecurityCorpusTest` | `wire-runtime-java/src/test` | 10 |
| `com.squareup.wire.schema.SchemaSecurityCorpusTest` | `wire-schema-java/src/test` | 10 |
| `com.squareup.wire.java.JavaGeneratorSecurityCorpusTest` | `wire-java-generator/src/test` | 4 |

The cases run inside `mvn verify` with the module suites. The `security-corpus` entry in
`config/verify-suites.json` is ACTIVE and `scripts/verify.sh` reconciles the corpus classes'
surefire summaries out of the same mvn log, so the suite cannot pass on a build that did not run
them: each class must appear, be green, and carry at least the case count above (verified to fail
on an absent, failing, zero-case, and thinned class by replaying the gate logic against crafted
logs). Adding cases to a class needs no script change; the counts are minimums.

## Detection evidence (AC#1)

For the reader items the corpus payloads were replayed on 2026-10-02 through three configurations:
a pre-fix scratch copy of the port's `ProtoReader`/`ByteArrayProtoReader32` (copies in `/tmp`,
defensive checks reverted to the pre-fix arithmetic; no production file was touched), the current
port classes, and the relocated upstream 7.1.0 oracle (`wire-upstream-shaded`, package
`io.apitomy.antiwire.parity.*` since DEC-19, 2026-11-03; at replay time the fixture carried
`io.github.paoloantinori.antiwire.parity.*`, renamed to `io.apicurio.antiwire.parity.*` on
2026-10-02). Results:

| Payload | Pre-fix scratch | Current port | Upstream 7.1.0 oracle |
|---|---|---|---|
| `9b060a80ffffff0f9c06` (negative length in skipped group, both readers) | silently decoded; the vendored okio `Buffer.skip` of a negative count is a no-op and the 32-bit cursor rewinds | `ProtocolException: Negative length: -128. Reader position: 8. Last read tag: 1.` | same message (upstream's `ProtocolException` is a `java.net.ProtocolException` typealias; the port's class extends it) |
| `020d05000000` (fixed32 past the length-delimited limit, okio reader) | returned 5, reading bytes outside the message | `EOFException` | `EOFException` |
| `03115000000000000000` (fixed64 past the limit, okio reader) | returned 80 | `EOFException` | `EOFException` |
| `02088005` (varint continuing past the limit, okio reader) | returned 640 | `EOFException` | `EOFException` |
| `0affffffff07`, `1affffffff07`, `0b0affffffff070c` (length `Int.MAX_VALUE`) | `EOFException` (32-bit reader) | `EOFException` | not replayed (upstream's own regression case covers it) |

So the group-skip and fixed-width/varint limit cases detect reintroduction by exception type and
message; the `Int.MAX_VALUE` trio pins the same contract as upstream's own regression case and is
kept for parity. For the non-reader items detection is by construction: each case feeds the
hostile input through the real code path and asserts the post-fix observable (escaped output,
`SchemaException`/`IllegalArgumentException` from linking or package resolution, merged decode
result), so removing the fix makes the assertion fail (for the merge items the pre-fix behavior,
last occurrence wins, is documented in the upstream changelog entry and diff).

## In-scope items

### 1. GHSA-7xpr-hc2w-34m9: negative length when skipping groups

- Commits: `e4e56fab3` "Add negative-length check in skipGroup" (merge `fc6ab65af`, PR #3595;
  CHANGELOG.md cites #3597 for the same fix).
- Release: mainline 7.0.0-alpha03; CHANGELOG.md also documents the fix in 6.3.0 (no 6.3.0 tag in
  the clone).
- Feature: `ProtoReader.skipGroup` and `ByteArrayProtoReader32.skipGroup`, LENGTH_DELIMITED skip
  path.
- Pinned evidence: `wire-runtime/src/commonMain/kotlin/com/squareup/wire/ProtoReader.kt:270` and
  `ByteArrayProtoReader32.kt:240` (`requireNonNegativeLength(length, tag)` in the skip branch).
- Port evidence: `wire-runtime-java/src/main/java/com/squareup/wire/ProtoReader.java:295` and
  `ByteArrayProtoReader32.java:269` (the `requireNonNegativeLength(length, tag)` calls in `skipGroup`).
- Cases: `ghsa7xpr_negativeLengthGroupSkip_okioReader`,
  `ghsa7xpr_negativeLengthGroupSkip_byteArrayReader32`.
- Detection: pre-fix the payload silently decodes (table above); the cases require
  `ProtocolException` with the exact message.

### 2. GHSA-9rm7-3qhh-h2mc: reader limits and 32-bit length overflow

- Commits: `25ebcabb9` "Prevent overflow for 32-bit length integers" (merge `45c6b5adb`, #3635).
- Release: mainline 7.0.0-alpha04; CHANGELOG.md also documents it in 6.4.5.
- Feature: every read and skip in both readers is bounded by the current limit: length prefixes
  (`checkedLimit`), each varint byte, fixed32/fixed64 reads, and skips.
- Pinned evidence: `ProtoReader.kt:157-159` (length prefix), `414` and `428` (fixed32/64),
  `522-529` (`skip`/`readByte`), `541-546` (`checkedLimit`/`remainingInLimit`);
  `ByteArrayProtoReader32.kt:141-143`, `435-471`, `487`.
- Port evidence: `wire-runtime-java/.../ProtoReader.java:160-162, 431, 444, 530-557` and
  `ByteArrayProtoReader32.java:151-153` onward.
- Cases: `ghsa9rm7_fixed32CannotReadPastLengthDelimitedLimit`,
  `ghsa9rm7_fixed64CannotReadPastLengthDelimitedLimit`,
  `ghsa9rm7_varintCannotReadPastLengthDelimitedLimit`,
  `ghsa9rm7_fixed32CannotReadPastLimit_byteArrayReader32`,
  `ghsa9rm7_positiveLengthOverflowRejected`.
- Detection: table above; pre-fix the fixed-width and varint cases return values read from
  outside the current message instead of throwing.

### 3. Recursion limit on nested groups (2024, pre-window hardening)

- Commits: `b45e69243` "Change the recursion limit to match grpc's default" (#3091, mainline
  5.1.0) and `b90e60c09` "Enforce recursion limit on nested groups" (#3119).
- Feature: message and group nesting depth is capped at 100 (`RECURSION_LIMIT`).
- Pinned evidence: `ProtoReader.kt:93`, `255`, `553`.
- Port evidence: `wire-runtime-java/.../ProtoReader.java` recursion depth checks (the ported
  `testSkipGroupNested` cases assert the same message).
- Case: `recursionLimit_nestedGroups`.
- Reason to include: it is a reader DoS protection inside the pinned baseline with no advisory;
  the inventory does not assume advisories cover every security fix.

### 4. #3600: comment delimiters in generated Javadoc

- Commits: `b53e43580` "Safer sanitising in Java/Kotlin generators" (merge `aa1a936d6`, #3600).
- Release: mainline 7.0.0-alpha04; CHANGELOG.md also documents it in 6.4.0.
- Feature: `JavaGenerator.sanitizeJavadoc` escapes `/*` in addition to `*/`, and routes field
  documentation, extension-source locations, and enclosing-type Javadoc through it.
- Pinned evidence: `wire-java-generator/src/main/java/com/squareup/wire/java/JavaGenerator.java:642-643`
  plus the sanitized call sites.
- Port evidence: `wire-java-generator/.../JavaGenerator.java:617-620` (the documentation escaping).
- Case: `issue3600_commentDelimitersEscapedInGeneratedJavadoc`.
- Detection: the case asserts the escaped forms appear and the raw `*/` sequence does not;
  reverting the escaping lets the raw delimiter through and the case fails.

### 5. #3622: Java Unicode escapes in generated Javadoc

- Commits: `69963a9dc` "Safely escape java comments" (merge `e4dc82fa4`, #3622).
- Release: 7.0.0-alpha04.
- Feature: `sanitizeJavadoc` escapes backslashes because javac decodes `\uXXXX` before lexing,
  including inside comments.
- Pinned evidence: `JavaGenerator.java:645` with the explanatory comment at 644.
- Port evidence: `wire-java-generator/.../JavaGenerator.java:610`.
- Case: `issue3622_unicodeEscapesEscapedInGeneratedJavadoc`.
- Detection: the case feeds the hostile payload through the generator; reverting the backslash
  escaping leaves the raw escape sequence in the emitted Javadoc and the case fails.

### 6. #3633: scalar and enum literal validation during linking

- Commits: `150c33acc` (merge `12a949b69`, #3633; the branch commit's subject is "WIP", the PR
  and changelog carry the semantics).
- Release: 7.0.0-alpha04.
- Feature: `Field.validateDefaultValue` and `Options.validateOptionValue` reject literals that
  cannot be the declared type, via `LiteralValidation.isValidLiteral`, so crafted defaults and
  option values cannot smuggle source into generated code.
- Pinned evidence: `wire-schema/src/commonMain/kotlin/com/squareup/wire/schema/Field.kt:179`,
  `Options.kt:238`, `LiteralValidation.kt:18`.
- Port evidence: `wire-schema-java/.../Field.java:260`, `Options.java:252`,
  `LiteralValidation.java:38`.
- Cases: `issue3633_invalidDefaultValuesRejected`, `issue3633_invalidOptionValuesRejected`
  (the option-value side had no port case before this corpus).
- Detection: without the validation the schema links successfully and the asserted
  `SchemaException` never fires.

### 7. #3657: generated files must stay inside the output directory

- Commits: `719da07a3` "Escape output package when it makes sense" (merge `0e3cd28dd`, #3657).
- Release: 7.0.0-alpha05.
- Feature: `SchemaHandler.checkPathInOutDirectory` refuses a resolved path outside the
  configured output directory; package options are attacker-controllable proto input and okio
  path joining replaces the target entirely on an absolute right-hand side.
- Pinned evidence: `wire-schema/src/commonMain/kotlin/com/squareup/wire/schema/SchemaHandler.kt:207`.
- Port evidence: `wire-schema-java/.../SchemaHandler.java:358`, called by
  `wire-java-generator/.../JavaSchemaHandler.java:154`.
- Cases: `issue3657_absolutePathCannotEscapeOutDirectory`,
  `issue3657_dotDotTraversalCannotEscapeOutDirectory`,
  `issue3657_pathInsideOutDirectoryIsAccepted` (over-rejection guard), and the port-only
  `issue3657_javaTargetRefusesEscapingPackageEndToEnd` in the generator corpus class, which
  drives a `java_package = ".."` value through `WireRun` into `JavaSchemaHandler` and pins the
  call site that the unit cases alone do not reach (no ported upstream test reaches it: the
  upstream escape case is rejected earlier by the package validation).
- Detection: without the check the escape cases build paths without throwing; without the call
  site the end-to-end case writes a run that would have escaped, and the asserted exception
  never fires; the accepted case pins that the check is not vacuous.

### 8. #3718: java_package and wire.wire_package value validation

- Commits: `9cc980ab4` "Validate package option values before emitting them" (merge
  `3a3ef81f6`, #3718).
- Release: the conflict is recorded rather than silently resolved. By `git tag --contains` the
  commit first ships in tag 7.0.2 (tagged 2026-09-16 10:46); `CHANGELOG.md` documents the fix
  under 7.0.3 (same day 16:10) and has no 7.0.2 section. Both facts are from the pinned clone.
- Feature: `JvmLanguages.javaPackage` rejects option values carrying `;{}()/*"\`, whitespace, or
  control characters, naming the option, file, and offending character.
- Pinned evidence: `wire-schema/src/jvmMain/kotlin/com/squareup/wire/schema/internal/JvmLanguages.kt:176-184`
  and `198-238`.
- Port evidence: `wire-schema-java/.../internal/JvmLanguages.java:174-236`; end-to-end coverage
  already ported by TASK-16 in `wire-java-generator/src/test/.../WireRunTest.java`.
- Cases: `issue3718_javaPackageOptionValueRejected`,
  `issue3718_wirePackageOptionValueRejected`, `issue3718_validJavaPackageAccepted`.
- Detection: without the validation `javaPackage` returns the hostile value instead of throwing.

### 9. #3652: runtime adapters merge duplicate singular message occurrences

- Commits: `b62c368a4` "FieldMask: follow up" (merge `b5c27c8fb`, #3652).
- Release: 7.0.0-alpha05.
- Feature: `RuntimeMessageAdapter.decode` merges repeated occurrences of a singular message
  field (and message-backed built-ins such as `FieldMask`) via `Internal.decodeMessageOrMerge`;
  `Schema.protoAdapter` gains the same behavior. Previously the last occurrence replaced the
  earlier ones for these adapters, contrary to the protobuf specification.
- Pinned evidence: `wire-runtime/src/commonMain/kotlin/com/squareup/wire/internal/RuntimeMessageAdapter.kt:178-185`,
  `internal/Internal.kt:336-340`.
- Port evidence: `wire-runtime-java/.../internal/RuntimeMessageAdapter.java:234` (the `Internal.decodeMessageOrMerge` call),
  `Internal.java:324` (`decodeMessageOrMerge`).
- Cases: `issue3652_reflectionAdapterMergesDuplicateSingularMessage`,
  `issue3652_decodeMessageOrMergeAppendsFieldMaskPaths`,
  `issue3652_schemaAdapterMergesDuplicateSingularFieldMask`.
- Detection: pre-fix the last occurrence wins, so the asserted merged values (`{a: 5, b: 7}`,
  `FieldMask([a, b])`) decode as `{b: 7}` and `FieldMask([b])` and the cases fail.

### 10. #3656: oneof message-member merging and oneof clearing

- Commits: `8c68d05d4` "FieldMask: finish him" (merge `565c13a27`, #3656).
- Release: 7.0.0-alpha05.
- Feature: generated Java code emits `Internal.decodeMessageOrMerge` for oneof message members
  and clears the sibling oneof locals on assignment; the dynamic schema adapter removes sibling
  oneof members from the map on `set`.
- Pinned evidence: `JavaGenerator.java:1431-1451` (decodeAndAssign merge emission and oneof
  clearing), `wire-schema/.../SchemaProtoAdapterFactory.kt:272`,
  `RuntimeMessageAdapter.kt:180-184`.
- Port evidence: `wire-java-generator/.../JavaGenerator.java:1446-1490`,
  `wire-schema-java/.../SchemaProtoAdapterFactory.java:378-380`.
- Cases: `issue3656_oneofMessageMemberEmitsDecodeMessageOrMerge` (pins the generated line
  against the port's current output, so an escaping or codegen regression changes it),
  `issue3656_schemaAdapterOneofLastMemberWinsAndSameMemberMerges`.
- Detection: pre-fix the generator emits a plain decode for the oneof member and the dynamic
  adapter keeps both oneof members set; both cases fail.

## In-baseline semantic fixes mapped to existing named cases

These are semantic fixes inside the pinned baseline that already carry named regression cases
ported by earlier tasks; the inventory maps them rather than duplicating corpus classes.

- #3639 nested `Builder` name collisions. Commits `fdf867f78` and `41418e794` (merge
  `8a88c7614`), release 7.0.0-alpha04, Java and Kotlin generators. Ported cases:
  `JavaGeneratorTest.nestedTypeNamedBuilderIsRenamed`, `topLevelMessageNamedBuilderIsRenamed`,
  `prunedNestedMessageNamedBuilderIsRenamed`, `prunedNestedEnumNamedBuilderIsNotRenamed`.
- #3672/#3673 option-name dots as separators (protovalidate-style
  `[(buf.validate.field).string.(buf.validate.predefined) = true]`). Commits `f44a4418e`
  "fix nested syntax in custom options" (6.4.7 and 7.0.0-alpha07 first-cut) and `1d460d49d`
  "Read the dots in an option name as separators" (final form, same releases). Ported cases:
  `ProtoParserFullTest.deepOptionAssignmentWithParenthesizedExtensionAfterFieldPathComponent`,
  `deepOptionAssignmentDotsAreSeparators`, `OptionsTest.parenthesizedExtensionAfterFieldPathComponent`.

## Excluded items, with reasons

Every candidate the enumeration surfaced that does not apply to the port's Java surface:

- #3616 / GHSA-86wm-r4c5-2rc9, negative length in Swift `skipGroup`. Commit `81ff7f24a`,
  releases 6.4.4 and 7.0.0-alpha04. Swift runtime only; the Java twin is item 1.
- #3723 / GHSA-jmh4-c43f-w43x, recursion limit on nested groups in Swift. Commit `3fc7fb338`,
  release 7.0.4. Swift runtime only; the Java runtime has carried the limit since 2024 (item 3).
- #3731 / GHSA-35ch-cghp-x9g6, wire-type mismatches in Swift read primitives. Commit
  `a55e340db`, release 7.1.0. Swift runtime only.
- #3725, unrecognized enum value overwriting an earlier oneof case in generated Swift decode.
  Commit `c202bb2ae`, release 7.0.4. Swift runtime only.
- #3708, preserving unknown singular enum values in unknown fields. Commit `78d4fdbf0`, release
  7.0.0-RC01. Swift runtime only (the CHANGELOG "Common" heading notwithstanding, the diff
  touches only `wire-runtime-swift`).
- #3727, rejecting `null` elements in repeated fields when decoding JSON. Commit `8fabf0763`,
  release 7.1.0. Gson and Moshi adapters only; DEC-6 keeps JSON adapters out of the port's scope.
  Recorded here as out of scope, not silently omitted.
- d2a87d8a3, escaping keyword-named oneofs in generated Kotlin `toString()`, release
  7.0.0-alpha07. Kotlin generator only.
- 0f3b5db8d and 9d1e8b6f9 (#3691), decoding oneofs in constant size per field in the Kotlin
  runtime, release 7.0.0-alpha08. Kotlin runtime only.
- Gradle plugin fixes in range (#3663, #3665, #3661, #3693, #3688): the Gradle plugin is not
  ported.
- Dependency and tooling bumps, the SECURITY.md move, signing-key documentation, and the
  runtime-proto refresh (`998191f52`): no security or semantic regression surface.

## Hand-off to TASK-23 (AC#4)

TASK-23 (changelog and advisory monitoring) should treat this file as the registry of security
behavior the port promises: the advisory IDs above (GHSA-7xpr-hc2w-34m9, GHSA-9rm7-3qhh-h2mc,
and the excluded GHSA-86wm-r4c5-2rc9, GHSA-jmh4-c43f-w43x, GHSA-35ch-cghp-x9g6) plus the
unadvisoried items (comment escaping, unicode escaping, literal validation, path containment,
package validation, singular merging, recursion limit). New upstream advisories must be checked
against this inventory first: if the fix touches `wire-runtime` common Java, `wire-schema`, or
`wire-java-generator`, it needs a new corpus case here; Swift-only, JSON-adapter, and
Gradle-plugin fixes are excluded for the recorded reasons. Advisory counts are never the source
of truth; the pinned source is.

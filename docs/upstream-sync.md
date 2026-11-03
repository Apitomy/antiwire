# Upstream synchronization and security response (TASK-23)

This is the port's standing procedure for tracking upstream square/wire after the pin:
how the pin is guarded, how new upstream releases and security fixes are detected, how a
change is triaged against the port's surface, who owns the response, how fast the port
promises to move, and what a full synchronization touches. It satisfies DEC-11 (the
procedure must exist and be demonstrated before the first release) and TASK-23 AC#1, and
it carries the worked rehearsal for AC#2 and AC#4 in section 9.

Companion documents, each authoritative for its own layer:

- [parity-runner.md](parity-runner.md): the pin file, provenance verification, and the
  bump mechanics this procedure invokes (its "Bump procedure" section).
- [security-regression-inventory.md](security-regression-inventory.md): the registry of
  security behavior the port promises, with pinned-source evidence and named regression
  cases. Every triage starts against this file; its method section defines the keyword
  sweep reused below.
- [decisions.md](decisions.md): DEC-1 (semantic target 7.1.0), DEC-6 (excluded
  functionality), DEC-11 (this procedure), DEC-13 (release gates that consume it).

## 1. Pinning discipline

The port's semantic target is recorded as an exact upstream identity in
`config/parity-pins.json`: the repository URL, the release tag, the annotated tag object
hash, and the commit that tag must resolve to. Both hashes are recorded because they name
different git object kinds (the tag object and the peeled commit); reviews of the plan
cited both, and a moved or retagged upstream is only detectable when both are pinned.

The pin is enforced mechanically, not by convention:

- `scripts/fetch-upstream.sh` refuses to proceed when the clone's tag resolves to any
  other object or commit, then detaches the worktree at the pinned commit, so every
  reconciliation reads the exact pinned tree ("worktree at pin"). The clone path defaults
  to `/tmp/wire` (`$ANTIWIRE_UPSTREAM` or the first argument overrides).
- The `parity-coverage` suite in `scripts/verify.sh` fails when the pins cannot be
  verified, so no green run exists against an unverified pin.
- `scripts/check-upstream.sh` (section 8) additionally checks the pinned tag against the
  live remote: if `refs/tags/7.1.0` on GitHub stops resolving to the pinned tag object
  and commit, the watch reports `state=PIN_MOVED` and every recorded parity measurement
  that cites the pin is suspect until re-verified.

The pin changes only through the bump procedure in parity-runner.md, which requires all
ACTIVE suites green on the new pin. Nothing else may edit `upstream` in the pins file.

## 2. Supported upstream range

The port supports exactly one upstream version: the pinned tag. Today that is 7.1.0
(DEC-1; no 6.4.0-equivalence mode exists, and the 6.4.0-versus-7.1.0 delta is captured
upstream-versus-upstream, never absorbed by the port). Consumers needing a different
Wire version are outside the compatibility contract until a bump moves the whole
contract to that version.

Forward from the port's first release (0.1.0), this procedure applies to every upstream
release: patches, minors, pre-releases, and backport lines. A new upstream release is
always maintenance work under this procedure, never a prerequisite for a port release
(TASK-23 AC#4); the first actual post-7.1.0 release will additionally serve as the dry
run DEC-11 asks for.

## 3. Monitoring channels

Detection deliberately does not assume advisories cover everything. The security
inventory includes fixes no advisory covers (the 2024 recursion-limit hardening predates
the advisory window, and several 2026 generator and linker fixes were never advisories at
all), so an advisory count is never the source of truth. Four channels, two automated and
two manual:

1. **Automated tag watch** (section 8): `scripts/check-upstream.sh` compares the newest
   tag on `https://github.com/square/wire` with the pin. It runs as the informational
   `upstream-watch` suite inside every `./scripts/verify.sh`, and can be run standalone
   (exit 0 only when current) or in `--security` posture. It detects BEHIND states, any
   tag kind including pre-releases, and pin integrity anomalies.
2. **Changelog read on every new tag**: upstream's `CHANGELOG.md` names advisory IDs and
   security entries per release. On each new tag, read the new sections:
   `git -C /tmp/wire diff <old-pin>..<new-tag> -- CHANGELOG.md`.
3. **Advisory page review, weekly, owned**: https://github.com/square/wire/security/advisories
   is reviewed every week by the maintainer (Paolo Antinoro). GitHub's advisories API and
   page are not fetchable without authentication, so this channel is a named manual step,
   not part of the script; the standing record lives as the upstream-sync duty in this
   repo's task ledger (backlog). A missed week is caught up in the next session and the
   miss is noted in the ledger, never silently skipped. The same weekly pass runs
   `./scripts/check-upstream.sh --security`: its nonzero exit is the BEHIND and
   needs-review signal consumed by a human on a fixed cadence (the informational suite in
   channel 1 never fails a build, so this strict run is the scheduled consumer of the
   signal). A scheduled CI job running the strict watch is the mechanical alternative; it
   is deliberately left as a maintainer decision (it sends failure mail to every watcher),
   not installed by this task.
4. **Non-advisory sweep on every new tag**: the keyword sweep over the new log range, the
   same sweep the inventory's method section used:
   `git -C <clone> log <old-pin>..<new-tag> --grep='negative\|skipGroup\|limit\|recursion\|recursive\|overflow\|escape\|valid\|sanitiz\|merge\|GHSA\|CVE' -i`
   plus a full read of the changelog diff. This is the channel that finds fixes no
   advisory covers; the watch's `--security` mode prints both commands.

Channel 1 fires the moment upstream publishes anything; channels 2 through 4 run on every
new tag and weekly regardless, so a fix that ships without an advisory, without a
changelog Security heading, or inside a patch release is still enumerated before the bump
decision.

## 4. Applicability triage

Every detected change is triaged against the security inventory first, then against this
table. The inventory's exclusion rows are precedents: cite them when the same surface
recurs.

| Upstream surface | Port module | Verdict | Duty |
|---|---|---|---|
| `wire-runtime` common JVM (readers, writers, adapters, internal) | `wire-runtime-java` | applies | port the fix; add or extend a `RuntimeSecurityCorpusTest` case; add an inventory row |
| `wire-schema` (parser, linker, options, handlers, `JvmLanguages`) | `wire-schema-java` | applies | port the fix; `SchemaSecurityCorpusTest` case; inventory row |
| `wire-java-generator` | `wire-java-generator` | applies | port the fix; `JavaGeneratorSecurityCorpusTest` case; inventory row |
| `wire-runtime-swift`, `wire-swift-generator` | none (DEC-6) | excluded | record an exclusion row; run the twin check below |
| Kotlin runtime specifics, `wire-kotlin-generator` | none (DEC-6) | excluded | record an exclusion row |
| JSON adapters (`gson-support`, `moshi-adapter`) | none (DEC-6) | excluded | record an exclusion row (precedent: #3727 null-in-repeated) |
| Gradle plugin | not ported | excluded | record an exclusion row |
| `wire-grpc`, `wire-reflector` | none (DEC-6) | excluded | record an exclusion row; JVM reflection itself stays in scope |
| `wire-tests` fixtures and tooling | fixtures only | no production surface | check whether regenerated fixtures change parity corpora before the bump |
| build, CI, docs, dependency bumps | none | no port surface | note in the bump record |

Twin check: a Swift-only or Kotlin-only fix is excluded only after confirming no JVM twin
exists in the same series. Precedents: GHSA-86wm-r4c5-2rc9 (Swift skipGroup) is the twin
of the in-scope GHSA-7xpr-hc2w-34m9; GHSA-jmh4-c43f-w43x (Swift recursion limit) has its
Java twin already in the baseline. If a twin exists, the JVM row applies regardless of
which language the advisory names.

Semantic (non-security) fixes take the same route with the corpus step replaced by the
relevant module suite: the case-map reconciliation of parity-coverage will refuse the
bump until every new upstream case is ported or dispositioned.

## 5. Maintenance owners and escalation

- **Owner**: the maintainer (Paolo Antinoro), with this repo's task ledger
  (`backlog/tasks`) as the work queue. Every detected change that survives triage becomes
  a ledger task naming the surface, the upstream commit, and the duty from section 4.
- **Escalation when the owner is unavailable**: the routing record is filed in the
  antiwire repo's issue tracker (https://github.com/Apitomy/antiwire/issues) with the
  `security` label (the label is created by the maintainer on first use; this procedure
  names it so the first filer does not have to invent one). The record carries the watch
  evidence, the triage verdict, and the section 4 duty.
- **Port-specific vulnerabilities** (found in the port, not inherited from upstream) go
  to the maintainer directly first, never to the public tracker, until a fix is ready;
  upstream-inherited findings are already public upstream and need no embargo.

All escalation paths in this document were exercised as local records only (section 9,
step 3); no real notification, issue, or message was created (TASK-23 AC#4, DEC-11).

## 6. Update-lag and response targets

PROPOSAL, pending maintainer ratification; until the maintainer records acceptance these
are the targets this task proposes, not commitments. They derive from measured upstream
behavior at the pinned era, not from aspiration: the 2026 security wave shows fix commit
to release intervals of one to two days (e4e56fab3 committed 2026-05-12; CHANGELOG.md
documents the 6.3.0 backport dated 2026-05-13 and mainline 7.0.0-alpha03 dated
2026-05-14); the 7.0.x patch series ran
roughly weekly (7.0.0 and 7.0.1 on 2026-09-10, 7.0.2 and 7.0.3 on 2026-09-16, 7.0.4 on
2026-09-25); minor intervals in 2026 ranged from 3 days (6.1.0 to 6.2.0) to 18 days
(7.0.0 to 7.1.0).

| Class | Target (from detection or public availability) |
|---|---|
| Security fix applicable to the port's surface | triage within 7 days; fix landed, or an explicit recorded risk acceptance, within 14 days |
| Security fix excluded by DEC-6 | exclusion row recorded in the inventory within 7 days |
| Patch release (7.1.x) | applicability assessed within 7 days of the watch signal |
| Minor or feature release | bump decision (bump now, or defer with a recorded reason and date) within 6 weeks |

The 14-day security target is roughly one upstream patch cycle plus slack for the parity
reconciliation; the 7-day triage aligns with the weekly manual advisory review; the
6-week decision bound spans several observed upstream minors, so the port never drifts
more than a bounded number of releases without an explicit, dated deferral.

## 7. Mechanics of a synchronization

The bump itself is the procedure in [parity-runner.md](parity-runner.md); it is quoted
here as the duty list, with the three duties this task adds on top:

1. Update `upstream` in `config/parity-pins.json` (tag, annotated tag object, resolved
   commit from a fresh clone; both hashes per section 1).
2. `./scripts/fetch-upstream.sh` with a fresh clone path; the pin check must pass.
3. Run `python3 scripts/check-parity-coverage.py`; update `config/upstream-case-map.json`
   and the case ledgers (`docs/task9-case-accounting.md`, `docs/task13-case-accounting.md`,
   `docs/task16-case-accounting.md`) so every added, renamed, or removed upstream case is
   ported, recorded missing with a reason, deferred with an owner, or excluded with a
   reason. **Case-map and ledger update duty**: nothing else may absorb the delta; the
   parity-coverage suite fails the run on unaccounted drift in either direction.
4. Port or disposition the new cases, then `./scripts/verify.sh`; all ACTIVE suites must
   pass.
5. **Security corpus extension duty** (this task): every security-relevant fix in the new
   range gets an inventory row (evidence: pinned source line, port line, release) and a
   named corpus case in the matching `*SecurityCorpusTest` class. The corpus floors in
   `scripts/verify.sh` are minimums, so adding cases needs no script change; thinning
   below the registry fails the suite. Excluded fixes get an exclusion row citing DEC-6
   or the section 4 verdict.
6. Record the synchronization in the ledger task: date, old and new pins, the triage
   table for the range, and the verdicts.

## 8. The automated watch

`scripts/check-upstream.sh` (TASK-23 AC#3) is the checked-in watch. It runs
`git ls-remote --tags` against the repository named in the pins file (no clone, no auth),
parses release tags, orders them numerically with pre-release markers (alpha < beta < rc
< unknown marker) sorting below the final release of the same core, and reports:

- `state=CURRENT`: no tag newer than the pin;
- `state=BEHIND`: one or more tags newer than the pin (pre-releases included: upstream
  has shipped security fixes in alphas and backport lines);
- `state=PIN_MOVED`: the pinned tag is absent from the remote or no longer resolves to
  the pinned tag object and commit (history rewrite; provenance invalidated);
- not-run: `git ls-remote` failed or the pins file is malformed (exit 3).

Modes and exits: default strict mode exits 0 only on a clean CURRENT (1 behind, or a
CURRENT whose snapshot needs review; 2 pin moved; 3 could not run); `--security` adds the
triage framing, the sweep commands, and the advisory checklist pointer; `--informational`
is the verify.sh integration. Strict modes treat unparsed tag names or unparsable
ls-remote lines as nonzero even on CURRENT, because either could hide a newer release.
The ls-remote call carries git stall limits (`http.lowSpeedLimit`/`http.lowSpeedTime`),
so a blackholed connection fails in bounded time instead of hanging the watch and
verify.sh with it. Historical `parent-*` build tags are excluded by name (11 today); any
other unparsed tag name is reported for manual review rather than dropped silently, and a
successful ls-remote that lists zero tags is treated as a broken fetch (could-not-run),
never as an upstream that deleted every release.

**Suite wiring.** The `upstream-watch` entry in `config/verify-suites.json` (owner
TASK-23, ACTIVE) runs the script in `--informational` mode inside every
`./scripts/verify.sh`, artifact-independent and unconditionally. Its gate is "the watch
ran and reported", never "upstream did not release": CURRENT, BEHIND, and PIN_MOVED all
record `PASS` with the state in the note, so an upstream release can never fail the
build. Only a watch that could not run records `NOT_RUN`, which the entry point reports
as MISSING and which fails the run like any other suite that never executed; an
informational PASS therefore always means a live comparison actually happened. The
deliberate tradeoff: reachability of github.com is now a precondition for a green run.
A watch that could not check anything must not print a vacuous PASS, so the failed fetch
fails the run; hosted CI always has egress to github.com, and an offline local run
failing on upstream-watch as MISSING is the honest outcome, not a false alarm.

**Real output today, 2026-10-02** (from `./scripts/verify.sh`, this working tree):

```
--- suite: upstream-watch (scripts/check-upstream.sh --informational) ---
upstream watch: https://github.com/square/wire (pin from .../config/parity-pins.json)
  pin: 7.1.0 (tag object da24c33ee1fe772a7a04617018087f46f26d1708, commit 9f62097dfe4995b5709d001ca0187e30ca0530ef)
  remote tags parsed: 203 (11 non-release parent-* tags ignored)
  newest upstream tag: 7.1.0
  tags newer than the pin: none
  pin integrity: ok
  state: CURRENT
RESULT upstream-watch.status=PASS
RESULT upstream-watch.note=state=CURRENT: pin 7.1.0 is the newest upstream tag (203 tags parsed); procedure docs/upstream-sync.md; this PASS records that the watch ran, not that the port is current
```

**Tested failure modes** (drilled 2026-10-02 against the live remote and synthetic local
bare remotes, using `ANTIWIRE_PINS` to point the watch at crafted pin files and crafted
repository URLs; the override exists for exactly these drills):

| Drill | Observed |
|---|---|
| pin left at real 7.0.4 identity | `state=BEHIND`, newer: 7.1.0, strict exit 1; informational `PASS` with `state=BEHIND`, exit 0 |
| pin at real 7.0.0-RC01 identity | newer: 7.0.0, 7.0.1, 7.0.2, 7.0.3, 7.0.4, 7.1.0 and none of the alphas: pre-releases sort below the final they precede, so a hypothetical 7.1.0-rc1 is never newer than the pinned final 7.1.0 |
| tag 7.1.0 with a wrong tag-object hash | `state=PIN_MOVED`, strict exit 2; informational `PASS` with `state=PIN_MOVED` |
| pinned tag absent from the remote (9.9.9) | `state=PIN_MOVED` (absent), exit 2 |
| remote carrying a tag the parser does not know (`v7.2.0` on a synthetic local remote) | state stays CURRENT but the name is reported as a WARNING, strict and `--security` exit 1 (an unparsed name could hide a newer release), informational `PASS` with the warning in the note |
| ls-remote succeeds but lists zero tags (local bare remote) | treated as a broken fetch, not a vanished upstream: could-not-run, exit 3; informational `NOT_RUN` |
| truncated JSON in the pins file | could-not-run, exit 3; informational `NOT_RUN` (recorded MISSING by verify.sh) |
| pins file without the `upstream` object | could-not-run, exit 3 |
| repository URL that does not resolve | `git ls-remote failed: Could not resolve host`, exit 3; informational `NOT_RUN` |
| pinned tag not a release tag (`banana`) | could-not-run, exit 3 |
| unknown flags, or both modes at once | usage error, exit 1 |

**Limitations, by design.** Advisories cannot be fetched without auth, so the script
watches tags only and the weekly advisory review is the owned manual channel (section 3).
Tag-name parsing covers the observed naming schemes (214 tags seen: numerics, alpha/rc
markers, the historical `wire-` prefix, snapshot markers like
`5.4.1-alpha.20250925.224059...`); a genuinely new scheme surfaces as an unparsed-name
warning, a `--security` nonzero exit, and the changelog read, never as a silent CURRENT.
Commits landing upstream without a tag are invisible to the watch until a tag carries
them; that window is bounded by upstream's release cadence and covered by the same
changelog and sweep channels.

## 9. Worked rehearsal: GHSA-7xpr-hc2w-34m9 replayed as a today-arrival

Scenario: on 2026-10-02 the watch reports a new upstream state containing commit
`e4e56fab3` "Add negative-length check in skipGroup" (merge `fc6ab65af`, PR #3595;
CHANGELOG.md cites #3597 for the same fix) with advisory GHSA-7xpr-hc2w-34m9 published.
In reality the fix predates the pin (first shipped in 7.0.0-alpha03 and backported in
6.3.0), so the port already carries it; that is what makes it a rehearsal: every step
below is executed against the real objects, and the expected landing state is "already
present, verify and pin the regression coverage". The exercise is recorded here locally;
no real notification was sent (AC#4).

### Step 1: detection

The watch channel fires on the tag; the sweep then enumerates the range. Replayed
verbatim against the clone at `/tmp/wire`:

```
$ git -C /tmp/wire log --all --grep='negative-length' --format='%h %ad %s' --date=short
0597be859 2026-06-17 Merge pull request #3616 from square/bquenaudon.2026-06-02.skiplength
81ff7f24a 2026-06-02 Add negative-length check in skipGroup for the Swift runtime
fc6ab65af 2026-05-13 Merge pull request #3595 from square/bquenaudon.2026-05-12.fix
e4e56fab3 2026-05-12 Add negative-length check in skipGroup
```

Two fix commits appear: the JVM fix (`e4e56fab3`) and its Swift twin (`81ff7f24a`,
GHSA-86wm-r4c5-2rc9, an inventory exclusion row). The sweep catching both, and triage
separating them, is the normal shape of this advisory family.

Advisory cross-check: https://github.com/square/wire/security/advisories/GHSA-7xpr-hc2w-34m9
names the negative-length skipGroup defect; upstream `CHANGELOG.md` carries the same fix
under both 7.0.0-alpha03 and 6.3.0 citing #3597 and the advisory ID. The inventory
already records the attribution nuance (PR #3595 versus changelog #3597), which is why
release attribution always comes from the clone, not from the changelog alone:
`git -C /tmp/wire describe --contains e4e56fab3` answers `7.0.0-alpha03~5^2`, so the
fix first ships in tag 7.0.0-alpha03.

For range context, the same sweep on the real 7.0.4..7.1.0 range demonstrates what a
genuine today-release looks like: it surfaces #3727 (JSON null-in-repeated, DEC-6
exclusion) and #3731 (Swift wire-type mismatch, DEC-6 exclusion), both correctly landing
in exclusion rows rather than port work.

### Step 2: applicability triage

Affected surface: `ProtoReader.skipGroup` and `ByteArrayProtoReader32.skipGroup`, the
LENGTH_DELIMITED skip path. Table verdict: `wire-runtime` common JVM, so
`wire-runtime-java` applies; the corpus duty targets `RuntimeSecurityCorpusTest`.

The port HAS the fix. Both readers guard the skip path:

- `wire-runtime-java/src/main/java/com/squareup/wire/ProtoReader.java` (call site at line 295):
  `requireNonNegativeLength(length, tag);` inside `STATE_LENGTH_DELIMITED` within
  `skipGroup`, with the throwing body in `requireNonNegativeLength(int, int)` (line 569 onward)
  (`if (length < 0) throw new ProtocolException("Negative length: " + length + ...)`).
- `wire-runtime-java/src/main/java/com/squareup/wire/ByteArrayProtoReader32.java:269`:
  the same call in the same branch (line 269), body in `requireNonNegativeLength(int, int)` (line 553 onward).

Upstream pinned evidence for comparison: `ProtoReader.kt:270` and
`ByteArrayProtoReader32.kt:240` at tag 7.1.0 (inventory item 1). The port's helper is a
named method where upstream inlines the check; that is a recorded mechanical adaptation,
not a semantic difference.

Named regression cases already exist:
`RuntimeSecurityCorpusTest.ghsa7xpr_negativeLengthGroupSkip_okioReader` and
`.ghsa7xpr_negativeLengthGroupSkip_byteArrayReader32` (inventory item 1), one per reader.

### Step 3: owner routing and simulated escalation

Routing record (local, simulated; no notification sent):

```
detection-id: 2026-10-02-GHSA-7xpr-hc2w-34m9
detected-by:  upstream-watch tag signal (channel 1) + keyword sweep (channel 4)
advisory:     GHSA-7xpr-hc2w-34m9 (JVM); twin GHSA-86wm-r4c5-2rc9 (Swift, excluded)
upstream:     e4e56fab3 (merge fc6ab65af, PR #3595; changelog #3597)
surface:      wire-runtime common JVM -> wire-runtime-java (triage table row 1)
verdict:      applies; already present in the port at the pinned baseline
owner:        maintainer (Paolo Antinoro); work item in this repo's task ledger
escalation:   if the owner is unavailable: file in the antiwire issue tracker with the
              `security` label, carrying this record (section 5); simulated only here
duty:         verify presence (done, step 2); corpus cases exist (step 5); no code change
status:       closed-with-evidence 2026-10-02
```

### Step 4: source-diff extraction

```
$ git -C /tmp/wire show e4e56fab3 --stat
 wire-runtime/src/commonMain/kotlin/com/squareup/wire/ByteArrayProtoReader32.kt | 1 +
 wire-runtime/src/commonMain/kotlin/com/squareup/wire/ProtoReader.kt            | 1 +
 wire-runtime/src/commonTest/kotlin/com/squareup/wire/ProtoReader32Test.kt      | 10 ++++++++++
 wire-runtime/src/commonTest/kotlin/com/squareup/wire/ProtoReaderTest.kt        | 10 ++++++++++
 4 files changed, 22 insertions(+)
```

The protective code, one line per production file:

```kotlin
val length = internalReadVarint32()
if (length < 0) throw ProtocolException("Negative length: $length. Reader position: $pos. Last read tag: $tag.")
skip(length)
```

The same guard in `ProtoReader.kt` (with `pos += length.toLong(); source.skip(...)`
following). Each production line ships with a matching upstream regression test,
`testSkipGroupRejectsNegativeLength`, asserting the exact message on the payload
`9b060a80ffffff0f9c06`.

### Step 5: implementation and test adaptation

If the port lacked the fix, the adaptation would be exactly two production insertions
(one per reader, in the `STATE_LENGTH_DELIMITED` branch of `skipGroup`) plus two corpus
cases mirroring upstream's tests: feed `9b060a80ffffff0f9c06` (group 99, field 1 with
length -128, end group) through each reader and require `ProtocolException`. The port's
actual state is the target state of that adaptation: the checks are present (step 2), and
the corpus carries the cases, in
`wire-runtime-java/src/test/java/com/squareup/wire/RuntimeSecurityCorpusTest.java:47-62`:

```java
ProtocolException e = assertThrows(ProtocolException.class,
    () -> TestMessages.Person.ADAPTER.decode(new Buffer().write(data)));
assertEquals("Negative length: -128. Reader position: 8. Last read tag: 1.", e.getMessage());
```

and the byte-array twin asserting the same message through
`TestMessages.Person.ADAPTER.decode(data)`. Reintroducing the defect (dropping either
guard) makes both cases fail on the exact message, so the corpus detects reintroduction;
the inventory's detection-evidence section additionally records the pre-fix behavior
(silent decode: the vendored okio `Buffer.skip` of a negative count is a no-op and the
32-bit cursor rewinds) and the relocated upstream 7.1.0 oracle throwing the identical
message. Upstream's own regression cases are ported in the runtime suites as well
(`wire-runtime-java/src/test/java/com/squareup/wire/ProtoReaderTest.java:83` and
`ProtoReader32Test.java:82`, both `testSkipGroupRejectsNegativeLength`), so
the coverage is doubled: corpus (security registry) and module suite (parity map).

### Step 6: full parity rerun on isolated classpaths

`JAVA_HOME=~/.sdkman/candidates/java/17.0.12-tem ./scripts/verify.sh`, this working tree,
2026-10-02:

```
VERDICT: all 12 ACTIVE suites passed.
```

including `runtime-tests` (869 cases), `schema-tests` (630), `protoc-oracle` (122),
`compiler-tests` (175), `security-corpus` (the three corpus classes green), and the new
`upstream-watch` (state=CURRENT, section 8). Isolation of the upstream oracles is
structural, not incidental: the never-published `wire-upstream-shaded` module holds
upstream `wire-runtime-jvm` 7.1.0 relocated to `io.apitomy.antiwire.parity.*` (DEC-19,
2026-11-03; the fixture carried its pre-DEC-8 name `io.github.paoloantinori.antiwire.parity.*`
when this rehearsal ran, renamed to `io.apicurio.antiwire.parity.*` later that day,
2026-10-02), so `runtime-tests` runs the port and the
upstream implementation side by side on one classpath (the `*ParityTest` classes) without
any duplicate `com.squareup.wire` class; `protoc-oracle` compares the port against
protobuf-java 4.36.1 reference models and fixtures generated by the pinned upstream
wire-compiler 7.1.0 toolchain from Maven Central; and `duplicate-class-check` proves on
every module test classpath that no retained class name resolves from two artifacts, so
an oracle can never silently stand in for the port's own code. The rehearsal's verdict
rests on those isolated classpaths: the corpus cases passed against the port's readers,
not against any upstream class.

## 10. Exercise record (AC#4)

This demonstration ran entirely locally on 2026-10-02: detection greps against the
pinned clone, watch drills against the live remote with crafted pin files, the routing
record above, and the full verify rerun. No issue was filed, no message sent, no real
incident notification exists. The first actual post-7.1.0 upstream release runs this same
procedure as future maintenance (a dry run per DEC-11), and TASK-21 consumes this
document as the sync-procedure input to the release gates (DEC-13).

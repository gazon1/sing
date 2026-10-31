# Audit and Development Plan — Singularity Todo KMP

**Date:** 2026-10-31
**Scope:** the whole repository at `32d860dd` (the current `origin/main`).
**Method:** read the code, the CI configuration and the whole issue tracker
(1,138 issues, `gazon1/sing`), and checked every finding against the tracker so
nothing here repeats an issue that is already open.

---

## What this repo does:

Singularity Todo is a personal task manager written once in Kotlin Multiplatform
and shipped as an Android app and a desktop app. It keeps its data in a local
Room database, syncs it to Supabase in the background through a Hybrid Logical
Clock, and lets an AI assistant act on that data through a tool layer that turns
every destructive action into a proposal the user has to confirm.

**Load assumed:** one person, one account, a handful of devices, a local SQLite
file that stays in the tens of megabytes. That is the load the code is built
for, and it is the load every finding below is judged against. The project does
not have to serve thousands of users — but it does have to keep the one user's
data intact.

**Scale of the thing:** ~191,000 lines of Kotlin across 2,304 files
(`shared/src/commonMain` 152,775; `desktopApp` 17,047; `shared/src/jvmMain`
12,273; `shared/src/androidMain` 5,433; `androidApp` 3,411), plus 402 Python and
shell scripts under `scripts/`, 908 decision records, 1,138 GitHub issues, and
2,886 commits since the first commit on 2026-09-02.

**Issues filed from this audit:** #1717, #1718, #1719, #1720 (must fix);
#1721–#1728 (should fix); #1729, #1730 (nice to have). Finding 13 was folded
into #1519 as a comment rather than filed as a near-duplicate. #1401, #1462 and
#1519 carry answers as comments.

**On the code itself:** this is a carefully built repository. The sync engine
carries comments explaining why each race is safe; the AI tools make every
destructive action a proposal; the gate registry proves each gate can still
fail; the room database was checked against its own exported schema. Reading it,
the defects are not in the design — they are in the machinery that is supposed
to catch defects, and that machinery is currently off.

---

# Audit findings

## Must fix

### 1. `main` does not compile — and has not since 2026-10-31  `#1717`

**What this is:** commit `32d860dd` (PR #1698) added a `ShellOverride` composable
that publishes a title and a navigation icon to the Android shell bar.

**Problem:** it writes three properties that the holder never got. `ScreenActionsHolder`
at that commit has only `actions`, `selectionTitle` and `onExitSelection` — so
`holder.title`, `holder.navigationIcon` and `holder.override` do not resolve.
Three more errors come from the same commit: `SettingsScreen.kt:176` passes an
`overrideShell` argument to a function that does not accept one, and
`TagsScreen.kt:27,47` imports `ImmutableList` from two packages. The CI log for
run `41185623927` carries all eight:

```
e: .../core/ui/components/ShellOverride.kt:23:16 Unresolved reference 'title'.
e: .../core/ui/components/ShellOverride.kt:24:16 Unresolved reference 'navigationIcon'.
e: .../core/ui/components/ShellOverride.kt:26:16 Unresolved reference 'override'.
e: .../feature/settings/SettingsScreen.kt:176:17 No parameter with name 'overrideShell' found.
e: .../feature/tags/TagsScreen.kt:27:38 Conflicting import: imported name 'ImmutableList' is ambiguous.
e: .../feature/tags/TagsScreen.kt:47:38 Conflicting import: imported name 'ImmutableList' is ambiguous.
```

Four separate jobs are red on `main` because of it: `CI`, `CodeQL`,
`E2E (Maestro)` and `Android device tests`. The previous commit `645e0908` is
red for the same reason.

**Fix:** the fix already exists — it is sitting uncommitted in the working tree.
`ScreenActionsHolder` gains `title`, `navigationIcon` and `override`;
`TopAppBar` gains the `overrideShell` parameter; `ShellOverride` drops the
`actions` parameter it was inventing; `SettingsScreen` keeps passing
`overrideShell = true`; `TagsScreen` drops the duplicate import. Commit those
five files and push.

**If we skip it:** nothing else can be verified. Every test job, every device
run and every E2E flow on `main` is reporting a compile error, so no signal
about the code reaches anyone.

**Filed as #1717.** I searched the titles and bodies of all 1,138 issues:
no open issue mentions the compile break, `ShellOverride`'s unresolved
references, or `overrideShell`. The nearest neighbours are #1695 (revert the
incidental `TagsScreen` import — one of the eight errors, already fixed by this
same change) and #1692 (decide the fate of `ShellOverride` — a design question,
not the break).

---

### 2. `main` has no merge gate at all, and the plan that would have added it was never carried out  `#1718`

**What this is:** a GitHub branch-protection rule requiring the `CI gate` check.

**Problem:** it does not exist. The API says so:

```
$ gh api repos/gazon1/sing/branches/main/protection
{"message":"Branch not protected","status":"404"}
$ gh api "repos/gazon1/sing/rulesets?includes_parents=true"
[]
```

Every branch reports `"protection":{"enabled":false,"enforcement_level":"off"}`.
The repository is public and the account has admin rights, so this is not a
plan limitation.

The reason is written down. `docs/decisions/2026-10-07-branch-protection-is-unavailable.md`
records that branch protection was a paid feature the repository could not use
while it was private, and that the owner would **make the repository public** to
unlock it. The repository is public now. The ADR also says what happens next:

> The first thing to do then is set the required check to `CI gate` and delete
> this entry, because a documented unenforceability is only honest while it is true.

Neither half happened. The entry is still there with `status: accepted`, and the
required check was never set. The command has been sitting ready-written in two
places since then — `.github/workflows/ci.yml:6` and `docs/architecture/ci.md:16`:

```
gh api repos/{owner}/{repo}/branches/main/protection -X PUT \
  -F required_status_checks='{"contexts":["CI gate"]}'
```

The cost of that not happening is measurable. Since `c3769812` on 2026-10-27,
`main` has taken **113 consecutive red CI runs** — 65 failures and 48
cancellations, zero green. Every one of those merges passed through a gate that
was not enforcing anything. PR #1698 merged at 08:41 on 2026-10-31 with
`CI gate`, `JVM tests`, `Android (free)`, `Android (pro)`, `Agent review`,
`Affected tests` and `autofix` all failing.

**Fix:** run the command above, then delete the ADR entry as it instructs.
Nothing else in the repository changes, and every merge from that moment on has
to carry a green `CI gate`.

**If we skip it:** the project keeps producing red merges at ~67 commits a day
and keeps calling them gated. The gate registry, the baseline ratchet and the
trace ratchet all report against a tree that was never checked.

**Filed as #1718.** Issue #1401 asks the right question — *"how is a red
merge possible when `CI gate` is required?"* — but its premise is wrong: there
was no required check. Issue #1462 asks whether to fold the device workflows
into it or make them a second required check; that decision is moot until one
exists. Neither issue says *"enable branch protection now that the repository is
public"*.

---

### 3. `AGENTS.md` and two open issues state the opposite of what is true  `#1719`

**What this is:** the instruction file every agent reads before touching the
repository.

**Problem:** `AGENTS.md:156` says:

> Branch protection требует **один** check — `CI gate`.

There is no branch protection. `.github/workflows/ci.yml:3` and
`docs/architecture/ci.md:13` both say so correctly, in the same repository, on
the same day. The three documents disagree, and the one that is loaded
automatically into every session is the wrong one.

The same false premise is baked into two open issues: #1401 is titled *"a red
merge reached main with a required CI gate"*, and #1462 opens with *"Branch
protection requires exactly one check, `CI gate`."* An agent asked to fix #1401
will spend its time looking for a `continue-on-error` in `ci.yml` — the
`ci-gate` job's logic is correct (`ci.yml:1434-1453` fails on any leaf that is
not `success` or `skipped`) — and will not find the actual answer, which is that
there is no rule.

**Fix:** one line in `AGENTS.md`, changed to say that branch protection is off
and that a merge carries no enforcement until the rule exists. Then close
#1401 and #1462 with that fact as the answer to their questions.

**If we skip it:** every future session inherits a false model of how this
repository is protected, and reasons from it. This is the belief that let 113
red merges look like normal.

---

### 4. The nightly full-suite run can be cancelled by the next push, and a cancelled night tells nobody  `#1720`

**What this is:** the scheduled CI run at 01:00 UTC, the one run that exercises
the whole suite rather than the changed part.

**Problem:** `ci.yml:28-30` gives the schedule and a push to `main` the *same*
concurrency group:

```yaml
concurrency:
  group: ci-${{ github.event.pull_request.number || github.ref }}
  cancel-in-progress: true
```

On `main` both evaluate to `ci-refs/heads/main`. With `cancel-in-progress: true`
and ~67 commits a day, a push lands while the nightly is still running and
cancels it. Then the job that would have reported the failure cannot see it:

```yaml
# ci.yml:1609
if: ${{ failure() && github.event_name == 'schedule' }}
```

A cancelled run's conclusion is `cancelled`, not `failure`, so `failure()` is
false and **no issue is opened**. The correlation over the 23 scheduled CI
nightlies is exact:

| Date | Conclusion | Issue opened? |
|---|---|---|
| 2026-10-31 | failure | yes |
| 2026-10-30 | **cancelled** | **no — silent** |
| 2026-10-29 | failure | yes |
| 2026-10-28 | failure | yes |
| 2026-10-27 | **cancelled** | **no — silent** |
| 2026-10-26 | failure | yes |
| 2026-10-25 | failure | yes |
| 2026-10-24 | success | — |

5 of the 23 scheduled nightlies were cancelled, and not one of them produced an
issue. Since 2026-10-24 there have been **eight consecutive scheduled nightlies
without a single green one**.

**Fix:** two lines. Give the schedule its own group
(`group: ci-${{ github.event_name == 'schedule' && 'nightly' || ... }}`), or set
`cancel-in-progress: ${{ github.event_name == 'pull_request' }}` — the same
shape the two emulator workflows already use. And change the alert condition to
`if: ${{ (failure() || cancelled()) && github.event_name == 'schedule' }}` so a
cancelled night still opens an issue.

**If we skip it:** the one run that would catch a red `main` before it reaches
users is the run most likely to be killed by the next push, and when it is
killed the silence is indistinguishable from a night that passed.

**Filed as #1720.** #1462 discusses a concurrency collision between the
*device* workflows on `main` — and that one was fixed: `e2e.yml:50` and
`android-device-tests.yml:70` both use
`cancel-in-progress: ${{ github.event_name == 'pull_request' }}`. The same bug
class was hardened in two workflows and left in the third. #618 mentions the
nightly contending on Gradle load, which is a different resource. Nothing
tracks the nightly being cancelled by a push, or the cancelled run being silent.

---

## Should fix

### 5. A failed export leaves a half-written archive behind, and the next scheduled push uploads it as the newest backup  `#1721`

**What this is:** `BackupExporter.export` writes the zip straight to
`options.destPath`.

**Problem:** it creates the destination file before the content is complete and
never removes it when the write fails. If an attachment named by `att.localPath`
is missing from disk, or the disk fills up mid-write, `codec.export(...).getOrThrow()`
(`BackupExporter.kt:176-177`) throws — and the file stays there. `scanBackups()`
(`BackupRepository.kt:212-230`) lists every file ending in `.zip`, sorted newest
first, with no check that the archive is complete. `pushScheduled()` then takes
`scanBackups().firstOrNull()` (`BackupRepository.kt:419`) — which is exactly that
broken file — and seals it into the cloud bucket.

**Fix:** write to `"$destPath.tmp"` and move it into place only after the codec
returns success, deleting the temp file on failure. `FileSystem` already has the
atomic `move` that `BackupCursorStore.save` uses.

**If we skip it:** the newest cloud backup is an incomplete snapshot, and the
person believes the backup worked.

---

### 6. A restored attachment is never checked against the checksum the archive already carries  `#1722`

**What this is:** `BackupImporter.applyAttachments` restores an attachment when
its bytes are present in the zip.

**Problem:** present is treated as good. `BackupImporter.kt:537-538` checks only
`bytes != null`; it never compares the length against `AttachmentDto.fileSizeBytes`
and never compares the hash against `AttachmentDto.checksum`, even though both
are carried in the payload (`BackupAttachmentDtos.kt:21-23`). A truncated or
empty entry is written to disk, the row's `localPath` is pointed at it, and
`restoredCount++` reports it as restored.

**Fix:** treat a zero-length entry as missing, and compare the bytes' hash to
`att.checksum` before counting the attachment as restored.

**If we skip it:** a restore silently replaces a user's attachment with an empty
file and reports success — the one failure mode a backup exists to prevent.

---

### 7. The Android backup decoder has no decompression budget; the JVM one does  `#1723`

**What this is:** `AndroidBackupCodec` and `JvmBackupCodec` are near-copies of
the same decoder.

**Problem:** the JVM side refuses more than 100,000 entries or 64 MB total
(`JvmBackupCodec.kt:135-139`, `:196-199`) and reads each entry through
`readEntryBounded` (`:142-159`). The Android side does neither: it calls
`zis.readBytes()` on every entry with no limit (`AndroidBackupCodec.kt:83-84`).
A small archive whose entries expand enormously makes the Android import
allocate until the process is killed — no exception, no message.

**Fix:** copy the two constants and `readEntryBounded` across, or delete
`AndroidBackupCodec` and share one decoder. The two files have already drifted
once, which is how this happened.

**If we skip it:** one bad archive crashes the app on the platform that has no
guard against it.

---

### 8. The backup schema chain silently skips two versions  `#1724`

**What this is:** `BackupMigrations.migrations` registers transforms for
versions 1, 3, 4 and 6.

**Problem:** the fold is `migrations[v]?.invoke(acc) ?: acc`
(`BackupMigrations.kt:105-121`), so a missing step is a no-op, not an error.
Versions 2 and 5 have no transform and the chain walks straight over them.
Today's result is correct only because every field added in v3 and v6 happens
to have a DTO default.

**Fix:** make the fold fail on a missing step
(`migrations[v] ?: error("no migration from schema v$v")`), and add the v2→v3
and v5→v6 transforms explicitly so the chain is total for v1..v6.

**If we skip it:** the first schema change that forgets a transform restores old
backups with the wrong data, and the symptom is "my data is wrong" rather than
"this backup is too old".

---

### 9. The JVM keyring read trims the secret and reports an empty value as absent  `#1725`

**What this is:** `secretToolRead` post-processes what the keyring returns.

**Problem:** `kr.getPassword(...)?.trim()?.takeIf { it.isNotEmpty() }`
(`JvmSecureStorage.kt:78-85`). A secret stored with surrounding whitespace comes
back without it. A secret stored as `""` comes back as `null`, and
`SecureStoragePort.read` promises that `null` means "the key is absent"
(`SecureStoragePort.kt:16-19`). The AES-GCM fallback file returns the stored
bytes verbatim, so the same key yields two different values depending on which
backend answered.

**Fix:** drop the `.trim()` and the `.takeIf { it.isNotEmpty() }`; return the
keyring's value as it is and let `null` mean only "absent".

**If we skip it:** the credential store breaks the contract its own port
documents, and a secret that happens to contain whitespace is silently
corrupted.

---

### 10. The keyring and its fallback file can disagree, and the older value in the file wins  `#1726`

**What this is:** `JvmSecureStorage` keeps two stores — the OS keyring and
`~/.config/singularity/secure.bin`.

**Problem:** `write` goes to the keyring when the keyring accepts it and only
falls back to the file when it does not (`:48-50`); it never updates the file
in that case. `read` tries the keyring first and falls through to the file when
the keyring returns nothing (`:44-46`). So: the keyring is unavailable at first
launch, the credential goes to `secure.bin`; the keyring comes back, the new
value is written to the keyring only, and the file still holds the old one; the
keyring fails again (locked GNOME Keyring, no D-Bus session) and `read` returns
the **old** credential from the file.

**Fix:** when the keyring write succeeds, delete that key from the fallback
file (`fallbackDelete` already exists at `:288-337`), or write to both and read
the newer.

**If we skip it:** after one keyring outage the app silently resumes using a
revoked or outdated credential — an old API key, an old refresh token.

---

### 11. The `CI gate` check does not wait for the ADR gate, so it would ignore a red one  `#1727`

**What this is:** the `ci-gate` job, the one Stage 1 makes required.

**Problem:** its `needs` list is
`[static, meta, tests, android, changes, agent-review, quick, detekt]`
(`ci.yml:1437`). The `adr-gate` job in the same workflow is not on it — and
`adr-gate` is the only place that runs `singularity-adr validate`
(`ci.yml:1510-1511`), which checks the ADR corpus itself rather than ADR
*references*. No gate in the registry covers this either: there is no entry for
the `ci-gate` needs list, so the gap is invisible to the tooling. `adr-gate` is
currently failing on `main` (its "Install singularity-adr" step, #548), and
`CI gate` does not carry that failure.

**Fix:** add `adr-gate` to `ci-gate`'s `needs` once #548 makes the job able to
run, and add a registry gate that asserts every job in `ci.yml` is either in
`ci-gate`'s `needs` or explicitly declared advisory.

**If we skip it:** the moment branch protection requires `CI gate`, a red ADR
gate becomes invisible — and the ADR corpus is the project's memory.

---

### 12. The backup package carries four dead declarations, and one of them lies  `#1728`

**What this is:** symbols that survived the package's refactoring.

**Problem:** `value class BackupCursor(val epochMillis: Long)` (`BackupCursor.kt:26`)
is declared and never used — the state carries a plain `Long`.
`ImportOptions.overwriteExisting` (`BackupOptions.kt:27,31,56`) is set by the DSL
and asserted by a test, but read by nothing, so `overwriteExisting = false` still
overwrites. `BackupRows.LINK_KINDS` (`BackupRowIndex.kt:33-37`) is never
consulted. `BackupManifest.isCompatibleWith` (`BackupManifest.kt:16-18`) has no
caller.

**Fix:** delete the four declarations and the test assertions that only cover
the dead flag.

**If we skip it:** `overwriteExisting` in particular is a public option that
promises something it does not do — a caller that sets it to `false` gets an
overwrite with no warning.

---

### 13. The archive container's two platform copies have already drifted once, in the security-relevant direction  — folded into `#1519`

**What this is:** `JvmArchiveCrypto` (128 lines) and `AndroidArchiveCrypto`
(133 lines) are byte-for-byte copies of the same implementation.

**Problem:** they are still identical today, but nothing enforces that at
compile time. The only check is a golden test in a separate source set per
platform, so a platform whose host test is not run loses the check. The same
copy-paste pattern is what produced finding 7.

**Fix:** move the shared body into `commonMain` over a `SecretKeyFactory`/
`Cipher` seam, or add one parameterized golden test over both
implementations — issue #1519 already asks for exactly that.

**If we skip it:** the next parameter change lands in one copy, and an archive
written on the desktop stops opening on the phone with "the password does not
open it".

---

## Nice to have

### 14. The only test of the backup migration chain asserts nothing about any migration  `#1729`

**What this is:** `BackupMigrationsTest` has two tests, and both assert
`result.containsKey("tasks")`.

**Problem:** `migrateEmptyChainReturnsIdentity` says *"No migrations registered,
so v1→v2 returns identity"* (`BackupMigrationsTest.kt:29`) — but v1→v2 **is**
registered and rewrites every task and note (`BackupMigrations.kt:23-58`); the
assertion simply cannot tell the difference. A regression that made `v4ToV5`
stop writing `verb` (`:80-90`) would restore every dependency edge as `BLOCKS`
and this test would still pass.

**Fix:** assert the migrated `schemaVersion`, the fields each transform adds,
and that `migrate` from each of v1..v6 reaches `CURRENT`.

**If we skip it:** the one part of the backup layer with no test at all is the
part that decides what an old backup restores as.

---

### 15. A contract test named for the missing-manifest case never exercises it  `#1730`

**What this is:** `BackupCodecContractTest` has a test called
`import fails with MalformedManifest when manifest entry is missing`.

**Problem:** the body exports a *valid* manifest and then asserts
`result.isSuccess` (`BackupCodecContractTest.kt:78-91`), so the
`BackupPayloadError.MalformedManifest("missing manifest.json")` path
(`JvmBackupCodec.kt:111-117`, `AndroidBackupCodec.kt:98-102`) is never taken.
A codec that stopped reporting a missing manifest would keep this green.

**Fix:** build a zip without the manifest entry and assert `MalformedManifest`,
or rename the test to say the case is deliberately not covered.

**If we skip it:** the file's most safety-relevant import failure mode has a
green test that proves nothing.

---

# Development plan

The backlog is not the problem. The problem is that the machinery meant to
catch defects is switched off, so defects leave the factory faster than anyone
can close them. The plan below is ordered by what unblocks the most.

## Measured today

| Number | Value |
|---|---|
| Issues created, last 7 days | **423** |
| Issues closed, last 7 days | **109** |
| Net backlog growth, last 7 days | **+314** |
| Open issues | **549** |
| Commits, last 7 days | **626** (~89/day) |
| Commits since 2026-10-01 | **2,063** |
| Consecutive red CI runs on `main` | **113** (since 2026-10-27) |
| Last green CI run on `main` | `c3769812`, 2026-10-27 |
| Scheduled nightlies cancelled / total | **5 / 23**, each silent |
| Consecutive scheduled nightlies without a green | **8** (since 2026-10-24) |

The tracker was reset on 2026-10-03 (the oldest surviving issue is #25). Since
then it has produced 1,138 issues and closed 589 of them. In the last week it
opened four times more issues than it closed. That ratio is the actual
bottleneck, and no amount of new issues improves it.

## Stage 0 — Make `main` build again *(today, ~1 hour)* — #1717

1. Verify the five changed files in the working tree compile; commit them as
   one commit that fixes #1661, #1662 and #1663.
2. Push, and wait for `CI gate` on `main` to go green — `gh run watch`, one
   call, not a polling loop.
3. Close #1695 — the `TagsScreen` import is fixed by the same commit.
4. Do not start anything else until this is green. Every other number in this
   document is measured against a red tree, and so is every judgement in it.

## Stage 1 — Put the merge gate back *(this week)* — #1718, #1719, #1720, #1727

1. Enable branch protection with the command already written in `ci.yml:6`:
   `gh api repos/gazon1/sing/branches/main/protection -X PUT -F required_status_checks='{"contexts":["CI gate"]}'`
2. Delete `docs/decisions/2026-10-07-branch-protection-is-unavailable.md` — the
   ADR says to delete it at the moment the rule is configured, and that moment
   is now.
3. Fix `AGENTS.md:156` to say the gate is a convention until the rule exists,
   and then (after step 1) that it is enforced.
4. Answer #1401 and #1462 with the facts rather than leaving them to be
   re-investigated. #1401's answer: there was no required check, so the red
   merge was possible; here is the rule now. #1462's answer: the device
   workflows are a second required check, or they are not gating — pick one,
   and write it down.
5. Give the nightly its own concurrency group and let a cancelled night open an
   issue (finding 4). Two lines in `ci.yml`.
6. Add `adr-gate` to `ci-gate`'s `needs` once #548 makes that job able to run,
   and add a registry gate that asserts every job in `ci.yml` is either in
   `ci-gate`'s `needs` or explicitly declared advisory.

## Stage 2 — Stop the churn *(this week, alongside Stage 1)*

The tracker runs at about 60 issues a day created and 15 a day closed. Three
rules change that ratio, and none of them require writing code:

1. **One issue, one symptom.** Every new issue must state a thing a user would
   notice. The existing backlog has 33 `test:` issues and 14 `chore:` issues
   that are internal refactors wearing an issue's clothes; they are work
   tickets, not defects, and they belong in a plan, not in the tracker.
2. **No new issues while the backlog grows.** For two weeks, close at least as
   many as you open. This is a flow rule, not a quality rule.
3. **Measure throughput, not creation.** The number that matters is issues
   closed per week (currently 109), not issues created (currently 423).

## Stage 3 — Triage the 549 *(one week)*

The backlog is young and mostly untriaged: 323 issues are under a week old, 226
are between one and four weeks old, 114 already carry the `backlog` label and 71
carry `ready-for-agent`. Most of it was written recently by the same hands that
wrote the code.

Triage in this order:

1. **Close outright** — anything that duplicates another issue, anything that
   describes work that has already shipped, and anything that cannot be stated
   as a symptom. Target: half the backlog.
2. **Convert** — the surviving `test:` and `chore:` issues become entries in a
   single ordered plan per area, linked from the issues that block them.
3. **Order** — what remains should fit on one page, ordered by what a user
   notices first. The existing UI issues (#1656–#1669) are a good example of
   one coherent slice: font scale, calendar overflow, screen-reader headings,
   colour contrast.

## Stage 4 — Product work *(after Stage 3)*

Only then does feature work restart, and it starts from the triaged list. The
product gaps the tracker already names, and that survive triage, are worth
looking at first:

- **Two-pane list/detail layout for wide desktop windows** (#1524) — the
  desktop app is a single-pane phone app on a wide screen.
- **Project reminders are persisted and never fired** (#676) —
  `PROJECT_REMINDERS_SUPPORTED` is `false`, so a feature is visible in the data
  model and dead in the product.
- **The attachments sheet cannot be opened** (#818) — adding a URL attachment
  is impossible from the UI.
- **Internationalisation** (#1634–#1637) — roughly 400 hardcoded strings remain
  in `commonMain`, and the app already ships Russian and English date formats
  that disagree about the same date (#1670).

## What this plan deliberately does not do

- It does not create more issues. The tracker is not short of work.
- It does not rewrite the gate registry, the sync engine or the DI graph. The
  sync engine, the AI tool layer and the room schema survived this review;
  they were read closely and they are in good shape.
- It does not try to close #1401 or #1462 by "fixing CI". They are questions
  with answers, and the answers are in Stage 1.

---

## Already tracked (not repeated here)

These defects exist and are already open in the tracker, so they are not
findings above:

- #1677, #1676, #1679, #1678, #1680 — the java-keyring migration, its fallback
  file, `isHardwareBacked()` semantics, and the unused `IS_LINUX` constant.
- #1478 — removals not travelling as changes in the backup chain.
- #1606, #1492, #1607, #1608 — the backup exporter's DI construction and the
  app-resolved destination delta write.
- #1203, #1209, #1406, #1428 — the backup export's memory profile, the
  attachment bytes travelling by hash, fail-closed verification, and the
  destination path semantics.
- #1519 — folding the golden crypto test into one parameterized test.
- #548 — the `singularity-adr` wrapper living outside the repository, which is
  why the `adr-gate` job fails on CI.
- #1401 and #1462 — answered in Stage 1 rather than repeated.
- #757, #819, #1481–#1484, #1487, #1488, #1463, #849, #851 — the Maestro and
  CI cost work.
- `openspec/changes/ci-gate-coverage` — the script-level gate-coverage gap.
  It does not cover the job-level gap in finding 11.

## Not checked

- **No build and no test run.** Every finding here comes from reading source
  and from CI logs. The compile break in finding 1 is proved by the CI log's
  compiler output, not by a local Gradle run; the working-tree fix was verified
  by reading the diff, not by compiling it.
- **`core/sync`** was read in full for the push/pull/outbox/shadow paths and
  the shadow, dead-letter and retry policies, but the Koog agent runtime, the
  Supabase Edge Functions and the RLS policies were not reviewed.
- **`supabase/`** — the migrations, the bucket policies and the RLS rules were
  not reviewed; `docs/architecture/local-stack.md` describes how to run them
  locally.
- **`scripts/`** — 402 files; the gate registry's reachability, the test-impact
  map and the Maestro tooling are covered by existing issues (#637, #1482,
  #1483, #1484) and were not re-derived.
- **`desktopApp/` and `androidApp/`** entry points were mapped but not audited
  line by line.
- **The `mcp-server` write surface** was mapped but not audited in full.

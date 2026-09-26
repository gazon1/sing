---
title: Epic 3 retro findings + sprint close-out — quality phase retrospective
date: 2026-09-26
status: accepted
tags: [retro, tech-debt, epic3, detekt, serialization, sync]
epic: refactor/techdebt-epic2-v2
---

# Epic 3 Retro Findings + Sprint Close-out

## Landmarks

| PR | Commit | Outcome |
|---|---|---|
| Rebase onto main | `83040173` | main moved 48 commits (KDoc enforcement, Konsist gates, MVI wave); 4 conflicts resolved; all sprint work preserved |
| PR 3.1 | `17d8dfd2` | **Slow suite 63 failures → 0.** Last class (FileLogWriterTest) had a broken assertion masked by `@Tag("slow")` since introduction (path string checked against filename pattern) |
| PR 3.2 | `13c25d69` | UserId migration: Tag/TagGroup/SavedAgendaView/SavedSearch `userId: String` → `UserId`; `""` sentinel → `UserId.anonymous`; wire format unchanged (value class serializes as string) |
| — | `d6930884` | **Production bug**: `Task` was never `@Serializable` — `toJson()` (outgoing sync path) threw SerializationException at runtime. Found by the migration probe; fixed + regression test locks the wire format |
| PR 3.3 | `9e99cea6` | **Detekt now enforces** (`ignoreFailures = false`): auto-correct sweep 356 → 23 shared / 0 desktop; baseline 918 entries (accepted conventions); custom rules promoted at 0 violations |
| PR 3.4a | `6fe709a2` | Empty action companions → `internal` (footgun closed; full slot API deliberately not applied to IntentActions-style wrappers) |
| PR 3.5 | — | Parallel waves already removed PomodoroRepository, monthNumber, Pomodoro skips, unused fakes. Remaining: `expect object Clock` → PlatformClock (72 files) — **deferred**, trigger: next touch of core/platform |

## Findings

| # | Finding | Severity | Action |
|---|---|---|---|
| R1 | **`Task.toJson()` threw at runtime** — syncable entity without `@Serializable`; `serializer<T>()` reflection fails silently-until-synced. The type migration probe surfaced it. | **Critical (found+fixed)** | `@Serializable` added; regression test asserts wire format. Lesson: a `SyncableEntity`-implementation test asserting `toJson()` succeeds should exist per entity — candidate for a Konsist-style gate. |
| R2 | **`@Tag("slow")` hid a broken assertion for weeks.** FileLogWriterTest's pattern check was always-false on absolute paths — the tag suppressed it instead of surfacing it. | Medium (lesson) | Fixed. Reinforces: tags mark cost, not correctness debt. |
| R3 | **Value-class serializer surprise.** `@JvmInline value class` used in a `@Serializable` class requires the class itself to be `@Serializable` (plugin-generated) — annotating `UserId` with `@Serializable` made the migration compile clean. | Low (doc) | Encoded in `singularity-todo-stable-json` skill. |
| R4 | `scopedUserId` is a `StateFlow<UserId>` while `current` is `UserId` — several repos mixed `.value` access during migration. | Low | Fixed mechanically; no API change (property names intentionally distinct). |
| R5 | detekt `--auto-correct` needs 2 passes to converge when both Indentation and NoSemicolons fire (first pass trades one for the other). | Low (doc) | Encoded in quality-tools skill. |
| R6 | Gradle daemon classloader caching for detekt rules (Epic 2 retro R1) bit again during PR 3.3 verification — `--stop` + rerun is mandatory after any detekt-rules change. | Medium (process) | Already encoded; followed. |

## Deferred (with triggers)

- `expect object Clock` → `PlatformClock` rename (72 files, warning-only value):
  trigger — next change touching `core/platform/`.
- `rememberDialogState()` migration (17 sites): trigger — any screen rework;
  mechanical, single-pattern.
- assertCanWrite rollout to remaining ~20 repositories: trigger — next
  repository PR (pattern established, additive).
- DIGEST.md slim policy (1804 lines > 1500 limit): trigger — next docs-audit.

## Sprint totals (tech-debt roadmap V2.1 → executed)

- 12 slow-tagged test classes untagged, 63 → 0 failures in slow suite.
- 4 production bugs fixed: SyncViewModel debounce, NoteEditor baseline/discard,
  DraftMviViewModel post-autosave hook, Task @Serializable sync crash.
- 2 real VM scope leaks fixed (BackupViewModel, SettingsViewModel).
- detekt: silent → enforcing (356 findings swept, custom rules promoted).
- Type safety: 4 more domain models on `UserId`; dead sync merge code removed.
- God-VMs: duplication removed instead of indirection added
  (Settings 347→182; ProjectDetail single-observer, pure combine).

## Links

- `2026-09-26-epic2-roadmap`, `2026-09-26-preflight-quick-wins`,
  `2026-09-26-preflight-retro-findings`, `2026-09-26-epic2-retro-findings`,
  `2026-09-26-pr24-rescope`
- Branch `refactor/techdebt-epic2-v2` (rebased onto main `a6065cc7`)

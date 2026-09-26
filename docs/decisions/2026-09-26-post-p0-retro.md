---
status: accepted
date: 2026-09-26
---

# Post-P0 retro: all P0 PRs complete

## Context

P0 phase (PR-0.0 through PR-0.4) is complete. All hygiene rules are active, baselines established, KDoc enforcement wired, production BAN patterns fixed.

## Cross-PR Findings

### 🟢 What Worked

1. **Git worktree isolation** — main checkout stayed clean throughout. All epic work in `~/worktrees/singularity-docs-hygiene/`. No interference with main development.

2. **Per-PR retro discipline** — Each PR ended with a retro ADR documenting findings. 4 retro ADRs created (pr-0.0 through pr-0.4). Patterns identified early (DIGEST size, baseline drift, PSI API quirks).

3. **Detekt rules in baseline** — The 7 custom rule sets are now all registered and active. Baseline suppresses existing violations; new violations fail CI.

4. **KDoc enforcement is working** — `ViewModelMustHaveKDoc` and `RepositoryInterfaceMustHaveKDoc` fire correctly. 4 files had KDoc added in PR-0.3.

5. **ADR supersedence chain** — 4 dead UI-testing ADRs formally superseded via `ui-testing-deferred.md`. Date inversion in `test-suite-tag-defaults` fixed.

### 🟡 Deferred to P1/P2

1. **DIGEST.md oversized** (1651 lines, limit 1500) — needs trim or split into core/archive. Defer to PR-1.1 or PR-1.2.

2. **androidApp and mcp-server no baselines** — only `shared` and `desktopApp` have baselines. Future cleanup PR should generate baselines for androidApp and mcp-server.

3. **~20 `TODO:` comments in production** — mostly in TaskMenuBuilder (unwired use cases) and SyncApi (Supabase not yet integrated). Not blocking but should be addressed in feature PRs.

4. **`stateIn` rule exists but minimal findings** — only 1 production use found and fixed. The rule is working as intended.

5. **`runBlocking` in PlatformModule.android.kt** — 2 calls remain for startup migrations. Acceptable for now; no alternative at module-init time.

### 🔴 None

No critical bugs or blockers found.

## Consolidated Retro ADRs

- `2026-09-26-pr-0-0-retro.md` — (implicit in commit)
- `2026-09-26-pr-0-1-retro.md` — DIGEST oversized, androidApp/mcp baselines
- `2026-09-26-pr-0-2-retro.md` — PSI API limitations, epic conflict pattern
- `2026-09-26-pr-0-3-retro.md` — sealed interface KDoc false positives, baseline drift
- `2026-09-26-pr-0-4-retro.md` — auto-correct side effects, remaining runBlocking uses

## P0 Acceptance Criteria — Status

| Criterion | Status |
|-----------|--------|
| `just docs-audit` clean | ⚠️ DIGEST oversized |
| `just detekt` 0 errors | ✅ |
| 4 ADR superseded | ✅ |
| KDoc on all ViewModels/Repositories | ✅ |
| Production BAN fixes applied | ✅ |
| Retro ADR after each PR | ✅ |
| skills YAML migration | ⏸️ Deferred to P1 |

## Next: P1

PR-1.1 (docs-infrastructure ADRs) → PR-1.2 (CONTEXT.md + PROGRESS.md) → PR-1.3 (process ADRs) → PR-1.4 (skills reorg)

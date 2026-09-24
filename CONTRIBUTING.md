# Contributing to Singularity Todo

Welcome! This document covers the practical development workflow. For architectural context, see `ARCHITECTURE.md` and `AGENTS.md`.

---

## Dev environment

**Requirements:** JDK 21+, Android SDK (API 34), Git.

```bash
git clone <repo>
cd singularity_cllone_kmp
./gradlew :shared:jvmTest          # smoke test — should pass
```

**Main branches:**
- `main` — stable, production-ready
- Feature branches from `main`, merged via PR

---

## Workflow

### 1. Before writing code

Read the relevant ADRs for your area:
- New feature? → check `docs/decisions/DIGEST.md` for similar decisions
- Architectural change? → write an ADR before or alongside the code

### 2. During development

```bash
# Fast feedback loop
./gradlew :shared:jvmTest           # unit tests (no emulator)
just lint                           # detekt + ktlint (auto-fix where possible)
just detekt-fix                     # auto-fix detekt violations in-place

# Full check before pushing
./check.sh
```

### 3. Before committing

- KDoc: All `expect/actual`, `*Repository` interfaces, and `*ViewModel` classes must have KDoc (enforced by `KDocOnContractRule`)
- No `TODO` comments without an ADR note
- No `runBlocking` in ViewModel init
- No `PassThroughUseCase` — VMs call repositories directly for basic CRUD

### 4. Commit message format

```
<type>(<scope>): <short description>

[Optional body — explain WHY for non-trivial changes]
```

Types: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `ci`
Scope: `tasks`, `notes`, `sync`, `di`, `detekt`, etc.

---

## Architecture rules

| Rule | Where enforced |
|---|---|
| `@JvmInline value class` for all IDs | `PassThroughUseCase` rule |
| No pass-through use cases | `PassThroughUseCase` detekt rule |
| Koin pure DSL (no annotations) | `koin-annotations` banned in `shared/` |
| Suspend + `Result<T>` in repositories | Convention check in code review |
| `createBackgroundScope()` injected, not hardcoded | Code review |

See `docs/decisions/DIGEST.md` for all architectural decisions.

---

## Testing strategy

| Layer | How | Where |
|---|---|---|
| ViewModel logic | `runTest { }` + `FakeRepositories` | `commonTest` |
| Repository + Room | `jvmTest` (real SQLite) | `jvmTest` |
| Android UI | Robolectric | `androidHostTest` |

**Never use MockK or Mockito** — use `Fake*` classes from `shared/src/commonMain/test/fakes/FakeRepositories.kt`.

---

## Documentation

- ADRs: `docs/decisions/*.md` — use `docs/templates/adr-template.md`
- Skills: `AGENTS.md` §"Ключевые скиллы" — update when adding new patterns
- Module READMEs: update when entry points or structure change
- Run `just docs-audit` to check doc freshness

---

## Dependency management

- All shared deps pinned in `gradle/libs.versions.toml`
- Platform-specific deps in `shared/build.gradle.kts`
- No `resolutionStrategy` override without ADR

---

## Code style

- ktlint enforced: `just detekt-fix` auto-formats
- detekt thresholds: baseline in `detekt-baseline.xml` (update with `just detekt-baseline`)
- Max line length: 120 (ktlint default)
- 2 spaces indent (Kotlin convention)

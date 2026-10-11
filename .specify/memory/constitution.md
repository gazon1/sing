# Constitution: Singularity Todo

**Version:** 1.0.0
**Ratified:** 2026-10-11
**Last Amended:** 2026-10-11

---

## Core Principles

### I. KMP Architecture

All architectural decisions must consider **Android + JVM Desktop only** (no iOS).

- Compose Multiplatform for UI
- Koin 4.x for DI
- Room for persistence
- Nav3 for navigation
- Kotlin Flow for async

### II. ADR Before Code

Every architectural decision requiring more than **1 week of effort** requires an ADR.

- ADRs live in `docs/decisions/`
- Validated via `singularity-adr validate`
- Immutable once accepted — never edit, only supersede

### III. Validate Early and Often

Run `adr validate` after every ADR change:

```bash
singularity-adr validate
```

Non-zero exit = broken contract. Fix before proceeding.

### IV. Immutable Historical Record

Accepted ADRs are **never edited**. Changes create new ADRs with `supersedes` links.

To revisit a decision:
1. Create a new ADR with Status: `accepted, supersedes NNNN`
2. Do NOT touch the prior ADR file

### V. CI Gates

All ADR changes must pass before merge:

```bash
singularity-adr validate
openspec validate --changes
```

### VI. MVI ViewModels

All ViewModels follow canonical pattern:

```kotlin
class MyViewModel(
    private val deps: MyDeps,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {
    init { addCloseable(scope) }
    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()
    init { scope.launch { repo.observe().collect { _state.value = it } } }
}
```

**Never use:** `stateIn`, `viewModelScope`, `runBlocking`

### VII. Test Fakes

Use fakes from `test/fakes/` instead of mocks.

- Virtual time via `advanceUntilIdle()`
- No `delay(N)` in tests — use virtual time

### VIII. OpenSpec Workflow

Every non-trivial change follows: **proposal → specs → design → adr → tasks → implement → converge**

- Architecture changes always require both ADR and spec
- Bug fixes, test-only, dep updates, doc-only — skip OpenSpec
- `skip_specs: true` in `.openspec.yaml` when no behavior changes

---

## Decision Criteria

### An ADR IS required when:

- Architectural pattern change (DI, navigation, data layer)
- New cross-cutting concern
- External dependency upgrade with behavioral change
- Deprecation of existing capability

### An ADR is NOT required for:

- Bug fixes with no behavior change
- Test-only changes
- Dependency version updates
- Detekt/ktlint auto-fixes
- Doc-only updates
- Purely additive, low-risk changes following existing patterns

---

## Governance

- Constitution supersedes all other practices
- Amendments require documented approval
- All PRs must verify ADR compliance via `singularity-adr validate`

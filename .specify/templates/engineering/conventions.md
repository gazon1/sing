# Engineering SDD — conventions

Every `speckit-engineering-*` step runs as its own `mcode exec` subprocess with no memory of the
previous step. This file is the one place the rules they share are written down. **Read it before
doing anything else.**

## Reporting rules

1. **Read the code, do not grep it.** Grep misses wiring, initialization order and the DI graph.
   Open the files the request names.
2. **Cite `path:line` for every factual claim.** A claim without a citation is a hypothesis.
3. **Label everything that is not a fact.** Use these exact headings in `investigation.md`:
   `## Verified facts` and `## Hypotheses` and `## Open questions`. Never blur them.
4. **Say "not reproduced" plainly.** If you could not reproduce the symptom, write that. An
   unreproduced symptom is a fact worth recording, not a gap to paper over.
5. **State what is out of scope** in every document.

## What "verified" means here

A fact is verified when you read the code that implements it, or ran the command you cite and
observed its output. It is **not** verified because a document, a comment, a KDoc or another
agent said so — those are claims, and a KDoc that lies is a bug worth reporting.

## Context this project requires

Singularity Todo — Kotlin Multiplatform, **Android + JVM Desktop only, no iOS**. Compose
Multiplatform for UI, Koin 4.x for DI, Room for persistence, Nav3 for navigation, Kotlin Flow for
async.

Before writing any engineering artifact, follow the mandatory pre-read chain in
`openspec/config.yaml`:

1. Read the real code.
2. `openspec list --specs`
3. `openspec/specs/MODULE-INDEX.md` — lists covered AND uncovered modules.
4. `docs/CONTEXT.md`
5. `docs/decisions/DIGEST.md`
6. Relevant ADRs in `docs/decisions/`
7. Relevant skills in `.agents/skills/`

The DI conventions in `docs/CONTEXT.md` bind: `single { }` for singletons and ports,
`viewModelOf(::Vm)` for ViewModels without runtime parameters, `factory { }` **never** for a
ViewModel, and no `*domainModule().toTypedArray()` in `modules()`.

## Determinism

Prefer the project's existing helpers over ad-hoc synchronization:

- virtual time via `advanceUntilIdle()`, never `delay(N)` to make a test pass;
- `MutableClock.kt` and `AwaitState.kt` from `shared/src/commonTest/.../test/helpers/`;
- fakes from `shared/src/commonTest/.../test/fakes/FakeRepositories.kt`, never MockK for state;
- an injected `AutoCloseableCoroutineScope`, never `viewModelScope`.

If a step needs an invocation you cannot make deterministic, say so in `verification.md` under
`## Known limitations` rather than shipping a flaky test.

## Change directory

All artifacts live in `openspec/changes/<slug>/`, where `<slug>` is a kebab-case identifier. The
pipeline never writes to `specs/NNN-*/` — that is the stock `/speckit.*` feature tree, and using it
would split one change across two directories.

| File | Owner | Concern |
|---|---|---|
| `.openspec.yaml` | `explore`, revised by `specify` | whether a capability spec is required |
| `investigation.md` | `explore` | facts, hypotheses, reproduction |
| `spec.md` | `specify` | required behavior, invariants, AC-NN |
| `model.md` | `model` | states and transitions, or a justified *Not applicable* |
| `verification.md` | `verify-design` | how correctness will be proven |
| `design.md`, `tasks.md` | `plan` | the solution and the work |
| `results.md` | `verify`, appended by `converge` | what actually ran |
| application code | `implement` | the change itself |

## Statuses

`results.md` records every check as exactly one of:

- `PASS` — actually executed, and it passed.
- `FAIL` — actually executed, and it found a problem.
- `NOT_RUN` — not executed.
- `BLOCKED` — cannot be executed; state the cause.
- `NOT_APPLICABLE` — not applicable; state the justification.

A check that was never run is never described as passing. This is the single rule this pipeline
exists to make hard to violate.
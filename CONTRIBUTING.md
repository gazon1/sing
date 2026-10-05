# Contributing

Thanks for looking at this. The project is pre-1.0 and small, so there is room
to shape it — but the conventions below are load-bearing, and a pull request that
ignores them will fail CI rather than be discussed.

Participation is governed by [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md). For a
conduct concern, use the private security advisory rather than a public issue —
see that file for why.

## The short version

```bash
git clone https://github.com/gazon1/sing.git
cd sing
./gradlew :shared:jvmTest        # the fast loop
./check.sh                       # the full gate — run this before opening a PR
```

`./check.sh` is the thing CI runs. It takes about twelve minutes. If you only run
the fast loop, CI will tell you the same thing, later.

## Before you write code

Read [`AGENTS.md`](AGENTS.md). It is the project's operating manual and it is
short on purpose. The parts that trip up newcomers:

- **Fakes, not mocks.** Every test double lives in
  `shared/src/commonTest/.../test/fakes/FakeRepositories.kt`. MockK exists for
  asserting outgoing calls and nothing else.
- **Every test class carries a `@Tag`.** `fast` unless the test crosses a process
  boundary — a real database, a real file, a Compose harness, a spawn. `slow` does
  not mean "takes a while". CI runs `-Ptest.tags=fast,slow`, and an untagged class
  is silently excluded.
- **No `delay()` in tests.** Use `awaitState` / `advanceUntilIdle` and virtual
  time.
- **Android + JVM only.** There is no iOS target, and adding one is a decision,
  not a module.

## Architecture you need to know

The layering is one-directional: `presentation → domain → data`. A feature lives
in `shared/src/commonMain/kotlin/com/singularity/todo/feature/<name>/`, and a new
one follows the shape in `.agents/skills/singularity-todo-feature-scaffold/`.

Two rules cause the most review comments:

1. **ViewModels take an injected `AutoCloseableCoroutineScope`.** Never
   `viewModelScope`, never `runBlocking` in `init`. A ViewModel that reaches for
   either cannot be tested.
2. **Pass-through use cases are not allowed.** A `UseCase` that forwards to a
   repository one-for-one is enforced against by a detekt rule, because it is a
   layer that looks like architecture and carries none.

DI is Koin 4.x pure DSL. Prefer `viewModelOf(::Vm)`. `factory` is never correct
for a ViewModel — it leaks. Bindings live in the per-domain `*DiModule.kt`, not in
`core/di/Modules.kt`, which is a façade that aggregates them.

## Gates

Every script in `scripts/` has a **positive control** that plants the defect it
exists to catch, and `check-gate-wiring.py` runs those controls on every commit.
A gate that cannot fail is treated as worse than no gate.

If you add a gate, it needs a control, and if you add a rule to a gate, that rule
needs its own control. The project's standing rule is that **a noisy gate is
worse than no gate** — if yours produces a finding you cannot justify, narrow the
pattern rather than suppressing the finding.

Two examples worth reading before you write one:
[`check-room-schema-integrity.py`](scripts/check-room-schema-integrity.py) and
[`check-pro-licence-boundary.py`](scripts/check-pro-licence-boundary.py).

## Documentation and decisions

- A change that alters a contract, a layer boundary, or a non-obvious tradeoff
  gets an ADR in `docs/decisions/`, then
  `./scripts/refresh-decisions-digest.sh`.
- The digest is generated. Do not hand-edit it.
- `just docs-audit` checks freshness and normalisation.

An ADR is not a changelog entry. Write one when a reasonable engineer would
have chosen differently and you want the next person to know why.

## `pro/` and licensing

`pro/` is FSL-1.1-ALv2 and everything else is Apache-2.0. Two constraints follow
from that and both are enforced by
[`check-pro-licence-boundary.py`](scripts/check-pro-licence-boundary.py):

- No file outside `pro/` may import `com.singularity.todo.pro`. The dependency
  points from `pro/` into the core, never back.
- No free build file may depend on a non-OSI vendor group.

`pro/` compiles with `-PwithPro=true` but AppTracer is disabled without a
`tracerAppToken`, so that path has **not** been exercised at runtime. If you
touch it, say so in the PR — and prefer a fakes-based test, which does not need
a token.

Anything derived from another project must be registered in
`config/legal/provenance-registry.tsv` with one of `ORIGINAL`,
`SPEC-COMPATIBLE`, `ADAPTED`, `REWRITTEN`, or `PORTED`, and marked in the file
itself. `check-provenance.py` enforces both directions. If your contribution is a
`REWRITTEN` or `ADAPTED`, say so in the PR description — it is not a
disqualification, it is a disclosure, and the alternative is a licence problem
discovered later.

## Pull requests

- Branch from `main`, keep it short, and rebase before you push — the branch
  moves.
- **Never force-push `main`.**
- The commit message is the record. What broke, and why this fixes it, is worth
  more than a summary of the diff.
- If a gate fails and the finding is real, fix it. If the finding is wrong,
  narrow the gate and say why in the PR — do not add a baseline entry to make it
  quiet.

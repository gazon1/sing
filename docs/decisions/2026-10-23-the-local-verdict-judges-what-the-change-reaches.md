---
date: 2026-10-23
status: accepted
deciders: [maintainer]
---

# The local verdict judges what the change reaches — and says so

## Context

The pre-push hook judged detekt over two modules (`:shared:detekt`,
`:desktopApp:detekt`) and the CI judged eight. Both numbers were lines in a
bash script. Nothing compared them with each other, and nothing wrote the
difference down — so a reader of the hook saw "detekt" and read it as "the
whole tree", and a violation in `androidApp`, `mcp-server`, `adr-core`,
`adr-cli`, `core-base` or `detekt-rules` looked like it would be caught
locally. It would not; it would be caught in CI, minutes later.

The hook's price for that pair of modules was never measured. The previous
decision (`2026-10-28-the-detekt-verdict-is-about-the-pushed-tree.md`)
carried an estimate — "two minutes against five" — and an estimate is not a
price.

### The measurement

Measured on 2026-10-23, on this host, with the hook's own invocation
(`./gw :shared:detekt :desktopApp:detekt --auto-correct --rerun`):

| Path | Seconds | What ran |
|---|---|---|
| warm — the current worktree | **561 s** (9 min 21 s) | `:shared:detekt` 7 min 26 s, `:desktopApp:detekt` 1 min 51 s |
| cold — a fresh worktree at the same sha | **565 s** (9 min 25 s) | `:shared:detekt` 8 min 20 s |

Two things the numbers say, and one they do not.

- **The whale is `:shared`.** 227 000 lines, 239 packages; it is 90 % of the
  verdict. `:desktopApp` is 1 min 51 s, `mcp-server` and `adr-core` are
  seconds.
- **Warm is not warm.** 561 s and 565 s are the same number. The reason is
  `./gw`: every worktree gets a private `GRADLE_USER_HOME`, so the
  configuration cache is cold in a fresh worktree no matter what, and Gradle
  says so — "configuration cache cannot be reused because Gradle user home
  directory has changed". Judging in the current worktree saves the worktree's
  build directory, not the analysis.
- **What they do not say:** the load average on this host was 34–50 throughout
  — ten other agent sessions share the machine. The absolute numbers are an
  upper bound; the *ratio* between the paths is the fact, and the ratio says
  the choice of tree is not the lever. The number of modules is.

## Idea

The hook should judge the modules the change can actually affect, and the
difference between the hook and the CI should be written down where both can
be read.

The instrument for the first already exists: `scripts/changed-modules.py`
computes, from the module graph the build files declare, which checks a
change makes worth running. It is already the answer to "what does this change
require?" for the local verification loop. The verdict asks the same question
and had been answered with a hardcoded pair.

## Decision

**The set of modules the hook judges is computed, not written.**

- A change in a module judges that module and the modules that compile against
  it, because that is what the module graph says the change can break. A
  change in `desktopApp` judges `:desktopApp:detekt`; a change in `shared`
  judges `:shared`, `:desktopApp`, `:androidApp` and `:mcp-server`.
- A change in the rules (`config/detekt/`, `detekt-rules/`), in the build
  (`build-logic/`, `gradle/`) or in `.editorconfig` judges **every** module —
  every module's verdict can move when the rules move.
- A change that moves neither Kotlin nor rules needs **no** detekt run, and
  the verdict says exactly that instead of claiming a clean tree. This is the
  old "does this push carry Kotlin?" check, moved into the script that
  decides what to judge — one rule instead of two that can disagree.
- A range with no known base (a brand new branch) is judged in full: the
  safe direction.

**The local verdict is partial, and it is named.** The hook prints the modules
it judged and says that a violation in a module the change does not reach is
CI's to find. An unwritten partiality reads as a full one.

**The difference is data, not prose.** `config/hooks/pre-push.json` records
what the hook judges (three structural gates, the detekt verdict delegated to
`scripts/pre-push-verdict.py`) and what only the CI judges (the eight detekt
modules, plus `:pro:detekt`, which only exists in the `-PwithPro` build and
is therefore outside the module graph). `scripts/check-hook-gates.py` checks
that the code still matches the record, in both directions: a gate the record
names and the hook does not run, a script the hook runs and the record does
not mention, a module the CI judges and the record does not list, a detekt
module named in the hook itself, and a record that does not state the
partiality.

## Rationale

- **The price follows the change.** A change that touches one module pays one
  module's detekt. A change that touches `shared` still pays the whale — but
  it now pays for four modules it can actually break instead of two it might,
  and the marginal modules are the cheap ones.
- **Coverage grows where it matters.** The fixed pair was wrong in both
  directions: too wide for a narrow change and too narrow for a shared one.
  The computed set is never narrower than the change and never wider than
  what the change can break — and it is wider than the old pair exactly where
  the old pair was missing modules.
- **The partiality is written, not implied.** The cost of narrowing is real:
  a violation in an untouched module is no longer caught locally. The answer
  to a cost is not to hide it — it is to write it down, in the record and in
  the hook's own output.
- **One source of truth.** The module graph already answers "what does this
  change require?" for the local loop. A second, hand-written list of modules
  in a bash script is a list that goes stale, and nothing notices until a
  violation lands in a module nobody judged.

## Consequences

- A push that changes one module runs one detekt task instead of two; a push
  that changes nothing Kotlin-related runs none. The hook's cost is now
  proportional to the change rather than to the repository.
- A push that changes `shared` runs four detekt tasks instead of two. The
  marginal cost is small (`:desktopApp` 1 min 51 s, `:mcp-server` seconds)
  and the coverage is the point.
- A violation in a module the change does not reach is caught by CI, which
  judges every module. The hook says so, in its own output.
- `:pro:detekt` stays CI-only: the pro catalogue only exists in the
  `-PwithPro` build, the module graph does not know it, and a free clone has
  no pro sources to judge. The record says so.
- `scripts/changed-modules.py` is now on the hook's path: a change to the
  module graph changes what the hook judges. That is intended — it is the same
  graph the local loop uses — and it is why the graph is a module with tests
  rather than a table inside a script.
- The hook no longer names a detekt module anywhere; `check-hook-gates.py`
  fails if it does.

## Links

- `scripts/pre-push-verdict.py` — the verdict, and the module selection
- `scripts/changed-modules.py` — the module graph (one source of truth)
- `config/hooks/pre-push.json` — the record of what the hook judges and what
  only CI judges
- `scripts/check-hook-gates.py` — the gate that keeps the record and the code
  together
- `docs/testing/gate-costs.md` — the published costs
- `docs/decisions/2026-10-28-the-detekt-verdict-is-about-the-pushed-tree.md` —
  the verdict is about the pushed tree

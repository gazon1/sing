# AFK issue→PR pipeline — investigation of the plan against this repository

## Request

The request is the plan at `docs/plans/2026-10-30-afk-issue-agent-pipeline.md`: build a queue
runner that reads GitHub issues, gives each one its own git worktree from `origin/main`, runs an
agent on it, and opens a pull request — with a Pi extension for live steering on top, and a
learning loop on top of that.

This document is the **Explore** step of Engineering SDD. It establishes what is true today, with
citations. It does not decide the design, does not write a cause, and does not change anything
outside `openspec/changes/afk-issue-agent-pipeline/`.

**Follow-up.** The plan was revised in place on 2026-10-30 against the findings below: the ten
plan claims listed as D1-D10 are corrected in the plan, each with its citation, and the plan's
header points here as the evidence. D11 was found during that revision — it is a stale comment in
this repository's own review skill, not a plan error, and the plan now describes the gate
commands from the recipes rather than from the skill's prose.

**Out of scope.** The runner itself (the plan puts it in a separate repository), the Pi extension,
the learning loop, and every implementation decision the plan has not yet made. Also out of scope:
any change to this repository's application code, gates, skills or CI. The plan's own text was
read as a *claim to check*, not as a source of truth — and several of its claims turned out to be
wrong (see "Discrepancies").

**Where the investigation was run.** `/home/max/orca/workspaces/singularity_cllone_kmp/cardinalfish`,
branch `gazon1/cardinalfish`, HEAD `daff1fbb`, on 2026-10-30. The remote is
`git@github.com:gazon1/sing.git`.

---

## What the code actually does

The plan builds on six mechanisms that already exist in this repository. Each is described below
as the code implements it, with the line that says so.

### 1. The issue intake contract

`.agents/skills/triage/SKILL.md:33-37` defines the label vocabulary — `needs-triage`,
`needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix` — and `:45` defines the transitions
between them. `:79` says that `ready-for-agent` is the state that gets **an agent brief comment**,
and `.agents/skills/triage/AGENT-BRIEF.md:3` says that brief "is the authoritative specification
that an AFK agent will work from". `AGENT-BRIEF.md:9-37` sets the four rules a brief obeys
(durable over precise, behavioural not procedural, complete acceptance criteria, explicit scope
boundaries), and `:39-68` is the template.

So the contract the plan calls "the single most important fact" is real, and it is a **documented
contract with a named format**, not a convention.

### 2. The worktree recipe

`scripts/setup-worktree.sh:5-8` states the contract in its own header: "Run once per worktree,
right after creating it." The script performs four repairs, each with a stated reason:

- `scripts/setup-worktree.sh:182-183` — writes `core.bare = false` and `core.hooksPath = .githooks`
  into the *per-worktree* config, by `--file`, never `--worktree` (`:51-69`).
- `scripts/setup-worktree.sh:268-286` — copies `.env` from the canonical checkout, refusing to
  overwrite one that already exists.
- `scripts/setup-worktree.sh:311-333` — creates a per-checkout Python venv for the gate tooling.
- `scripts/setup-worktree.sh:335` — resolves the worktree-private `GRADLE_USER_HOME`.

`gw:84-85` is the other half: `./gw` exports `GRADLE_USER_HOME` from `scripts/setup-gradle-home.sh`
when it is not already set.

### 3. The agent-review gate

`scripts/ci/check-agent-review.py` reads a pull request's comments and demands a review:

- `scripts/ci/check-agent-review.py:137` — the marker is the literal `<!-- agent-review -->`.
- `scripts/ci/check-agent-review.py:164-178` — the marker must be the **first non-blank line** of
  the comment. A comment that merely *contains* it is not a review.
- `scripts/ci/check-agent-review.py:146-149` — the verdict line must be exactly
  `**Verdict:** pass` or `**Verdict:** fail`; `**Verdict:** pass | fail` matches nothing.
- `scripts/ci/check-agent-review.py:216-238` — the **newest** marked comment decides; a newest
  comment that states no verdict decides nothing, and does not fall back to an older verdict.
- `scripts/ci/check-agent-review.py:233-237` — a `fail` verdict fails the check.
- `scripts/ci/check-agent-review.py:88-94` — the check explicitly does not judge the comment's
  author: "the reviewer runs under the same account as the author, so 'separate' is a fact about
  the session, not about the login."

The wiring is in `.github/workflows/ci.yml`: the `agent-review` job at `:1364-1395` runs
`python3 scripts/ci/check-agent-review.py --pr "$PR_NUMBER"` (`:1395`), and the `ci-gate` job at
`:1418-1421` lists `agent-review` in `needs:`. The assertion at `:1432-1437` accepts only
`success` or `skipped`; anything else fails the single required check.

### 4. The verdict-file gate and the issue registry

`scripts/check-review-verdicts.py:19-29` requires every active change under `openspec/changes/`
to carry `.specify/reviews/<slug>.md` with frontmatter `change`, `verdict`, `date`, `pr`,
`reviewed`, `documents`. `:144-161` computes the `documents` digest over the change's contract
documents. `:387-390` is the failure message for a change with no verdict file.

`scripts/check-issue-registry.py:28-35` requires every directory under `openspec/changes/`
(including `archive/`) to have a record in `.specify/issues/<slug>.yaml`, and the record must point
back at that change **by slug**, not by path.

The two gates also cross-check each other. `scripts/ci/check-agent-review.py:313-354`
(`pointer_findings`) demands that a change whose *contract* the pull request moves carry a verdict
file naming **that** pull request — the contract vocabulary is in
`scripts/src/sgate/gatekit/verdicts.py:195-220` (`moved_contracts`: the proposal and the spec
deltas, not `tasks.md`).

### 5. The gate registry and the one-recipe gate

`scripts/ci/static-gates.sh:20-29` says the file is **generated** from
`sgate/gate_manifest.py` and must not be edited by hand; `:439` is one of the entries
(`issue registry covers every change`). `scripts/check-gate-wiring.py:30` and its Part F (`:2716`)
require every registered gate script to have a positive control.

`justfile:74` aliases `gate := tests::gate`. The recipe is `.just/tests/mod.just:190-220` and has
**three** steps: `./check.sh` (`:198`), `just lint` (`:207`), and the Maestro agenda flows on a
device (`:216-217`). The third step is the only one with a skip, `SKIP_MAESTRO=1`, and the recipe
says in its own output that a skipped run "does not gate the flows" (`:210-212`).

`docs/decisions/2026-10-04-one-gate-recipe.md` is the ADR that owns the recipe: `:39` fixes the
composition, `:41-43` explains why the Maestro step announces its skip, `:47` says the flows "need a
booted emulator", `:55-57` says the steps stop at the first failure, and `:86-88` records that
`just gate` does **not** run `docs-audit` or `find-unwired-surfaces`.

### 6. The reviewer's own contract

`.agents/skills/code-review-pr-workflow/SKILL.md` is the skill the plan invokes for step 7. It
defines: the branch-naming scheme (`:28-35`), the gate the author runs (`:40-50` — `./check.sh`,
`just tcheck-evals`, `just docs-audit`), the review brief including a 25-minute budget
(`:230-246`), the comment format (`:268-278`), and the re-count rule (`:295-300`).

Note that `:40-50` names a **different** command set than `just gate`. The two are not the same
claim.

---

## Reproduction

Every command below was run on this host on 2026-10-30 from the repository root. Output is
verbatim.

### The queue selector behaves as the plan says

```
$ gh issue list --repo gazon1/sing --label ready-for-agent --state open --json number --jq 'length'
76

$ gh issue list --repo gazon1/sing --label ready-for-agent --state open --json number --jq 'length'
   (same command without --limit)
30

$ gh issue list --repo gazon1/sing --label ready-for-agent --state open \
    --search '-label:agent-in-progress' --limit 500 --json number --jq 'length'
76

$ gh issue list --repo gazon1/sing --label ready-for-agent --state open \
    --search 'no:label:agent-in-progress' --limit 500 --json number --jq 'length'
0

$ gh issue list --repo gazon1/sing --state open --search '-label:bug' --limit 500 --json number --jq 'length'
398

$ gh issue list --repo gazon1/sing --state open --search 'no:label:bug' --limit 500 --json number --jq 'length'
0
```

The default page size is **30**, the minus form excludes, and the `no:label:` form returns nothing.
`gh issue list --help` has no `--filter` flag; the only match for "filter" is
`-A, --author string  Filter by author`.

### The labels the plan names exist

```
$ gh label list --repo gazon1/sing --limit 100 | grep -E "ready-for-agent|agent-in-progress|agent-reviewed"
ready-for-agent     Spec is ready for an agent to implement                       #0e8a16
agent-in-progress   An AFK agent is working on this issue                        #0E8A16
agent-reviewed      The pull request carries the review of a separate agent — re-count the Agent review gate   #0E8A16
```

All three exist. The plan's one-time-setup step ("the claim label does not exist in the repo yet")
is already done.

### `just gate` exists, and has three steps, not two

```
$ grep -n "alias gate" justfile
74:alias gate   := tests::gate

$ grep -n -A28 "^gate:" .just/tests/mod.just
192:gate:
...
197:    banner "1/3 check.sh (tests + Android assemble + docs)"
198:    SKIP_ADB={{SKIP_ADB}} ./check.sh
...
203:    banner "2/3 detekt"
...
209:    if [[ "${SKIP_MAESTRO:-0}" == "1" ]]; then
210:        banner "3/3 agenda flows — SKIPPED (SKIP_MAESTRO=1)"
211:        echo "Not run. A run without the flows does not gate the flows."
212:        exit 0
213:    fi
...
216:    banner "3/3 agenda flows on device"
217:    just tests::gate-maestro agenda
```

### `mcode exec` has `--cwd`; `pi` has no `--max-steps`

```
$ mcode exec --help | grep -E "cwd|continue|max-steps|output-schema|output-format|permission"
  --cwd <path>                        workspace directory
  --continue                          continue the latest active Session in --cwd
  --max-steps <count>                 maximum assistant steps
  --output-format <format>            output format: text, json, or stream-json
  --output-schema <schema>            JSON Schema file or inline object for the final answer
  --permission <policy>               permission policy: smart, full, or off (default: "smart")

$ pi --help | grep -c "max-steps"
0

$ pi --help | grep -E "^\s+--(mode|print|no-session|append-system-prompt|model|cwd)"
  --mode <mode>                       Output mode: text (default), json, or rpc
  --print, -p                         Non-interactive mode: process prompt and exit
  --no-session                        Don't save session (ephemeral)
  --append-system-prompt <text>       Append text or file contents to the system prompt (can be used multiple times)
  --model <pattern>                   Model pattern or ID (supports "provider/id" and optional ":<thinking>")
```

No `--cwd` in Pi's option list. No `--max-steps` in Pi's option list.

### The ADR corpus, counted by the repository's own rule

```
$ python3 -c "<read config/docs/adr-corpus.json, glob docs/decisions with decisionPattern>"
live top-level dated ADRs: 900
archived dated ADRs     : 14
total dated ADRs        : 914
all .md under docs/decisions (recursive): 917
non-dated top-level .md  : ['coroutines-test-background-work-advance-until-idle.md',
                            'ktlint-cli-formatter-editorconfig-detekt-alignment.md']
```

### The verdict gate is green today

```
$ python3 scripts/check-review-verdicts.py --quiet
check-review-verdicts: note — 4 baseline entr(ies) name a change that is not open any more; remove them with --update-baseline
exit=0
```

### The toolchain the plan names

```
$ command -v mcode pi orca gh
/home/max/.minimax-code/bin/mcode
/home/max/.nvm/versions/node/v24.14.0/bin/pi
/home/max/.config/orca/linux-orca-cli-shim/orca
/usr/bin/gh

$ pi --version      → 1.4.0
$ orca --version    → 1.4.240
$ gh --version      → gh version 2.102.0 (2026-09-30)
```

### What `orca` actually offers

```
$ orca worktree create --help
  --agent <id>          Launch a known TUI agent in the first terminal
  --prompt <text>       Prompt text for agent-backed commands
  --issue <number|null> Linked GitHub issue number
  --base-branch <ref>   Base branch/ref to create the worktree from
  --setup run|skip|inherit   Setup policy for repo-defined setup hooks
  --run-hooks

$ orca automations create --help
  --provider <agent>     Agent id such as codex, claude, or gemini
  --precheck <command>
  --trigger <schedule>   Automation schedule preset, cron, or RRULE

$ orca agent list
Unknown command: agent list
```

### The committed issue snapshot has no reader

```
$ grep -rln "issues-snapshot" scripts/ .github/
scripts/refresh-issues-snapshot.py
```

`config/docs/issues-snapshot.json` is written by that one script and read by no gate. The
"backlog↔issues" gate that motivated it was removed — `scripts/ci/static-gates.sh:774-778`
records the removal dates (2026-10-19 and 2026-10-15).

---

## Verified facts

Every item below was read in the code or observed in a command's output.

### About this repository's mechanisms

1. The `ready-for-agent` label exists with the description "Spec is ready for an agent to
   implement", and **76** open issues carry it. (command output above)
2. `agent-in-progress` and `agent-reviewed` both exist as labels. (command output above)
3. `gh issue list` returns **30** results by default. (command output above)
4. `--search '-label:X'` excludes label X; `--search 'no:label:X'` returns **zero** results. There
   is no `--filter` flag on `gh issue list`. (command output above)
5. The triage state machine is `needs-triage → needs-info → ready-for-agent → ready-for-human →
   wontfix`, with `needs-info` returning to `needs-triage`. (`.agents/skills/triage/SKILL.md:33-45`)
6. The agent brief is the authoritative specification and has four binding rules plus a fixed
   template. (`.agents/skills/triage/AGENT-BRIEF.md:3,9-68`)
7. `scripts/setup-worktree.sh` is documented as mandatory once per worktree and performs the
   `core.bare` repair, the `core.hooksPath` repair, the `.env` copy, the per-checkout venv and the
   private Gradle home. (`scripts/setup-worktree.sh:5-8,182-183,268-286,311-333,335`)
8. `core.hooksPath` is set **per worktree** via `git config --file`, never `--worktree`, and the
   extension `extensions.worktreeConfig` is switched on last.
   (`scripts/setup-worktree.sh:51-69,197-209`)
9. `./gw` exports a worktree-private `GRADLE_USER_HOME` when one is not already set. (`gw:84-85`)
10. The agent-review marker must be the **first non-blank line** of the comment; a comment that
    quotes the format is not a review. (`scripts/ci/check-agent-review.py:164-178`)
11. The verdict line must be exactly `**Verdict:** pass` or `**Verdict:** fail`; a line naming
    both matches nothing. (`scripts/ci/check-agent-review.py:146-149`)
12. The **newest** marked comment decides, and a newest comment with no verdict decides nothing
    rather than falling back to an older verdict.
    (`scripts/ci/check-agent-review.py:216-238`)
13. The check does not judge the comment's author. (`scripts/ci/check-agent-review.py:88-94`)
14. The `agent-review` job runs the check on pull requests, and `ci-gate` waits for it; only
    `success` or `skipped` counts as green. (`.github/workflows/ci.yml:1364-1395,1418-1437`)
15. The `pull_request` trigger includes the `labeled` type, so applying a label re-runs the
    workflow. (`.github/workflows/ci.yml:19-25`)
16. The review skill states the re-count rule explicitly: a comment-triggered run does not help,
    because GitHub counts such a run's checks on the default branch; the re-count is the
    `agent-reviewed` label or a push.
    (`.agents/skills/code-review-pr-workflow/SKILL.md:295-300`)
17. Every active change needs `.specify/reviews/<slug>.md` with six frontmatter fields, and the
    `documents` digest is over the contract documents.
    (`scripts/check-review-verdicts.py:19-29,144-161,387-390`)
18. Every change directory — archived ones included — needs `.specify/issues/<slug>.yaml`, and the
    record must point back by slug. (`scripts/check-issue-registry.py:28-35`)
19. A pull request that moves a change's **contract** must carry a verdict file naming **that**
    pull request. (`scripts/ci/check-agent-review.py:313-354`,
    `scripts/src/sgate/gatekit/verdicts.py:195-220`)
20. `scripts/ci/static-gates.sh` is **generated** from `sgate/gate_manifest.py`; the manifest is
    the source of truth. (`scripts/ci/static-gates.sh:20-29`)
21. Every registered gate script needs a positive control.
    (`scripts/check-gate-wiring.py:30,2716`)
22. `check-doc-dead-refs.py` scans `AGENTS.md`, `CODING_STANDARDS.md`, `ARCHITECTURE.md`,
    `README.md`, `PROGRESS.md`, every skill's `SKILL.md`, `shared/src/**/*.kt`, `specs/**/*.md`
    and the **module build files** from `module_build_relpaths`. It does **not** scan
    `openspec/changes/`. (`scripts/check-doc-dead-refs.py:100,409-440`)
23. `AGENTS.md` is capped at 300 lines. (`scripts/check-agents-size.py:84`; `AGENTS.md` is 243
    lines)
24. `just gate` has **three** steps — `./check.sh`, `just lint`, and the Maestro agenda flows on a
    device. Only the third has a skip, and the skip announces itself.
    (`.just/tests/mod.just:190-220`)
25. The Maestro step needs a booted emulator, and the steps stop at the first failure.
    (`docs/decisions/2026-10-04-one-gate-recipe.md:47,55-57`)
26. `just gate` does not run `docs-audit` or `find-unwired-surfaces`; both are in CI.
    (`docs/decisions/2026-10-04-one-gate-recipe.md:86-88`)
27. The review skill's author-side gate is a **different** set: `./check.sh`, `just tcheck-evals`,
    `just docs-audit`. (`.agents/skills/code-review-pr-workflow/SKILL.md:40-50`)
28. All three recipes exist: `tcheck-evals` (`justfile:86` → `.just/tests/mod.just:372`),
    `docs-audit` (`.just/tests/mod.just:327`), `lint` (`.just/tests/mod.just:299`).
28. The branch-naming types in use are `feat`, `fix`, `refactor`, `docs`, `chore`, `build`,
    `test`. (` .agents/skills/code-review-pr-workflow/SKILL.md:28-35`)
29. The reviewer must not run Gradle, and has a 25-minute budget.
    (`.agents/skills/code-review-pr-workflow/SKILL.md:175-180,230-246`)
30. The ADR corpus is **900** live dated ADRs, **914** including 14 archived, **917** `.md` files
    in total under `docs/decisions/`. (command output above)
31. `config/docs/issues-snapshot.json` is committed but read by no gate; the gate that needed it
    was removed on 2026-10-19/2026-10-15. (command output above,
    `scripts/ci/static-gates.sh:774-778`)
32. `check.sh` runs static gates → `androidApp:compileDebugKotlin` → `jvmTest` → `desktopApp:test`
    → `assembleDebug` → detekt, and times each step against a recorded cost baseline.
    (`check.sh:1-6,42-60`)
33. There is **no root-level `test` Gradle task** — no `register("test")` / `named("test")` in any
    `build.gradle.kts`, and the root project applies no Java plugin. (grep over `**/build.gradle.kts`;
    `build.gradle.kts:1-18`)

### About the harnesses

34. `mcode exec` **has** `--cwd <path>`, `--continue` ("continue the latest active Session in
    `--cwd`"), `--max-steps`, `--output-format text|json|stream-json`, `--output-schema`,
    `--permission smart|full|off`, and `--append-system-prompt-file`. (command output above)
35. `pi` has `--mode text|json|rpc`, `--print/-p`, `--no-session`, `--append-system-prompt` ("Append
    text or file contents"), `--model`; it has **no** `--cwd` and **no** `--max-steps`. (command
    output above)
36. `mcode`, `pi` (1.4.0), `orca` (1.4.240) and `gh` (2.102.0) are all installed and on `PATH`.
    (command output above)
37. `orca worktree create` accepts `--agent`, `--prompt`, `--issue`, `--base-branch`, `--setup
    run|skip|inherit` and `--run-hooks`; `orca automations create` requires `--provider <agent>`
    and accepts `--precheck` and `--trigger`. (command output above)
38. `orca agent list` is not a command; `orca automations create --help` describes the provider
    ids as "codex, claude, or gemini". (command output above)

### Discrepancies — where the plan's text and this repository's code disagree

These are the load-bearing corrections. Each is stated as *the plan says X; the code says Y*.

- **D1 — `mcode exec` does have `--cwd`.** The plan says twice that it does not
  (`docs/plans/2026-10-30-afk-issue-agent-pipeline.md:389-391`: "`mcode exec` has no `--cwd` flag
  either, so the second call is simply run from the same place"). `mcode exec --help` lists
  `--cwd <path>  workspace directory`. The plan's *reasoning* — "so the second call is simply run
  from the same place" — follows from a premise that is false, and `--continue` resolves the
  session "in `--cwd`", not in the process's current directory.
- **D2 — `pi` has no `--max-steps`.** The plan's cost mitigation is "Cap with `--max-steps`"
  (`:696`), placed in the row about `pi` runs. `pi --help` has no such flag; `--max-steps` is an
  `mcode exec` flag. As written, the Pi runner has no CLI-level step cap.
- **D3 — `just gate` is three steps, and the third needs a device.** The plan says it "runs
  `./check.sh` and then detekt" (`:669-671`). It also names `docs/architecture/verification.md` as
  the document that names `just gate` (`:670-671`). Two separate errors: the recipe has a third
  step (Maestro agenda flows on a device, `.just/tests/mod.just:216-217`, which the ADR says needs
  a booted emulator), and `docs/architecture/verification.md` does **not** mention `just gate` at
  all — a grep over `docs/`, `.agents/` and `AGENTS.md` finds it only in two ADRs
  (`docs/decisions/2026-10-04-one-gate-recipe.md`, `docs/decisions/2026-10-04-doc-size-budget-was-already-red.md`),
  in the command map at `AGENTS.md:141`, and in the plan itself.
- **D4 — the plan's "904 ADRs" matches no count.** The repository's own corpus rule yields 900 live
  dated ADRs, 914 including the archive, and 917 `.md` files. The number 904 appears nowhere in
  this repository except the plan itself (`:613`, `:719`).
- **D5 — `check-doc-dead-refs.py` does not scan `scripts/` build scripts.** The plan says it
  "scans `scripts/` build scripts for dangling paths" (`:205-206`). The script's target list is
  `AGENTS.md`, `CODING_STANDARDS.md`, `ARCHITECTURE.md`, `README.md`, `PROGRESS.md`, skills,
  `shared/src/**/*.kt`, `specs/**/*.md` and the **module build files** from
  `module_build_relpaths` (`scripts/check-doc-dead-refs.py:100,409-440`). `scripts/` is not in it.
- **D6 — `orca`'s agent set is not the plan's set.** The plan says the list is
  `claude|codex|opencode|devin` plus `cursor|codebuddy|grok|omp` for `--extra-agent-args`
  (`:150-152`). There is no `orca agent list` command to confirm it; `orca automations create
  --help` says `--provider <agent>  Agent id such as codex, claude, or gemini` — `gemini` is
  offered, and the plan's list omits it. The plan's exact set is not confirmable from the CLI.
- **D7 — `orca worktree create` has a setup policy the plan ignores.** The plan says orca "makes
  its own checkout; it does not run this repo's `scripts/setup-worktree.sh`" (`:153-156`). The
  command has `--setup run|skip|inherit` ("Setup policy for repo-defined setup hooks") and
  `--run-hooks`. Whether either reaches *this* repository's script is unverified — but the
  statement as written ignores the flags that exist to do exactly that.
- **D8 — the plan's step 7 omits the CI re-trigger.** The `agent-review` job runs on
  `pull_request` and reads the comments; posting a comment does **not** start a new run, and the
  review skill says so in as many words — "A run triggered by a *comment* would not help"
  (`.agents/skills/code-review-pr-workflow/SKILL.md:295-300`). The re-count is the
  `agent-reviewed` label (which exists) or a push. The plan's step 7 (`:309-314`) posts the
  comment and stops; with no label applied and no push, the pull request stays red.
- **D9 — the plan's steps 6-7 omit the two registry artifacts.** A pull request that creates an
  OpenSpec change must carry `.specify/issues/<slug>.yaml` (`scripts/check-issue-registry.py:28-35`)
  and, once the contract is written, `.specify/reviews/<slug>.md` with `pr:` naming that pull
  request (`scripts/check-review-verdicts.py:19-29`,
  `scripts/ci/check-agent-review.py:313-354`). The plan mentions the verdict-file gate as an
  existing fact (`:76-79`) but never tells the runner to produce the file.
- **D10 — the branch name is outside the repository's own vocabulary.** The plan pushes
  `agent/issue-$N` (`:281`, `:307`). The review skill's naming scheme is
  `<type>/<scope>-<slug>` with types `feat`, `fix`, `refactor`, `docs`, `chore`, `build`, `test`
  (`.agents/skills/code-review-pr-workflow/SKILL.md:28-35`). `agent` is not among them, and the
  plan's shape carries no scope and no slug.
- **D11 — found while revising the plan: the review skill's own inline comments are stale.**
  `.agents/skills/code-review-pr-workflow/SKILL.md:43` describes `./check.sh` as "jvmTest →
  desktopApp:test → assembleDebug → detekt (enforcing)", and `:45` describes `just docs-audit`
  as "frontmatter + doc sizes + dead refs + DIGEST". Neither matches the recipes.
  `.just/tests/mod.just:327-360` shows `docs-audit` runs the pro/licence boundary, the provenance
  registry, the DIGEST freshness refresh and the source-tree dump; the doc-size budgets were
  removed on 2026-10-19 (`:331-334`). And `check.sh` runs more than the four steps named — static
  gates, `verifyToolSurface`, `verifySyncFieldAllowlist`, `testAndroidHostTest`, `adr-cli:test`,
  `detekt-rules:test`, `mcp-server` compile and detekt (`check.sh:130-386`).

  This one is not the plan's error — it is the repository's. But the plan quotes the skill's
  command *set*, and a reader who copies the skill's inline descriptions would be misled about
  what the gate actually covers. The **set** of three commands is current; the descriptions next
  to them are not, so the recipes are the authority.

### Facts about the plan itself

- The plan states that no runner code exists ("**Status.** Plan only. Nothing is implemented.",
  `:6-7`). Verified: there is no `runner.sh`, no `prepare-issue.sh`, no `run-agent.sh`, no
  `guardrails.md` and no `prompts/` directory anywhere in this repository.
- The plan's own "Not verified" section (`:723-753`) lists eight open items. This investigation
  did not close any of them; two more were added (D2, D9).

---

## Hypotheses

Each is stated with what would confirm it and what would refute it. None of these was reproduced
here.

- **H1 — a missing `--append-system-prompt` file is silently ignored by Pi.** The plan claims this
  was observed (`:299-303`): with the file present the model answered `GUARDED`, with the path
  missing it answered `Done` and the run still exited 0.
  *Confirms:* running `pi --mode json -p --append-system-prompt /nonexistent/path "<prompt>"`
  exits 0 and the model does not comply with an instruction that only the file could have
  carried. *Refutes:* Pi exits non-zero, or prints a warning naming the missing file.
- **H2 — Pi's `commandcode` provider requires the proxy environment variables.** The plan says
  every call dies with `Request timed out` after ~10 s without `HTTPS_PROXY=http://127.0.0.1:9191`
  and the rest (`:94-110`).
  *Confirms:* a Pi call to `commandcode/...` times out with the variables unset and succeeds with
  them set. *Refutes:* the call succeeds unset.
- **H3 — `commandcode/reflection/beam:free` responds while the default `commandcode` models time
  out.** (plan `:118`)
  *Confirms:* the free model answers and the defaults do not, in the same environment.
  *Refutes:* both behave the same.
- **H4 — `orca worktree create --setup run` executes this repository's `scripts/setup-worktree.sh`.**
  *Confirms:* after the command, the created worktree answers
  `git rev-parse --is-bare-repository` → `false` and has a `.env`. *Refutes:* the worktree is
  still treated as bare, or has no `.env`.
- **H5 — a prompt array survives a compaction mid-run.** The plan says the docs describe Pi's own
  recovery (`:358-364`) but that no compaction was observed (`:731-738`).
  *Confirms:* an array of three prompts completes across a `compaction_start`/`compaction_end`
  boundary with all three turns accounted for. *Refutes:* the remaining prompts are dropped.
- **H6 — the RPC `steer` record interrupts a running turn.** The plan reports this as verified
  (`:542-546`) but also lists it as untested end to end (`:745-750`).
  *Confirms:* a `steer` record sent mid-turn changes the agent's next action.
  *Refutes:* the agent completes the turn unchanged, or the record is ignored.
- **H7 — `mcode exec --continue` finds the right session when several exist in one directory.**
  The plan verified only the single-session case (`:739-740`).
  *Confirms:* with two sessions in the same `--cwd`, `--continue` resumes the latest active one.
  *Refutes:* it resumes an arbitrary or the wrong session.
- **H8 — an unattended host can run the Maestro step of `just gate`.** The ADR says the flows
  need a booted emulator (`docs/decisions/2026-10-04-one-gate-recipe.md:47`) and the recipe
  announces that a skipped run "does not gate the flows" (`.just/tests/mod.just:210-212`).
  *Confirms:* `just gate` completes all three steps on the AFK host.
  *Refutes:* step 3 needs a device the runner does not have, and the runner must therefore use
  `SKIP_MAESTRO=1` — a run that, by the repository's own words, does not gate the flows.

---

## Open questions

These need a decision or an external fact. None of them is answerable from this repository alone.

1. **Where does the runner live?** The plan argues for a separate repository because this one's
   gates are strict (`:202-209`). If it is a separate repository, then *this* OpenSpec change
   directory records an investigation whose implementation happens elsewhere — and the two
   registry artifacts (`.specify/issues/`, `.specify/reviews/`) are only needed if the change
   lands here. The plan never says which repository the runner's own change lives in.
2. **Which of the 76 issues produce an OpenSpec change?** If the runner implements an issue that
   changes behaviour in this repository, the pull request must carry a change directory, a
   registry entry and a verdict file. If it implements a bug fix or a docs change, it must carry
   none of them. The runner has to decide this per issue, and the plan does not say how.
3. **What does the runner's prompt array actually say to run?** The plan's prompts
   (`prompts/01-brief.md`, `02-implement.md`, `03-verify.md`) are described but not written. The
   repository already names the gate an author runs — `./check.sh`, `just tcheck-evals`,
   `just docs-audit` (`.agents/skills/code-review-pr-workflow/SKILL.md:40-50`) — and the ADR
   explains why `just gate` is a separate, slower thing. The runner's prompt should name one of
   them deliberately; today the plan names both inconsistently.
4. **How is the runner's cost bounded?** Pi has no `--max-steps` (D2). Either the runner prefers
   `mcode exec` (which has the flag), or it needs a different ceiling — a turn budget read from
   the event stream, a `--max-steps`-equivalent the plan has not found, or a per-issue cost cap.
5. **Does the Phase-2 extension share the cron runner's lock?** The `flock` in step 0 is
   cron-local, and the plan itself says the label cannot be the lock because adding a label is
   idempotent (`:240-244`, `:690`). The extension "must claim the issue too" (`:585-588`) — but
   claiming is not excluding. What stops the extension and the timer from taking the same issue
   at the same moment is not answered.
6. **Who re-triggers CI after the review comment lands?** D8 identifies the gap; the answer is
   probably "the runner applies the `agent-reviewed` label", but the plan does not say so, and it
   matters because the label must exist on the *pull request*, not the issue.
7. **What happens when the review verdict is `fail`?** `scripts/ci/check-agent-review.py:233-237`
   fails the gate on `fail`, and the plan's step 7 has no branch for it. The pull request stays
   red; the plan's failure path only mentions the worktree and the label.
8. **Is the reviewer's `gh` identity the same as the author's?** The gate does not care
   (`:88-94`) and the review skill says the same (`:302-303`). But a bot that opens a pull request
   and comments on it under the same login is what the gate is designed around — worth stating
   explicitly in the runner, because it reads like a rule that was checked and found not to exist.
9. **Cost of one full run.** The plan lists this as not verified (`:730`) and nothing in this
   repository can answer it.
10. **Does the runner need `gh` write scope beyond issues and pull requests?** Pushing a branch
    needs contents write; the plan defers token scoping to Phase 4 (`:676-677`).

---

## Constraints found

Patterns any solution must respect, gathered from the code and from `AGENTS.md`.

**The repository's gates are strict, and the plan's reason for a separate repository is sound.**
`scripts/ci/static-gates.sh:20-29` — the registry is generated from
`scripts/src/sgate/gate_manifest.py`; edit the manifest, never the shell file. Every registered
gate script needs a positive control (`scripts/check-gate-wiring.py:30,2716`). A gate cannot be
made advisory by appending `|| true` (`scripts/ci/static-gates.sh:17-18`).

**Do not add a third rules mechanism.** `AGENTS.md` is capped at 300 lines
(`scripts/check-agents-size.py:84`), the ADR corpus is already at 900 live records, and every
change needs a verdict file. A free-text lessons file the agent edits would collide with all
three — and, in a public repository, an agent-authored file that every later run reads is a
persistent injection channel. The plan reaches the same conclusion at `:607-650`; the repository
state confirms it.

**The verdict vocabulary has exactly two values and one pointer.** `pass` and `fail`; the
pointer is the `pr:` field, which must name the pull request whose review the verdict records.
(`scripts/check-review-verdicts.py:19-29`, `.agents/skills/code-review-pr-workflow/SKILL.md:76-145`)

**The review comment format is exact.** The marker opens the comment; the verdict is the whole
value of its line; the newest marked comment decides.
(`scripts/ci/check-agent-review.py:146-149,164-178,216-238`)

**The reviewer reads, it does not build.** "Do not run Gradle" — a reviewer that starts a build
competes with the author's for the machine's Gradle slots.
(`.agents/skills/code-review-pr-workflow/SKILL.md:175-180`) The review has a 25-minute budget and
must state its start time. (`:230-246`)

**Worktrees need `scripts/setup-worktree.sh`, and Gradle needs `./gw`.** Every worktree gets its
own `GRADLE_USER_HOME`, its own daemon registry, its own `.env` and its own gate-tooling venv.
(`scripts/setup-worktree.sh:5-8,268-333`, `gw:84-85`) Two Gradle invocations at once is the
prohibition the plan already respects (`AGENTS.md`, "Два Gradle-вызова одновременно не запускай").

**One issue per run, one process.** The plan's "a crash loses one issue, not the queue" is the
right shape and matches the repository's one-change-per-proposal rule
(`openspec/config.yaml:39-43`).

**Logs go to a unique file; TMPDIR is private.** The plan's `build/diagnostics/issue-$N/` matches
the repository's convention (`AGENTS.md`, "Логи и временные каталоги"), and `build/diagnostics/`
is where the coroutine dumps land too.

**Never auto-merge.** The plan says so (`:707`), and the gate machinery is built around a human
merge: the `agent-reviewed` label exists so the *review* is re-counted, not so the change lands
unattended.

**A change in this repository needs four artifacts, not one.** `.openspec.yaml`, the change
directory, `.specify/issues/<slug>.yaml`, and — once the contract is written —
`.specify/reviews/<slug>.md` with a matching `documents:` digest.

**A note on this investigation's own footprint.** Creating
`openspec/changes/afk-issue-agent-pipeline/` makes the directory an *active change*, and the
verdict gate's scaffold exemption (`.openspec.yaml` plus a `proposal.md` matching the
spec-create template — `scripts/check-review-verdicts.py:250-268`) does not apply, because the
Engineering SDD pipeline writes `investigation.md`, not `proposal.md`. Both gates now report the
new directory and both exit 1. Observed:

```
$ python3 scripts/check-review-verdicts.py --quiet
ERROR: afk-issue-agent-pipeline has no review verdict — write reviews/afk-issue-agent-pipeline.md,
       or add the change to the baseline by hand
exit=1

$ python3 scripts/check-issue-registry.py --quiet
ERROR: no registry entry: afk-issue-agent-pipeline → issues/afk-issue-agent-pipeline.yaml
  fix with: python3 scripts/openspec-import-all.py
exit=1
```

This is a consequence of the pipeline's own layout, not a defect found in the plan, and it needs a
decision before the change can be merged: a verdict file, a baseline entry, and a registry entry.
The issue-registry gate names its own fix (`scripts/openspec-import-all.py`); the verdict file
cannot be written until a review has happened, so the honest options are a baseline entry or a
review of this investigation.

---

## References

Files read in this repository:

- `docs/plans/2026-10-30-afk-issue-agent-pipeline.md` — the request
- `.specify/templates/engineering/conventions.md` — the house rules for this step
- `openspec/config.yaml` — the mandatory pre-read chain and the change rules
- `openspec/specs/MODULE-INDEX.md` — spec coverage
- `docs/CONTEXT.md` — domain vocabulary
- `docs/decisions/2026-10-04-one-gate-recipe.md` — the ADR that owns `just gate`
- `docs/decisions/2026-10-04-gradle-daemon-isolation.md` — referenced by `scripts/setup-worktree.sh:78`
- `docs/decisions/2026-10-23-the-bare-flag-lives-in-the-worktree-config.md` — referenced by `scripts/setup-worktree.sh:79`
- `scripts/setup-worktree.sh` — the worktree recipe
- `gw`, `scripts/setup-gradle-home.sh` — the private Gradle home
- `check.sh` — the local gate
- `justfile`, `.just/tests/mod.just` — `just gate`
- `scripts/ci/static-gates.sh`, `scripts/src/sgate/gate_manifest.py` — the gate registry
- `scripts/check-gate-wiring.py` — the positive-control gate
- `scripts/ci/check-agent-review.py` — the review gate
- `scripts/check-review-verdicts.py` — the verdict-file gate
- `scripts/check-issue-registry.py` — the issue registry gate
- `scripts/src/sgate/gatekit/verdicts.py` — the shared verdict vocabulary
- `scripts/check-doc-dead-refs.py`, `scripts/check-agents-size.py` — the documentation gates
- `scripts/refresh-issues-snapshot.py` — the orphaned issue snapshot
- `.github/workflows/ci.yml` — the `agent-review` job and the `ci-gate` assertion
- `.agents/skills/triage/SKILL.md`, `.agents/skills/triage/AGENT-BRIEF.md` — the issue contract
- `.agents/skills/code-review-pr-workflow/SKILL.md` — the review contract
- `config/docs/adr-corpus.json` — the ADR corpus definition
- `AGENTS.md` — the repository's own rules

Host facts, read from the tools themselves: `mcode exec --help`, `pi --help`, `pi --version`,
`orca --version`, `orca worktree create --help`, `orca automations create --help`,
`orca agent list`, `gh --version`, `gh issue list --help`, and the `gh label list` /
`gh issue list` queries quoted in "Reproduction".

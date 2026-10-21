# Script tooling audit

> **Reference, not a procedure.** This is the inventory of the repository's Python
> and shell tooling, taken on 2026-10-20 against commit `59163997`. It goes stale
> whenever the tooling changes, so a change to the tooling updates this file in the
> same commit.

Scope: `scripts/**`, the four packages under `scripts/src/`, the gate registries, and
the CI surfaces that invoke them. Product code (Kotlin) is out of scope.

The question this document answers is not "how few files can the tooling have". It is
**which custom code enforces a rule of this repository, and which custom code
re-implements something a library already does correctly.** The first kind stays. The
second kind is a liability: it is maintained, tested, and reviewed like a rule, while
behaving like a convenience.

Two rules decided the classification of every item below:

1. **A rule that exists only here stays custom.** The repository has a floor per
   scenario, a ratchet that moves one way, a waiver whose premise is checked, and a
   corpus of decisions with a status field. No library enforces those.
2. **A mechanism that only exists to carry a rule out moves to a library** — but only
   when the library handles the actual input language. YAML parsed by regex is not
   YAML; a scanner that finds nothing and reports success is not a check.

## Baseline

Measured on the checkout at `59163997`, no local state:

| Surface | Result |
|---|---|
| `python3 -m unittest discover -s scripts/tests` | **1190 tests, OK, 129 s** |
| `sgate run-all` | **29 blocking + 1 advisory, all green**, 399 s of gate time |
| `scripts/ci/static-gates.sh` | 30 gate lines |
| `scripts/src/sgate/commands/_registry.py` | 45 `GateSpec` entries, 37 controls |
| Static gates job wall time (CI) | 4 m 11 s |
| `JVM tests, detekt, coverage` job wall time (CI) | 28 m |

Two heaviest gates carry **57 % of all gate time**: `declared dependencies are used`
(152 s) and `unwired surfaces` (74 s).

## Inventory

72 files in `scripts/` (21 598 lines): 61 Python and 11 shell. Plus 4 packages under
`scripts/src/` (6 918 lines).

### Packages

| Package | Files | Lines | Role |
|---|---|---|---|
| `sgate` | 34 | 5 416 | The gate CLI: registry, dispatch, controls, reachability |
| `traceability` | 13 | 3 991 | Scenario model, normalisation, rendering, JUnit mapping |
| `emulator` (+ `emulator.maestro`) | 19 | 2 260 | Device acquisition, startup locking, Maestro execution |
| `scanners` | 3 | 702 | Kotlin source scanning (`source_text`, `test_classes`) |

70 files and 12 597 lines in the four packages, counted with `__pycache__` excluded
(the `sgate` figure includes its 24 command modules).

Entry points installed by `scripts/src/pyproject.toml`: `sgate`, `ensure-emulator`,
`run-maestro`. All five runtime dependencies (`click`, `loguru`, `rich`, `pyyaml`,
`pydantic`) are in use.

### The 38 gate-shaped scripts

`scripts/check-*.py`, `find-unwired-surfaces.py` and `measure-flake-rate.py` — 38 files
in all. Most are gates: each has an entry in `_registry.GATES`, and each script is
the thing a control sabotages to prove the gate can fail. (`check-gate-wiring.py` is
the exception — it is a gate that is not in `GATES`, for the reason the ADR records.)

### The 34 other files

23 Python scripts and 11 shell scripts. The Python ones:

| Script | Role |
|---|---|
| `adr_corpus.py` | ADR frontmatter reader, imported by 3 scripts |
| `docs_tree.py` | Docs-tree walker, imported by the digest refresher |
| `scanning.py` | Import shim for `scanners` |
| `check_adr_status.py`, `check-openspec-stale.py` | ADR/spec policy gates |
| `normalize-adr-frontmatter.py` | ADR frontmatter normaliser |
| `refresh-decisions-digest.py` | Digest generator |
| `render-adr-tools-section.py`, `resolve_stale_adr_status.py` | ADR toolchain |
| `openspec-import-all.py`, `refresh-issues-snapshot.py` | spec-kit bridge |
| `release_version.py` | Release version computation |
| `e2e-shard-plan.py` | E2E shard plan generator |
| `run-maestro-e2e.py`, `run-android-ui-tests.py` | E2E runners |
| `run-evals.py` | Workflow evals |
| `junit-annotations.py`, `kover-markdown-table.py`, `instrumented-run-summary.py`, `step-duration-report.py` | Report renderers |
| `measure-baseline-composition.py` | Detekt baseline report |
| `print-source-tree.py` | Source-tree printer |

The 11 shell scripts are `scripts/ci/*.sh` (5), `scripts/release/*.sh` (3) and
`scripts/*.sh` (3).

### CLI conventions

29 scripts use Click, 21 use `argparse`, 11 parse `sys.argv` by hand. The hand-rolled
parsers are not an oversight — `normalize-adr-frontmatter.py:251` and
`refresh-decisions-digest.py:69` document that a silently-ignored flag is a lie, and
they fail loudly on an unknown flag instead. That is the right behaviour; they are
**not** duplication candidates.

## Findings

Findings are ordered by consequence, not by line count.

### F1 — `yaml_has_no_duplicate_keys` invokes a script that does not exist

`scripts/src/sgate/commands/yaml_has_no_duplicate_keys.py:88` runs
`scripts/check-yaml-duplicate-keys.py`. **That file is not in the repository.** The
command therefore always exits `IO_ERROR` (2).

It is also not registered in `scripts/ci/static-gates.sh` — only `YAML is structurally
valid` (the `yamllint` function) is. So the gate is dormant and CI is green because
it never runs.

The property it claims is covered by a stronger parser that does run:
`.yamllint.yml:41` sets `key-duplicates: level: error` (ruamel), invoked from
`static-gates.sh:171` over every tracked YAML file.

The `.yamllint.yml:44-46` comment nonetheless claims the in-repo gate "catches this
too via a custom PyYAML loader". No such loader exists.

**Status: FIXED (2026-10-20).** The command module and its registry entry are gone;
the `.yamllint.yml` comment now says what is true (that `key-duplicates` is the only
duplicate-key detector, and that `yaml.safe_load` overwrites duplicates silently). The
gate's own secondary defect — a verdict re-derived by regex from a child's stderr —
disappeared with it.

### F2 — `ui wait budgets are consistent` skips 26 of 435 Maestro waits

`scripts/check-ui-wait-budgets.py:133-135` matched Maestro waits with three regexes
over raw lines. A wait whose `visible:` is not `id:` was skipped outright:

```python
# scripts/check-ui-wait-budgets.py:310-311
            if not elements:
                continue
```

Measured against the real corpus (`Maestro/flows/**`): **435 wait commands, 409
recognised, 26 silently skipped**. The skipped ones are real, current flows:

```yaml
# Maestro/flows/attachments/01-add-url.yaml:54
- extendedWaitUntil:
    visible:
      text: "Example Article"
    timeout: 5000
```
```yaml
# Maestro/flows/agenda/07-copy-to-profile.yaml:53
- extendedWaitUntil:
    visible: "Copy to profile"
    timeout: 8000
```

A wait written as `visible: "Text"` or `visible: {text: …}` is invisible to the gate,
and the gate still prints "ui wait budgets are consistent" and exits 0.

The docstring at `check-ui-wait-budgets.py:266-272` justified the line scan by saying a
YAML parse "answers a different, larger question" — but the repository already parses
Maestro files with `yaml.safe_load_all` in `scripts/ci/validate-maestro-selectors.py:30`.
The justification does not survive that.

**Status: FIXED (2026-10-20).** The scanner is now a YAML parse; the 26 text waits are
counted and held to a floor (`config/docs/ui-wait-text-waits-floor.txt`, currently 26),
so a new one is a finding and lowering the count is the debt being paid down. The
budget rule for id-bearing waits is unchanged (409 waits, 0 mismatches before and after).

### F3 — `check-workflow-env-context` passes when there are no workflows

```python
# scripts/check-workflow-env-context.py:112-114
def _workflow_files() -> list[pathlib.Path]:
    if not WORKFLOWS.is_dir():
        return []
```

If `.github/workflows/` is missing or renamed, `scan()` returns `[]` and the gate
reports success. The sibling gate gets this right:

```python
# scripts/check-workflow-exit-status.py:233-235
    if not workflows:
        click.echo(f"::error::no workflows found under {WORKFLOWS}", err=True)
        raise ctx.exit(IO_ERROR)
```

**Status: FIXED (2026-10-20).** The guard is in place; with the workflows directory
absent the gate now exits `IO_ERROR` (2) instead of reporting success.

The same file has a false-positive path: `_ENV_WRITE` (`:53-56`) recognises
`echo "X=y" >> "$GITHUB_ENV"` but not a `printf` or a multi-line `echo`. That is loud
(a false failure), not silent — noted, not urgent.

### F4 — `check-script-conventions` has two permanently-dead rules

`scripts/check-script-conventions.py:76-80`, rule R5.4:

```python
        if "gate" in name or len(content) > 500:
            if "ADR" not in content and "#" not in content.split("\n")[0]:
```

`content.split("\n")[0]` is the shebang `#!/usr/bin/env python3`, which always contains
`#`. The condition is therefore always false and **R5.4 can never fire**.

`check_json_flag` (`:130-141`) is a no-op: every branch `continue`s or falls off the
end, so `errors` is always empty. It documents itself as checking R2.2 and R3.7 — two
rules nothing enforces.

The shim's docstring (`scripts/src/sgate/commands/conventions.py:21-22`) advertises six
rules; four of them (R1.1, R2.3, R3.3, R5.3) have no implementation at all.

**Status: FIXED (2026-10-20).** R5.4 was removed rather than repaired: making it fire
exposes 26 scripts that cite no ADR, which is *adding* lint coverage rather than
simplifying it, and the plan forbids expanding lint rules indiscriminately.
`check_json_flag` remains as a named function with an empty body, so the gap is a
visible line instead of a rule that quietly disappeared. The docstring now lists the
three rules that are enforced and names the ones that are not.

### F5 — Three Kotlin comment-strippers, and the weakest one under-reports

The canonical implementation is `scripts/src/scanners/source_text.py` — a single
character walk with nesting-aware block comments (`_end_of_block_comment`, `:97`) and
string-literal handling. ADR `2026-10-19-ast-grep-for-kotlin-parsing-in-python-gates`
records that a Kotlin parser was measured and rejected (`tree-sitter-kotlin` mis-parses
39 of 1646 files), and that decision holds.

Two scripts nevertheless wrote their own:

| Implementation | Nested `/* /* */ */` | Strips string literals? |
|---|---|---|
| `scanners/source_text.py` | yes | yes |
| `find-unwired-surfaces.py:99-148` | **no** — `/\*.*?\*/` stops at the inner `*/` | yes |
| `check-dependency-usage.py:89-91` | **no** | **no** |

`check-dependency-usage.py` is the weaker of the two: a `//` inside a string literal
eats the rest of the line, so a dependency reference on that line becomes invisible and
the gate under-reports usage. The failure direction is silent.

A third brace matcher sits in `scripts/src/scanners/test_classes.py:218-228`
(`_has_test_member`) where `source_text.block_body` / `line_depths` already exist.

**Verdict: CONSOLIDATE** — point both scripts at `scanners.source_text`, and delete
the third brace matcher. The `find-unwired-surfaces` change needs a re-run against its
allowlist (the ADR records that AST and regex agreed on P4, so the check is cheap).

**Status: DONE 2026-10-21** (issues #773, #774, #775). Both scripts read `scanners.source_text`; the
second brace matcher in `test_classes.py` is gone, and the class body is now read with
`source_text.line_depths`. Measured on the corpus: 1676 Kotlin files, 447 test classes,
**0 differences** in the inventory. The dependency gate's blind spot is gone with it.

### F6 — Two hand-rolled parsers of the Maestro `tags:` header disagree

`scripts/src/traceability/links.py:268-274` scans the **whole file** and treats every
`---` as ending a tags block. `scripts/src/traceability/maestro_suggest.py:201-205`
stops at the **first** `---`. The docstring of the second says "the same shape
`scan_maestro` reads" — the duplication is acknowledged, not reconciled.

A flow whose `tags:` block sits after a `---` is found by one and not the other.

**Verdict: CONSOLIDATE** — one reader, and a test that asserts the other agrees.

**Status: DONE 2026-10-21** (issues #773, #774, #775). The reader is `scanners/maestro_flow.py`, and
it is the *runner's* rule — the runner is what filters on a tag, so a reader that
disagrees with it is wrong about a fact the runner acts on. The runner
(`emulator/maestro/_flow.py`), the traceability scanner (`traceability/links.py`) and
the suggester (`traceability/maestro_suggest.py`) all read it now, and
`scripts/tests/test_maestro_flow_reader.py` asserts that they agree against the real
flows. Measured: 105 flows, **0 differences** — and the old Python reader and the old
shell reader agree with it on all 105 as well.

A fourth copy was found while consolidating: `scripts/maestro-flow-tags.sh`, a shell
implementation of the same reader with no caller left. It is a deletion, not a
consolidation, and it goes with the dead code of the next round.

### F7 — `workflow_gradle.py` is the right pattern, applied to half the callers

`scripts/src/workflow_gradle.py` is a genuine consolidation: it joins line
continuations *before* splitting (`iter_invocations`, `:160`), and it reads YAML
steps by ordinal rather than by line number (`iter_steps`, `:200`).

Two scripts use it: `check-gradle-test-population.py:91` and
`check-build-output-readers.py:93`. The largest script in the tree does not:

```python
# scripts/check-gate-wiring.py:1281
    joined = re.sub(r"\\\n\s*", " ", text)
```

`workflow_gradle.py:89` is `_LINE_CONTINUATION = re.compile(r"\\\n\s*")` — the same
rule, two copies. `check-gate-wiring.py:2099` additionally defines a third notion of
"a Gradle task name" (`:a:b` anywhere in raw YAML) against `workflow_gradle.is_test_task`
(`:144`, which works on shell tokens).

**Verdict: CONSOLIDATE (partially)** — reuse the shared continuation join. The task-name
rule is genuinely a different question over a different corpus and may stay separate.

**Status: DONE 2026-10-21** (issue #775), for the join only. The rule is now public —
`workflow_gradle.join_continuations` — and `check-gate-wiring.py` calls it instead of
carrying a second copy of the regex. The task-name rule stays separate: it is a
different question over a different corpus, and merging it would have changed what
that gate measures.

### F8 — `emulator`/`maestro` re-export layers with no callers

`scripts/src/emulator/__init__.py` exports 24 names. Measured by AST import
analysis (`from emulator import X`, excluding the package's own modules), **7 have
no caller outside the package**: `adb`, `adb_on`, `list_avds`, `probe_device_ready`,
`_avd_is_running`, `ensure_device`, `Config`. The rest are reached by
`scripts/tests/test_ensure_emulator.py` and `scripts/run-android-ui-tests.py`.

Two of the seventeen that a first pass listed as unused are in fact used —
`Acquisition` and `acquire_device` are imported from `emulator._startup` by
`scripts/tests/test_ensure_emulator.py` and `scripts/tests/test_device_contracts.py`,
and `cli` is a submodule name that a plain grep matches in unrelated files. The
list above is the AST result, not a grep result.

`scripts/src/emulator/maestro/_device.py:12-27` re-exports `adb`, `adb_on`,
`device_alive` from `emulator._device`; its only importer (`maestro/_types.py:67`)
takes those three from `emulator._device` directly, so the aliases are dead.
`maestro/_device.py:30` (`adb_check`) has no reference anywhere.
`maestro/_testing.py:61-147` (`AdbCall`, ~90 lines) is referenced by no code — only
mentioned in one ADR.

The standalone E2E scripts duplicate a small primitive set rather than the package's
responsibilities: `try_run` (`run-maestro-e2e.py:69`), `device_alive` (`:112`),
device discovery (`:276`), wake/unlock/IME (`:322-325` — the packaged CLI does the
same three calls again at `maestro/_cli.py:153-156`), ANSI colours (`:58-64`).
Locking, fixture cleanup, artifacts and flow execution are **not** duplicated: the
scripts delegate to `python3 -m emulator` / `python3 -m emulator.maestro`, so the
package's guarantees are inherited.

Two differences must survive any consolidation:

- `emulator/_device.py:22` returns `None` on timeout/OSError; `maestro/_subprocess.py:12`
  lets `TimeoutExpired` propagate. `maestro/_runner.py:69` relies on the second — a
  300 s Maestro hang currently raises out of `run_one_flow`.
- The standalone `device_alive` (`run-maestro-e2e.py:112`) probes with `echo ping`;
  the packaged one (`emulator/_device.py:174`) does not. Dropping the probe changes
  when a wedged device is declared dead.

The exit-code namespaces also collide across four files with different meanings
(`1` = "not ready" | "flow failed" | "validation failure"; `2` = "emulator crashed" |
"assembleDebug failed" | "I/O error"). This is worth settling before any exit-code
consolidation.

**Verdict: DELETE** the dead aliases and `AdbCall`'s package export; **CONSOLIDATE**
the duplicated primitives only where the semantics match; **DECIDE** the two semantic
questions explicitly rather than by substitution.

### F9 — Five `sgate` adapters scrape human-readable output, and two already have a JSON contract

`scripts/src/sgate/models.py` defines `GatePayload`, whose `honest_pass` (`:41`) ANDs
the parser's verdict with the underlying exit code — "a gate that reports green on a
failed underlying script is worse than no gate". The intent is right and the
mechanism exists.

Five adapters parse a child's stdout and never use it:

| Adapter | Parses | Producer has `--json`? |
|---|---|---|
| `build_output_readers.py` | `check_build_output_readers: OK` | **yes** (`check-build-output-readers.py:168`) |
| `gradle_test_population.py` | `check_gradle_test_population: OK` | **yes** (`check-gradle-test-population.py:183`) |
| `room_schema_integrity.py` | `Room schema integrity: SCHEMA_VERSION …` | no |
| `test_task_external_inputs_declared.py` | producer text | no |
| `light_adr_modules_stay_light.py` | producer text | no — and the producer is gone (see below) |

For the first two the producer already emits a stable JSON verdict, and the adapter
re-derives the same verdict from a regex over prose. `check-gradle-test-population.py`
emits exactly `{"passed", "blocking_errors", "warnings"}` — the same shape the adapter
reconstructs.

The mechanism is visible in `build_output_readers.py:85`: the adapter builds
`py_cmd = [sys.executable, str(script)]` and runs it **without** `--json`, then
reconstructs the verdict from the prose. The producer's JSON branch
(`check-build-output-readers.py:182-186`) already emits `{"passed", "blocking_errors"}`
— the same two fields, computed by the producer rather than inferred by a regex over
its output. The adapter's `passed` is `bool(_OK_RE.search(stdout))`: a claim about
the script's output text, not about the script's exit status.

**Verdict: CONSOLIDATE → DONE (2026-10-20)** — the four live adapters now consume the
contract; two producers gained the `--json` branch they needed to offer it; the fifth
adapter was an orphan of a gate retired on 2026-10-15 and is gone. See
`docs/decisions/2026-10-20-f9-adapters-read-the-contract.md`.

### F10 — The registry is duplicated, and the duplication is checked rather than removed

30 gate lines in `scripts/ci/static-gates.sh`; 45 `GateSpec` entries in
`_registry.GATES`. `scripts/check-gate-registries.py` compares them — names, modes,
and coverage — and fails on drift. Nine Python gates have no shell line because a
cluster wrapper runs them; that membership is **inferred** by regex over the
wrapper's source (`scripts/src/gate_reachability.py:197`), not declared. Renaming a
line inside a wrapper would silently drop a gate from the covered set.

**Verdict: CONSOLIDATE (next round, after the inventory)** — one declarative manifest
generating both surfaces, and cluster membership declared as data.

### F11 — Two registries of "where does this live"

`GATE_EXEMPTIONS` (13), `NOT_RUN_IN_STATIC` (6, already carrying
`Placement(reason, tracked_by)`) and `GRADLE_TASK_PARITY` (11) are three dictionaries
with different shapes answering the same question.

**Verdict: CONSOLIDATE** — one placement record.

### F12 — `measure-baseline-composition.py` has no callers

Zero references across the repository, and it is not a gate. It is a manual
measurement tool — it was written to answer a one-off question about detekt
suppressions, and the question has been answered.

**Verdict: INVESTIGATE** — it still documents *how* to recompute the numbers, which
has some value. But an unrunnable, untested script that nothing invokes is a trap for
the next reader. Either wire it into a `just` recipe (making it reachable) or delete
it. It is the only script in `scripts/` with no caller at all.

### F13 — Twelve gate scripts have no unit test

Of 38 gate scripts, 26 have a matching `scripts/tests/test_*.py`. Twelve do not:
`check_ci_workflow_shape`, `check_dead_settings`, `check_detekt_governance`,
`check_gate_registries`, `check_hooks_path`, `check_openspec_stale`,
`check_rebase_compiles`, `check_skill_drift`, `check_sync_allowlist_regenerated`,
`check_traceability_ratchet`, `check_workflow_env_context`,
`measure_baseline_composition`.

Two of them are gates that watch the gates: `check_gate_registries` (the two
registries agree) and `check_ci_workflow_shape`. A meta-gate with no test is the
shape this repository has spent three rounds removing elsewhere.

**Verdict: ADD TESTS** — starting with the two meta-gates.

### F14 — The generated gate registry is stale and unwatched

`docs/scripts/gate-registry.md` lists private modules (`-gate-runner`, `-parsers`,
`-registry`, `-runner`) as gates, and reports `mode=unknown` for most rows. It is
generated by `sgate render-registry`, whose source is the *command modules* directory
rather than the registry, so it lists modules instead of gates.

`render-registry --check` exists and passes — it compares the file against the same
imperfect generator. **No CI step or `check.sh` step invokes it**, so the document can
drift without anyone noticing.

**Status: FIXED (2026-10-20).** The generator reads `_registry.GATES`; the table now has
one row per registered gate (45 + header) with the registry's own mode. A new blocking
gate, `gate registry is current`, runs `sgate render-registry --check` in the static
job, with a sabotage control that edits the table's mode column and requires the gate
to fail.

## Classification

| Disposition | Count | Items | Status |
|---|---|---|---|
| **Keep** (repo-specific rule, correctly implemented) | 30 | The gate scripts with tests; `workflow_gradle.py`; `source_text.py`; `traceability/spec.py`; `traceability/junit_xml.py`; `check-test-runs.py`; `check-workflow-exit-status.py`; `adr_corpus.py`; `scanning.py` | — |
| **Fix** (a defect, not a simplification) | 6 | F1, F2, F3, F4, F9, F14 | **all fixed 2026-10-20/21** |
| **Consolidate** (one authoritative implementation) | 5 | F5, F6, F7, F8 (partial), F10+F11 | open |
| **Delete** | 1 | F1's dead gate (done); plus F8's dead aliases | partly done |
| **Investigate** | 1 | F12 | open |
| **Add tests** | 1 | F13 | open |

### What the first round of fixes changed

Five defects, each independently mergeable, landed on 2026-10-20:

- **F1** — a gate whose script had been deleted in `9b3fa75a` (with `.yamllint.yml` as
  its verified replacement) was still in the registry, still dispatchable, and would
  have exited `IO_ERROR` on every run. It was not in `static-gates.sh`, so CI was
  green because it never ran. Removed, and the stale `.yamllint.yml` comment that
  claimed a second detector exists is corrected.
- **F3** — `check-workflow-env-context` reported success when the workflows directory
  was absent. It now refuses, as its sibling gate does.
- **F4** — `check-script-conventions` advertised eight rules and enforced two. R5.4
  could never fire (its condition tested the shebang line for a `#`); `check_json_flag`
  was a no-op. Both are gone, and the docstring now names what is enforced.
- **F14** — the generated gate catalogue listed private modules as gates, missed every
  registry-generated gate, and reported `mode=unknown` for most rows. It is now
  generated from `_registry.GATES`, and a new blocking gate keeps it current.
- **F2** — the Maestro wait scanner skipped 26 of 435 waits and said nothing. It is now
  a YAML parse, and the text waits it cannot price are counted against a floor.


### What the second round of fixes changed (F9)

**F9 — four `sgate` adapters now read their producer's JSON contract.** Two
(`build_output_readers`, `gradle_test_population`) had producers that already emitted
`--json`; the adapters ran them in text mode and asked the output for the word `OK`.
The other two live adapters (`room_schema_integrity`, `test_task_external_inputs_declared`)
had producers with no JSON branch at all — so the producers gained one, and the
adapters read it. All four now contain no verdict-shaped regular expression, and their
exit code is the honest one: the payload's `passed` ANDed with the producer's exit code
(`GatePayload.honest_pass`). The reading itself lives in one place,
`run_json_contract` in `scripts/src/sgate/commands/_runner.py`.

**One orphan removed, not guarded.** The fifth adapter, `light_adr_modules_stay_light`,
pointed at `scripts/check-adr-module-boundary.py`, which was deleted on 2026-10-15
(commit `a2d6cba8`) when the owner retired that gate along with 25 others. The adapter
was classified as a `gate` in `COMMAND_MODULES` but was in neither `_registry.GATES` nor
`static-gates.sh`: it was in no suite and could only fail. Invoking it raised
`NameError: name 'logger' is not defined`, so even its "script not found" path was
broken. Guarding it would have made a retired gate callable again, so the module and
its registry entry are gone — the second case of the `unwired_backlog_refs` shape.

**`--check` became true.** In all four adapters the flag was accepted and ignored,
while its help text promised "Dry-run: print result without exiting non-zero". It now
reports the verdict and exits 0, in both the human and the JSON path.

ADR: `docs/decisions/2026-10-20-f9-adapters-read-the-contract.md`.


### What the third round of fixes changed (the contract, closed)

**Two more holes in the contract, and the third found by the gate that was
written to close them.** The GitHub Actions annotation is written to stdout,
because that is where Actions reads workflow commands from — so a gate whose
stdout carried a document had two claimants for one channel, and in CI the
annotation won. Round 2 fixed the four adapters it had rewritten; the two
places it did not reach were `handle_result` (what **every registry-generated
gate** calls) and a hand-copied twin of it in `adr_status_policy`. Both are
closed, and `adr_status_policy`'s `--check` — accepted and ignored, like the
adapters' was — now reports and exits 0.

**The opt-out became a mode.** `annotate=False` on every call site was a
promise each future adapter had to remember. `contract_stdout()` in
`sgate/output.py` claims stdout once, where the document is emitted, and the
annotation goes to stderr inside it: still in the CI log, no longer a workflow
command, and no longer competing with the document for the channel.

**A gate keeps every gate to the contract.** `scripts/check-gate-json-contract.py`
runs each gate the shell registry dispatches through `sgate` with `--json` and
requires stdout to be exactly one JSON object carrying `passed`. It reuses the
liveness probe's heavyweight filter, so the two gates skip the same gates, and
it runs the module from *this* checkout rather than the `sgate` on PATH — on a
machine with two checkouts of this repository, the binary on PATH is an
editable install pointing at the other one.

Writing it found a third instance on the first run: `sgate render-registry
--check --json` printed `{"status": "current"}` — a status word, and no
verdict. It carries `passed` now.

ADR: `docs/decisions/2026-10-21-one-owner-of-stdout.md`.


### The findings, as issues

Every finding above is filed, so that a reader of this document can follow the
work without reading this document:

| Finding | Issue | Shape of the work |
|---|---|---|
| F5 | [#773](https://github.com/gazon1/sing/issues/773) | three Kotlin comment-strippers become one canonical scanner — **done 2026-10-21** |
| F6 | [#774](https://github.com/gazon1/sing/issues/774) | two Maestro `tags:` header readers that disagree become one — **done 2026-10-21** |
| F7 | [#775](https://github.com/gazon1/sing/issues/775) | the third Gradle invocation caller uses the shared parser — **done 2026-10-21** |
| F8 | [#776](https://github.com/gazon1/sing/issues/776) | dead `emulator` exports and the `maestro` aliases |
| F9 (round 2) | [#769](https://github.com/gazon1/sing/issues/769) | the last two contract holes: `handle_result`, `adr_status_policy` |
| F9 (round 2) | [#770](https://github.com/gazon1/sing/issues/770) | one owner for stdout: the contract becomes a mode |
| F9 (round 2) | [#771](https://github.com/gazon1/sing/issues/771) | meta-gate: every gate speaks the JSON contract |
| F10 + F11 | [#779](https://github.com/gazon1/sing/issues/779) | one manifest of gates and one table of placements |
| F12 | [#777](https://github.com/gazon1/sing/issues/777) | `measure-baseline-composition.py`: wire it or delete it |
| F13 | [#778](https://github.com/gazon1/sing/issues/778) | 12 gate scripts have no unit test |
| — | [#772](https://github.com/gazon1/sing/issues/772) | `sgate run-all` should read the payload, not only the exit code |
### What the fourth round of fixes changed (F5, F6, F7)

**One implementation per question.** Four questions in this repository had two answers,
and every divergence had a quiet direction:

| Question | Before | After |
|---|---|---|
| what does this Kotlin file say, minus its comments | two private regexes | `scanners.source_text` |
| where does this class body end | a second brace counter | `scanners.source_text.line_depths` |
| what tags does this Maestro flow declare | three readers (two Python, one shell) | `scanners.maestro_flow` |
| which command does this continued block run | two copies of the continuation regex | `workflow_gradle.join_continuations` |

**The dependency gate's blind spot is gone with it.** `check-dependency-usage.py`
stripped comments with a regex that did not know a `//` inside a string literal is not
a comment. Everything after that quote on the line disappeared — including a real
reference to the dependency — so the gate under-reported usage and a used dependency
could look unused. It now reads the canonical reader.

**The corpus did not move, and that is the measurement.** 1676 Kotlin files and 447
test classes: the inventory before and after is identical. 105 Maestro flows: the tags
before and after are identical — and the old Python reader and the old shell reader
agree with the new one on all 105 as well. The change is in the failure direction, not
in the answers.

**One reader found that the audit had not named.** `scripts/maestro-flow-tags.sh` — a
shell implementation of the Maestro tag reader — has no caller left. The runner moved
to Python and took the shell file's job with it. It is a deletion, and it goes with
the rest of the dead code in the next round rather than in this one.

ADR: `docs/decisions/2026-10-21-one-implementation-per-question.md`.


## What was measured and rejected

The plan this audit answers proposed replacing custom scanners with a Kotlin parser.
**The repository already measured that and rejected it with data**: ADR
`2026-10-19-ast-grep-for-kotlin-parsing-in-python-gates` records `ast-grep` as not
adopted and `tree-sitter-kotlin` mis-parsing 39 of 1646 files. That decision stands,
and `scanners/source_text.py` is the consolidation it produced. F5 is about *using*
that scanner, not replacing it.

The same applies to YAML: PyYAML is already the parser everywhere it should be, except
in the five places F2, F6 and F7 name. The finding is not "use a YAML library" — it is
"stop writing one".

## Sequencing

Ordered so that each step is independently mergeable and each fixes a defect before
it consolidates code.

1. ~~**Fix the four defects** (F1, F3, F4, F14)~~ — **done 2026-10-20**, PR #764.
2. ~~**Fix F2**~~ — **done 2026-10-20**, PR #764. The measured count is 26 waits of
   435, not 38 of 533: the first count included waits the gate was never asked about.
3. **Delete the dead aliases** (F8) and `measure-baseline-composition.py`'s status
   (F12) — no behaviour change, verified by the existing 1202 tests.
4. **Add the two meta-gate tests** (F13).
5. ~~**Consolidate the scanners and the Gradle parser** (F5, F7)~~ — **done 2026-10-21**
   (issues #773, #774, #775), together with F6. Each was measured against the corpus before and after:
   1676 files / 447 test classes and 105 flows, zero differences.
6. ~~**Consolidate the adapters** (F9)~~ — **done 2026-10-20**. Four adapters read
   their producer's JSON contract instead of scraping its prose; two producers gained
   the `--json` branch they needed to offer it; the fifth adapter was an orphan of a
   gate retired on 2026-10-15 and is gone.
7. **One registry, one placement table** (F10, F11) — the largest change; needs its
   own change record.

Steps 3–4 are independent and can ship separately. Steps 5–7 change what the gates
measure, so each needs its own before/after measurement.

### The next round, concretely

Two changes, in this order, each with its own measurement:

1. ~~**F5 + F6 + F7 — one implementation of each question.**~~ — **done 2026-10-21**
   (issues #773, #774, #775). The two Kotlin strippers read `scanners/source_text.py`; the class body is
   read with `source_text.line_depths`; the Maestro tag reader is `scanners/maestro_flow.py`
   and all three consumers read it; the continuation join is
   `workflow_gradle.join_continuations`. Measured before and after on the corpus:
   zero differences, and the dependency gate's under-reporting is fixed.
2. **F8 + F12 — delete what nothing calls.** Seven dead `emulator` exports, the
   `maestro/_device.py` aliases, `AdbCall` (~90 lines), and either a `just` recipe
   for `measure-baseline-composition.py` or the file itself. No behaviour change;
   the 1202 tests are the check.

F10 and F11 stay out of that round. They are the ones that remove duplication
rather than relocate it, and every gate name and mode moves when they land — that
wants its own change record and its own before/after count of both registries.


### Sizing the remaining work

Measured on 2026-10-20, so the next round starts from numbers rather than from a
first impression:

| Finding | What is left | Size | Risk if done |
|---|---|---|---|
| F5 | ~~3 Kotlin comment-strippers~~ — done 2026-10-21 (#773) | — | done: `scanners/source_text` reads Kotlin for both scripts |
| F6 | ~~2 Maestro `tags:` header parsers that disagree~~ — done 2026-10-21 (#774) | — | done: `scanners/maestro_flow.py`, one reader, three consumers |
| F7 | ~~the shared Gradle parser used by 2 of 3 callers~~ — done 2026-10-21 (#775) | — | done for the join; the task-name rule stays separate by design |
| F8 | 7 dead `emulator` exports, `maestro/_device.py` aliases, `AdbCall` (~90 lines) | ~120 lines | low — no behaviour change; 1202 tests cover the rest |
| F10, F11 | 2 registries → 1 manifest; 3 placement tables → 1 | large | **high** — every gate name and mode moves |
| F12 | 1 script with no caller | 1 file | low — delete or wire into a `just` recipe |
| F13 | 12 gate scripts with no unit test | 12 test files | low — additive |

F9 is done (2026-10-20): the four live adapters read the contract, two producers
gained the `--json` branch, and the orphan adapter is gone.

The cheapest meaningful next round is F6 + F7 (about 40 lines, no behaviour change)
followed by F8 + F12 (deletions). F10 and F11 are the ones that actually
remove duplication rather than relocate it, and they are the ones that want their
own change record.


## Evidence and limits

Every finding above was verified against the checkout at `59163997` (the fixes landed
on `origin/main` at `12078fa2`). The F2 count is **26 waits of 435**, measured by
replaying the gate's own regexes over `Maestro/flows/**` and confirmed afterwards by
the YAML parser that replaced them.

The "no callers" claims come from two passes: a repository-wide grep over `.py`,
`.sh`, `.yml`, `.md`, `.toml` and `.just` files (excluding `.git`, `build` and
`__pycache__`), and — for the emulator exports (F8) — an AST import analysis, which
corrected the grep's first answer from eleven to seven. A caller outside this
repository cannot be excluded, though `sgate`, `emulator` and `traceability` are
repo-local packages installed from `scripts/src/pyproject.toml`.

`scripts/src/traceability/links.py` and `maestro_suggest.py` diverge in code (F6); the
number of flows where the divergence changes a finding was not measured.
`check-doc-dead-refs.py` scans four top-level docs plus `SKILLS_DIR` and `shared/src`
but not `docs/**` — whether another gate covers `docs/architecture/**` was not
confirmed.

### A note on running the suite locally

`check.sh` runs `sgate run-all` from `PATH`, and the installed `sgate` on a developer
machine is an **editable install** — which points at whichever checkout was installed,
not necessarily this one. On this machine it resolves to
`/home/max/orca/workspaces/singularity_cllone_kmp/gulper`, and the run fails there
with `doc dead refs` — **with and without this change's commits**, so it is the
environment, not the code.

Everything in this document was therefore measured with
`PYTHONPATH=$PWD/scripts/src python3 -m sgate run-all`, which uses this checkout. A
failure that reproduces with the changes stashed is the environment; a failure that
does not is the change. That distinction was applied to every measurement here, and
it is the reason the baseline was re-taken with the work stashed.

`scripts/src/sgate/commands/_registry.py` and `check-gate-wiring.py` load their
modules **by path** rather than through `import sgate.<name>`, so they read this
checkout's registry even when an editable install points elsewhere. That is not
accidental: it is what makes a gate on the registry trustworthy on a machine with
two checkouts of it.

## Links

- `docs/decisions/2026-10-20-a-gate-that-reads-less-than-it-claims.md` — the change
  record for the five defects fixed here
- `docs/decisions/2026-10-20-gate-truth-round-two.md` — the round that made the gate
  corpus coherent without reading the gates
- `docs/decisions/2026-10-19-ast-grep-for-kotlin-parsing-in-python-gates.md` — the
  measured rejection of a Kotlin parser in Python gates, which F5 upholds




"""sgate.gate_manifest — one table of placements for every gate.

The gates themselves are declared in ``sgate.commands._registry.GATES``: that is
the manifest — a gate's name, its mode, and how it is dispatched. What this
module holds is the other half of the declaration, the part that used to be
scattered over three tables and one shell file:

``scripts/ci/static-gates.sh``
    the gate lines ``sgate run-all`` reads, and what CI's ``static`` job runs;
``NOT_RUN_IN_STATIC``
    the gates that are deliberately not a line in that file, each with the place
    that runs it instead;
the membership of the cluster gates
    which gates run through a wrapper — inferred, until now, by reading the
    wrapper's source with a regex.

All three are the same fact: *where does this gate run?* One table answers it.

Why the table is not the shell file
-----------------------------------
The shell file was a second registry: 32 gate lines carrying a name, a mode and
a command that ``_registry.GATES`` already carried. Drift was possible, and
``scripts/check-gate-registries.py`` existed to catch it — a check that proves
two declarations agree instead of removing the second one. The shell file is now
a *generated* artifact: ``scripts/ci/static-gates.sh`` holds a generated
region, and ``sgate render-static-gates --check`` is the gate that says the file
is current. The prose that explained each gate moved here with it: a comment
above a gate line is part of the declaration, and a declaration in two places is
two declarations.

The cluster membership
----------------------
Nine gates run through a cluster wrapper — one gate that runs several checks
and reports every failure, not the first. Which gates those are was inferred
by scanning the wrapper's source for the file names it mentions, so renaming a
line inside a wrapper silently dropped a gate from the covered set: nothing
failed, the gate simply stopped being known. ``CLUSTERS`` declares the
membership, and ``scripts/check-gate-registries.py`` holds it against the
wrapper's own list, so the two cannot disagree quietly.

ADR: docs/decisions/2026-10-21-one-manifest-and-one-placement-table.md
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum


class GateMode(StrEnum):
    """Whether a gate failure blocks the pipeline or is only advisory."""

    BLOCKING = "blocking"
    ADVISORY = "advisory"


class PlacementKind(StrEnum):
    """Where a gate runs."""

    STATIC = "static"        # a line in scripts/ci/static-gates.sh
    CLUSTER = "cluster"      # runs through a cluster wrapper
    ELSEWHERE = "elsewhere"  # runs somewhere else, named by tracked_by


@dataclass(frozen=True, slots=True)
class Placement:
    """Where one gate runs.

    A reason in prose rots: the step it names gets renamed and the sentence
    still reads exactly as it did. ``tracked_by`` is the pointer that resolves —
    a path, and for a workflow the *name of a step* — and
    ``scripts/check-waiver-references.py`` is what says it still resolves.
    """

    kind: PlacementKind
    #: The command the shell registry runs (STATIC).
    command: str = ""
    #: The prose rendered above the gate line (STATIC).
    note: str = ""
    #: The cluster gate that runs this one (CLUSTER).
    cluster: str = ""
    #: Why this gate is not a line in the shell registry (ELSEWHERE).
    reason: str = ""
    #: The pointer that resolves — a path, or a workflow step name (ELSEWHERE).
    tracked_by: str = ""


#: The cluster gates: the wrapper, and the gates it runs.
#:
#: Each wrapper declares its own members — ``MEMBERS`` in
#: ``scripts/check-ci-workflow-shape.py`` and
#: ``scripts/check-detekt-governance.py`` — and runs them. This table says which
#: wrapper runs which gate, so that a member is a declared fact rather than a
#: regex inference over a source file. ``scripts/check-gate-registries.py``
#: compares the two and fails when they disagree.
CLUSTERS: dict[str, tuple[str, ...]] = {
    "ci workflow shape is declared": (
        "build-output readers precede the invocations that rewrite them",
        "gradle invocations keep one test population",
        "test-task external inputs declared",
    ),
    "detekt governance is consistent": (
        "detekt baseline ratchet",
        "detekt rule inventory is current",
        "detekt rule registry",
        "detekt rules config is current",
        "file-level suppressions explain themselves",
        "lint rules are declared decisions",
    ),
}


#: Where every registered gate runs. One entry per gate, keyed by display name.
#:
#: The keys are the gate names, so this table and ``_registry.GATES`` are joined
#: by name — and a name in one and not the other is a gate nobody can place.
PLACEMENTS: dict[str, Placement] = {
    "gate registries agree": Placement(
        kind=PlacementKind.STATIC,
        command='python3 scripts/check-gate-registries.py --strict',
        note="""The two gate registries against each other, first: everything below is
dispatched through one of them, and a gate that exists in one and not the
other is a gate that runs on no one — or one that runs without a name.

This was a warning until 2026-10-20, because the drift predated it. The
drift is paid: the shell file and _registry.py now carry the same names.""",
    ),
    "version catalog has no literals": Placement(
        kind=PlacementKind.STATIC,
        command='sgate version_catalog_has_no_literals',
        note="""Build hygiene""",
    ),
    "ci workflow shape is declared": Placement(
        kind=PlacementKind.STATIC,
        command='python3 scripts/check-ci-workflow-shape.py',
        note="""The shape of the CI workflow's Gradle block: one gate for three checks.

  - a test task whose inputs are declared;
  - one test population per job (measured 2026-10-16, run 38933517902: a later
    Gradle invocation re-ran :desktopApp:test *without* -Ptest.tags, whose
    Gradle default is excludeTags("slow"), so the @Tag("slow") scenario classes
    were skipped rather than executed and the traceability step reported a
    target that had in fact been measured as producing nothing);
  - a reader of build output runs after the invocation that rewrites it (the
    traceability step failed that way on the same run, and the flake step would
    have done it silently while being continue-on-error).

One file, three properties, one CI row. Each check keeps its own script, unit
tests and positive control; the wrapper runs all three and reports every
failure, not the first.""",
    ),
    "waiver references resolve": Placement(
        kind=PlacementKind.STATIC,
        command='sgate waiver_references_resolve',
        note="""A waiver whose premise is prose rots silently. The one control waived from the
clean-run guard promises that both surfaces run `:detekt-rules:koverVerify` a
step later; rename the CI step or drop the task from it and the promise is
false while the waiver keeps waiving. This checks the promise, not the pass.""",
    ),
    "declared dependencies are used": Placement(
        kind=PlacementKind.STATIC,
        command='sgate declared_dependencies_are_used',
    ),
    "room schema integrity": Placement(
        kind=PlacementKind.STATIC,
        command='sgate room_schema_integrity',
    ),
    "the text-field transformation table is true": Placement(
        kind=PlacementKind.STATIC,
        command='python3 scripts/check-text-field-transformations-table.py',
        note="""The skill that tells an agent which transformation to use for which text
field carries a table of the transformations and the rule each one applies.
Nothing held it to the code: a transformation could be added, renamed, or
have its rule changed, and the table kept claiming the old thing.

So the table is read and compared with the Kotlin: a transformation the code
declares and the table does not name fails; a row that names a transformation
the code does not declare fails; and the facts the row claims — the digit
limit, the length bound, the range — are derived from the source and
compared with what the row says. The rule sentence is checked as far as a
sentence can be: it has to mention the fact, so a sentence that stops saying
what the code does fails too.""",
    ),
    "the platform reference ports are true": Placement(
        kind=PlacementKind.STATIC,
        command='python3 scripts/check-platform-reference-ports.py',
        note="""The platform reference is the inventory of the seam: which port is bound to
which implementation on which platform. It said so itself — "nothing here is
generated … no gate will notice" — and it had rotted: it still named a port
that was deleted, and it did not name the twenty ports the seam has.

So the reference is checked against the code: a port the platform modules
bind and the table does not name fails; a row that names a port the code does
not bind fails; and an implementation a row attributes to a platform has to
exist in that platform's source set. The table's fourth column says which of
its rows the gate checks, so a row that cannot be checked says so.""",
    ),
    "profile reset inventory is true": Placement(
        kind=PlacementKind.STATIC,
        command='python3 scripts/ci/check-profile-reset-inventory.py',
        note="""The E2E suite isolates one flow from the next by wiping the app's content
between flows, and the list of tables that wipe reaches is a list of
exceptions. Exceptions rot silently: a table added to the Room schema is not a
row in a document, so the document keeps claiming the wipe reaches the whole
profile while the code has stopped reaching it — and the next flow finds the
leftover row and blames the test.

So the schema, the wipe's own list and the inventory document are held to
each other. A table nobody decided about fails; a table the document names and
the schema no longer has fails; a table the code wipes and the document calls
"not profile content" fails.""",
    ),
    "git hooks path is per-worktree": Placement(
        kind=PlacementKind.STATIC,
        command='sgate git_hooks_path_is_per_worktree',
        note="""A gate that dies instead of reporting is indistinguishable from a gate that
passes, for every consumer that reads only the exit code. This found a real
one on its first run: check-kiwi-inventory-ratchet.py crashed with
"NoneType has no attribute loader" and had been dead since #430 (#529).

"Gates are alive" — the probe that runs every gate here once and reports the
ones that die — is NOT in this registry any more. It was, and it cost this
job a second full pass over the gate corpus: 63 seconds to prove that gates
do not crash, on a job whose other gates take 13 seconds together. It now
runs in the `tests` job of ci.yml, where the Gradle step has already
warmed everything and 63 seconds lands in slack nobody waits on.

Two things follow from it no longer being here. It probes the gates named in
this file, so it no longer probes itself — a probe that probed itself would
launch every gate, and every gate would launch the probe (#529). And the
`# no-self-probe` marker it used to carry is gone with the line; the filter
stays in check-gate-liveness.py for the gates that still need it (#534).
A skill is a hand-written description of code nobody diffs against it, so it is
always the stale one and always reads like documentation rather than like a bug.
The "ADR tools" section is generated from AdrSurface.kt and McpToolCatalog.kt;
this compares the committed text with what the code says today (#526).
An absolute core.hooksPath is correct in exactly one checkout and silently wrong in
every worktree, and git config does not travel with the repo (#519).""",
    ),
    "hook gates are declared once": Placement(
        kind=PlacementKind.STATIC,
        command='sgate hook_gates_are_declared_once',
        note="""The pre-push hook judges three gates of forty-one and the detekt modules a
change reaches; the CI judges every module. Both numbers were lines in a
bash script, and nothing compared them with the other side — so "detekt" in
the hook read as "detekt" over the whole tree, and a violation in an
untouched module looked like it would be caught locally.

The difference is written down — config/hooks/pre-push.json — and this gate
checks that the code still matches the record, in both directions: a gate the
record names and the hook does not run, a script the hook runs and the record
does not name, a module the CI judges and the record does not list. The
partiality is part of the record, because an unwritten partiality reads as a
full one.""",
    ),
    "unwired surfaces": Placement(
        kind=PlacementKind.STATIC,
        command='sgate unwired_surfaces',
    ),
    "settings read by a feature": Placement(
        kind=PlacementKind.STATIC,
        command='sgate settings_read_by_a_feature',
    ),
    "a write tool proposes before it writes": Placement(
        kind=PlacementKind.STATIC,
        command='python3 scripts/check-ai-write-tools.py',
        note="""The write path is half a write path.

A proposal is a change nobody has agreed to yet: the user reads it and decides.
Four delete tools and `write_task_proposal` work that way. Eight tools —
`create_task`, `update_task`, `create_project`, `update_project`, `create_note`,
`update_note`, `create_tag`, `decompose_and_create` — write to the user's data
immediately. The model can create a task without the user ever seeing a
proposal for it: the same act, two treatments, decided by which tool the model
happened to pick.

This gate measures the state rather than deciding it. Every direct writer is
listed in `config/docs/ai-direct-writes.txt` with the reason it writes
directly, so each exception is a decision somebody wrote down instead of an
accident. A new direct writer fails the gate until it either proposes or
someone says why it may not. A listed tool that no longer writes fails it too:
a stale entry hides the fact that the work was done.""",
    ),
    "coverage task list": Placement(
        kind=PlacementKind.STATIC,
        command='sgate coverage_task_list',
        note="""The coverage report measures every module that is in it.

The list of test tasks that feed the aggregated report is written by hand,
in the root build script. A module added to the build is not added to that
list, so its tests never run in the coverage job — and a module whose
classes ARE in the denominator is then scored as 0% covered, which reads
like a coverage problem and is a measurement one.
""",
    ),
    "coverage floor declaration": Placement(
        kind=PlacementKind.STATIC,
        command='sgate coverage_floor_declaration',
        note="""The coverage floor is declared where Kover reads it.

The app coverage floors lived in the root build script for months, in a
`plugins.withId("…kover")` block that never executed — the root project has
no Kover extension to configure, so the floors were evaluated by no task.
The declaration now lives in settings.gradle.kts, and this gate is what
stops it from moving back.
""",
    ),
    "coverage timeout": Placement(
        kind=PlacementKind.STATIC,
        command='sgate coverage_timeout',
        note="""The timeout covers the run it was set for.

The instrumented coverage run takes 37 minutes on the development host and
longer on a CI runner, and the job's timeout was a literal that nobody
compared with that number. This gate reads the measured cost and the
timeout and says so when the timeout cannot cover the measurement — before
the job is killed at the moment it was about to report.
""",
    ),
    "selectable text coverage": Placement(
        kind=PlacementKind.STATIC,
        command='sgate selectable_text_coverage',
        note="""The prose a person cannot copy.

The inventory table in the selectable-text skill says which surfaces render
their text with a widget that makes it selectable, and which are excluded on
purpose. The blocking reader of that table is a JVM test, so it cannot be a
line here — this gate is the half that needs no JVM: it finds the prose that
no row covers.

It was advisory while the inventory was open: it had to guess what "prose"
means from the text of a call, and a guess is wrong in more ways than a
comparison is. The inventory is closed now, so the remaining findings are
decisions rather than gaps, and the empty list is the assertion."""),
    "issue registry covers every change": Placement(
        kind=PlacementKind.STATIC,
        command='python3 scripts/check-issue-registry.py --quiet',
        note="""The registry is the source of truth for a change, and nothing checked it.

Four changes landed in main without an entry in `.specify/issues/`: the close
rule consolidation and the three that followed it. Nothing failed — the specs
validated, CI was green — and the registry simply did not mention them. A
change the registry does not know about is a change the next session cannot
find: the decision, the spec and the tasks are in the tree, but nothing points
at them and nothing says they are this branch's work.

The fix is one command, the same one that writes the record:
`python3 scripts/openspec-import-all.py` — and `--replace` for a record that
names another change, because without it the importer skips a record that
already exists.

The gate walks the changes and looks up their records; it does not walk the
records, because most of the registry is history. What it checks is the
direction that can still go wrong for work happening now: a change with no
record, a record that names a different change, and two changes claiming one
slug."""),
    "AGENTS.md pointers resolve": Placement(
        kind=PlacementKind.STATIC,
        command='python3 scripts/check-agents-pointers.py --quiet',
        note="""A pointer that does not resolve is a stop that costs exactly what the note
it came from was written to remove.

The note that introduced this gate pointed `EXEMPT_VM_TEST` at the harness file;
it lives in the conventions test that reads it. Nobody noticed for a session,
because a wrong pointer does not fail — it costs one `ls` and then costs the
reader the question the note was supposed to answer.

The rule covers the pointers that are unambiguous: a backticked path inside the
repository, with `{a,b}` and `...` resolved. A path that is relative to prose
around it is not checked, because where it is relative to is not written down
and the gate would be guessing."""),
    "review verdicts cover every change": Placement(
        kind=PlacementKind.STATIC,
        command='python3 scripts/check-review-verdicts.py --quiet',
        note="""The review of a change has to be somewhere the next session can read.

`AGENTS.md` says a change gets a review by a separate agent before the PR is
opened, and it says it in one line. The review that came with the issue-registry
gate found four real defects in a diff that had been written, reviewed and
pushed — and nothing in the tree said so. The finding existed in the reviewer's
answer, in a session that was about to end.

Every open change carries its verdict in `.specify/reviews/<slug>.md`: the
change, the verdict, the date, the pull request, the commit the review was
made against, the digest of the change's contract documents, and what the
review said. The digest is checked: a contract that moved after the verdict
was written leaves a verdict that speaks for a change that is not there any
more, and the gate says so. The changes that predate the rule are in
`config/docs/review-verdicts-baseline.txt`,
and that list only shrinks — accepting a new exception is an edit a reviewer
can see, not a side effect of a maintenance command."""),
    "the tag catalogue matches the tags": Placement(
        kind=PlacementKind.STATIC,
        command='sgate the_tag_catalogue_matches_the_tags',
        note="""The catalogue of the ids a Maestro flow may address.

`Maestro/TAGS.md` is a golden file, and its reader is a JVM test — so a tag
added today and a catalogue not rewritten are both green until someone runs
the forty-minute suite. This gate is the same comparison without the JVM: it
parses `TestTags.kt` and the generated section of the catalogue and says in a
second whether they still agree.

It reports; it does not rewrite. Regeneration needs the JVM
(`-PupdateGoldens=true`), and the gate names that command when it finds a
difference."""),
    "GitHub workflows are valid": Placement(
        kind=PlacementKind.STATIC,
        command='github_workflows_valid',
        note="""
Workflow + YAML lint, two real tools over the corpus the gates above only
parse. actionlint checks GitHub Actions *semantics* — expressions, action
inputs, and script-injection: on 2026-10-15 it flagged adr-gate.yml for
splicing the attacker-controlled PR title straight into a `run:` block
(`TITLE="${{ github.event.pull_request.title }}"`), which no in-repo gate
caught. yamllint re-parses every tracked YAML with ruamel, a stricter parser
than the PyYAML the duplicate-key gate uses, so a defect one parser accepts
silently still fails here. Its config (.yamllint.yml) is structure-only: the
default profile was 213/220 line-length noise, and a gate that is red on the
day it lands is a gate nobody reads. Both binaries must be on PATH — CI's
static job installs them (pinned in config/toolchain-versions.env) through
.github/actions/setup-python-tools; a missing binary is a loud 127, not a
skip, because an unreachable gate is worse than an absent one.

shellcheck is named explicitly, and its absence is a failure. actionlint
DISABLES its shellcheck integration when the binary is not found, without
saying so — so the gate's verdict silently depended on the image: green on a
developer machine with no shellcheck, red on a runner that has one, same code.
`command -v` turns that invisibility into a message. One line, because the
registry parser is a single-line regex: a gate command split across lines is
read as a command ending in an unterminated quote.""",
    ),
    "YAML is structurally valid": Placement(
        kind=PlacementKind.STATIC,
        command='yaml_is_structurally_valid',
    ),
    "doc links": Placement(
        kind=PlacementKind.STATIC,
        command='sgate doc_links',
        note="""
Markdown links resolve. The gate is scripts/check-doc-links.sh, which wraps
lychee; the reasoning for the flags it passes lives in that script's header.

It is not a replacement for scripts/check-doc-dead-refs.py, which checks the
*other* shape of reference — a path inside backticks, and a dated ADR slug
cited without its `.md`. That script's PATH_RE matches backticks only, so
Markdown links were never in its scope. This gate is coverage the repository
did not have.

It is not a replacement because of how the two resolve. lychee resolves a
relative link against the file that contains it, which is what a Markdown
reader's editor does. The script resolves against the repository root and
falls back to matching on basename alone, so a link written one directory too
high ("../AGENTS.md" from docs/decisions/) still lands on a file of that name
and is reported as "drift" — a warning, never a failure. Fifteen such links
sat in ADRs and skills on 2026-10-19, invisible to every gate. They were
fixed, not accepted.

The binary must be on PATH, for the same reason actionlint needs shellcheck:
a gate that cannot run looks exactly like a gate that has nothing to report.
CI installs it pinned (config/toolchain-versions.env holds the version; the
PyPI wheel of the upstream Rust binary) through .github/actions/setup-python-tools.""",
    ),
    "navigation doc matches the code": Placement(
        kind=PlacementKind.STATIC,
        command='sgate navigation_doc_matches_the_code',
        note="""
The navigation doc says what opens what, and it is pointed at from the nav3
skill — so an agent that follows the pointer reads it. `doc links` above
checks that the links in it resolve; it cannot see that a *claim* about
behaviour has stopped being true, because the link is fine and the sentence
is wrong.

Two rows went false on 2026-10-22 and nothing noticed: the deep-link row for
`singularity://view/{viewId}` still named `SavedAgendaEdit` after the code
moved to `SavedAgendaResults`, and the platform row still said the desktop
tasks stack pushes the `Detail` start onto the stack after the code changed
to seed the stack with the requested route itself.

The gate compares the doc against the code: the types a deep-link row names
must be the types the matcher names, every link the code declares must have a
row, and a claim that the tasks stack is pushed onto must agree with what the
platform assemblers do.""",
    ),
    "DI fixture matches the definitions": Placement(
        kind=PlacementKind.STATIC,
        command='sgate di_fixture_matches_the_definitions',
        note="""
The DI fixture table says what to hand a Koin definition that takes its
arguments at runtime. The definition lives in `shared/.../core/di/`, the
fixture in `desktopApp/.../jvmTest/`, and nothing kept them in step.

On 2026-10-22 `TaskDetailCoordinator` was given a route instead of a
`TaskId`, the definition was updated, and the fixture was not — so the graph
could not be created. The test that catches it lives in the full desktop
suite: an hour of wall clock away on a loaded host, at the very end of the
work.

This gate reads the same two facts from the sources and compares them without
a JVM, so it runs in seconds, in the static gates and in the pre-push hook.
It is a text comparison, so it is a floor and not a proof: it catches a
definition and a fixture that disagree about which type they are talking
about, and leaves a value built by a factory function to the runtime test.""",
    ),
    "doc dead refs": Placement(
        kind=PlacementKind.STATIC,
        command='python3 scripts/check-doc-dead-refs.py --strict',
        note="""
The other reference shape: a path inside backticks, and a dated ADR slug
cited without its `.md`. It was registered in the Python registry with a
positive control and a green run, and no gate line anywhere invoked it —
`check-gate-wiring.py` counted it as "wired" because a *comment* in this
file named the script, and a prose mention is not an invocation. A gate that
runs on no one reports success forever.

The script resolves against the repository root and falls back to matching on
basename alone, so a path written one directory too high is reported as
"drift" — a warning under the default flags, a failure under `--strict`.
`--strict` is the flag CI uses: drift is a reference pointing at the wrong
place, and a warning does not stop anything.""",
    ),
    "gate registry is current": Placement(
        kind=PlacementKind.STATIC,
        command='sgate render-registry --check',
        note="""
The generated gate catalogue: which gates exist, and whether each one blocks.

`docs/scripts/gate-registry.md` is generated from `_registry.GATES`. It had
no freshness check anywhere — not in this file, not in check.sh — so it could
drift silently. It drifted: the generator read the *command modules* directory
rather than the registry, so the table listed private helpers (`_gate-runner`,
`_parsers`, `_registry`, `_runner`) as gates, missed every gate whose command
comes from a GateSpec, and reported `mode=unknown` for most rows.

`--check` compares the file against what the generator produces now. The
generator is the registry, so a gate that is added, removed or reclassified
changes this table, and this gate is what says so.""",
    ),
    "the shell registry is current": Placement(
        kind=PlacementKind.STATIC,
        command='sgate render-static-gates --check',
        note="""
The shell registry itself is generated. The gate lines below used to be
written by hand in this file, next to the prose that explains them, and the
Python registry carried the same names and modes — two declarations of one
fact, kept in step by a check that compared them.

Now there is one declaration: `sgate.gate_manifest.PLACEMENTS` says where each
gate runs, and this file is rendered from it. `sgate render-static-gates
--check` is the gate that says the file is current; after changing the
manifest, run `sgate render-static-gates --write`.""",
    ),
    "the cheat sheet stays a cheat sheet": Placement(
        kind=PlacementKind.STATIC,
        command='python3 scripts/check-agents-size.py',
        note="""AGENTS.md is read by every session, in one go, and every line of it
is paid for whether the session needs it or not. It was 517 lines; the rules
that needed more room moved to docs/architecture/, and this gate keeps the
file from growing back — a change that needs more room needs a document and
a pointer, not a longer cheat sheet.""",
    ),
    "a git command that reads a tree controls its environment": Placement(
        kind=PlacementKind.STATIC,
        command='python3 scripts/check-git-environment.py',
        note="""Git exports GIT_DIR (and GIT_WORK_TREE, GIT_INDEX_FILE,
GIT_COMMON_DIR) to every hook it runs, and those variables override the
directory a git command was pointed at. A digest computed that way is a
digest of a tree nobody asked for — and a `git config` in a test's temporary
repository lands in the shared config of the checkout, which is how
core.sshCommand was once replaced by a test fixture.

A script that reads a tree that is not its own checkout's root must remove
those variables from the environment the command inherits.""",
    ),
    "the checkout can work": Placement(
        kind=PlacementKind.STATIC,
        command='python3 scripts/check-checkout-invariants.py',
        note="""The checkout has to be able to commit, to run its hooks, and to
keep the SSH connection alive through a long pre-push hook. A bare checkout
refuses every command, a hooks path that leads nowhere runs no hooks at all,
and a lost keepalive kills the push after the hook said the tree was clean.

The expected values are written down in config/checkout-invariants.json, and
scripts/doctor.sh reads the same file to diagnose a checkout that drifted.""",
    ),
    "the published gate costs are current": Placement(
        kind=PlacementKind.STATIC,
        command='sgate gate-costs --check',
        note="""
What the verification loop costs.

The loop grew by minutes at a time — a wave added an Android host test, another
added a module to the detekt sweep — and nobody could say so, because nothing
measured it. Now `sgate run-all` times every gate it runs and `check.sh` times
every Gradle step it runs, and the result is published in
docs/testing/gate-costs.md.

This gate compares two committed files: the published page and the recorded
baseline. That is a question a CI runner can answer honestly. The comparison with
a *local* measurement is not one CI can answer, and it is not asked here — the
local report names what got slower, and a CI runner has no local measurement to
name.""",
    ),
    "gates speak the JSON contract": Placement(
        kind=PlacementKind.STATIC,
        command='python3 scripts/check-gate-json-contract.py',
        note="""
Every gate dispatched through `sgate` is called with `--json` by
`sgate run-all`, and the document that lands on stdout is what a consumer
reads. The contract is one shape: one JSON object, and nothing else.

Nothing checked that, and the defect happened twice: the GitHub Actions
annotation is written to stdout, because that is where Actions reads
workflow commands from, so a gate whose stdout carried a document had two
claimants for one channel; and four adapters rebuilt a verdict from prose
instead of reading the verdict their producer already emitted. Both were
found by reading the code, and both were found late.""",
    ),
    "detekt governance is consistent": Placement(
        kind=PlacementKind.STATIC,
        command='python3 scripts/check-detekt-governance.py',
        note="""
NOT HERE: scripts/check-gate-wiring.py
See the ADR for this gate and the note in scripts/check-rebase-compiles.py.

It is tempting to call the meta-gate from the registry, and it is wrong. Part B
proves `check-test-runs.py` can fail by sabotaging config/docs/test-runs-baseline.txt
and re-running it — and that gate reads JUnit XML which only exists once the test
tasks have run. The registry is static by definition: in CI's `static` job there is
no build output at all. Calling it from here reproduces #206 exactly, failing with
"already fails on a clean tree" — a true statement that reads as a false alarm.

A gate that proves another gate works inherits that gate's preconditions and runs
them at its own time. So the meta-gate stays where the results are: after the tests,
in check.sh and in ci.yml's `tests` job.

Lint-rule governance: one gate for six checks over one subject.

  - the generated detekt.yml is current;
  - the generated rule table is current;
  - every rule has a registration;
  - the baseline does not grow silently;
  - a lint rule states the decision it implements;
  - a suppression says why it is there.

Six CI rows for one subject became one. Each check keeps its own script, its
own unit tests and its own positive control — a control proves that a check
can fail, and one control for six checks would prove only that something in
them can fail.""",
    ),
    "scenario specs valid": Placement(
        kind=PlacementKind.STATIC,
        command='sgate scenario_specs_valid',
        note="""detekt-rules branch coverage floor: 60% (below 2026-10-10 measured 65.1%).
This gate runs in ci.yml's `tests` job (where Gradle is available), not here:
it is the Gradle task `:detekt-rules:koverVerify`, named by ci.yml's
"Kover verify (detekt-rules)" step and by check.sh step 9b. The floor itself
lives in detekt-rules/build.gradle.kts (DETEKT_RULE_BRANCH_FLOOR) as a Kover
verify rule; Part C of check-gate-wiring.py holds it to a usable value.

Scenario traceability
traceability validate/coverage resolve the installed `traceability` package from
scripts/src/ (installed by `pip install -e scripts/src/`), so there is no
PYTHONPATH to set and no stand to start.""",
    ),
    "coverage matrix current": Placement(
        kind=PlacementKind.STATIC,
        command='sgate coverage_matrix_current',
    ),
    "coverage holes did not grow": Placement(
        kind=PlacementKind.STATIC,
        command='sgate coverage_holes_did_not_grow',
    ),
    "supabase schema is self-consistent": Placement(
        kind=PlacementKind.STATIC,
        command='sgate supabase_schema_is_self_consistent',
        note="""
Server schema""",
    ),
    "sync allowlist seed matches contract": Placement(
        kind=PlacementKind.STATIC,
        command='sgate sync_allowlist_seed_matches_contract',
    ),
    "maestro selectors valid": Placement(
        kind=PlacementKind.STATIC,
        command='sgate maestro_selectors_valid',
        note="""
Maestro""",
    ),
    "maestro flow catalogue is current": Placement(
        kind=PlacementKind.STATIC,
        command='sgate maestro_flow_catalogue_is_current',
        note="""The flow catalogue: the flows this repository promises to have, written down.
A flow deleted without a word leaves the suite smaller and every run green,
because a suite reports the flows that are left.""",
    ),
    "ui wait budgets are consistent": Placement(
        kind=PlacementKind.STATIC,
        command='sgate ui_wait_budgets_are_consistent',
        note="""The wait budgets: one written number for the screen both UI layers wait for.
The instrumented suite and the Maestro flows cannot share a constant — separate
runners — and they drifted: forty-one waits for the same shell carried five
different timeouts, and the instrumented suite's 8 s is the reason
`android-device-tests` was red on every run of `main`.""",
    ),
    "the toolbox surface is declared once": Placement(
        kind=PlacementKind.STATIC,
        command='python3 scripts/check-skill-drift.py',
        note="""
Source and documentation contracts. Both have a GateSpec in
scripts/src/sgate/commands/_registry.py and a positive control in GATE_CONTROLS,
but neither had a line here — so `sgate run-all`, which reads *this* file, never
ran them. The scripts were green, the controls were registered, and nothing
executed: a gate that runs on no one reports success forever. Part H of
check-gate-wiring.py is what finally said so, once it stopped resolving
`run-all` against the Python mirror instead of this registry.""",
    ),
    "workflow steps keep their exit status": Placement(
        kind=PlacementKind.STATIC,
        command='sgate workflow_steps_keep_their_exit_status',
        note="""
Workflow semantics. actionlint reads a workflow as YAML plus GitHub's expression
language; neither knows that in POSIX a pipeline's status is the *last* command's.
Four CI steps piped, so their failures could not fail them.""",
    ),
    "workflow env context resolves": Placement(
        kind=PlacementKind.STATIC,
        command='sgate workflow_env_context_resolves',
        note="""
The `env` context holds the variables a workflow defines — not the runner's
environment. `${{ env.ANDROID_HOME }}` expands to the empty string, silently,
and a path built from it names nothing. That is not a theory: the Maestro
shards failed a release cycle with "emulator binary not found in PATH"
because the cache paths were written `${{ env.ANDROID_HOME }}/…`.""",
    ),
    "releases describe themselves": Placement(
        kind=PlacementKind.STATIC,
        command='sgate releases_describe_themselves',
        note="""
Releases. `meta` proves a tag was on main with a green CI run; it says nothing
about whether a release came out of it, nor whether the release that exists
describes the assets it actually carries. `publish` had never run, so neither
half had ever been checked by anything.""",
    ),
    "pro licence boundary": Placement(
        kind=PlacementKind.STATIC,
        command='python3 scripts/check-pro-licence-boundary.py',
        note="""
Provenance and licensing. Both were registered in `_registry.py` with a positive
control and a sabotage that can fail, and neither had a line here — so `run-all`,
which reads this file, never ran them. A control for a gate that never executes
proves nothing about the gate.""",
    ),
    "provenance": Placement(
        kind=PlacementKind.STATIC,
        command='python3 scripts/check-provenance.py',
    ),
    "adr references are valid": Placement(
        kind=PlacementKind.STATIC,
        command='python3 scripts/check-adr-references.py',
        note="""
ADR references: a prose mention of a dated-ADR slug carries no path, so a moved or
deleted decision leaves a dangling claim nothing else sees.""",
    ),
    "script conventions": Placement(
        kind=PlacementKind.STATIC,
        command='sgate conventions',
        note="""
─── Deliberately NOT here ─────────────────────────────────────────────────────

Four more gates are registered in `_registry.py`, each with a positive control,
and each used to be absent from this file. They are not runnable in CI, so
registering them would trade "a gate that never runs" for "CI red for a reason
unrelated to this change":

    doc dead refs                    exit 1   flaky tests are acknowledged   exit 1
    rebase compiles                  exit 1   test runs                      exit 1

They are the repo's knowingly-red corpus, in the same shape as the
`script conventions` entry below was before the Click migration landed.
Each carries `needs_clean_run=False` or a
documented exemption in `GATE_CONTROLS` for exactly this reason. `manifest-verify`
now reports a registry entry with no gate line in this direction too, so the two
lists cannot drift apart unnoticed again — which is how all eleven went missing.

The `doc sizes are within budget`, `req id uniqueness`, `backlog issue refs` and
`gate honesty` entries that used to stand here are gone: the first three gates
were removed on 2026-10-19 and 2026-10-15, and the fourth — the one that planted
a detekt violation in a throwaway worktree to prove the lint gate could fail —
was removed on 2026-10-20, with its script, its prune helper and its `just`
recipe. See the ADR of 2026-10-20.

AppTracer

REMOVED: "local branch is not behind origin/main" (bash scripts/ci/check-unmerged.sh)

The premise was sound — rebasing onto a stale main creates divergent history that
needs a force-push — but a runner cannot check it. CI checks out a detached commit
with no tracking branch and no `origin/main` ref, so the gate failed every run on
the runner's setup rather than on anyone's divergence:

    check-unmerged: upstream 'origin/main' does not exist. Run 'git fetch' first.

A gate that is always red for a reason unrelated to what it checks trains people to
read past it, and it was the third blocking failure in `Static gates` on a run whose
other two were unrelated. Divergence prevention is not lost: `.githooks/pre-rebase`
implements the same rule locally, where an upstream ref genuinely exists, and does so
on the ref about to be rebased onto rather than on a tracking branch CI never has.

Gate-script conventions. Blocking now that the corpus is green: the last three
argparse holdouts are migrated, and the check finds the argparse import by
parsing the module rather than by looking for a substring — which is how it
used to report itself as a violation while the corpus was already clean.

It was advisory for as long as it was red on a clean tree, and a control
cannot tell "caught the mutation" from "still failing the known violations".
That is the whole reason it had no positive control; it has one now.""",
    ),
    # ── Cluster members ─────────────────────────────────────────────────────────
    # They run because their cluster runs them: one gate line, one CI row, one
    # subject. The wrapper runs each member and reports every failure.
    "build-output readers precede the invocations that rewrite them": Placement(
        kind=PlacementKind.CLUSTER,
        cluster="ci workflow shape is declared",
    ),
    "gradle invocations keep one test population": Placement(
        kind=PlacementKind.CLUSTER,
        cluster="ci workflow shape is declared",
    ),
    "test-task external inputs declared": Placement(
        kind=PlacementKind.CLUSTER,
        cluster="ci workflow shape is declared",
    ),
    "detekt baseline ratchet": Placement(
        kind=PlacementKind.CLUSTER,
        cluster="detekt governance is consistent",
    ),
    "detekt rule inventory is current": Placement(
        kind=PlacementKind.CLUSTER,
        cluster="detekt governance is consistent",
    ),
    "detekt rule registry": Placement(
        kind=PlacementKind.CLUSTER,
        cluster="detekt governance is consistent",
    ),
    "detekt rules config is current": Placement(
        kind=PlacementKind.CLUSTER,
        cluster="detekt governance is consistent",
    ),
    "file-level suppressions explain themselves": Placement(
        kind=PlacementKind.CLUSTER,
        cluster="detekt governance is consistent",
    ),
    "lint rules are declared decisions": Placement(
        kind=PlacementKind.CLUSTER,
        cluster="detekt governance is consistent",
    ),
    # ── Not a line in the shell registry ───────────────────────────────────────
    # Each of these runs somewhere else, and the place is named. A gate that
    # runs on no one reports success forever, so the reason is not decoration:
    # it says which surface runs it.
    "flaky tests are acknowledged": Placement(
        kind=PlacementKind.ELSEWHERE,
        reason=(
            "CI runs it as a step of the `tests` job, after the JUnit XML it reads "
            "exists: `python3 scripts/check-flaky-tests.py --current shared/build/"
            "test-results/jvmTest`. It cannot be a gate line: the static job runs "
            "before any test has run, so the directory it reads does not exist."
        ),
        tracked_by=".github/workflows/ci.yml#Late flake pass (advisory)",
    ),
    "gates are alive": Placement(
        kind=PlacementKind.ELSEWHERE,
        reason=(
            "CI runs it as a step of the `meta` job. It probes every gate named in "
            "static-gates.sh, so inside that file it would probe the file it is "
            "probing — a probe that probes itself launches every gate, and every gate "
            "launches the probe (#529). It also costs a second full pass over the gate "
            "corpus (63s), which belongs in its own job, parallel with the JVM tests, "
            "not in the one whose wall time is the sum of its slowest gate."
        ),
        tracked_by=".github/workflows/ci.yml#Gates are alive",
    ),
    "gate scripts' own unit tests": Placement(
        kind=PlacementKind.ELSEWHERE,
        reason=(
            "CI runs them as a step of the `meta` job: `python3 -m unittest discover "
            "-s scripts/tests`. 1187 assertions in 70 seconds — more than every other "
            "gate in the static job together — and they are about the gates, not "
            "about the app, so they belong beside the gates and not behind the JVM "
            "tests."
        ),
        tracked_by=".github/workflows/ci.yml#Gate scripts' own unit tests",
    ),
    "instrumented test floor": Placement(
        kind=PlacementKind.ELSEWHERE,
        reason=(
            "CI runs it in android-device-tests.yml, the only job with an emulator. It "
            "needs the instrumented XML that job produces."
        ),
        tracked_by=".github/workflows/android-device-tests.yml#Check the instrumented suite's floor",
    ),
    "maestro keyboard commands have something to hide": Placement(
        kind=PlacementKind.STATIC,
        command='python3 scripts/check-maestro-keyboard-commands.py',
        note="""
A `hideKeyboard` is a BACK keypress when there is no keyboard to hide.

Measured on a phone (2026-10-28): a flow's second `hideKeyboard` dismissed the
editor dialog, and the run failed on an element the editor was holding. The
editors are dialogs, so BACK closes them — the command that means "dismiss" and
the command that means "hide the keyboard" are the same key.

So a `hideKeyboard` follows a text input, one per text input, and never
follows anything else.""",
    ),
    "maestro flows do not name a pixel": Placement(
        kind=PlacementKind.STATIC,
        command='python3 scripts/check-maestro-screen-geometry.py',
        note="""
A flow that names a pixel is a claim about one display.

Measured on a phone (2026-10-28): the flows that scrolled the settings rail
by pixel never reached the trailing tabs, and the flows that tapped the menu
sheet's last item tapped the system navigation bar instead. On the CI
emulator they are green - a statement about the emulator that reads like a
statement about the app.

A swipe may be a share of the screen, or the element that scrolls. Both mean
the same gesture on every display.""",
    ),
    "maestro results are within the floor": Placement(
        kind=PlacementKind.ELSEWHERE,
        reason=(
            "CI runs it as the `maestro-results` job of e2e.yml, after the shards: it "
            "reads the JUnit XML the run wrote, and that XML does not exist until a "
            "shard has run. It runs on the shards' combined reports rather than "
            "inside a shard, because the floor in "
            "config/docs/maestro-failure-floor.txt is a number for the whole suite — "
            "a gate that ran inside one of the four nightly shards would measure a "
            "quarter of the suite against the whole suite's floor, and pass on debt "
            "it could not see."
        ),
        tracked_by=".github/workflows/e2e.yml#Maestro results gate",
    ),
    "rebase compiles": Placement(
        kind=PlacementKind.ELSEWHERE,
        reason=(
            "CI runs it as a step of the `tests` job, after the Gradle invocation it "
            "compiles: it needs the build output to exist."
        ),
        tracked_by=".github/workflows/ci.yml#Post-rebase DI compile",
    ),
    "test runs": Placement(
        kind=PlacementKind.ELSEWHERE,
        reason=(
            "CI runs it as a step of the `tests` job, against the JUnit XML that "
            "Gradle has just written, with the run stamp the job records before it "
            "starts. A gate line would read a directory that does not exist yet."
        ),
        tracked_by=".github/workflows/ci.yml#Executed test counts",
    ),
    'the link grammar has one home': Placement(
        kind=PlacementKind.STATIC,
        command="sgate the_link_grammar_has_one_home",
        note=(
            'The grammar of a link has a home: TaskLinkSpec for a deep link, core/links/InternalLinks.kt for an internal one. A second copy of it is a link that opens nothing.'
        ),
    ),
    'the tool catalogue is current': Placement(
        kind=PlacementKind.STATIC,
        command="sgate the_tool_catalogue_is_current",
        note=(
            'The catalogue of the gate scripts, generated from their own docstrings. A catalogue that lost a tool is a catalogue that sends a reader to a tool that is not there.'
        ),
    ),
}

#: The gates that are deliberately NOT a line in ``scripts/ci/static-gates.sh``.
#:
#: A view over :data:`PLACEMENTS`, not a second table: the entries and their
#: reasons live in one place, and this is the subset of them that runs
#: elsewhere. A gate with no placement at all is a gate nobody can find.
NOT_RUN_IN_STATIC: dict[str, Placement] = {
    name: placement
    for name, placement in PLACEMENTS.items()
    if placement.kind is PlacementKind.ELSEWHERE
}


def static_placements() -> list[tuple[str, Placement]]:
    """The gates that are a line in the shell registry, in registry order."""
    return [
        (name, placement)
        for name, placement in PLACEMENTS.items()
        if placement.kind is PlacementKind.STATIC
    ]


def comment_lines(note: str) -> list[str]:
    """A note as the shell registry spells it: every line is a comment.

    The marker is added here and only here. A note that has to remember to
    carry its own ``#`` is a note that one day does not — and a note without
    the marker is not a comment but a command: the shell registry is a shell
    file, and the shell reads the prose above a gate line as much as a person
    does. The result was a registry that ran ``The`` as a command and still
    reported every gate as passed.
    """
    return [f"# {line}".rstrip() for line in note.split("\n")]


def render_static_registry(modes: dict[str, str]) -> str:
    """The gate lines of ``scripts/ci/static-gates.sh``, generated.

    ``modes`` maps a gate name to its mode (``blocking`` / ``advisory``); the
    mode belongs to the gate, not to its placement, so it is read from the
    manifest rather than stored twice.

    The prose above each gate line is part of the declaration and travels with
    it: a comment that explains why a gate exists is not decoration around a
    registry entry, it is the reason the entry exists.
    """
    lines: list[str] = []
    for name, placement in static_placements():
        if placement.note:
            lines.extend(comment_lines(placement.note))
        lines.append(f'gate {modes[name]} "{name}" {placement.command}')
    return "\n".join(lines) + "\n"

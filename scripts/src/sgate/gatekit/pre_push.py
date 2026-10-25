"""What the pre-push hook runs, declared once.

The hook used to carry a block of code per gate: the script, its arguments,
the condition under which the gate runs, and the message it prints when it
fails. Every one of those blocks was a second copy of the declaration the
test fixture reads, and the two drifted — a gate added to the hook and to
neither was a failing test that only the full run found.

This module is the declaration. The hook reads it, the test fixture reads it,
and the registry derives the list of scripts from it, so the three cannot
disagree.

The module deliberately imports nothing from the rest of the package: the hook
runs it by path, in a tree that may carry nothing else of the package, and a
module that needed its neighbours could not be read there.
"""

from __future__ import annotations

from dataclasses import dataclass


PRE_PUSH_TREE_RULES: tuple[str, ...] = (
    "check-agents-pointers",
    "check-issue-registry",
    "check-review-verdicts",
)


@dataclass(frozen=True)
class PrePushStep:
    """One gate the pre-push hook runs against the tree that is being pushed.

    The hook used to carry a block of code per gate: the script, its
    arguments, the condition under which it runs, and the message it prints
    when it fails. Adding a gate to the hook meant writing that block, and
    the block was the only place the gate existed — so the gate's name was
    written down twice (once in the hook, once in the registry the test
    reads), and the two drifted.

    The step is the declaration. The hook reads it and runs it; the test
    reads it and stubs it; the gate that compares the hook with the registry
    reads it and knows what the hook must run.

    The fields say what the block used to say:

    * ``script`` — the script, relative to the repository root.
    * ``args`` — its arguments. ``@TREE@`` stands for the tree being pushed.
    * ``script_from`` — which copy of the script runs: ``tree`` (the pushed
      tree's own copy) or ``repo`` (this checkout's copy).
    * ``pythonpath_from`` — which copy of the sgate package is on
      ``PYTHONPATH``: ``tree``, ``repo``, or ``none`` for a script that
      imports nothing from it.
    * ``when`` — when the step runs at all. ``always``; ``if-dir:<path>``
      when the tree has that directory; ``if-import:<module>`` when that
      module can be imported; ``if-script`` when the pushed tree has the
      script; ``if-touched:<pattern>[,<pattern>...]`` when the push touched
      one of those paths. Several conditions may be joined with ``;``, and
      every one of them has to hold — the tree rules are skipped when the
      tree does not have the rule **and** when the gate CLI cannot run, so
      that a machine without ``click`` is not stopped by a rule it cannot
      execute.
    * ``message`` / ``hint`` — what the hook says when the step fails.
      ``@REF@`` stands for the ref being pushed, ``@TREE@`` for its tree.
    """

    label: str
    script: str
    args: tuple[str, ...] = ()
    script_from: str = "tree"
    pythonpath_from: str = "tree"
    when: str = "always"
    message: str = ""
    hint: str = ""
    show_output: bool = False


#: The gates the pre-push hook runs against the tree that is being pushed.
#:
#: The order is the order the hook ran them in, and it is the order a reader
#: expects: the cheap checks that always run, then the rules that only run
#: when the push touches what they are about.
def _tree_rule_steps() -> tuple[PrePushStep, ...]:
    """The tree rules, as steps.

    The three rules that are cheap, that CI enforces, and that a contributor
    would otherwise only meet as a red CI run: the pointers AGENTS.md names,
    the registry entry of every change, and the review verdict of every
    change.

    They run against the pushed tree, not the worktree, for the same reason
    the gates above them do: a rule that reads a different tree than the one
    being pushed checks the wrong thing.

    The rule that runs is the one **in the pushed tree** — the same copy CI
    would run, because CI runs the tree's own registry. Running today's
    script against an older tree applies rules that did not exist when that
    tree was written: a branch cut before this change would be blocked for
    violations CI would never report on it. A tree that does not have the
    rule does not have the rule, so the check is skipped and says so.

    Each rule costs well under a second — measured: the pointers gate 0.1 s,
    the registry gate 1.1 s, the verdict gate 0.2 s — so the local loop
    carries the same rules CI does, at a price nobody notices. The suite as a
    whole is not in this hook on purpose: it is a minute and a half, and it
    is where the incremental temptation lives. What is here is the part that
    is cheap and that a broken tree would otherwise carry to CI.

    A rule the pushed tree does not have is skipped: a tree that predates the
    rule does not have the rule, and a push of an old branch is not blocked
    for a violation CI would never report on it.
    """
    return tuple(
        PrePushStep(
            label=f"{rule} gate",
            script=f"scripts/{rule}.py",
            args=("--quiet",),
            script_from="tree",
            pythonpath_from="repo",
            when="if-script;if-import:click",
            message=f"pre-push FAILED — {rule} found something in the tree of @REF@.",
            hint=f"Run python3 scripts/{rule}.py to see where, or push with --no-verify.",
        )
        for rule in PRE_PUSH_TREE_RULES
    )


PRE_PUSH_STEPS: tuple[PrePushStep, ...] = (
    PrePushStep(
        label="version-catalog gate",
        script="scripts/build-version-catalog-gate.py",
        args=("--quiet", "."),
        script_from="repo",
        pythonpath_from="none",
        message="pre-push FAILED — hardcoded version literal in the tree of @REF@.",
        hint=(
            "Add it to gradle/libs.versions.toml and reference it via libs.*, "
            "or push with --no-verify."
        ),
    ),
    PrePushStep(
        label="maestro selector gate",
        script="scripts/ci/validate-maestro-selectors.py",
        args=("Maestro/flows",),
        script_from="repo",
        pythonpath_from="none",
        when="if-dir:Maestro/flows",
        message="pre-push FAILED — unknown id: selector in a Maestro flow in the tree of @REF@.",
        hint=(
            "Add it to TestTags.kt or DYNAMIC_EXPANSIONS / ALLOWLIST, "
            "or push with --no-verify."
        ),
    ),
    # The wait budgets: the instrumented suite and the Maestro flows wait for
    # the same screen and cannot share a constant. The gate is what keeps the
    # two copies of the number in step — and it is the gate that would have
    # caught the 8-second shell budget that kept `android-device-tests` red.
    #
    # It is skipped rather than failed when its dependencies are missing: a
    # gate that cannot run must not become a reason a push cannot happen, and
    # CI runs it either way.
    PrePushStep(
        label="ui wait budgets gate",
        script="scripts/check-ui-wait-budgets.py",
        args=("--root", "@TREE@"),
        script_from="repo",
        pythonpath_from="repo",
        when="if-import:click",
        message="pre-push FAILED — the UI wait budgets disagree in the tree of @REF@.",
        hint="Run python3 scripts/check-ui-wait-budgets.py to see where, or push with --no-verify.",
    ),
) + _tree_rule_steps() + (
    # The promise files.
    #
    # Some files in this repository promise something about the code next to
    # them, and each promise has been broken at least once — always in CI,
    # always hours later, always on somebody else's tree:
    #
    #   Maestro/TAGS.md                — the catalogue of the ids a flow may
    #                                     address, generated from TestTags.kt
    #   config/docs/maestro-flows.txt  — the catalogue of the flows themselves
    #   .specify/issues/*.yaml         — the registry of every openspec change
    #
    # A checker for each of them exists and costs seconds. What was missing was
    # the moment: the promise is kept when the file next to it changes, not
    # when CI eventually notices.
    #
    # So the check runs against the tree that is being pushed, and only when
    # that tree actually touches the file that carries the promise — a push
    # that touches nothing relevant pays nothing.
    #
    # The checkers are run FROM the pushed tree (`$tree/scripts/...`), because
    # each derives its repository root from its own file location: the copy in
    # the tree is the copy that knows about the tree.
    PrePushStep(
        label="tag catalogue gate",
        script="scripts/check-test-tags-catalog.py",
        args=("--check", "--quiet"),
        script_from="tree",
        pythonpath_from="tree",
        when="if-touched:shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt$",
        message="pre-push FAILED — Maestro/TAGS.md does not match TestTags.kt in the tree of @REF@.",
        hint=(
            "Regenerate it: ./gradlew :shared:jvmTest -PupdateGoldens=true, "
            "or push with --no-verify."
        ),
        show_output=True,
    ),
    PrePushStep(
        label="maestro flow catalogue gate",
        script="scripts/check-maestro-flow-catalogue.py",
        script_from="tree",
        pythonpath_from="tree",
        when="if-touched:Maestro/flows/,config/docs/maestro-flows.txt$",
        message=(
            "pre-push FAILED — the Maestro flow catalogue does not match the flows "
            "in the tree of @REF@."
        ),
        hint="Add the new flow to config/docs/maestro-flows.txt, or push with --no-verify.",
        show_output=True,
    ),
    PrePushStep(
        label="issue registry gate",
        script="scripts/check-issue-registry.py",
        args=("--quiet",),
        script_from="tree",
        pythonpath_from="tree",
        when="if-touched:openspec/changes/",
        message=(
            "pre-push FAILED — an openspec change in the tree of @REF@ has no record "
            "in .specify/issues/."
        ),
        hint=(
            "Record it with: python3 scripts/openspec-import-all.py --replace, "
            "or push with --no-verify."
        ),
        show_output=True,
    ),
    # Two places where a commit can make a claim false and nothing says so.
    #
    # The DI fixture table says what to hand a Koin definition that takes its
    # arguments at runtime, and the navigation doc says what opens what. Both
    # are read by something else — a graph that is built, an agent that follows
    # a pointer — and both drifted on 2026-10-22 while the suite that would
    # have caught them was an hour away.
    #
    # They are text gates, so they cost seconds. The check is against the tree
    # being pushed, like every other gate here.
    PrePushStep(
        label="DI fixture gate",
        script="scripts/check-di-fixture-sync.py",
        args=("--root", "@TREE@", "--check"),
        script_from="tree",
        pythonpath_from="tree",
        message=(
            "pre-push FAILED — a Koin definition and the DI fixture table disagree "
            "in the tree of @REF@."
        ),
        hint="Run python3 scripts/check-di-fixture-sync.py to see where, or push with --no-verify.",
    ),
    PrePushStep(
        label="navigation doc gate",
        script="scripts/check-navigation-doc-sync.py",
        args=("--root", "@TREE@", "--check"),
        script_from="tree",
        pythonpath_from="tree",
        message=(
            "pre-push FAILED — the navigation doc claims something the code does not do "
            "in the tree of @REF@."
        ),
        hint=(
            "Run python3 scripts/check-navigation-doc-sync.py to see where, "
            "or push with --no-verify."
        ),
    ),
)

#: The scripts the hook runs that are not gates.
#:
#: They judge nothing about the tree and block nothing on their own: the
#: runner of the gate suite's own tests, and the delegate that asks GitHub
#: what it thinks of the commit being pushed. The hook calls them with logic
#: of their own — a log file, a report of the failing tests — so they are not
#: steps.
PRE_PUSH_MACHINERY: tuple[str, ...] = (
    "scripts/run-gate-tests.py",
    "scripts/ci-verdict.py",
)



def pre_push_step_table() -> str:
    """The steps, one line each, for the hook's loop to read.

    The line is ``|``-separated, and the fields are the ones the hook needs.
    The hook reads the table instead of carrying a block per gate, so a gate
    is added here and nowhere else.

    The separator is a character that appears in none of the fields, and the
    arguments are separated by spaces — an argument with a space in it would
    be two arguments, so no argument may have one.
    """
    lines = []
    for step in PRE_PUSH_STEPS:
        lines.append(
            "|".join(
                (
                    step.label,
                    step.script,
                    " ".join(step.args),
                    step.script_from,
                    step.pythonpath_from,
                    step.when,
                    "yes" if step.show_output else "no",
                    step.message,
                    step.hint,
                )
            )
        )
    return "\n".join(lines)


def pre_push_step_labels() -> tuple[str, ...]:
    """The label of every step, in the order the hook runs them.

    The label is how the hook says what it decided. A step that was run and a
    step that was skipped are both decisions, and both are named — the hook
    tells the declaration which gates it had an answer for, and the
    declaration says which gates it expected an answer for.
    """
    return tuple(step.label for step in PRE_PUSH_STEPS)


def undecided_labels(decided: str) -> tuple[str, ...]:
    """The gates the hook did not decide, and the ones it decided twice.

    The hook runs the gates from the table, so a table of twelve gates and a
    hook that answered for eleven is a hook that did not check one of them.
    The count alone would not say which one, and a gate that nobody ran is
    the failure this arrangement exists to prevent: the hook says "OK" about
    a tree it never looked at.
    """
    answered = [label for label in decided.split(',') if label]
    expected = pre_push_step_labels()
    missing = tuple(label for label in expected if label not in answered)
    repeated = tuple(label for label in expected if answered.count(label) > 1)
    unknown = tuple(label for label in answered if label not in expected)
    return missing + repeated + unknown


if __name__ == '__main__':
    import sys

    if sys.argv[1:] == ['--table']:
        print(pre_push_step_table())
    elif sys.argv[1:2] == ['--verify-decided']:
        labels = sys.argv[2] if len(sys.argv) > 2 else ''
        undecided = undecided_labels(labels)
        if undecided:
            expected = pre_push_step_labels()
            answered = len(expected) - len([label for label in expected if label not in labels])
            print(
                f'the hook answered for {answered} of {len(expected)} declared gates; '
                f'it did not answer for: {", ".join(undecided)}'
            )
            raise SystemExit(1)
        print('OK')
    else:
        print('usage: pre_push.py --table | --verify-decided <labels>', file=sys.stderr)
        raise SystemExit(2)

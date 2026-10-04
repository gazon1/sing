#!/usr/bin/env python3
"""Fail when a coverage report was produced without the tests actually running.

`just cr` wipes every module's kover directory and then asks Kover for a report. The wipe
removes the binary reports; it does not invalidate the *test task*. Gradle decides a task's
outcome from its own inputs, so `:shared:jvmTest` can come back `UP-TO-DATE` or `FROM-CACHE` —
re-execute nothing, contribute no fresh coverage data, and leave the report thinner than the
truth. Nothing in the resulting number says so.

This is not hypothetical. It is the leading hypothesis for #138, where a Koin definition body
sits at 4/18 lines after a test that genuinely resolves it, and it applies to every floor
rather than to that one file.

The check is deliberately narrow: it reads a captured Gradle log and asks one question — did the
test tasks that appear in it actually execute? It does not re-run anything, guess at timings, or
interpret coverage numbers. A gate that cannot fail is worse than no gate, and so is its mirror
image — one that fires on correct code — so:

  * a log with no test task at all is a failure, not a pass: the report was produced from
    nothing, which is the worst case and the easiest to mistake for success;
  * `UP-TO-DATE` and `FROM-CACHE` are failures. The task was in the graph, should have
    contributed, and did not;
  * `NO-SOURCE` is a **warning**. A test task with no sources is a module that has no tests,
    which the coverage report already states honestly as 0% — not an integrity problem with the
    measurement. It was an error in the first version, and running it on a real tree showed that
    failing the whole ratchet over `:androidApp:testDebugUnitTest` blocks everyone forever over a
    fact that was never in doubt;
  * only tasks that are genuinely test tasks are judged. AGP's resource tasks are named after the
    variant they serve — `convertXmlValueResourcesForJvmTest`, `generateResourceAccessorsForJvmTest`
    — and a first version matching `.*[Tt]est$` reported 19 findings on a healthy run, one of them
    real;
  * a task with no outcome word executed. Treating its absence as a failure would make the gate
    red on every healthy run, and a gate that cries wolf is switched off once and never read again.

Usage:  check-coverage-measurement.py <gradle-log> [--allow-cached]

`--allow-cached` downgrades UP-TO-DATE/FROM-CACHE to a warning while still failing on a missing
or NO-SOURCE test task. It exists for deliberate re-measurement without re-execution; the gate
says it was overridden rather than implying a real measurement happened.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass

# `> Task :shared:jvmTest UP-TO-DATE` — the outcome word is absent when the task ran.
TASK_LINE = re.compile(r"^> Task (?P<path>:[\w:.-]+)\s*(?P<outcome>[A-Z-]+)?\s*$")

# A Gradle *test* task, as opposed to anything else whose name happens to end in "Test".
#
# The first version of this was `.*[Tt]est$` and it produced 19 findings on a healthy run, of
# which one was real. AGP names its resource-processing tasks after the variant they serve —
# `convertXmlValueResourcesForJvmTest`, `copyNonXmlValueResourcesForCommonTest`,
# `generateResourceAccessorsForAndroidHostTest`, `prepareComposeResourcesTaskForTest`,
# `javaPreCompileDebugUnitTest` — and every one of them ends in `Test` while none of them runs a
# test. A gate that fires on correct code is the same failure as one that fires on nothing,
# wearing the opposite mask, and it is how a gate gets switched off for good.
#
# So the match is anchored: a test task is `test`, `jvmTest`, or a variant unit test that
# *starts* with `test`. Everything else is not one, whatever it is called.
TEST_TASK = re.compile(r"^(?:test|jvmTest|test[A-Za-z0-9]*UnitTest)$")

# Outcomes that mean "this task did not execute".
NOT_EXECUTED = {"UP-TO-DATE", "FROM-CACHE"}
NO_SOURCE = "NO-SOURCE"


@dataclass(frozen=True)
class Observation:
    """One test task the log says something about."""

    path: str
    outcome: str | None  # None means Gradle printed no outcome word, i.e. it executed

    @property
    def ran(self) -> bool:
        return self.outcome not in NOT_EXECUTED and self.outcome != NO_SOURCE


def parse(log: str) -> list[Observation]:
    """Every test task mentioned in the log, in the order Gradle printed them."""
    seen: list[Observation] = []
    for line in log.splitlines():
        match = TASK_LINE.match(line.strip())
        if match is None:
            continue
        path = match.group("path")
        task_name = path.rsplit(":", 1)[-1]
        if TEST_TASK.match(task_name) is None:
            continue
        seen.append(Observation(path=path, outcome=match.group("outcome")))
    return seen


def module_of(path: str) -> str:
    """`:shared:jvmTest` -> `:shared`."""
    return path.rsplit(":", 1)[0]


def check(log: str, allow_cached: bool = False) -> tuple[list[str], list[str], int]:
    """Return (errors, warnings, downgraded).

    `downgraded` counts test tasks that did not execute and were let through by
    `allow_cached`. It is separate from `warnings` because the two mean opposite things: a
    module with no tests leaves the measurement *valid*, while a cached task means the
    measurement is not one. Reporting both under one heading is how a summary line ends up
    claiming the tasks executed when they did not.
    """
    errors: list[str] = []
    warnings: list[str] = []
    downgraded = 0

    observations = parse(log)
    if not observations:
        return (
            [
                "No test task appears in the Gradle log, so the coverage report was produced "
                "from nothing. This is the worst case and the easiest to mistake for success: "
                "a ratchet comparing floors against it would report 'all floors held' having "
                "measured no code. Re-run with RERUN=1 as an environment variable."
            ],
            [],
            0,
        )

    # A module whose every test task is non-executing and at least one of which reports
    # NO-SOURCE is a module with no tests. `:androidApp` is the live case: `testDebugUnitTest`
    # is NO-SOURCE and its aggregator `test` is UP-TO-DATE, both meaning "there is nothing to
    # run" rather than "something ran and produced nothing".
    #
    # Two conditions, and the second is the one that stops this becoming a standing pass:
    # no task in the module may have *executed*, and none may be FROM-CACHE. A cached task
    # proves the opposite of "no tests" — it means the task ran at some point and produced
    # outputs worth restoring — so a module holding one is a covered module with a measurement
    # problem, which is exactly #146. UP-TO-DATE is ambiguous on its own and is read as
    # benign only when the module also has a NO-SOURCE sibling.
    by_module: dict[str, list[Observation]] = {}
    for observation in observations:
        by_module.setdefault(module_of(observation.path), []).append(observation)

    for module, group in by_module.items():
        cached = any(o.outcome == "FROM-CACHE" for o in group)
        if not cached and not any(o.ran for o in group) and any(o.outcome == NO_SOURCE for o in group):
            names = ", ".join(f"{o.path} ({o.outcome or 'executed'})" for o in group)
            warnings.append(
                f"{module} has no tests ({names}). It contributes no coverage, which the report "
                "below already shows as 0%. Flagged so it is not mistaken for a covered module."
            )
            continue

        for observation in group:
            label = f"{observation.path} ({observation.outcome or 'executed'})"
            if observation.outcome == NO_SOURCE:
                # A warning, not a failure. This one was an error in the first version, on the
                # reasoning that "a module with no tests is a measurement gap, not a zero" —
                # and running it on a real tree showed that reasoning was wrong in practice:
                # failing the whole ratchet over a module with no unit tests blocks everyone
                # forever over a fact the coverage report already states as 0%.
                warnings.append(
                    f"{label} has no sources, so this variant contributes no coverage."
                )
            elif observation.outcome in NOT_EXECUTED:
                message = (
                    f"{label} did not execute. Gradle served it from "
                    f"{'its up-to-date state' if observation.outcome == 'UP-TO-DATE' else 'the build cache'}, "
                    "so no fresh coverage data was written and the report that follows is thinner "
                    "than the truth — with nothing in the number to say so. Re-run with "
                    "RERUN=1 (as an environment variable, not `just cr RERUN=1` — see issue #86) "
                    "to get a real measurement."
                )
                if allow_cached:
                    downgraded += 1
                    warnings.append(message)
                else:
                    errors.append(message)

    return errors, warnings, downgraded


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("log", help="path to the captured Gradle log")
    parser.add_argument(
        "--allow-cached",
        action="store_true",
        help="downgrade UP-TO-DATE/FROM-CACHE to a warning (still fails on missing/NO-SOURCE)",
    )
    args = parser.parse_args(argv)

    try:
        with open(args.log, encoding="utf-8") as handle:
            log = handle.read()
    except OSError as error:
        print(f"check-coverage-measurement: cannot read {args.log}: {error}", file=sys.stderr)
        return 2

    errors, warnings, downgraded = check(log, allow_cached=args.allow_cached)
    for warning in warnings:
        print(f"check-coverage-measurement: WARNING: {warning}", file=sys.stderr)
    for error in errors:
        print(f"check-coverage-measurement: FAIL: {error}", file=sys.stderr)

    if errors:
        print(
            f"\n{len(errors)} problem(s). The coverage numbers from this run do not describe "
            "execution, so no floor comparison from them is meaningful.",
            file=sys.stderr,
        )
        return 1

    if downgraded:
        # Not "OK — the test tasks executed". They did not, and the whole reason this script
        # exists is that a report built from a run that executed nothing is indistinguishable
        # from a real one. An override that still claims execution would reintroduce the exact
        # lie the gate was written to stop, one level up.
        print(
            f"check-coverage-measurement: PASSED WITH WARNINGS — {downgraded} test task(s) did "
            "not execute. The gate was overridden, so the floors below describe a run that wrote "
            "no fresh coverage data. This is not a measurement."
        )
        return 0

    suffix = f" ({len(warnings)} module(s) contributed nothing, as expected)" if warnings else ""
    print(f"check-coverage-measurement: OK — the test tasks in this run executed{suffix}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

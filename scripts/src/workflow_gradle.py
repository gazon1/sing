"""workflow_gradle — one parser for "which Gradle tasks does this surface run".

## Why this module exists

Three gates answer questions about the same command line, and until 2026-10-17
each had its own idea of what a Gradle invocation is:

* ``scripts/check-gate-wiring.py`` — presence of a ``:module:task`` string
  anywhere in a surface, comments stripped (``named_gradle_tasks``)
* ``scripts/check-gradle-test-population.py`` — a command that can run tests,
  plus the ``-Ptest.tags`` selector it carries
* ``scripts/check-build-output-readers.py`` — the same, positioned by step

The second and third disagreed, and the disagreement was silent until their own
sabotage control found it. PR #690 rewrote ``ci.yml``'s main Gradle invocation
from a folded YAML scalar into a ``|`` block of backslash continuations::

    run: |
      set -o pipefail
      ./gradlew --continue \\
        --no-build-cache \\
        :shared:jvmTest \\
        -Ptest.tags=fast,slow \\
        2>&1 | tee build.log

The readers gate fed this parser one line at a time, so ``./gradlew
--continue \\`` was read as a command carrying no task — not test-capable —
while every task sat on continuation lines it never saw. **The run that
produces the results became invisible**, so the job looked like it had exactly
one test-capable invocation and the "nothing can rewrite what nobody produced"
shortcut skipped it whole. The control proved the point by passing a sabotaged
workflow:

    ERROR: gate 'build-output-readers' PASSED a deliberately sabotaged input
    (.github/workflows/ci.yml). It cannot detect.

A derivation that quietly sees part of its input is the defect class this
repository measures itself against. Three copies of one definition is three
places for it to hide.

## What is deliberately not unified

**Presence is not invocation.** ``named_gradle_tasks`` answers "does this task
name appear on this surface at all" — it deliberately matches only the
fully-qualified ``:module:task`` form, anywhere in the file, comments stripped,
and it sees a task written on a continuation line just fine because it is a
whole-file regex. The other two answer "what does this command run", which needs
shell and YAML structure. Sharing the *tokenizer* is worth doing; collapsing the
two policies into one would make Part I report every `--tests`-style mention of
a task as an invocation, which is the "the check invents a defect to look
thorough" failure ``check_gradle-wiring.py`` warns about in its own docstring.

So this module owns the tokenizer, the continuation handling, the test-task
predicate and the selector. Each gate keeps its own policy.

## Usage

    import workflow_gradle as wg

    for invocation in wg.iter_invocations(step_text, origin="ci.yml::tests::Gradle"):
        if invocation.test_capable and invocation.selector != "fast,slow":
            ...

    for step in wg.iter_steps(Path(".github/workflows/ci.yml")):
        if "build/test-results" in step.text:
            ...
"""

from __future__ import annotations

import pathlib
import re
from dataclasses import dataclass
from typing import Iterator

import yaml

#: A Gradle invocation. ``./gw`` is the repository's pass-through wrapper.
_GRADLE = re.compile(r"(?:^|[\s;&|(])(?:\./gradlew|\./gw)\b")

#: `-Ptest.tags=fast,slow` and the space-separated `-Ptest.tags all` both bind.
SELECTOR = re.compile(r"-Ptest\.tags[= ]([^\s;&|)]+)")

#: Shell separators. Fragments are split on these so a pipeline cannot hide an
#: invocation on its right-hand side.
_SPLIT = re.compile(r"[|\n;&]+")

#: A trailing backslash continues the command onto the next line.
_LINE_CONTINUATION = re.compile(r"\\\n\s*")

#: A whole-line shell comment. Trailing `# ...` is deliberately NOT stripped:
#: ``./gradlew foo # runs the tests`` is still an invocation.
_FULL_LINE_COMMENT = re.compile(r"^\s*#")

#: Exact task names that pull in a test task, module-qualified or bare.
EXACT_TEST_TASKS = frozenset(
    {
        "test",
        "check",
        "build",
        "jvmTest",
        "testAndroidHostTest",
        "androidHostTest",
        "connectedDebugAndroidTest",
    }
)

#: Task-name suffixes that mark a test task: `jvmTest`, `testAndroidHostTest`.
#: Anchored on a segment boundary so `latest` and `koverMarkdownTable` are not
#: mistaken for one — the latter is matched as a Kover task instead.
_TEST_SUFFIX = re.compile(r"^(?:[A-Za-z0-9_.-]+:)*(?:[A-Za-z0-9_.-]*[Tt]est)$")

#: Anything under Kover. Kover's aggregate tasks read the binary reports the test
#: tasks produce, so an invocation that runs one can run those tests.
_KOVER = re.compile(r"^:?(?:[A-Za-z0-9_.-]+:)*kover[A-Za-z0-9]*$")


def join_continuations(text: str) -> str:
    """Join the lines a trailing backslash continues onto one line.

    A command written across ``\\`` lines is one command, and a reader that
    splits the text first sees two. The join happens *before* any split, so
    the question "which tasks does this invocation name" has one answer
    instead of one per reader.

    This is the whole rule: a backslash at the end of a line, the newline,
    and the indentation that follows it become a single space. The regex is
    the module's own; it used to be copied into `check-gate-wiring.py`, and a
    copy is a second answer waiting to drift.
    """
    return _LINE_CONTINUATION.sub(" ", text)


@dataclass(frozen=True, slots=True)
class Invocation:
    """One Gradle invocation found in a surface."""

    #: `origin` plus the line the command starts on, for a report a reader can act on.
    location: str
    #: The `-Ptest.tags` value, or `None` for Gradle's default. Absent is a
    #: population of its own, not an absence of one.
    selector: str | None
    #: True when this invocation names a task that can execute tests.
    test_capable: bool
    #: The task tokens it names, in order.
    tasks: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class Step:
    """One workflow step that runs something inline."""

    job: str
    #: Ordinal among the steps that run something, within the job.
    position: int
    name: str
    text: str


def is_test_task(token: str) -> bool:
    """True when this command-line word names a task that runs tests.

    Flags and property assignments are not tasks, which is why ``-Ptest.tags=…``
    cannot make an invocation test-capable on its own.
    """
    if token.startswith("-") or "=" in token:
        return False
    bare = token.rsplit(":", 1)[-1]
    if bare in EXACT_TEST_TASKS:
        return True
    if _KOVER.match(token):
        return True
    return bool(_TEST_SUFFIX.match(bare)) and "test" in bare.lower()


def iter_invocations(text: str, origin: str = "") -> Iterator[Invocation]:
    """Every Gradle invocation in *text*, in order.

    Line continuations are joined **before** the text is split, so a command
    written across ``\\`` lines is one invocation wherever its tasks are. This is
    the defect the module exists to prevent; do not split first.
    """
    joined = join_continuations(text)
    for line_no, line in enumerate(joined.splitlines(), start=1):
        if _FULL_LINE_COMMENT.match(line):
            continue
        for fragment in _SPLIT.split(line):
            fragment = fragment.strip()
            if not _GRADLE.search(fragment):
                continue
            selector_match = SELECTOR.search(fragment)
            tasks = tuple(word for word in fragment.split() if _is_named_task(word))
            where = f"{origin}:{line_no}" if origin else str(line_no)
            yield Invocation(
                location=where,
                selector=selector_match.group(1) if selector_match else None,
                test_capable=any(is_test_task(task) for task in tasks),
                tasks=tasks,
            )


def _is_named_task(token: str) -> bool:
    """A command-line word that reads as a task name.

    Excludes the launcher itself, flags, property assignments, GitHub
    expressions and shell redirects — the three things a ``run:`` block splices
    in rather than names.
    """
    if token.startswith("-") or "=" in token or "${{" in token or ">" in token:
        return False
    if re.match(r"^\d", token):
        return False
    return not _GRADLE.search(token)


def iter_steps(path: pathlib.Path, root: pathlib.Path | None = None) -> Iterator[Step]:
    """Every step of every job in *path* that runs something inline, in order.

    Position is the step's ordinal within the job rather than a line number: a
    ``run:`` scalar may be one folded line, a ``|`` block of continuation lines,
    or a single line, and the shape of the scalar is not a fact about the
    workflow that a gate about workflow order should depend on.
    """
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    for job_name, job in (data.get("jobs") or {}).items():
        if not isinstance(job, dict):
            continue
        position = 0
        for step in job.get("steps") or []:
            if not isinstance(step, dict) or not isinstance(step.get("run"), str):
                continue
            name = str(step.get("name", "run"))
            yield Step(job=str(job_name), position=position, name=name,
                       text=step["run"])
            position += 1


__all__ = [
    "Invocation",
    "Step",
    "is_test_task",
    "join_continuations",
    "iter_invocations",
    "iter_steps",
]
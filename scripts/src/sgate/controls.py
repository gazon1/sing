"""sgate.controls — declarative positive controls for gates.

This module holds **data, not code**. The reason is import topology, not taste:
`_registry.py` is imported by every gate, so anything executable here would be
loaded on every `sgate run-all`, i.e. on every CI run of every gate. A
`subprocess`/`os.utime`/git import chain in the registry would put a tool
dependency inside the module every gate loads — the same defect class this
refactor removes elsewhere (a registry that quietly carries machinery).

So each control is a *description* of the one input change that must make its
gate fail. The appliers — `_replace`, `_regex_sub`, `_append`, `_drop_lines`,
and the fixture seeding — live in `scripts/check-gate-wiring.py`, the only
place that performs them.

Two rules the data enforces, because both have already bitten this repository:

1. A control that matches nothing must be **loud**. Four controls once matched
   nothing after their target moved on, the gate read an untouched file and
   correctly passed, and the harness reported a passing control. Hence
   `Edit.__post_init__` refusing an edit that finds nothing: an empty `old`/`new`
   pair, an empty `pattern`, or a `regex` that declares no `repl` at all. An empty
   *replacement* is not that case — `repl=''` deletes the match, which is a real
   sabotage, so "unset" (`None`) and "empty" (`""`) are different values.
2. A gate that is red on a clean tree must **say so with a date**, not merely
   waive. `Control.needs_clean_run=False` carries `until` for that reason; a
   waiver without an expiry is output nobody reads.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Literal

#: Kinds of control. `exempt` is the only one that carries no input change —
#: it is a documented decision that a gate has no positive control.
ControlKind = Literal["sabotage", "fixture", "gradle", "exempt"]

#: How the edit is applied. Each value names an applier in check-gate-wiring.py.
EditKind = Literal[
    "replace",
    "regex",
    "append",
    "drop_lines",
    "hooks_path",
    "age_dir",
    "drop_second_line",
    "strip_suppression_reason",
]

_EDIT_REQUIRES_TEXT: frozenset[EditKind] = frozenset({"replace", "drop_lines", "append"})
_EDIT_REQUIRES_REGEX: frozenset[EditKind] = frozenset({"regex"})


@dataclass(frozen=True)
class Edit:
    """One input change, described.

    Kept separate from `Control` so an edit is reusable and so the required
    fields for each kind can be validated in one place.
    """

    kind: EditKind
    #: `replace` / `drop_lines`: the text to find (must be non-empty).
    old: str = ""
    #: `replace`: the replacement; `append`: the text to add.
    new: str = ""
    #: `replace` occurrences to touch; -1 means every occurrence.
    count: int = -1
    #: `regex`: the pattern (must be non-empty).
    pattern: str = ""
    #: `regex`: the replacement. `None` means the declaration forgot it; `""` is a
    #: deliberate deletion of the match and is legal (see `__post_init__`).
    repl: str | None = None
    #: `regex`: `re` flags, spelled as a name so the declaration stays readable.
    flags: str = ""
    #: `age_dir`: how many days into the past to set the directory mtime.
    days: int = 0

    def __post_init__(self) -> None:
        if self.kind in _EDIT_REQUIRES_TEXT and not self.old and not self.new:
            raise ValueError(
                f"edit {self.kind!r} needs `old` (the text to find) or `new`; "
                "an edit with nothing to match is a control that proves nothing"
            )
        if self.kind == "replace" and not self.old:
            raise ValueError(
                "edit 'replace' needs `old`: replacing nothing matches every file "
                "and sabotages none of them"
            )
        if self.kind in _EDIT_REQUIRES_REGEX and not self.pattern:
            raise ValueError(f"edit {self.kind!r} needs a non-empty `pattern`")
        # `None` is "the declaration forgot", not "replace with nothing". An empty
        # replacement is the *deletion* edit — `Edit(kind='regex', pattern=p,
        # repl='')` removes the match — and refusing it took down the whole sgate
        # import when the log-interpolation control started declaring one. What must
        # stay loud is an edit that finds nothing, and that is `pattern`'s job: the
        # applier asserts the substitution matched at least once.
        if self.kind == "regex" and self.repl is None:
            raise ValueError("edit 'regex' needs `repl`")
        if self.kind == "age_dir" and self.days <= 0:
            raise ValueError("edit 'age_dir' needs `days` > 0 to move the mtime into the past")
        if self.flags:
            # Fail at declaration time rather than at sabotage time: a typo'd flag
            # name would otherwise reach the gate and quietly change what matched.
            if not hasattr(re, self.flags):
                raise ValueError(f"unknown re flag {self.flags!r}")

    def compiled_flags(self) -> int:
        """The `re` flag mask, resolved at use time from the declared names."""
        mask = 0
        for name in filter(None, self.flags.split("|")):
            mask |= getattr(re, name.strip())
        return mask


@dataclass(frozen=True)
class Control:
    """The input change that must make one gate fail, plus why it is that change.

    `path` is repo-relative. `argv` is the **control-time** command, which is not
    necessarily the registered one: six gates pass extra flags (`--quiet`,
    `--require … --max-age 86400`) and five fixtures substitute `{tmp}` paths at
    run time. That is why `argv` lives here instead of being rebuilt from
    `GateSpec.script`.
    """

    kind: ControlKind
    #: The repo-relative path of the **gate script** this control proves. This is the
    #: join key back to the registry, and deliberately the script path rather than a
    #: display name: the registry names gates in prose ("ADR status policy") while
    #: scripts are identified by file, and one script can back two gates
    #: ("no dead doc refs" and "no dead skill symbols" share
    #: scripts/check-doc-dead-refs.py) while carrying two different controls.
    proves: str = ""
    #: The file (or directory) the control edits. Empty for `exempt`.
    path: str = ""
    #: The control-time command. `{tmp}` is substituted with the fixture root.
    argv: tuple[str, ...] = ()
    #: The edit to apply. `None` for `fixture` (the fixture seeds its own tree)
    #: and for `exempt`.
    edit: Edit | None = None
    #: Why this control, in the words of the person who chose it. Not decoration:
    #: several of these record a regression that already happened.
    why: str = ""
    #: `fixture` only — the setup function name in check-gate-wiring.py that
    #: builds the synthetic tree this control runs against.
    fixture: str = ""
    #: `exempt` only — why this gate has no positive control.
    reason: str = ""
    #: False when the gate is red on a clean tree, so "fails on sabotage"
    #: would prove nothing.
    needs_clean_run: bool = True
    #: For `needs_clean_run=False`: the date this waiver stops being acceptable.
    #: A waiver with no expiry is output nobody reads, which is the defect
    #: `check-gate-liveness` exists to catch, reproduced here.
    until: str = ""
    #: For `needs_clean_run=False`, the alternative to a date: a reference to the
    #: thing that has to change — an issue slug, or the file whose un-triaged state
    #: is what makes the gate red. Required unless `until` is set, so a waiver
    #: always points at something a reader can go and check.
    tracked_by: str = ""

    def __post_init__(self) -> None:
        if self.kind == "exempt":
            if not self.proves:
                raise ValueError(
                    "an exemption must name the script it exempts (`proves`)"
                )
            if not self.reason:
                raise ValueError(
                    f"{self.proves}: an exemption must state its reason — "
                    "an unexplained gap reads as an oversight"
                )
            return
        if not self.proves:
            raise ValueError(
                f"{self.kind} control must name the gate script it proves (`proves`); "
                "a control with nothing to prove cannot be checked for coverage"
            )
        if not self.argv:
            raise ValueError(f"{self.kind} control needs the command it runs")
        if self.kind == "fixture":
            if not self.fixture:
                raise ValueError("fixture control needs `fixture` naming its setup")
            if self.edit is not None:
                raise ValueError("fixture control seeds a tree; it does not also edit one")
            return
        if not self.path:
            raise ValueError(f"{self.kind} control needs `path`")
        if self.kind == "gradle":
            return
        if self.edit is None:
            raise ValueError(f"{self.kind} control needs an `edit`")
        if self.needs_clean_run is False and not (self.until or self.tracked_by):
            # A fixture never runs against the clean tree — it builds its own — so
            # "red on a clean tree" is not a condition it can be in, and the rule
            # does not apply to it.
            if self.kind == "fixture":
                return
            raise ValueError(
                f"{self.path}: needs_clean_run=False needs an `until` date or a "
                "`tracked_by` reference — a waiver that points at nothing is how a "
                "permanently-red advisory gate becomes noise nobody reads"
            )

    def argv_for(self, tmp: Path | None = None) -> list[str]:
        """The control-time command, with `{tmp}` substituted when given."""
        root = "" if tmp is None else str(tmp)
        return [a.replace("{tmp}", root) for a in self.argv]


@dataclass(frozen=True)
class Parity:
    """A declared CI/local asymmetry for a gate itself, and the reason it is allowed."""

    #: `ci` — CI only. `local` — check.sh/just only. `both` — the default.
    scope: Literal["ci", "local", "both"] = "both"
    #: The leading argument that distinguishes the two invocations.
    arg: str = ""
    #: Why the asymmetry is correct. An empty reason is rejected by the gate,
    #: not by the dataclass, so that the error names the gate.
    why: str = ""
    #: Where the asymmetry is visible: a repo-relative path, optionally with an
    #: `#anchor` naming the step or the text inside it. Required when `scope` is
    #: not `both` — an asymmetry nobody can go and look at is a claim, and a
    #: claim that cannot be checked is the thing the parity gate exists to
    #: replace.
    tracked_by: str = ""


@dataclass(frozen=True)
class GradleTaskParity:
    """A declared CI/local asymmetry for a Gradle task, and the reason it is allowed.

    Used by ``check_gradle_task_parity`` in check-gate-wiring.py to validate
    that every Gradle task named by a surface is named by the right surfaces.
    """

    #: `ci` — CI only. `local` — check.sh/just only. `covered` — CI reaches
    #: the task without spelling it (e.g. through a composite task), so check.sh
    #: must still name it. `both` — the default (named by both surfaces).
    scope: Literal["ci", "local", "covered", "both"] = "both"
    #: Why the asymmetry is correct. An empty reason is rejected by the gate.
    why: str = ""


__all__ = ["Control", "ControlKind", "Edit", "EditKind", "GradleTaskParity", "Parity"]
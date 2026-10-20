"""sgate.gate_reachability — which gate scripts a surface can actually reach.

The question this answers is "does this gate run?", and it is asked twice:

- by `check-gate-wiring.py`, over every surface a gate may be named from — the
  shell registry, the workflows, `check.sh`, the `just` recipes;
- by `scripts/check-gate-registries.py`, over the gate lines of the shell
  registry alone, to tell a registered gate that runs from one that only exists.

Both need the same three things: a scan that reads a surface without reading
its prose, a resolution of `sgate <name>` to the script that command runs, and
a bounded walk of the delegation a gate script performs on another. The walk
was written twice — once in each place — and the two copies were free to
disagree about what "reachable" means. They live here now.

The walk is bounded because a cycle must terminate: two shims that exec each
other would otherwise loop forever, and a gate that hangs is not a better
outcome than a gate that is wrongly reported.

The module takes the repository root as a parameter. It must: `sgate` is
installed as an editable package, and a sibling worktree can be what is
installed — a module that resolved the root from `sgate.repo` would read a
different checkout's tree and report a clean bill of health about the wrong
repository.
"""
from __future__ import annotations

import pathlib
import re
from typing import Callable

#: `sgate <command>` — a gate delegated to the sgate CLI. The command name may
#: contain a dash — `sgate run-all` is the driver every surface invokes — and a
#: character class without one matched the `run` in `run-all`, which resolved to
#: nothing and, under the old blanket fallback, marked all 32 registry scripts
#: reachable.
SGATE_INVOCATION = re.compile(
    r"(?:^|[\s'\"(=|])sgate\s+([a-z][a-z0-9_-]*)", re.MULTILINE
)

#: `exec python3 scripts/x.py` / `exec bash scripts/x.sh` — a shim delegating.
#: The optional prefix accepts any shell variable, not just `$ROOT`: the shim in
#: this repository uses `"$ROOT/scripts/…"`, and a scanner written against that one
#: spelling silently misses `$PWD/` and `${HERE}/`, which is how a reachable gate
#: gets reported as an orphan and an editor "fixes" a gate that was never broken.
SHIM_DELEGATION = re.compile(
    r"exec\s+(?:python3|bash|sh)\s+[\"']?"
    r"(?:\$\{?[A-Za-z_][A-Za-z0-9_]*\}?/)?(?:scripts/)?"
    r"(check[-_][\w.-]+\.(?:py|sh))"
)

#: Any mention of `scripts/check-*.py|.sh` inside a gate script's own text. Broader
#: than `SHIM_DELEGATION` on purpose: gate-to-gate invocation is how one gate ends
#: up running another, and it is invisible to a scanner that only reads surfaces.
#: The path must be in *command position* — preceded by an interpreter, a shell, or
#: `./`. Bare prose in a docstring does not count, and that distinction is the whole
#: reason this is narrower than "any mention": `check-gate-liveness.py` writes
#: "check-backlog-issue-refs.py went with the removed backlog-issue-refs gate" in its
#: exemption text, and matching that would keep a retired gate reachable by accident
#: while appearing to check.
GATE_TO_GATE_CALL = re.compile(
    r"(?:python3\s+|bash\s+|sh\s+|\./)(scripts/check[-_][\w.-]+\.(?:py|sh))"
)

#: A gate script on disk: `scripts/check-*.py` or `.sh`.
GATE_CANDIDATE = re.compile(r"check[-_][\w.-]+\.(?:py|sh)$")

#: A script named in a command, in command position.
SCRIPT_INVOCATION = re.compile(
    r"(?:^|[\s'\"(=|])(?:python3\s+)?(?:\./)?"
    r"(scripts/check[-_][\w.-]+\.(?:py|sh))"
)


def strip_comment_lines(text: str) -> str:
    """Drop the lines that are comments, keeping the line structure.

    Comments are prose, and prose mentions commands. `.github/actions/
    setup-repo-tools/action.yml` says "…sgate and its parsers…" in a comment,
    which the invocation regex reads as a command called `and`.
    """
    return "\n".join(
        "" if line.lstrip().startswith("#") else line for line in text.splitlines()
    )


def follow_delegation(root: pathlib.Path, invoked: set[str]) -> set[str]:
    """The scripts the invoked scripts run themselves.

    A gate that runs another gate reaches it: `check-detekt-registrations.sh`
    invokes `check-doc-dead-refs.py` by path, and that is the only thing running
    it — so scanning surfaces alone reported it as an orphan while it executed on
    every run. The walk is bounded, so a gate-to-gate cycle still terminates.
    """
    reached = set(invoked)
    frontier = list(invoked)
    for _ in range(3):
        found: set[str] = set()
        for rel in frontier:
            path = root / rel
            try:
                body = path.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            for match in SHIM_DELEGATION.finditer(body):
                candidate = f"scripts/{match.group(1)}"
                if (root / candidate).is_file() and candidate not in reached:
                    found.add(candidate)
            for match in GATE_TO_GATE_CALL.finditer(body):
                candidate = match.group(1)
                if (root / candidate).is_file() and candidate not in reached:
                    found.add(candidate)
        if not found:
            break
        reached |= found
        frontier = sorted(found)
    return reached


def reachable_gate_scripts(
    root: pathlib.Path,
    surfaces: list[pathlib.Path],
    backing: dict[str, list[str]],
    module_backed: Callable[[], set[str]] | None = None,
    non_gate_commands: frozenset[str] = frozenset(),
    run_all_scripts: Callable[[], list[str]] | None = None,
) -> tuple[set[str], list[str]]:
    """The gate scripts a surface can reach, and the invocations that resolved to nothing.

    `backing` maps an `sgate <command>` to the scripts that command runs.
    `module_backed` names the commands that are gates implemented as a module
    rather than a script — they run something, back no `scripts/check-*` file,
    and are not typos. It is a callable, and so is `run_all_scripts`: both read
    the registry, and reading the registry is not free — it resolves which
    checkout this gate is talking about. A caller that points the scan at a
    synthetic tree must not have the registry read from the real one behind its
    back.
    """
    invoked: set[str] = set()
    unresolved: list[str] = []
    for surface in surfaces:
        try:
            text = surface.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        text = strip_comment_lines(text)
        for match in SCRIPT_INVOCATION.finditer(text):
            invoked.add(match.group(1))
        for match in SGATE_INVOCATION.finditer(text):
            cmd = match.group(1)
            scripts = backing.get(cmd)
            if scripts is None and module_backed and cmd in module_backed():
                continue
            if scripts is None and cmd == "run-all" and run_all_scripts is not None:
                scripts = run_all_scripts()
            if scripts:
                invoked.update(scripts)
            elif cmd not in non_gate_commands:
                # Loud rather than broad. A name that resolves to nothing is either a
                # command that runs no check script (info, render-registry) or a typo,
                # and the difference matters: the old behaviour answered both by
                # marking all 32 registry scripts reachable.
                unresolved.append(f"{surface.relative_to(root)}: sgate {cmd}")
    return follow_delegation(root, invoked), unresolved


def covered_scripts(
    root: pathlib.Path,
    shell_gates: list[tuple[str, str, str]],
    registry_by_name: dict[str, object],
) -> set[str]:
    """The scripts the gate lines of the shell registry run.

    Three steps, in order: a script named in a gate command is run by that
    gate; `sgate <name>` runs whatever the registry says that name is, so the
    command resolves through the registry to a script; and a script that a
    covered script runs is itself covered — the delegation case, where a cluster
    gate names its members in its own source.
    """
    covered: set[str] = set()
    for _mode, _name, command in shell_gates:
        for match in re.finditer(
            r"(?:^|[\s'\"])((?:scripts/)?(?:check|gen)[-_][\w.-]+\.(?:py|sh))", command
        ):
            covered.add(match.group(1))
        tokens = command.split()
        if tokens and tokens[0] == "sgate" and len(tokens) > 1:
            spec = registry_by_name.get(tokens[1])
            script = getattr(spec, "script", None)
            if script:
                covered.add(str(script))
    # A cluster wrapper names its members in its own source — as a command
    # string in a list, not in command position — and it runs them. The strict
    # walk above reads command position only, because a prose mention must not
    # make a retired gate look reachable; here the question is different: a
    # script a covered script *names* is one the covered script runs.
    named = re.compile(r"scripts/(?:check|gen)[-_][\w.-]+\.(?:py|sh)")
    frontier = list(covered)
    for _ in range(2):
        found: set[str] = set()
        for rel in frontier:
            path = root / rel
            try:
                body = path.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            for match in named.finditer(body):
                candidate = match.group(0)
                if (root / candidate).is_file() and candidate not in covered:
                    found.add(candidate)
        if not found:
            break
        covered |= found
        frontier = sorted(found)
    return covered

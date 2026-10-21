"""emulator.maestro._policy — what the host bridge is allowed to run.

The bridge (see `emulator.maestro._bridge`) executes device commands on behalf
of a Maestro script. Which commands it may execute is a policy question, and
the policy lives in a file — `config/maestro/bridge-policy.yaml` — rather than
in a Python literal, because the set grows with the flows: a flow that needs
`settings put` should not have to edit the matcher to get it.

THE RULE, and the whole point of this module: a command is allowed only when
the WHOLE command matches one of the policy's patterns. A prefix is not a
match. `run-as com.singularity.todo ; rm -rf /` begins with an allowed command
and is refused anyway, because the device's shell would run the second half as
the *shell* user rather than the app's.

That rule lives in the code — `fullmatch`, never `match` and never `search` —
and the policy file may not ask for anything else: a policy whose `match` is
not `whole` is rejected, and so is a pattern that contains `.*` or `.+`,
which is a prefix match wearing a different hat. The rule is recorded in
`docs/decisions/2026-10-28-the-bridge-policy-is-a-file.md`.

Fail-closed: a policy that cannot be read, cannot be parsed or names another
app allows *nothing*. A bridge whose policy is broken must refuse commands,
not fall back to a wider set.
"""
from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path

import yaml

from emulator._loguru import get_logger
from emulator.maestro._types import APP_ID, ROOT

#: Where the policy lives, relative to the repository root.
POLICY_RELPATH = Path("config") / "maestro" / "bridge-policy.yaml"

#: Environment variable that overrides the policy location. For a test, or for
#: a runner that keeps its policy outside the checkout.
POLICY_ENV_VAR = "MAESTRO_BRIDGE_POLICY"

#: The only match mode the loader accepts.
MATCH_WHOLE = "whole"

#: The policy version this loader understands.
POLICY_VERSION = 1

#: The placeholder a pattern may use to refer to the app's package id.
APP_ID_PLACEHOLDER = "{app_id}"


class PolicyError(RuntimeError):
    """The policy is missing, malformed, or asks for something unsafe."""


@dataclass(frozen=True)
class PolicyEntry:
    """One allowed shape of a device command."""

    id: str
    pattern: str
    why: str
    regex: re.Pattern[str]

    def matches(self, command: str) -> bool:
        """Whether the WHOLE command is this shape.

        `fullmatch`, not `match`: a command that starts with this shape and
        carries more arguments is a *different* command, and it is not allowed.
        """
        return self.regex.fullmatch(command) is not None


@dataclass(frozen=True)
class Policy:
    """The set of device commands the bridge may run."""

    app_id: str
    entries: tuple[PolicyEntry, ...]
    source: Path

    def allows(self, command: str) -> bool:
        """Whether the bridge may run this command.

        The command is normalised first (see `normalize`); a command that
        cannot be normalised is refused.
        """
        normalized = normalize(command)
        if normalized is None:
            return False
        return any(entry.matches(normalized) for entry in self.entries)

    def ids(self) -> tuple[str, ...]:
        """The ids of the allowed shapes, in policy order."""
        return tuple(entry.id for entry in self.entries)


def normalize(command: str) -> str | None:
    """The command as the bridge compares it, or `None` if it cannot be one.

    Only the whitespace around the command is removed. Nothing inside it is
    rewritten: collapsing whitespace would change the script inside
    `sh -c '...'`, and a command carrying a control character is refused
    rather than cleaned, because a newline is how a second command is smuggled
    in.
    """
    stripped = command.strip()
    if not stripped:
        return None
    if any(ord(char) < 0x20 or ord(char) == 0x7F for char in stripped):
        return None
    return stripped


def default_policy_path() -> Path:
    """Where the policy is read from.

    `MAESTRO_BRIDGE_POLICY` wins when it is set; otherwise the policy in the
    checkout the runner was started from.
    """
    override = os.environ.get(POLICY_ENV_VAR)
    if override:
        return Path(override)
    return ROOT / POLICY_RELPATH


def load_policy(path: Path | None = None) -> Policy:
    """Read a policy file and compile it.

    Raises `PolicyError` for anything that would make the policy unsafe or
    ambiguous: an unreadable file, a shape that is not a mapping, a pattern
    that is not a valid regular expression, a `match` mode other than `whole`,
    or an app id that is not this app's.
    """
    source = path if path is not None else default_policy_path()
    try:
        text = source.read_text(encoding="utf-8")
    except OSError as exc:
        raise PolicyError(f"the policy at {source} cannot be read: {exc}") from exc
    try:
        document = yaml.safe_load(text)
    except yaml.YAMLError as exc:
        raise PolicyError(f"the policy at {source} is not valid YAML: {exc}") from exc
    return parse_policy(document, source)


def parse_policy(document: object, source: Path) -> Policy:
    """Turn a parsed policy document into a `Policy`, or refuse it."""
    if not isinstance(document, dict):
        raise PolicyError(f"the policy at {source} is not a mapping")
    version = document.get("version")
    if version != POLICY_VERSION:
        raise PolicyError(
            f"the policy at {source} declares version {version!r}, "
            f"this runner understands {POLICY_VERSION}"
        )
    mode = document.get("match")
    if mode != MATCH_WHOLE:
        raise PolicyError(
            f"the policy at {source} asks for match mode {mode!r}; "
            f"the only mode is {MATCH_WHOLE!r} — a prefix is not a match"
        )
    app_id = document.get("app_id")
    if not isinstance(app_id, str) or not app_id:
        raise PolicyError(f"the policy at {source} does not name an app")
    if app_id != APP_ID:
        raise PolicyError(
            f"the policy at {source} names {app_id!r}; this bridge may only "
            f"reach {APP_ID!r}"
        )
    commands = document.get("commands")
    if not isinstance(commands, list) or not commands:
        raise PolicyError(f"the policy at {source} lists no commands")
    entries: list[PolicyEntry] = []
    seen: set[str] = set()
    for raw in commands:
        entry = _parse_entry(raw, source, app_id)
        if entry.id in seen:
            raise PolicyError(
                f"the policy at {source} lists the shape {entry.id!r} twice"
            )
        seen.add(entry.id)
        entries.append(entry)
    return Policy(app_id=app_id, entries=tuple(entries), source=source)


def _parse_entry(raw: object, source: Path, app_id: str) -> PolicyEntry:
    """Turn one policy entry into a `PolicyEntry`, or refuse it."""
    if not isinstance(raw, dict):
        raise PolicyError(f"the policy at {source} has a command that is not a mapping")
    entry_id = raw.get("id")
    pattern = raw.get("pattern")
    why = raw.get("why", "")
    if not isinstance(entry_id, str) or not entry_id:
        raise PolicyError(f"the policy at {source} has a command without an id")
    if not isinstance(pattern, str) or not pattern:
        raise PolicyError(f"the policy at {source} has no pattern for {entry_id!r}")
    if not isinstance(why, str):
        raise PolicyError(f"the policy at {source} has a non-text why for {entry_id!r}")
    expanded = pattern.replace(APP_ID_PLACEHOLDER, re.escape(app_id))
    # A `.*` in a pattern is a prefix match in disguise: `run-as {app_id} .*`
    # would swallow everything after the package id, and the command it allows
    # is not the command the entry claims to describe. The shape has to be
    # spelled out — a negated class such as `[^']*` is how a variable part is
    # written without letting it run to the end of the line.
    for greedy in (".*", ".+"):
        if greedy in expanded:
            raise PolicyError(
                f"the policy at {source} has a pattern for {entry_id!r} that "
                f"contains {greedy!r}: it would match anything after the shape "
                f"it names, and the shape has to be spelled out"
            )
    try:
        compiled = re.compile(expanded)
    except re.error as exc:
        raise PolicyError(
            f"the policy at {source} has an invalid pattern for {entry_id!r}: {exc}"
        ) from exc
    return PolicyEntry(id=entry_id, pattern=pattern, why=why, regex=compiled)


def empty_policy(source: Path | None = None) -> Policy:
    """A policy that allows nothing.

    This is what a broken policy degrades to: a bridge whose policy cannot be
    read refuses every command rather than falling back to a wider set.
    """
    return Policy(
        app_id=APP_ID,
        entries=(),
        source=source if source is not None else default_policy_path(),
    )


#: The policy read last, and the path it was read from. The path is part of
#: the key, so a test that points ``MAESTRO_BRIDGE_POLICY`` somewhere else
#: gets that policy rather than the cached one.
_DEFAULT_POLICY: tuple[Path, Policy] | None = None


def default_policy() -> Policy:
    """The policy the bridge runs with, read once per path.

    Fails closed: a policy that cannot be read or parsed allows *nothing*.
    The runner loads the policy itself and refuses to start when it is broken
    (see `emulator.maestro._cli`); this fallback is for the callers that
    only have a command to check.
    """
    global _DEFAULT_POLICY
    path = default_policy_path()
    cached = _DEFAULT_POLICY
    if cached is not None and cached[0] == path:
        return cached[1]
    try:
        policy = load_policy(path)
    except PolicyError as exc:
        get_logger().error(f"The bridge policy could not be loaded: {exc}")
        policy = empty_policy(path)
    _DEFAULT_POLICY = (path, policy)
    return policy

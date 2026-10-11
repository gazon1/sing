"""sgate.commands.base — base class and registration helpers for all gate Click commands."""
from __future__ import annotations

import click
from typing import ClassVar

# Standard exit codes (declared in every gate's docstring)
CLEAN = 0
VALIDATION_FAILURE = 1
IO_ERROR = 2
CONFIG_ERROR = 3
KIWI_UNAVAILABLE = 4
INTERNAL_ERROR = 5


class GateCommand(click.Command):
    """Base Click command for all sgate gate commands.

    Subclasses must set:
    - KIWI_REQUIRED: ClassVar[bool] — True if this gate needs Kiwi TCMS
    """

    KIWI_REQUIRED: ClassVar[bool] = False


def register(cmd: click.Command, name: str | None = None) -> None:
    """Register a Click command with the top-level sgate CLI group.

    Idempotent: skipping if the command name is already registered.
    Called by each command module at import time so that `sgate <name>`
    works after `import sgate.commands.<name>`.
    """
    from sgate import cli

    cmd_name = name or cmd.name
    if cmd_name not in cli.main.commands:
        cli.main.add_command(cmd, name=name)


def group() -> click.Group:
    """Return the top-level CLI group.

    Use as: from sgate.commands.base import main; main.add_command(...)
    """
    from sgate import cli

    return cli.main

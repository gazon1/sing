"""sgate.commands.base — base class and registration helpers for all gate Click commands."""
from __future__ import annotations

from typing import Callable, TypeVar

import click

# Standard exit codes (declared in every gate's docstring)
CLEAN = 0
VALIDATION_FAILURE = 1
IO_ERROR = 2
CONFIG_ERROR = 3
KIWI_UNAVAILABLE = 4
INTERNAL_ERROR = 5

F = TypeVar("F", bound=Callable[..., object])


class GateCommand(click.Command):
    """Base Click command for all sgate gate commands.

    Subclasses must set:
    - KIWI_REQUIRED: ClassVar[bool] — True if this gate needs Kiwi TCMS
    """

    KIWI_REQUIRED: ClassVar[bool] = False


def run(cli_fn: Callable[..., object], argv: list[str] | None = None) -> int:
    """Canonical runner for a Click CLI function.

    Click 8.x in standalone_mode=False returns the ctx.exit() argument as
    the function result; it does NOT raise SystemExit. The function result
    is the exit code.

    Usage in each gate script's main()::

        def main(argv=None) -> int:
            return run(cli.main, argv)

    This replaces the 4-line pattern::

        def main(argv=None) -> int:
            try:
                return cli.main(standalone_mode=False, args=argv)
            except SystemExit:
                raise

    Args:
        cli_fn: The Click command's ``main`` method.
        argv: Command-line arguments (default: sys.argv[1:]).

    Returns:
        The exit code (0 = clean, non-zero = failure).
    """
    return cli_fn(standalone_mode=False, args=argv)


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

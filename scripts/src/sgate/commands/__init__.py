"""sgate.commands — Click command modules, one per gate.

Each module exposes a @main.command() decorator. The cli.py module
discovers and registers them via importlib.

Generated shim modules (45) are replaced by _registry.GATES + make_command().
Custom gates (hand-written Click commands) remain as individual modules.
"""
from __future__ import annotations

# Register all registry-based gate commands.
from sgate.commands import _registry

_registry.register_all()

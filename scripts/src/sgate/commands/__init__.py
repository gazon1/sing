"""sgate.commands — Click command modules, one per gate.

Each module exposes a @main.command() decorator. The cli.py module
discovers and registers them via importlib.
"""
from __future__ import annotations

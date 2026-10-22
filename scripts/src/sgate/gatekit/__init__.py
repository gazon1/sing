"""sgate.gates — the pieces every gate is built from.

Forty-one gate scripts, fifteen thousand lines of Python, and every one of
them had its own idea of how to parse a markdown table, how to read a Kotlin
source, and how to answer the three questions a caller asks of a command:
did it pass, what did it find, and what do I exit with.

This package holds those three answers once:

  * `sgate.gates.findings` — what a gate found, and how sure it is;
  * `sgate.gates.markdown` — the tables a document claims things in;
  * `sgate.gates.kotlin` — what the sources declare and mention;
  * `sgate.gates.cli` — the shape every gate command has.

A gate that uses them is a gate that does one thing: compare a fact in a
document with a fact in the code, and say whether they agree.

ADR `docs/decisions/2026-10-22-the-gate-suite-runs-this-checkout.md`.
"""
from __future__ import annotations

from sgate.gatekit.cli import gate_command
from sgate.gatekit.findings import (
    ADVISORY,
    BLOCKING,
    Finding,
    advisory_findings,
    blocking_findings,
)
from sgate.gatekit.markdown import code_spans, rows_matching, table_rows, type_names
from sgate.gatekit.paths import assert_inside, generated_path, is_inside
from sgate.gatekit.kotlin import (
    declared_classes,
    declared_objects,
    link_matchers,
    link_specs,
    kotlin_files,
)

__all__ = [
    "ADVISORY",
    "BLOCKING",
    "Finding",
    "advisory_findings",
    "blocking_findings",
    "code_spans",
    "declared_classes",
    "assert_inside",
    "declared_objects",
    "gate_command",
    "generated_path",
    "is_inside",
    "kotlin_files",
    "link_matchers",
    "link_specs",
    "rows_matching",
    "table_rows",
    "type_names",
]

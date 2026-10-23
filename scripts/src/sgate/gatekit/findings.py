"""sgate.gates.findings — what a gate found, and how sure it is.

A gate that reports one kind of finding has to decide, once, how loudly to
report it. Two kinds showed up while this module was written:

* A **fact** the code contradicts — a document names a type the code does not
  have. The reader is wrong and the build should say so.
* A **guess about prose** — a paragraph that mentions a thing and a verb is
  read as a claim. The reader is probably wrong, and probably.

The second kind is reported and does not fail the build. A gate that blocks on
a guess blocks on the wrong thing, and the cost of that is a build that is red
for a sentence nobody has to fix.

The severity is a property of the *finding*, not of the gate: one gate reports
both kinds, and the gate's mode in the registry says whether the gate as a
whole blocks.
"""
from __future__ import annotations

from dataclasses import dataclass

#: A finding that states a fact the code contradicts. It fails the build.
BLOCKING = "blocking"

#: A finding that states a guess about prose. It is reported and does not fail
#: the build.
ADVISORY = "advisory"


@dataclass(frozen=True)
class Finding:
    """One thing a gate found."""

    severity: str
    message: str

    @property
    def is_blocking(self) -> bool:
        """Whether this finding fails a `--check` run."""
        return self.severity == BLOCKING

    def __str__(self) -> str:
        return self.message


def blocking_findings(findings: list[Finding]) -> list[Finding]:
    """The findings that fail a `--check` run."""
    return [f for f in findings if f.is_blocking]


def advisory_findings(findings: list[Finding]) -> list[Finding]:
    """The findings that are reported and do not fail a `--check` run."""
    return [f for f in findings if not f.is_blocking]

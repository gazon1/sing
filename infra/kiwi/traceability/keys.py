"""``TestKey`` and the one normalisation function both sides of the join use.

The join between "the scenario a test claims" (read from **source**) and "the
test that ran" (read from **JUnit XML**) is the fragile part of this whole
system, and the XML is messier than "the classname". Measured on this repo's own
output, not assumed:

| Source            | ``classname``                  | ``name``                          |
|-------------------|--------------------------------|-----------------------------------|
| ``shared`` jvmTest| ``com.singularity.todo.arch.FooTest`` | ``some_test()[jvm]``  |
| ``desktopApp``    | ``com.singularity.todo.FooTest``     | ``some_test()``          |
| Maestro           | flow-derived                   | flow ``name:``                   |

So the ``name`` carries a trailing ``()`` and, on KMP targets, a ``[jvm]``
suffix; ``@Nested`` adds ``$`` to the classname. Per-test linkage cannot ignore
that, and a scanner that normalises differently from the result reader produces
a matrix that is silently empty rather than loudly wrong.

One function (:func:`normalise_test_name`) is therefore used by *both*
consumers. If a second normalisation appears anywhere, this file is wrong.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

__all__ = ["TestKey", "normalise_test_name", "normalise_classname"]

# `[jvm]`, `[android]`, `[desktop]`, ... — the KMP target suffix Gradle appends.
# Matched at the end only, and a name is never *only* a suffix, so a real
# bracketed parameter such as `test[1]` is deliberately not stripped here.
_PLATFORM_SUFFIX = re.compile(r"\[[A-Za-z0-9_]+\]$")

# The trailing `()` Kotlin/JUnit always appends to a parameterless test method.
# Requires the parens to be the very last thing, so a genuine name like
# `assert_(x)` survives.
_EMPTY_PARENS = re.compile(r"\(\)$")


def normalise_test_name(raw: str) -> str:
    """Strip the noise Gradle appends to a test method name.

    ``"foo()[jvm]"`` -> ``"foo"``, ``"foo()"`` -> ``"foo"``, ``"foo"`` ->
    ``"foo"``. Idempotent, which matters because the desktop shape carries the
    parens and the shared shape carries both.
    """
    name = raw.strip()
    # The suffix goes first: `foo()[jvm]` still ends in `)`+suffix before the
    # parens are visible as a trailing pair.
    name = _PLATFORM_SUFFIX.sub("", name).strip()
    name = _EMPTY_PARENS.sub("", name).strip()
    return name


def normalise_classname(raw: str) -> str:
    """Normalise a ``classname`` attribute, resolving ``@Nested`` ``$`` nesting.

    JUnit writes a nested class as ``Outer$Inner`` while the source declares it
    nested, so the scanner and the reader must meet in the middle. Inner-to-
    outer order is preserved; only the separator is normalised, and the whole
    thing is dotted so a nested class is distinguishable from a top-level one
    by segment count alone.

    No ``@Nested`` classes exist in the current output, so this is handled
    rather than assumed — the first nested test would otherwise be a test that
    passes locally and never links.
    """
    fqcn = raw.strip()
    if not fqcn:
        return ""
    if "$" in fqcn:
        return ".".join(part for part in fqcn.split("$") if part)
    return fqcn


@dataclass(frozen=True, slots=True)
class TestKey:
    """One test, identified the way the join needs it.

    ``fqcn`` is dotted and normalised; ``method`` is the **display name** as
    JUnit reports it, free of ``()`` and of any ``[platform]`` suffix. Equality
    is what the scanner builds and the result reader looks up, so it is the whole
    contract.

    The method field holds the display name rather than the Kotlin ``fun`` name
    because that is what Gradle writes into ``<testcase name>`` — measured on
    this repo's output, not assumed. A key built from ``fun`` matches nothing
    while looking entirely correct, and the only symptom is an empty matrix.
    """

    fqcn: str
    method: str

    @classmethod
    def from_xml(cls, classname: str, name: str) -> "TestKey":
        """Build a key from raw JUnit XML attributes, normalising both."""
        return cls(normalise_classname(classname), normalise_test_name(name))

    def __str__(self) -> str:  # pragma: no cover - debugging aid
        return f"{self.fqcn}.{self.method}"

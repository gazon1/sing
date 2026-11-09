"""The toolchain pins live in exactly one file: mise.toml.

The gate-tooling versions — actionlint, shellcheck, yamllint, lychee, ktlint,
rumdl — are written down once, in `mise.toml`, and mise installs them from
there: on a developer machine and on a runner. The local run and the CI run of
the same gate therefore use the same bytes, because there is only one answer
to read.

This test holds that arrangement in place:

* every gate tool the suite shells out to has a fixed version in mise.toml;
* neither install path repeats a version literal;
* no version literal appears anywhere else in the repository.

The expected values are *derived from mise.toml*, not hard-coded here. A test
that hard-codes the versions would have to be edited every time a pin moves,
and an edit of a test to accommodate a change is exactly the edit that lets a
second copy back in. The literal check therefore asks a different question —
"does this version string appear anywhere but the one file?" — and that question
stays true across a bump.

The one thing this test cannot see is a duplicate that uses a *different*
spelling of the same version (e.g. `1.7` for `1.7.7`). That is what the
`ALLOWED_DUPLICATES` entries below are for: every place in the repository that
still names a pin, with the reason it is allowed to. An entry that is no longer
needed should be removed, and the test fails when a new duplicate appears
anywhere it is not listed.

Run with: python3 -m unittest discover -s scripts/tests
"""
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
MISE_TOML = ROOT / "mise.toml"
RESOLVER = ROOT / "scripts" / "toolchain-version.py"

#: The install paths. Neither may repeat a version: the tools come from mise,
#: and mise reads the pin out of mise.toml.
CONSUMERS = (
    ROOT / "scripts" / "ci" / "setup-local-tools.sh",
    ROOT / ".github" / "actions" / "setup-python-tools" / "action.yml",
)

#: The gate/tooling binaries the gate suite shells out to, and the tool name
#: each one is pinned under in mise.toml.
GATE_TOOLS = (
    "actionlint",
    "shellcheck",
    "yamllint",
    "lychee",
    "ktlint",
    "rumdl",
    # The pkl CLI is what hk reads hk.pkl through: hk shells out to it, so the
    # gate suite shells out to it too.
    "pkl",
)

#: Directories that hold build output, caches or vendored tool state. They are
#: not source and may contain anything.
SKIP_DIRS = frozenset(
    {
        ".git",
        ".gradle",
        ".gradle-user-home",
        ".kotlin",
        ".venv",
        ".venv-bak",
        "__pycache__",
        ".idea",
        ".agent",
        ".zcode",
        ".minimax",
        ".cxx",
        "build",
        "node_modules",
        "out",
        "target",
        "dist",
        "coverage",
        ".pytest_cache",
        ".ruff_cache",
        ".mypy_cache",
        ".rumdl_cache",
    }
)

#: Files that still name a pin, and why that is allowed. None of them installs
#: anything: they are prose (a skill page, an ADR, a change proposal) or a
#: version catalog that happens to hold the same string for another tool. A
#: file that DOES install must read the pin out of mise.toml — that is the rule
#: the two consumers are held to above.
#:
#: Every entry here is a *tracked* file. A generated one (the decisions digest
#: mirrors the ADR text) cannot be an entry: the file does not exist in a fresh
#: checkout, so the entry would fail a check that nothing can satisfy — and
#: the scan would have to know about it anyway. Generated files are skipped by
#: path instead, below.
ALLOWED_DUPLICATES = {
    "gradle/libs.versions.toml": (
        "the version catalog: androidx-appcompat happens to be 1.8.0, the same "
        "spelling as the ktlint pin. Two different tools, two different "
        "releases, one string."
    ),
    ".agents/skills/singularity-todo-quality-tools/SKILL.md": (
        "skill page: prose that tells the reader which versions CI installs"
    ),
    "docs/decisions/2026-10-19-doc-links-gate-lychee.md": (
        "ADR: the historical record of the decision to adopt lychee"
    ),
    "docs/decisions/2026-11-02-ktlint-cli-replaces-the-detekt-formatting-layer.md": (
        "ADR: the historical record of the decision to adopt ktlint CLI, with "
        "the release it was pinned to"
    ),
    "docs/decisions/2026-11-03-the-formatting-rules-have-one-owner.md": (
        "ADR: the historical record naming the ktlint release probed"
    ),
    "openspec/changes/archive/2026-10-25-the-gates-run-on-every-machine/proposal.md": (
        "change proposal: the historical record of the proposal"
    ),
}

#: Generated files the scan skips. They are gitignored: a fresh checkout does
#: not have them, and they are written from the files that are tracked. The
#: decisions digest mirrors the ADR text, so it carries whatever the ADRs
#: carry — including a version literal.
GENERATED_FILES = frozenset(
    {
        "docs/decisions/DIGEST.md",
        "docs/decisions/DIGEST-full.md",
    }
)

#: Files bigger than this are binaries or generated blobs, not source.
_MAX_FILE_BYTES = 1_000_000


def read_pins() -> dict[str, str]:
    """The pinned versions, keyed by tool name. Raises if mise.toml is missing."""
    import tomllib

    with MISE_TOML.open("rb") as handle:
        tools = tomllib.load(handle).get("tools", {})
    return {str(name): str(version) for name, version in tools.items()}


def version_literals(pins: dict[str, str], tools: tuple[str, ...]) -> set[str]:
    """Every spelling of a pin that must not appear outside mise.toml."""
    literals: set[str] = set()
    for tool in tools:
        value = pins.get(tool)
        if value:
            literals.add(value)
            literals.add(value.lstrip("v"))
    return {lit for lit in literals if lit}


def iter_source_files(root: pathlib.Path):
    """Every readable source file under `root`, skipping build output."""
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        for name in sorted(filenames):
            path = pathlib.Path(dirpath) / name
            try:
                if path.stat().st_size > _MAX_FILE_BYTES:
                    continue
            except OSError:
                continue
            yield path


def read_text(path: pathlib.Path) -> str | None:
    """The file's text, or None when it is not text we can read."""
    try:
        data = path.read_bytes()
    except OSError:
        return None
    if b"\x00" in data:
        return None
    return data.decode("utf-8", errors="ignore")


def scan_for_duplicates(root: pathlib.Path, literals: set[str], *, skip: set[str]):
    """`{relative path: [literal, ...]}` for every file that names a pin."""
    hits: dict[str, list[str]] = {}
    for path in iter_source_files(root):
        relative = path.relative_to(root).as_posix()
        if relative in skip or relative in GENERATED_FILES:
            continue
        text = read_text(path)
        if text is None:
            continue
        found = sorted(lit for lit in literals if lit in text)
        if found:
            hits[relative] = found
    return hits


class PinsFileTest(unittest.TestCase):
    """The one file the pins live in."""

    def test_mise_toml_exists(self):
        self.assertTrue(MISE_TOML.is_file(), f"missing {MISE_TOML}")

    def test_every_gate_tool_has_a_fixed_version(self):
        pins = read_pins()
        for tool in GATE_TOOLS:
            with self.subTest(tool=tool):
                version = pins.get(tool)
                self.assertTrue(version, f"{tool} has no pin in {MISE_TOML.name}")
                self.assertNotEqual(
                    version,
                    "latest",
                    f"the pin of {tool} is 'latest' — that is not a version",
                )

    def test_the_resolver_answers_with_the_pinned_version(self):
        # The shell cannot read TOML, so it asks this script. If the answer
        # were not the pin, the formatter in CI would install a version the
        # gate does not expect.
        pins = read_pins()
        for tool in GATE_TOOLS:
            with self.subTest(tool=tool):
                answer = subprocess.run(
                    [sys.executable, str(RESOLVER), tool],
                    capture_output=True,
                    text=True,
                    check=False,
                )
                self.assertEqual(0, answer.returncode, answer.stderr)
                self.assertEqual(pins[tool], answer.stdout.strip())

    def test_the_resolver_refuses_a_tool_that_is_not_pinned(self):
        answer = subprocess.run(
            [sys.executable, str(RESOLVER), "no-such-tool"],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertNotEqual(0, answer.returncode)
        self.assertIn("not pinned", answer.stderr)


class ConsumerTest(unittest.TestCase):
    """The install paths take the tools from mise; neither repeats a version."""

    def test_no_consumer_repeats_a_version_literal(self):
        literals = version_literals(read_pins(), GATE_TOOLS)
        for consumer in CONSUMERS:
            text = consumer.read_text(encoding="utf-8")
            for literal in sorted(literals):
                with self.subTest(consumer=consumer.name, literal=literal):
                    self.assertNotIn(
                        literal,
                        text,
                        f"{consumer.name} repeats the pin {literal} — "
                        "mise.toml is the only place it may appear",
                    )


class DuplicatePinTest(unittest.TestCase):
    """A pin is written once, in mise.toml."""

    def test_no_pin_is_written_outside_mise_toml(self):
        literals = version_literals(read_pins(), GATE_TOOLS)
        skip = {MISE_TOML.relative_to(ROOT).as_posix()}
        # The test derives its expectations from mise.toml, so it holds no
        # literal of its own — but it is skipped anyway, so that adding one
        # (e.g. in a comment) cannot make the test fail on itself.
        skip.add(pathlib.Path(__file__).resolve().relative_to(ROOT).as_posix())

        hits = scan_for_duplicates(ROOT, literals, skip=skip)
        unexpected = {
            path: found
            for path, found in hits.items()
            if path not in ALLOWED_DUPLICATES
        }
        self.assertEqual(
            {},
            unexpected,
            "a toolchain pin is written outside "
            f"{MISE_TOML.relative_to(ROOT).as_posix()}: "
            f"{unexpected}. Move the version into mise.toml and read it "
            "from there. If a site is allowed to name the version, add it to "
            "ALLOWED_DUPLICATES in this test, with the reason.",
        )

    def test_every_allowed_duplicate_still_exists(self):
        # An allowlist entry for a file that has since been fixed is dead
        # weight that hides a future duplicate in the same file.
        for path in ALLOWED_DUPLICATES:
            with self.subTest(path=path):
                self.assertTrue(
                    (ROOT / path).is_file(),
                    f"{path} is allowlisted but does not exist — remove the entry",
                )

    def test_the_scan_actually_finds_a_duplicate(self):
        # A scan that finds nothing proves nothing. This plants a pin in a
        # throwaway tree and asserts the scanner reports it.
        literals = version_literals(read_pins(), GATE_TOOLS)
        planted = sorted(literals)[0]
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / "sub").mkdir()
            (root / "sub" / "copy.txt").write_text(f"install lychee == {planted}\n", encoding="utf-8")
            hits = scan_for_duplicates(root, literals, skip=set())
        self.assertIn("sub/copy.txt", hits)
        self.assertIn(planted, hits["sub/copy.txt"])


if __name__ == "__main__":
    unittest.main()

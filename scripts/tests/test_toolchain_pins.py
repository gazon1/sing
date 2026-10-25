"""The toolchain pins live in exactly one file.

The four gate-tooling versions — lychee, yamllint, shellcheck-py and the
actionlint release — used to be written out twice: once in the local install
path (`scripts/ci/setup-local-tools.sh`) and once in the CI install path
(`.github/actions/setup-python-tools/action.yml`). Two copies of a pin is a pin
that can drift, and the drift does not announce itself: the local run and the
CI run of the same gate simply disagree, which reads as a broken gate rather
than as a version bump.

So the pins now live in `config/toolchain-versions.env`, and this test holds
that arrangement in place:

* the pins file exists and declares exactly the four tools with a version;
* neither consumer repeats a version literal;
* no version literal appears anywhere else in the repository.

The expected values are *derived from the pins file*, not hard-coded here. A
test that hard-codes the versions would have to be edited every time a pin
moves, and an edit of a test to accommodate a change is exactly the edit that
lets a second copy back in. The literal check therefore asks a different
question — "does this version string appear anywhere but the one file?" — and
that question stays true across a bump.

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
import re
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
PINS_FILE = ROOT / "config" / "toolchain-versions.env"

#: The two install paths. Both must read the pins file, neither may repeat it.
CONSUMERS = (
    ROOT / "scripts" / "ci" / "setup-local-tools.sh",
    ROOT / ".github" / "actions" / "setup-python-tools" / "action.yml",
)

#: The four tools the gate suite shells out to, and the key each one is pinned
#: under. The keys are part of the contract: both consumers read them.
EXPECTED_KEYS = (
    "LYCHEE_BIN_VERSION",
    "YAMLLINT_VERSION",
    "SHELLCHECK_PY_VERSION",
    "ACTIONLINT_VERSION",
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
    }
)

#: Files that still name a pin, and why that is allowed. None of them installs
#: anything: they are prose (a skill page, an ADR, a change proposal) or a
#: comment / help message that describes what CI installs. A file that DOES
#: install must read the pins file instead — that is the rule the two
#: consumers are held to above.
#:
#: Every entry here is a *tracked* file. A generated one (the decisions digest
#: mirrors the ADR text) cannot be an entry: the file does not exist in a fresh
#: checkout, so the entry would fail a check that nothing can satisfy — and
#: the scan would have to know about it anyway. Generated files are skipped by
#: path instead, below.
ALLOWED_DUPLICATES = {
    ".agents/skills/singularity-todo-quality-tools/SKILL.md": (
        "skill page: prose that tells the reader which versions CI installs"
    ),
    "docs/decisions/2026-10-19-doc-links-gate-lychee.md": (
        "ADR: the historical record of the decision to adopt lychee"
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

#: A pin file line: KEY=VALUE, plain shell assignment.
_PIN_LINE = re.compile(r"^[A-Z][A-Z0-9_]*=[A-Za-z0-9._+-]+$")

#: Files bigger than this are binaries or generated blobs, not source.
_MAX_FILE_BYTES = 1_000_000


def read_pins() -> dict[str, str]:
    """The pins file as a mapping. Raises if the file is missing."""
    text = PINS_FILE.read_text(encoding="utf-8")
    pins: dict[str, str] = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, _, value = line.partition("=")
        pins[key.strip()] = value.strip()
    return pins


def version_literals(pins: dict[str, str]) -> set[str]:
    """Every spelling of a pin that must not appear outside the pins file.

    The actionlint pin is a GitHub release tag (`v1.7.7`) but the asset it
    downloads is named after the version without the `v` (`1.7.7`), so both
    spellings are searched for.
    """
    literals: set[str] = set()
    for value in pins.values():
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

    def test_pins_file_exists(self):
        self.assertTrue(PINS_FILE.is_file(), f"missing {PINS_FILE}")

    def test_declares_exactly_the_four_tools(self):
        pins = read_pins()
        self.assertEqual(
            sorted(EXPECTED_KEYS),
            sorted(pins),
            f"{PINS_FILE.name} must declare exactly the four pinned tools",
        )

    def test_every_pin_has_a_version(self):
        pins = read_pins()
        for key in EXPECTED_KEYS:
            with self.subTest(key=key):
                self.assertTrue(pins.get(key), f"{key} has no version")

    def test_actionlint_pin_is_a_release_tag(self):
        # The consumers derive the asset name from the tag by dropping the
        # leading "v", so a tag without one would download a file that does not
        # exist.
        pins = read_pins()
        self.assertTrue(
            pins["ACTIONLINT_VERSION"].startswith("v"),
            "the actionlint pin must be a release tag with a leading 'v'",
        )

    def test_the_file_is_safe_to_source(self):
        # Both consumers source it under `set -euo pipefail`. A command
        # substitution that can fail, or an expansion of a variable that may be
        # unset, would take the install down with it. Comments are inert, so
        # only the assignment lines are checked.
        text = PINS_FILE.read_text(encoding="utf-8")
        for raw in text.splitlines():
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            with self.subTest(line=line):
                self.assertNotIn("$(", line, "no command substitution")
                self.assertNotIn("`", line, "no backticks")
                self.assertNotIn("${", line, "no expansion of an unset variable")
                self.assertRegex(line, _PIN_LINE, f"not a plain assignment: {line}")


class ConsumerTest(unittest.TestCase):
    """The two install paths read the pins file; neither repeats it."""

    def test_each_consumer_reads_the_pins_file(self):
        for consumer in CONSUMERS:
            with self.subTest(consumer=consumer.name):
                self.assertTrue(consumer.is_file(), f"missing {consumer}")
                self.assertIn(
                    "toolchain-versions.env",
                    consumer.read_text(encoding="utf-8"),
                    f"{consumer.name} does not read the pins file",
                )

    def test_each_consumer_uses_every_pin_key(self):
        for consumer in CONSUMERS:
            text = consumer.read_text(encoding="utf-8")
            for key in EXPECTED_KEYS:
                with self.subTest(consumer=consumer.name, key=key):
                    self.assertIn(key, text, f"{consumer.name} does not use {key}")

    def test_no_consumer_repeats_a_version_literal(self):
        literals = version_literals(read_pins())
        for consumer in CONSUMERS:
            text = consumer.read_text(encoding="utf-8")
            for literal in sorted(literals):
                with self.subTest(consumer=consumer.name, literal=literal):
                    self.assertNotIn(
                        literal,
                        text,
                        f"{consumer.name} repeats the pin {literal} — "
                        "the pins file is the only place it may appear",
                    )


class DuplicatePinTest(unittest.TestCase):
    """A pin is written once, in the pins file."""

    def test_no_pin_is_written_outside_the_pins_file(self):
        literals = version_literals(read_pins())
        skip = {PINS_FILE.relative_to(ROOT).as_posix()}
        # The test derives its expectations from the pins file, so it holds no
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
            f"{PINS_FILE.relative_to(ROOT).as_posix()}: "
            f"{unexpected}. Move the version into the pins file and read it "
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
        literals = version_literals(read_pins())
        planted = sorted(literals)[0]
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            (root / "sub").mkdir()
            (root / "sub" / "copy.txt").write_text(f"install lychee-bin=={planted}\n", encoding="utf-8")
            hits = scan_for_duplicates(root, literals, skip=set())
        self.assertIn("sub/copy.txt", hits)
        self.assertIn(planted, hits["sub/copy.txt"])


if __name__ == "__main__":
    unittest.main()

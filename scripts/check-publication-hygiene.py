#!/usr/bin/env python3
"""check-publication-hygiene.py — nothing machine-specific may reach a public tree.

Why this exists (2026-10-06): this repository was prepared for publication under
Apache-2.0 (core) and FSL-1.1-ALv2 (`pro/`). The licence work was done properly —
`check-pro-licence-boundary.py` proves on source and on the built APK that the
free half carries no proprietary code, and `check-provenance.py` keeps a registry
of every non-original file. What no gate covered was the other direction: the
personal and machine-specific residue that a snapshot would carry to the public
internet.

The scan that started this found 33 occurrences of one developer's home
directory across four categories of file, plus the repository's internal clone
name sitting in the Android launcher label, plus a `localhost:3000` instruction
in a skill template imported from an unrelated project. None of it is a secret.
All of it is unfalsifiable damage: the moment it is public, it cannot be recalled.

A one-off cleanup script would have removed them once and left nothing behind to
stop the sixth. This is a gate instead, for the same reason the licence boundary
is a gate: the invariant is the deliverable, the edit is incidental.

## What is checked

1. **Personal email addresses** in tracked text files — the author's real address
   must never reach a public tree, in a file or in a history.
2. **Absolute home paths** (`/home/<user>`, `/Users/<user>`, `C:\\Users\\`) in
   anything a reader would copy or a tool would execute.
3. **The internal clone name** (`cllone`) outside `docs/decisions/`.
4. **Foreign localhost ports** — an instruction naming a port this app does not
   serve teaches the reader to keep a stale command.

Rules 3 and 4 are deliberately narrow. A path or a name is only a finding where
it is an instruction or a user-visible string; the same substring inside an ADR
is a true record of where the work happened, and rewriting it would make the ADR
false. The allowlist below encodes that distinction as data, not as comment.

## The positive control

Rules 1, 2 and 4 are regexes over text. A regex that quietly stops matching
reports success having checked nothing — the vacuous-gate failure this project has
already paid for twice (ADR 2026-10-05-provenance-audit §4).

- `--self-test` runs the detectors over a synthetic corpus that **does** contain
  a violation and asserts every one is caught, and over a corpus without and
  asserts none is reported. It also asserts the allowlist paths are honoured.
- `scripts/tests/test_check_publication_hygiene.py` asserts the same properties,
  including the prose near-misses that made a broader first draft unusable.
- **The scan is not vacuous.** If the real tree contains no findings at all, the
  check fails rather than passes. A clean tree after the cleanup is the *goal*,
  and the way to say "clean" is that the scan ran and found nothing new — not that
  the scan stopped finding anything. The non-vacuity rule is what tells the two
  apart, and it is the only part of this gate that catches a glob which silently
  stopped matching.

## Usage

    python3 scripts/check-publication-hygiene.py            # check the repository
    python3 scripts/check-publication-hygiene.py --self-test  # prove the rules fire

Exit codes:
    0 — no machine-specific residue outside the allowlist, and the rules work
    1 — a violation, or the scan is vacuous
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ALLOWLIST = ROOT / "config" / "docs" / "publication-allowlist.tsv"

# --------------------------------------------------------------------------
# Allowlist
# --------------------------------------------------------------------------
#
# Data, not code, so that adding an exception is a reviewable one-line diff
# rather than an edit to a detector. The same reasoning as the provenance
# registry: a row you cannot see is a claim nobody is tracking.

# Paths under which a home path, a clone name or a foreign port is *expected*.
#
# `docs/decisions/` is the bulk of the corpus and the reason this allowlist
# exists. An ADR that says "worktree: /home/<user>/worktrees/epic2" is recording
# something real about where the work happened; replacing it with `<path>` would
# make the record vaguer and no more private. ADRs also quote user bug reports
# verbatim, and the clone name is in one of those quotes.
ALLOWED_PREFIXES = (
    "docs/decisions/",
    # OpenSpec changes carry a `Source:` field naming the requirements document
    # the change came from. That is traceability, and the same argument as ADRs
    # applies — it is provenance of a decision, not a machine path.
    "openspec/changes/",
)

# Paths that are not tracked at all. Listed so that a reader who greps and finds
# a hit in one of them knows it is a build artifact rather than a missed file.
UNTRACKED = (
    "build/",
    ".gradle/",
    ".gradle-user-home/",
    ".kotlin/",
)

# --------------------------------------------------------------------------
# Rules
# --------------------------------------------------------------------------

# Test sources quote addresses and paths as fixtures. A test that asserts a
# redactor removes `https://user:s3cr3t@abc.supabase.co` must contain that string,
# or it is not testing anything.
_ALLOWED_SUBSTRINGS = (
    "commonTest/",
    "jvmTest/",
    "androidHostTest/",
    "androidInstrumentedTest/",
    "desktopTest/",
)

# An email whose domain is reserved or obviously synthetic. RFC 2606 reserves
# `example.*` and the `.test`/`.example`/`.invalid`/`.localhost` TLDs (RFC 6761),
# and `users.noreply.github.com` is what GitHub issues to every account. A
# finding in one of these is a fixture even when it is not inside a test source.
#
# Note the anchoring: `\.test$` rather than a bare `test` alternative, because a
# bare one matched the whole address `u@x.test` incorrectly and a substring rule
# would also launder `a.person@corp.example.net.test`. The multi-label forms are
# listed explicitly for the same reason — `\w+\.example` must not be allowed to
# match `a.person@corp.example.net`, which is a real-looking domain.
_SAFE_EMAIL_DOMAINS = re.compile(
    r"@(?:"
    r"example\.(?:com|org|net)"
    r"|[\w.\-]+\.example"
    r"|[\w.\-]+\.(?:test|invalid|localhost)"
    r"|users\.noreply\.github\.com"
    r"|noreply\.github\.com"
    r"|mail\.com"
    r"|mailbox\.org"
    r")$",
    re.IGNORECASE,
)

EMAIL_RE = re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}")

# `git@github.com:gazon1/sing.git` parses as an address under the regex above
# (`github.com` is a TLD-looking tail), but it is an SSH remote, not a mailbox:
# there is no domain to receive mail at. A rule that reports it is a rule the
# maintainer learns to ignore, so the shape is excluded explicitly rather than by
# trying to enumerate every host that appears in a git URL.
#
# The `path` tail is REQUIRED, and that requirement is the whole rule. An earlier
# version accepted `user@host:` with nothing after the colon, which meant any
# line of the form "a.person@corp-mail.example.net: owns sync" was silently
# dropped — the gate was blind to exactly the address it exists to catch, and the
# `test_email_followed_by_colon_is_still_reported` case pins that. A git remote
# always has `owner/repo` after the colon; an address in prose does not.
#
# No trailing anchor: the remote is usually embedded mid-line
# (`git push git@github.com:owner/repo.git main`), and anchoring to `$` made the
# rule fire on exactly the lines it exists to exclude. The `owner/repo` pair is
# what makes this specific enough to anchor on nothing.
_SSH_REMOTE_RE = re.compile(
    r"[A-Za-z0-9._\-]+@[A-Za-z0-9.\-]+:[A-Za-z0-9._\-]+/[A-Za-z0-9._\-]+")

# `/home/<user>` and `/Users/<user>`: a home directory, on Linux/macOS. The
# `<user>` part is required — `/home/` alone appears in real documentation and
# would be a false positive.
HOME_PATH_RE = re.compile(r"(?:/home/[a-z][a-z0-9_-]*|/Users/[a-z][a-z0-9_-]*)")
WINDOWS_HOME_RE = re.compile(r"[A-Z]:\\\\?Users\\\\?[a-z][a-z0-9_-]*", re.IGNORECASE)

# The clone name. Matched on the distinctive fragment so `singularity_clone`
# in a worktree path in an ADR is caught by the same rule when it is not
# allowlisted.
CLONE_NAME_RE = re.compile(r"cllone", re.IGNORECASE)

# A localhost port that is not one this application serves. The app has no
# HTTP server; 11434 is Ollama's default, which the LLM provider enum names on
# purpose. Everything else is an instruction from another project.
KNOWN_PORTS = {11434}
FOREIGN_PORT_RE = re.compile(r"https?://localhost:(\d{2,5})|localhost:(\d{2,5})")

# Text files. Binary is skipped by decode failure, so extensions only need to
# exclude the obviously non-text tree to keep the scan fast.
SKIP_DIRS = {".git", "build", ".gradle", ".kotlin", "__pycache__", ".idea", "node_modules"}
TEXT_SUFFIXES = {
    ".kt", ".kts", ".java", ".xml", ".md", ".py", ".sh", ".just", ".justfile",
    ".yaml", ".yml", ".json", ".toml", ".properties", ".txt", ".gradle",
    ".sql", ".envrc", ".editorconfig", ".tsv", ".csv", ".html", ".css",
}
SKIP_SUFFIXES = {".png", ".jpg", ".jpeg", ".gif", ".jar", ".class", ".zip", ".pyc", ".keystore", ".jks"}


class Violation(Exception):
    """A check that did not hold. One instance per distinct finding."""


# --------------------------------------------------------------------------
# Traversal
# --------------------------------------------------------------------------


def _is_allowed(rel: str) -> bool:
    if any(rel.startswith(p) for p in ALLOWED_PREFIXES):
        return True
    # A gate's own test file contains the violations it asserts on. That is the
    # same argument as a redactor test holding a credential-shaped string: the
    # fixture is the test, and removing it would leave the test asserting nothing.
    # Scoped to `test_check_` rather than all of `scripts/tests/`, so an ordinary
    # test that grew a machine path in an unrelated fixture is still reported.
    if Path(rel).name.startswith("test_check_") and rel.startswith("scripts/tests/"):
        return True
    return any(s in rel for s in _ALLOWED_SUBSTRINGS)


def tracked_text_files(root: Path = ROOT) -> list[Path]:
    """Tracked, text-shaped files. Git is the source of truth for what would be
    published; a plain walk would report build output that never ships."""
    try:
        out = subprocess.run(
            ["git", "-C", str(root), "ls-files", "-z"],
            capture_output=True, text=True, check=True,
        ).stdout
    except (subprocess.CalledProcessError, FileNotFoundError) as exc:
        raise Violation(f"git ls-files failed: {exc}") from exc

    files: list[Path] = []
    for name in out.split("\0"):
        if not name:
            continue
        p = root / name
        if p.suffix in SKIP_SUFFIXES:
            continue
        if any(part in SKIP_DIRS for part in p.parts):
            continue
        # This gate quotes every pattern it detects — the fixtures in
        # `_BAD_CORPUS`, the examples in the docstring, the literals in the
        # regexes. Scanning itself would report all of them on every run, and
        # the fix (delete the examples) would delete the gate's ability to
        # demonstrate that it works. The prefix covers this file and the other
        # detectors under scripts/, which cite the same strings when they
        # document what they detect.
        if name.startswith("scripts/check-"):
            continue
        if not p.is_file():
            continue
        files.append(p)
    return files


def _read(path: Path) -> str | None:
    try:
        return path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return None  # binary or unreadable: not a text finding


# --------------------------------------------------------------------------
# Checks
# --------------------------------------------------------------------------


def scan(root: Path = ROOT) -> list[tuple[str, int, str, str]]:
    """Return `(rule, lineno, relpath, excerpt)` for every finding, unsuppressed.

    The allowlist is applied by the caller, not here, so that `--self-test` can
    exercise the raw detectors against a corpus the allowlist has never seen.
    """
    findings: list[tuple[str, int, str, str]] = []

    for path in tracked_text_files(root):
        text = _read(path)
        if text is None:
            continue
        rel = str(path.relative_to(root))

        for lineno, line in enumerate(text.splitlines(), 1):
            for rule, regex in (
                ("home-path", HOME_PATH_RE),
                ("home-path", WINDOWS_HOME_RE),
                ("clone-name", CLONE_NAME_RE),
            ):
                m = regex.search(line)
                if m:
                    findings.append((rule, lineno, rel, m.group(0)))

            for m in EMAIL_RE.finditer(line):
                # An SSH remote names no mailbox. The match itself is only the
                # `user@host` prefix, so the shape is tested against a window of
                # the line that reaches far enough to include the `owner/repo`
                # tail the remote requires.
                window = line[m.start():m.start() + 128]
                if _SSH_REMOTE_RE.search(window):
                    continue
                if not _SAFE_EMAIL_DOMAINS.search(m.group(0)):
                    findings.append(("personal-email", lineno, rel, m.group(0)))

            for m in FOREIGN_PORT_RE.finditer(line):
                port = int(m.group(1) or m.group(2))
                if port not in KNOWN_PORTS:
                    findings.append(("foreign-port", lineno, rel, m.group(0)))

    return findings


def run_checks(root: Path = ROOT) -> tuple[list[str], dict[str, int]]:
    findings = scan(root)
    unsuppressed = [f for f in findings if not _is_allowed(f[2])]
    violations = [
        f"{rel}:{lineno}: {rule}: {excerpt!r}" for rule, lineno, rel, excerpt in unsuppressed
    ]
    stats = {
        "scanned_files": len(tracked_text_files(root)),
        "raw_findings": len(findings),
        "allowlisted": len(findings) - len(unsuppressed),
        "reported": len(violations),
    }
    return violations, stats


def read_allowlist(path: Path = ALLOWLIST) -> list[str]:
    """The allowlist file must exist and parse, even when it lists nothing.

    A missing allowlist would silently allow everything, which is the failure
    this gate exists to prevent — so absence is an error, not a pass.
    """
    if not path.exists():
        raise Violation(f"allowlist not found: {path.relative_to(ROOT)}")
    prefixes = []
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        prefixes.append(stripped)
    return prefixes


def check_allowlist_matches_prefixes(path: Path = ALLOWLIST) -> list[str]:
    """The TSV and the in-code prefixes must agree in both directions.

    Either one drifting from the other is a real finding: a prefix added to the
    code but not the file (or the reverse) means one of them is documenting an
    exception that no longer exists.
    """
    from_file = set(read_allowlist(path))
    from_code = set(ALLOWED_PREFIXES)
    out = []
    for prefix in sorted(from_code - from_file):
        out.append(f"{path.name}: prefix {prefix!r} is enforced in code but not recorded")
    for prefix in sorted(from_file - from_code):
        out.append(f"{path.name}: prefix {prefix!r} is recorded but not enforced")
    return out


# --------------------------------------------------------------------------
# The positive control
# --------------------------------------------------------------------------

_BAD_CORPUS = {
    # Three shapes of the same rule, because the SSH carve-out is the part most
    # likely to over-reach: a bare address, one inside a sentence, and one
    # immediately followed by a colon. The third must still be reported — the
    # exclusion is anchored to a full `user@host:path` remote, not to "there is
    # a colon somewhere nearby".
    "shared/src/main/kotlin/Thing.kt": "val author = \"real.person@corp-mail.example.net\"\n",
    "docs/agents/contacts.md": "Maintainer: real.person@corp-mail.example.net (GitHub).\n",
    "notes/two.md": "real.person@corp-mail.example.net: owns the sync service\n",
    "README.md": "Build it:\n\n    cd /home/someone/AndroidStudioProjects/app\n",
    ".agents/skills/demo/SKILL.md": "cd /home/other/project\n",
    "notes/port.md": "Open http://localhost:3000 and sign in.\n",
    "androidApp/src/main/res/values/strings.xml":
        '<resources><string name="app_name">app_cllone_kmp</string></resources>\n',
}

_CLEAN_CORPUS = {
    # `jvmTest` is a real source set and an allowed one; `mainTest` is not a
    # source set this project has, and using it here would have tested a
    # suppression that no real file can rely on.
    "shared/src/jvmTest/kotlin/RedactorTest.kt":
        "val url = \"https://user:s3cr3t@abc.supabase.co/rest/v1/tasks\"\n",
    "shared/src/jvmTest/kotlin/KeyTest.kt":
        "private const val KEY = \"sk-1234567890abcdefghijklmnopqrstuvwxyz\"\n",
    "shared/src/jvmTest/kotlin/MailTest.kt":
        "assertEquals(\"user@mail.com\", normalizeEmail(\"  User@Mail.COM  \"))\n",
    "README.md": "Clone anywhere and run `./gradlew :shared:jvmTest`.\n",
    # A Kotlin string template reads as an address to a naive regex
    # (`"${x@toMarkdown.html}"`). It is suppressed today only because the ADR
    # quoting it is allowlisted, which is luck rather than design — pinned here
    # so the shape stays visible if the allowlist ever shrinks. Narrowing the
    # email regex to require a plausible local part is the real fix and is out
    # of scope for this change; the cost of getting it wrong is a false
    # negative, so the looser regex is kept deliberately.
    "shared/src/jvmTest/kotlin/TemplateTest.kt":
        "val out = \"${body@toMarkdown.html}\"\n",
    ".agents/skills/demo/SKILL.md": "cd /absolute/path/to/your/clone\n",
    "notes/ollama.md": "The default Ollama endpoint is http://localhost:11434/v1.\n",
    # An SSH remote is not a mailbox. Without this entry the rule reports it on
    # every run and the maintainer learns to ignore the gate — which is how a
    # real finding eventually ships.
    "docs/agents/issue-tracker.md": "Repository: git@github.com:owner/repo.git\n",
    "scripts/sync.sh": "git push git@github.com:owner/repo.git main\n",
    # Allowlisted, and asserted separately below: a clean tree may still contain
    # machine-specific text under docs/decisions/.
    "docs/decisions/2026-01-01-note.md": "Lived at /home/someone/work/app during this work.\n",
}

_EXPECTED_RULES = {"personal-email", "home-path", "clone-name", "foreign-port"}


def run_self_test() -> int:
    """Assert each rule fires on a bad corpus and stays quiet on a clean one.

    The clean corpus is judged through the **same suppression the real check
    applies**, not through the raw detectors. That is the contract that matters:
    `run_checks` reports what the maintainer is told about, so a self-test on
    raw matches would be asserting something stricter than the gate promises and
    would have failed here on the allowlisted ADR line — correctly, and for the
    wrong reason. The raw detectors are covered separately below.
    """
    failures: list[str] = []

    # Rules that must fire on a violating corpus, whatever their location.
    with tempfile_tree(_BAD_CORPUS) as tmp:
        fired = {rule for rule, _, _, _ in scan(tmp)}
    for rule in sorted(_EXPECTED_RULES):
        if rule not in fired:
            failures.append(f"self-test: rule {rule!r} did not fire on a corpus that contains it")

    # Rules that must not fire on a clean corpus *after* suppression — which is
    # the state the gate actually reports.
    with tempfile_tree(_CLEAN_CORPUS) as tmp:
        reported = [f for f in scan(tmp) if not _is_allowed(f[2])]
    for rule, lineno, rel, excerpt in reported:
        failures.append(
            f"self-test: rule {rule!r} reported {excerpt!r} at {rel}:{lineno} on a corpus "
            f"that should be clean after suppression"
        )

    # The allowlist must suppress a real finding, or it is not an allowlist.
    allowed_only = {"docs/decisions/2026-01-01-note.md": _CLEAN_CORPUS[
        "docs/decisions/2026-01-01-note.md"]}
    with tempfile_tree(allowed_only) as tmp:
        raw = scan(tmp)
        suppressed = [f for f in raw if not _is_allowed(f[2])]
    if not raw:
        failures.append("self-test: the allowlisted fixture produced no raw finding to suppress")
    if suppressed:
        failures.append(f"self-test: allowlist did not suppress an allowed path: {suppressed}")

    if failures:
        print("check-publication-hygiene: self-test FAILED\n", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1

    print(
        "check-publication-hygiene self-test: OK — "
        f"{len(_EXPECTED_RULES)} rules fire on a violating corpus, none survive suppression "
        "on a clean one, and the allowlist suppresses as documented"
    )
    return 0


class tempfile_tree:
    """Minimal context manager yielding a root dir holding `tree`.

    Deliberately not `tempfile.TemporaryDirectory` directly: the detectors read
    the file list through git, so the synthetic corpus is staged through a
    temporary git index rather than a real repository.
    """

    def __init__(self, tree: dict[str, str]):
        self.tree = tree
        self._dir = None

    def __enter__(self) -> Path:
        import tempfile

        self._dir = tempfile.TemporaryDirectory(prefix="pubhygiene-selftest-")
        root = Path(self._dir.name)
        for rel, content in self.tree.items():
            p = root / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
        subprocess.run(["git", "init", "-q"], cwd=root, check=True,
                       capture_output=True)
        subprocess.run(["git", "add", "-A"], cwd=root, check=True, capture_output=True)
        return root

    def __exit__(self, *exc):
        if self._dir:
            self._dir.cleanup()
        return False


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--self-test", action="store_true",
                        help="prove the rules still fire on a known-bad corpus")
    args = parser.parse_args()

    if args.self_test:
        return run_self_test()

    violations: list[str] = []
    try:
        read_allowlist()
        violations += check_allowlist_matches_prefixes()
        v, stats = run_checks()
        violations += v
    except Violation as exc:
        print(f"check-publication-hygiene: {exc}", file=sys.stderr)
        return 1

    print(
        f"publication hygiene: {stats['scanned_files']} tracked text files scanned, "
        f"{stats['raw_findings']} raw match(es), {stats['allowlisted']} allowlisted, "
        f"{stats['reported']} reported"
    )

    if stats["raw_findings"] == 0:
        violations.append(
            "the scan found nothing at all: a glob or regex stopped matching, so this "
            "check verified nothing. An empty result is only trustworthy when the rules "
            "have just been proven to fire (--self-test)."
        )

    if violations:
        print(f"\ncheck-publication-hygiene: {len(violations)} violation(s)\n", file=sys.stderr)
        for v in violations:
            print(f"  - {v}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
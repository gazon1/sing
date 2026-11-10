#!/usr/bin/env python3
"""pre-push-verdict.py — the detekt verdict about the tree that is being pushed.

The pre-push hook has one question to answer: **does the tree that is being
pushed hold a detekt violation?** This script answers it, and the hook prints
what it says.

Why it is a script and not a shell function: the decision has two branches
that are easy to get wrong and impossible to see in a diff — which tree to
judge, and whether the tree changed under detekt's feet. Both are testable
here, and the tests are cheap: this file and its test take seconds, while the
hook it replaces grew to hundreds of lines of bash whose branches nothing
exercised.

The tree is chosen like this:

  - the current worktree, when it holds byte-identical content for everything
    detekt can read — then the worktree *is* the pushed tree, and judging it
    there is warm (this checkout's own Gradle home and build directories);
  - a throwaway worktree at the pushed sha, otherwise. The hook already
    checks one out for the same sha and passes it as ``--fallback-tree``.

The verdict READS the tree; it does not correct it.

The verdict is about the change, not about the repository: a finding in a file
the pushed range carries is the change's own, and a finding in a file it does
not touch is the repository's — the branch did not put it there, and the
branch is not the place to fix it. The repository's findings are the
repository's CI run to report.

When there is nothing to compare with — no base, or a diff that cannot be
computed — there is no way to tell the two apart, and every finding counts as
the push's own. The ratchet is a way of not blocking on somebody else's debt,
not a way of skipping a gate.

The base is the trunk, and only the trunk: the trunk merge-base the caller
names, or the trunk's remote-tracking ref when it names none. The branch's own
upstream is **not** a base — it is the remote's copy of this branch, so
everything the branch already carries is above it, and measuring against it
calls the branch's own earlier work "the repository's" and clears the findings
the branch brought. A caller that knows there is no base says so with
``--no-base``, instead of leaving the script to find a base of its own.

Two signals make the verdict red, and both are needed:

  - a finding of the push's own — what detekt reported about the files the
    pushed range carries, and about every file when there is no range to
    compare with;
  - the content digest before and after the run — the evidence that the run
    changed the tree it was judging. The verdict runs detekt read-only, so
    this is a safety net rather than the usual case: it catches any task in
    the invocation that writes to a source file (ADR
    2026-10-23-the-verdict-reads-the-tree-it-does-not-correct-it.md).

The digest is a hash over the content of every Kotlin file, not
``git status --porcelain``: a file that was already modified before the run
keeps the same status line after the correction, so a status comparison
cannot see that anything happened.

Which modules are judged is computed, not listed: the module graph in
``scripts/changed-modules.py`` says what a change can break, so a change in one
module judges that module and the modules that compile against it, and a change
in the rules or in the build judges every module. The list is therefore never
wider than the change and never narrower than what the change can break — and
it is never a hardcoded pair of module names that goes stale.

The verdict is PARTIAL, and says so: it judges the modules this change can
affect. A violation in a module the change does not touch is CI's to find —
CI judges every module, the hook judges the affected ones. The registry
``config/hooks/pre-push.json`` records both sides, and
``scripts/check-hook-gates.py`` checks that they still agree.

Usage:
    python3 scripts/pre-push-verdict.py --sha <sha> [--repo .] [--fallback-tree PATH]
    python3 scripts/pre-push-verdict.py --sha <sha> --json
    python3 scripts/pre-push-verdict.py --sha <sha> --no-base   # nothing to compare with

Exit codes:
    0 — the pushed tree carries no finding of its own
    1 — the pushed tree holds a violation this change brought, or the run
        changed the tree
    2 — the verdict could not be taken (no tree, no Gradle)
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import pathlib
import re
import subprocess
import sys

ROOT: pathlib.Path = pathlib.Path(__file__).resolve().parent.parent

# The one answer to "which git variables must a command inside a tree not
# inherit". It lives in `scripts/src/sgate/gatekit/gitenv.py` because the
# gates, the hook and the tests all need it, and a second copy of the list in
# this file is a list that goes stale: the hook and this script dropped
# different sets of the names for a while, and nobody noticed because both
# were right about the tree they ran in. ADR
# `docs/decisions/2026-10-23-a-test-fixture-must-not-reach-the-developers-repository.md`.
#
# It is loaded *by path*, the way `scripts/changed-modules.py` is loaded below,
# and not imported as `sgate.gatekit.gitenv`: the package `sgate` imports
# `click` and `loguru` when it is imported, and the hook runs this script with
# the ambient `python3`, which is not guaranteed to have either. A verdict that
# dies on an import is a push that fails for a reason that has nothing to do
# with the verdict. `scripts/gitenv_loader.py` is that way of loading, and it
# is shared with the other scripts that cannot reach the package either — the
# loading is one answer too.
#
# The loader lives beside this script, so the directory has to be on the path
# before it can be imported. The hook runs the script by its path, which puts
# the directory there on its own; a test that loads the script by path does
# not, and the line below is what makes both work.
_SCRIPTS_DIR = str(pathlib.Path(__file__).resolve().parent)
if _SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, _SCRIPTS_DIR)

from gitenv_loader import load_gitenv  # noqa: E402


#: How a path that is not valid UTF-8 is read.
#:
#: A filename is a byte string, and git will store a filename whose bytes are
#: not valid UTF-8. Reading such a path strictly raises `UnicodeDecodeError`,
#: and the reader that raises takes the whole verdict with it: a push is
#: stopped by a message about a filename nobody can read, and the message
#: says detekt reported findings — which is not what happened.
#:
#: `surrogateescape` maps the undecodable bytes to lone surrogates and back
#: again, so the path survives the round trip, still equals the path git
#: reports, and can still be compared with it. The paths are read as bytes
#: and decoded here for exactly this reason: a text-mode subprocess also
#: rewrites a carriage return in a filename into a newline, which changes the
#: path rather than merely failing to read it.
PATH_DECODE = "surrogateescape"


def decode_path(raw: bytes) -> str:
    """A path, as the bytes git wrote it.

    @param raw the bytes of one path.
    """
    return raw.decode("utf-8", PATH_DECODE)


def display_path(path: str) -> str:
    """A path, as it can be printed.

    A path whose bytes are not valid UTF-8 holds lone surrogates, and the
    terminal cannot encode them. The undecodable bytes are shown as `?`: the
    path is a report, and a report nobody can print is a report nobody reads.
    The comparison that decides the verdict is done on the exact bytes, not on
    this.

    @param path the path as it was read.
    """
    return path.encode("utf-8", "replace").decode("utf-8")


def lossy_path(path: str) -> str:
    """A path, as a JVM reads it.

    A JVM decodes a filename with the platform charset and replaces every byte
    it cannot decode with U+FFFD — so the detekt log names a file whose name
    is not valid UTF-8 by a name that is not the file's name. The change's own
    files are named by their bytes; the log names them by the JVM's rendering
    of those bytes, and the two names have to be recognised as one file. A
    finding that cannot be recognised as the change's own is called inherited,
    and the push is waved through on the strength of a finding the log cannot
    name.

    @param path the path as the changed-path reader produced it.
    """
    return path.encode("utf-8", PATH_DECODE).decode("utf-8", "replace")


#: The paths that can change a detekt verdict:
#:   - the Kotlin sources detekt analyses;
#:   - the build scripts that decide which source sets are scanned;
#:   - the detekt config, the baseline and the custom rules;
#:   - the version catalog the build scripts read;
#:   - .editorconfig — ktlint reads it from inside detekt (max_line_length,
#:     ktlint_class_signature_rule_force_multiline_when_parameter_count…),
#:     so a worktree with a different .editorconfig is judged by different
#:     rules than the tree it stands in for.
DETEKT_PATHSPECS = (
    ':(glob)**/*.kt',
    ':(glob)**/*.kts',
    'config/detekt/**',
    'build-logic/**',
    'detekt-rules/**',
    'gradle/**',
    '.editorconfig',
)

#: The detekt tasks the hook judges when nothing else is known. The real
#: choice is computed from the module graph (see `detekt_tasks_for_change`),
#: and this list is only the fallback for a push whose range has no known base.
#: The CI judges more modules — see config/hooks/pre-push.json, which is what
#: check-hook-gates.py reads.
DEFAULT_TASKS = (":shared:detekt", ":desktopApp:detekt")

#: Paths whose change can change the verdict of EVERY module: the rules, the
#: custom rules, the build that decides the source sets, and the editorconfig
#: that ktlint reads from inside detekt.
VERDICT_WIDE_PREFIXES = (
    'config/detekt/',
    'detekt-rules/',
    'build-logic/',
    'gradle/',
    '.editorconfig',
)

#: The build files themselves — the root ones and every module's own.
#:
#: Each module's build file holds its own `detekt { }` block — the config it
#: reads, the source sets it scans, the baseline it compares against — so a
#: change there can change what detekt reports for a file the push does not
#: carry. That is why a build file counts as wide even though it is not a
#: directory. The list is the build's own notion of the build
#: (`scripts/changed-modules.py`, `BUILD_AREAS`), read the same way.
VERDICT_WIDE_FILES = (
    'build.gradle.kts',
    'settings.gradle.kts',
    'gradle.properties',
    'gradlew',
    'gradlew.bat',
)


def is_wide_path(path: str) -> bool:
    """Does a change to this path change what every file looks like to detekt?

    The directories that hold the rules and the build logic, and the build
    files themselves — the root ones and every module's own, because each
    module's build file holds its own detekt block.
    """
    if path.startswith(VERDICT_WIDE_PREFIXES):
        return True
    return path.rsplit('/', 1)[-1] in VERDICT_WIDE_FILES

#: Where the change is measured against when the push does not name a base.
#: The trunk's remote-tracking ref: a branch that does not exist on the remote
#: yet is still a change *against the trunk*, and measuring it against nothing
#: would judge the whole repository — every module, for a one-line change.
DEFAULT_BASE = "origin/main"

#: The Gradle command the hook uses. ./gw, not ./gradlew: a worktree-private
#: GRADLE_USER_HOME, so a push in one worktree cannot kill the daemons of
#: another (ADR 2026-10-04-gradle-daemon-isolation.md).
DEFAULT_GRADLE = "./gw"


class VerdictUnavailable(Exception):
    """The verdict could not be taken — there is no tree to judge."""


#: The answer, taken from the file that owns it.
tree_env = load_gitenv().tree_env


def load_module_graph():
    """The module graph, loaded from scripts/changed-modules.py.

    One source of truth: the local verification plan, the CI module list and
    this verdict all read the same graph, so a module that appears in one and
    not in the others cannot be forgotten.
    """
    path = ROOT / "scripts" / "changed-modules.py"
    spec = importlib.util.spec_from_file_location("changed_modules", path)
    if spec is None or spec.loader is None:
        raise VerdictUnavailable(f"could not load the module graph from {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def changed_paths(tree: pathlib.Path, base: str, sha: str) -> list[str]:
    """The paths the pushed range touches, in the tree that is judged.

    The paths are read the way the repository reads them everywhere else:
    `core.quotePath=false` and the `-z` format. Git's default escapes a path
    with non-ASCII letters (`Caf\\303\\251.kt`) and quotes a path with a
    space, a quote or a backslash — and the escaped path matches nothing:
    the finding in that file is not recognised as the push's own, and the
    module does not claim the file, so the change is judged as if it carried
    no Kotlin at all. `scripts/changed-modules.py` reads the paths the same
    way, and the two answers have to agree.

    The output is read as bytes and decoded with `surrogateescape`, so a
    filename whose bytes are not valid UTF-8 is read rather than raised on —
    see `PATH_DECODE`.
    """
    result = subprocess.run(
        [
            "git",
            "-c",
            "core.quotePath=false",
            "diff",
            "--name-only",
            "-z",
            "--no-renames",
            f"{base}...{sha}",
        ],
        cwd=tree,
        env=tree_env(),
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        raise VerdictUnavailable(
            f"could not compare {base}...{sha[:8]} in {tree}: "
            f"{result.stderr.decode('utf-8', 'replace').strip()}",
        )
    return [decode_path(part) for part in result.stdout.split(b"\0") if part]


def default_base(tree: pathlib.Path) -> str | None:
    """The ref the change is measured against when the push does not name one.

    The trunk's remote-tracking ref (`origin/main`), and nothing else.

    The branch's own upstream is **not** a base for this verdict. The verdict
    answers "does this push carry a finding of its own", and the base of that
    question is the trunk: a finding the trunk already had is the trunk's
    debt, and a finding the branch brought is the branch's own. The upstream
    is the remote's copy of this branch — everything the branch already
    carries is above it, so measuring against it calls the branch's own
    earlier work "the repository's" and lets it through. ADR
    2026-11-09-the-pre-push-hook-judges-the-push-not-the-repository.md says
    the same: the verdict's base is the trunk merge-base, not the branch's
    upstream. A release line is named through `SGATE_BASE_REF`, which the
    hook resolves to its merge-base before it calls this script.

    So there are two answers:

      1. the trunk's remote-tracking ref — a branch that does not exist on the
         remote yet is still a change *against the trunk*, and measuring it
         against nothing would judge the whole repository — every module, for
         a one-line change;
      2. nothing — and then every module is judged and every finding counts as
         the push's own, the safe direction.
    """
    return DEFAULT_BASE if _rev_parse(tree, DEFAULT_BASE) else None


def _rev_parse(tree: pathlib.Path, ref: str) -> str | None:
    """The sha a ref points at, or None when the ref does not exist here."""
    result = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", ref],
        cwd=tree,
        env=tree_env(),
        capture_output=True,
        text=True,
        check=False,
    )
    return result.stdout.strip() or None if result.returncode == 0 else None


def module_of(task: str) -> str:
    """The module a Gradle task belongs to.

    `:shared:detekt` → `shared`; `:tools:unwritten-properties:test` →
    `tools:unwritten-properties`. The module is a path in Gradle, so the name
    can have more than one segment — taking the first one would say `tools`
    and hide which module was meant.
    """
    return task[1:].rsplit(":", 1)[0] if task.startswith(":") else task


#: A finding as detekt prints it:
#:
#:     TooManyFunctions - [Class 'BackupImporter' with '19' functions ...] at /path/File.kt:44:7
#:
#: The rule, what it says, and where it is. The path is absolute — it is the
#: tree that was judged.
_FINDING_RE = re.compile(
    r"(?P<rule>[A-Za-z][\w]*)\s+-\s+\[(?P<message>[^\]]*)\]\s+at\s+"
    r"(?P<path>.+?):(?P<line>\d+):(?P<col>\d+)"
)


def read_log(log: pathlib.Path) -> str:
    """The text of a detekt log.

    The log is read as bytes and decoded with `surrogateescape` — see
    `PATH_DECODE`. The log names the files detekt found things in, and a path
    in the log has to be *the same path* the changed-path reader produced: a
    finding in a file whose name is not valid UTF-8 has to be recognisable as
    that file's finding, or it is called inherited, the push is waved
    through, and the finding is nobody's.
    """
    try:
        return log.read_bytes().decode("utf-8", PATH_DECODE)
    except OSError:
        return ""


def findings_in(log: pathlib.Path) -> list[tuple[str, str]]:
    """The findings detekt reported: the file they are in, and their rule.

    The file is what the verdict is about: a finding in a file this change
    does not touch is the repository's finding, and the repository's CI run
    is where it is reported. A finding in a file this change carries is the
    change's own.
    """
    text = read_log(log)
    found: list[tuple[str, str]] = []
    for match in _FINDING_RE.finditer(text):
        found.append((match.group("path"), match.group("rule")))
    return found


def relative_to(tree: pathlib.Path, path: str) -> str:
    """The path of a finding, as the tree names it.

    A finding outside the tree that was judged cannot be relativised — and a
    finding that is not in the tree is not the change's own file, so it is
    left as it is.
    """
    try:
        return str(pathlib.Path(path).resolve().relative_to(tree.resolve()))
    except (ValueError, OSError):
        return path


def classify_findings(
    findings: list[tuple[str, str]],
    tree: pathlib.Path,
    changed: list[str],
    wide: bool,
) -> tuple[list[tuple[str, str]], list[tuple[str, str]]]:
    """The findings this change brought, and the ones it inherited.

    A finding in a file the change carries is the change's own. A finding in a
    file the change does not touch is the repository's — the branch did not
    put it there, and the branch is not the place to fix it.

    A change in the rules, in the custom rules or in the build is different:
    it can change what every file looks like to detekt, so every finding
    counts as the change's own. The same goes for the editorconfig, which
    ktlint reads from inside detekt.

    ``wide`` is also true when there is nothing to compare with — no base, or
    a diff that could not be computed. Then every finding counts as the
    change's own, because a finding that cannot be attributed cannot be
    called somebody else's.

    A file is also recognised by the name the log gives it: detekt runs on a
    JVM, and a JVM replaces every byte of a filename it cannot decode with
    U+FFFD — so the log names a file whose name is not valid UTF-8 by a name
    that is not the file's name (see `lossy_path`). Both names are the same
    file, and a finding in it is the change's own.
    """
    changed_set = set(changed)
    lossy_set = {lossy_path(path) for path in changed}
    own: list[tuple[str, str]] = []
    inherited: list[tuple[str, str]] = []
    for path, rule in findings:
        relative = relative_to(tree, path)
        if wide or relative in changed_set or relative in lossy_set:
            own.append((relative, rule))
        else:
            inherited.append((relative, rule))
    return own, inherited


def detekt_tasks_for_change(tree: pathlib.Path, base: str | None, sha: str) -> tuple[list[str], str]:
    """The detekt tasks this change makes worth running, and why.

    Three cases, in the order they are decided:

      - the change touches the rules, the custom rules or the build — then
        every module's verdict can change, so every module is judged;
      - the change touches a module — then that module and the modules that
        compile against it are judged, because that is what the module graph
        says the change can break;
      - the change touches nothing that detekt reads — then no detekt run is
        needed, and the verdict says so instead of claiming a clean tree.

    A range with no known base is judged in full: the safe direction.
    """
    graph = load_module_graph()
    every = [f":{module}:detekt" for module in graph.TASKS.detekt_modules()]

    if base is None:
        return every, "the pushed range has no known base — every module is judged"

    paths = changed_paths(tree, base, sha)
    wide = [path for path in paths if is_wide_path(path)]
    if wide:
        return every, f"{wide[0]} changed — the rules or the build moved, so every module is judged"

    tasks, _reasons = graph.plan(paths)
    # The plan is the LOCAL plan: it names the gate scripts as well as the
    # Gradle tasks, because a change to the gates is a change that has to be
    # checked by the gates. The verdict is a Gradle verdict, so only the
    # Gradle tasks are its business.
    gradle_tasks = [task for task in tasks if task.startswith(":")]
    detekt = [task for task in gradle_tasks if task.endswith(":detekt")]
    if not detekt:
        if gradle_tasks:
            # The change reaches a module that has no detekt task of its own:
            # `:tools:unwritten-properties` applies the plain kotlin("jvm")
            # plugin, so its verdict is its test suite. That is CI's to run —
            # the local verdict is a detekt verdict, and it says so instead of
            # claiming a clean tree it never measured.
            modules = sorted({module_of(task) for task in gradle_tasks})
            return [], (
                f"the change reaches {', '.join(modules)}, whose verdict is its test suite — "
                "no detekt task exists for it, so the local verdict does not judge it"
            )
        return [], "this change moves no Kotlin and no rules — no detekt run is needed"
    modules = sorted({module_of(task) for task in detekt})
    return detekt, f"the change reaches {', '.join(modules)}"


def git(repo: pathlib.Path, *args: str, check: bool = True) -> str:
    """Run a git command in `repo` and return its stdout.

    The output is decoded with `surrogateescape` — see `PATH_DECODE`. A
    command that lists paths (`git status --porcelain`) reports a filename
    that is not valid UTF-8 as it is, and reading it strictly would take the
    verdict down over a byte in a name.
    """
    result = subprocess.run(
        ["git", *args],
        cwd=repo,
        env=tree_env(),
        capture_output=True,
        check=False,
    )
    if check and result.returncode != 0:
        raise VerdictUnavailable(
            f"git {' '.join(args)} failed in {repo}: "
            f"{result.stderr.decode('utf-8', 'replace').strip() or result.returncode}",
        )
    return result.stdout.decode("utf-8", PATH_DECODE)


def worktree_holds_sha(repo: pathlib.Path, sha: str) -> bool:
    """Does the worktree hold exactly the content of `sha` for everything detekt reads?

    Tracked differences AND untracked files count: `git diff` cannot see a new
    Kotlin file, and detekt reads it.
    """
    diff = subprocess.run(
        ["git", "diff", "--quiet", sha, "--", *DETEKT_PATHSPECS],
        cwd=repo,
        env=tree_env(),
        capture_output=True,
        check=False,
    )
    if diff.returncode != 0:
        return False
    status = git(repo, "status", "--porcelain", "--untracked-files=all", "--", *DETEKT_PATHSPECS)
    return not any(line.startswith("??") for line in status.splitlines())


def kotlin_digest(tree: pathlib.Path) -> str:
    """One hash over the content of every Kotlin file in a tree.

    Tracked and untracked: detekt reads both, and a correction to an
    untracked file is as much a violation as a correction to a tracked one.

    `git hash-object --stdin-paths` reads the paths FROM stdin — passing them
    as arguments as well is an error.
    """
    listing = subprocess.run(
        ["git", "ls-files", "--", "*.kt", "*.kts"],
        cwd=tree,
        env=tree_env(),
        capture_output=True,
        check=False,
    )
    untracked = subprocess.run(
        ["git", "ls-files", "--others", "--exclude-standard", "--", "*.kt", "*.kts"],
        cwd=tree,
        env=tree_env(),
        capture_output=True,
        check=False,
    )
    paths = [
        decode_path(part)
        for part in (listing.stdout + untracked.stdout).splitlines()
        if part
    ]
    if not paths:
        return "empty"
    hashed = subprocess.run(
        ["git", "hash-object", "--stdin-paths"],
        cwd=tree,
        input="\n".join(paths).encode("utf-8", PATH_DECODE),
        capture_output=True,
        check=False,
        env=tree_env(),
    )
    if hashed.returncode != 0:
        raise VerdictUnavailable(
            f"could not hash the Kotlin files in {tree}: "
            f"{hashed.stderr.decode('utf-8', 'replace').strip()}"
        )
    return hashed.stdout.decode("utf-8", PATH_DECODE).strip()


def run_tasks(
    tree: pathlib.Path,
    tasks: tuple[str, ...],
    gradle_cmd: str,
    log: pathlib.Path,
) -> tuple[int, list[str]]:
    """Run the detekt tasks in ONE Gradle invocation.

    Returns the exit code and the tasks that failed.

    One invocation, not one per task: the constraint that forced one Gradle
    call per detekt task was `--auto-correct` reaching only the LAST task of
    an invocation (ADR 2026-10-17). The verdict does not correct, so the
    constraint is gone — and a single invocation pays the configuration phase
    once instead of once per module, which is what a wide change costs.

    The flag is NOT passed, and that is the point: the verdict reads the tree
    it judges, so it cannot change the tree that is being pushed. Correction
    is a separate step (`just detekt-fix`), and the verdict tells the user
    about the findings instead of quietly repairing them.

    `--rerun` forces re-analysis: without it Gradle is UP-TO-DATE and the
    verdict would be about the previous run's inputs. `--rerun` re-runs
    detekt alone and leaves its dependencies alone.

    `--continue` runs every judged module even when an earlier one failed, so
    the report names all the findings instead of the first one.
    """
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("w", encoding="utf-8") as out:
        result = subprocess.run(
            [
                *gradle_cmd.split(),
                *tasks,
                "--rerun",
                "--continue",
                "--no-daemon",
                "--console=plain",
            ],
            cwd=tree,
            stdout=out,
            stderr=subprocess.STDOUT,
            check=False,
        )
    return result.returncode, failed_tasks_in(log)


#: Gradle's plain console prints a failed task as `> Task :module:detekt FAILED`.
_FAILED_TASK = re.compile(r"^> Task (\S+) FAILED", re.MULTILINE)


def failed_tasks_in(log: pathlib.Path) -> list[str]:
    """The tasks the log says failed, in the order the log reports them."""
    text = read_log(log)
    seen: list[str] = []
    for match in _FAILED_TASK.finditer(text):
        task = match.group(1)
        if task not in seen:
            seen.append(task)
    return seen


def select_tree(repo: pathlib.Path, sha: str, fallback: pathlib.Path | None) -> tuple[pathlib.Path, str]:
    """Pick the tree that holds the pushed content, and say which one it is."""
    if worktree_holds_sha(repo, sha):
        return repo, f"the current worktree — byte-identical to {sha[:8]}"
    if fallback is not None and fallback.is_dir():
        return fallback, f"a temporary worktree at {sha[:8]}"
    raise VerdictUnavailable(f"no tree holds {sha[:8]}")


def judge(
    repo: pathlib.Path,
    sha: str,
    fallback: pathlib.Path | None,
    gradle_cmd: str,
    tasks: tuple[str, ...] | None,
    log_dir: pathlib.Path,
    base: str | None = None,
) -> dict:
    """Take the verdict. Returns a report; `clean` says whether the tree is clean."""
    tree, label = select_tree(repo, sha, fallback)

    reason = "the tasks were given on the command line"
    if tasks is None:
        tasks, reason = detekt_tasks_for_change(tree, base, sha)

    # The files this change carries. They say which findings are the change's
    # own: a finding in a file the change does not touch is the repository's.
    #
    # The comparison needs a base to compare with. Without one — no base at
    # all, or a diff that cannot be computed — there is no way to tell the two
    # apart, and a finding that cannot be attributed is a finding the push
    # might have brought. It counts as the push's own: the ratchet is a way of
    # not blocking on somebody else's debt, not a way of skipping a gate.
    changed: list[str] = []
    comparable = base is not None
    rules_moved = False
    if base:
        try:
            changed = changed_paths(tree, base, sha)
        except VerdictUnavailable:
            comparable = False
        rules_moved = any(is_wide_path(path) for path in changed)
    wide = rules_moved or not comparable

    before = kotlin_digest(tree) if tasks else ""

    failed: list[str] = []
    run_failed = False
    log = log_dir / f"detekt-{sha[:8]}.log"
    if tasks:
        rc, failed = run_tasks(tree, tuple(tasks), gradle_cmd, log)
        run_failed = rc != 0
        if run_failed and not failed:
            # The invocation failed without naming a task — a configuration
            # failure, a broken build script. The verdict is red, and it says
            # the run failed rather than naming a module that did not.
            failed = ["<the detekt run itself>"]

    after = kotlin_digest(tree) if tasks else ""
    mutated = bool(tasks) and before != after

    # The findings, and who brought them.
    #
    # The verdict is about the pushed tree, and the pushed tree holds every
    # finding the repository carries — including the ones that were already
    # there before the branch forked. Those are the repository's findings:
    # the branch did not put them there, and the branch is not the place to
    # fix them. The ones in the files the change carries are the change's.
    findings = findings_in(log) if tasks else []
    own, inherited = classify_findings(findings, tree, changed, wide)
    if run_failed and not findings:
        # The run failed without naming a finding: a broken build script, a
        # configuration failure. There is nothing to classify, so nothing is
        # cleared — the verdict stays red.
        verdict_red = True
    else:
        verdict_red = bool(own)

    return {
        "sha": sha,
        "tree": str(tree),
        "tree_label": label,
        "tasks": list(tasks),
        "coverage_reason": reason,
        "base": base,
        "failed_tasks": failed,
        "mutated": mutated,
        "clean": not verdict_red and not mutated,
        "judged": bool(tasks),
        "own_findings": [{"path": path, "rule": rule} for path, rule in own],
        "inherited_findings": [{"path": path, "rule": rule} for path, rule in inherited],
        "log_dir": str(log_dir),
    }


def tolerant_stdio() -> None:
    """Let the report be printed even when a path in it cannot be encoded.

    A path whose bytes are not valid UTF-8 holds lone surrogates, and the
    terminal cannot encode them: printing the report raises, the report is
    lost, and the caller reads the exit code as "detekt reported findings" —
    an accusation about a file nobody can name. The report replaces what
    cannot be encoded, the way `display_path` does.
    """
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")
        except (AttributeError, OSError, ValueError):
            pass


def main(argv: list[str] | None = None) -> int:
    tolerant_stdio()
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--sha", required=True, help="the commit being pushed")
    parser.add_argument("--repo", default=".", help="the repository root")
    parser.add_argument(
        "--fallback-tree",
        default=None,
        help="a worktree already checked out at the sha, used when the current one differs",
    )
    parser.add_argument("--gradle-cmd", default=DEFAULT_GRADLE, help="the Gradle command to run")
    parser.add_argument(
        "--tasks",
        default=None,
        help="the detekt tasks to judge (default: the modules this change can affect)",
    )
    parser.add_argument(
        "--base",
        default=None,
        help="the ref the pushed range starts at; the change is measured against it",
    )
    parser.add_argument(
        "--no-base",
        action="store_true",
        help="there is no base to compare with — every finding counts as the push's own",
    )
    parser.add_argument("--log-dir", default=None, help="where the detekt logs are written")
    parser.add_argument("--json", action="store_true", help="print the report as JSON")
    args = parser.parse_args(argv)

    repo = pathlib.Path(args.repo).resolve()
    fallback = pathlib.Path(args.fallback_tree).resolve() if args.fallback_tree else None
    log_dir = pathlib.Path(args.log_dir) if args.log_dir else pathlib.Path("/tmp/pre-push-verdict")
    tasks = tuple(t for t in args.tasks.split()) if args.tasks else None
    if args.no_base and args.base:
        parser.error("--base and --no-base say two different things")
    if args.no_base:
        # The caller knows there is no base. Saying so is the safe direction:
        # every finding counts as the push's own.
        base = None
    else:
        base = args.base or default_base(repo)

    try:
        report = judge(repo, args.sha, fallback, args.gradle_cmd, tasks, log_dir, base)
    except VerdictUnavailable as exc:
        if args.json:
            print(json.dumps({"clean": False, "unavailable": str(exc)}, ensure_ascii=True))
        else:
            print(f"pre-push: the detekt verdict could not be taken — {exc}", file=sys.stderr)
        return 2

    if args.json:
        # `ensure_ascii=True`, so a path whose bytes are not valid UTF-8 is
        # escaped rather than raised on when the report is written out. The
        # path survives exactly — it is the same string, escaped.
        print(json.dumps(report, ensure_ascii=True))
        return 0 if report["clean"] else 1

    print(f"   detekt verdict taken on {report['tree_label']}")
    if report["judged"]:
        print(f"   detekt verdict: judged {len(report['tasks'])} modules")
        print(f"   modules judged: {', '.join(report['tasks'])}")
        print(f"   why: {report['coverage_reason']}")
        print(
            "   the local verdict covers the modules this change can affect — "
            "a violation in a module it does not touch is CI's to find",
        )
    else:
        # Not "the tree is clean": nothing was judged, so nothing was found.
        # The hook reads this line and says "not taken", not "clean".
        print(f"   detekt verdict: nothing to judge — {report['coverage_reason']}")
    for task in report["failed_tasks"]:
        print(f"   detekt {task}: FAILED — see {report['log_dir']}")
    for finding in report["own_findings"]:
        print(f"   detekt finding: {display_path(finding['path'])} — {finding['rule']}")
    if report["inherited_findings"]:
        print(
            f"   {len(report['inherited_findings'])} finding(s) are in files this change does "
            f"not touch — the repository's own, and the repository's CI run is where they "
            f"are reported",
        )
    if report["mutated"]:
        print("   the tree that is being pushed changed under the verdict run:")
        diff = subprocess.run(
            ["git", "--no-pager", "diff"],
            cwd=report["tree"],
            capture_output=True,
            check=False,
            env=tree_env(),
        )
        # The diff is shown, not read: a path that is not valid UTF-8 is
        # replaced rather than raised on, because a report that cannot be
        # printed is a report nobody sees.
        print(diff.stdout.decode("utf-8", "replace"))
    if report["clean"]:
        if report["inherited_findings"]:
            print("   the pushed tree carries no finding of its own")
        else:
            print("   the pushed tree is clean")
    return 0 if report["clean"] else 1


if __name__ == "__main__":
    sys.exit(main())

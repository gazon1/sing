#!/usr/bin/env python3
"""check-doc-dead-refs.py — find references to files that no longer exist.

The batch cleanup in this sprint found ~80 stale references in docs, skills, ADRs and
KDoc, almost all of them the residue of a refactor that removed a file. They are
invisible to a plain grep (the path looks fine) and only surface when an agent tries to
open the file.

This script extracts every backticked `*.kt` / `*.py` / `*.sh` / `*.md` path from
Markdown and KDoc and reports the ones that do not resolve.

Deliberately conservative: it only reports a reference when it can locate the source
file but not the target, and it skips anything that looks like an example, a glob, an
ADR that is itself being referenced by slug, or a path in a historical ADR section.
Historical ADRs are checked but reported separately, because their whole purpose is to
record what was true at the time.

Usage: python3 scripts/check-doc-dead-refs.py [--include-adr] [--max N]
     python3 scripts/check-doc-dead-refs.py --skill-symbols   # check Kotlin symbol references in skills
"""
from __future__ import annotations

import pathlib
import re
import subprocess
import sys
from pathlib import PurePosixPath

# `scripts/src` on sys.path, and it has to be there *before* the first import
# that uses it: this gate must resolve `sgate` against *this* checkout, not
# against whatever `sgate` happens to be installed — an editable install of a
# sibling worktree is a different tree under the same package name, and an
# import that happens first is already bound to it. When this file is loaded by
# a test harness via importlib, sys.path[0] is `scripts/tests` and the package
# is invisible; this file's own location is authoritative.
_scripts_src = str(pathlib.Path(__file__).resolve().parent / "src")
_added = _scripts_src not in sys.path
if _added:
    sys.path.insert(0, _scripts_src)

import click
from sgate.commands.base import run
from sgate.gatekit.gradle import module_build_relpaths

ROOT = pathlib.Path(__file__).resolve().parent.parent
SKILLS_DIR = ROOT / ".agents" / "skills"
DECISIONS_DIR = ROOT / "docs" / "decisions"
SRC_DIRS = ["shared/src", "shared", "androidApp", "desktopApp", "mcp-server",
            "detekt-rules", "scripts", "docs", "config", "evals", ".agents", "gradle",
            "Maestro", "openspec",
            # .github/ — workflow-файлы. Без него любая ссылка на `ci.yml` из
            # AGENTS.md или ADR считалась бы мёртвой: каталог не индексируется,
            # и гейт сообщал бы ровно о документе, который учит агента ими
            # пользоваться. Побочный эффект полезный: сами workflow-и теперь
            # сканируются на висящие ссылки — раньше не сканировались вовсе.
            ".github",
            # infra/ (локальные стенды, в т.ч. Kiwi TCMS). Без него любая
            # ссылка на infra/kiwi/... из скилла или ADR считалась бы мёртвой
            # — и либо базилась бы, либо вводила автора в заблуждение.
            "infra",
            # supabase/ (миграции серверной схемы). Тот же случай, что у .github и
            # infra выше: каталог не индексируется, поэтому ссылка на
            # `supabase/migrations/2026-10-07-sync_schema.sql` из KDoc теста
            # объявлялась мёртвой при существующем файле. Гейт, который запрещает
            # ссылаться на несуществующее, не должен запрещать ссылаться на
            # существующее — иначе автор убирает точную ссылку вместо того, чтобы
            # чинить индекс.
            "supabase",
            # tools/ — модули анализаторов вне основной сборки (см.
            # tools/unwritten-properties). Индексируется по той же причине: ADR на
            # них ссылается путём, и без каталога такая ссылка выглядит мёртвой.
            "tools"]

# Top-level files that exist but are not under SRC_DIRS.
#
# The list is an allowlist rather than "every file in the root", because a
# reference to something that is genuinely absent has to stay reportable. That
# makes it a place where a real file can be missing: the README's Documentation
# table cites CONTRIBUTING.md, SECURITY.md and CODE_OF_CONDUCT.md, all three of
# which exist, and the gate called them dead until they were listed here. A gate
# that reports a live file as dead does not get baselined — it gets ignored, and
# the references it really should catch go with it.
TOP_LEVEL_FILES = [
    "AGENTS.md", "ARCHITECTURE.md", "README.md", "PROGRESS.md", "check.sh",
    "justfile", "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
    "skills-lock.json", "SKILL-MECHANICS.md", "CLAUDE.md", "LICENSE",
    "gradlew", "gradlew.bat", "package.json",
    "CONTRIBUTING.md", "SECURITY.md", "CODE_OF_CONDUCT.md",
    # The judgement rules a reviewing agent applies. A root-level document like
    # this one is cited from AGENTS.md, and an uncited-from-the-index file is
    # exactly the case the allowlist above exists for.
    "CODING_STANDARDS.md",
]

# Per-module build files live beside their module's source, not in an indexed dir.
# The list is read from `settings.gradle.kts` rather than written here: a module
# added to the build used to be invisible to this gate until somebody typed its
# name in this list, and the list had already drifted from the modules the build
# actually has.
GLOB_BUILD = module_build_relpaths(ROOT)


CLEAN = 0
VALIDATION_FAILURE = 1


def _gitignore_patterns() -> list[re.Pattern[str]]:
    """Compile .gitignore into anchored regexes.

    A reference to a *generated* file is not a broken reference. `DIGEST.md` is
    gitignored on purpose (see 5c0c2e9d — it is rebuilt by a post-checkout hook),
    so it is absent in a fresh clone and present after a docs refresh. Reporting
    it as dead made the check environment-dependent: green on a machine where
    someone had run `just docs-regen`, red everywhere else. Consulting
    .gitignore rather than hardcoding a name keeps the rule correct as more
    generated artifacts appear.
    """
    gi = ROOT / ".gitignore"
    if not gi.is_file():
        return []
    out: list[re.Pattern[str]] = []
    for raw in gi.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue
        # Strip a trailing '/': we only test file paths.
        line = line.rstrip("/")
        # A pattern containing '/' is anchored at the repo root; otherwise it
        # matches at any depth (gitignore semantics).
        if "/" in line:
            pattern = re.escape(line)
        else:
            pattern = r"(?:^|/)" + re.escape(line) + r"$"
        out.append(re.compile(pattern))
    return out


def is_generated(ref: str) -> bool:
    """True when `ref` is gitignored — i.e. built, not authored.

    Two ways to match, and the second one was a real CI-only failure.

    A gitignore pattern containing `/` is anchored at the repo root, so
    `docs/decisions/DIGEST.md` matches that exact path and nothing else. But a
    document may reference the same generated file by **basename** — three skills
    write plain `DIGEST.md` — and references are resolved by basename elsewhere in
    this script. So the literal-path test missed those entirely, and the gate
    reported them dead on a fresh checkout while passing on any machine where the
    post-checkout hook had run. That is precisely the environment-dependence the
    gitignore handling was added to remove; it just did not cover the basename
    form of the same path.
    """
    if any(p.search(ref) for p in _GITIGNORE):
        return True
    return PurePosixPath(ref).name in _GITIGNORE_BASENAMES


_GITIGNORE = _gitignore_patterns()

# Basenames of gitignored *files*, so `DIGEST.md` is recognised as generated even
# though the pattern that produces it is root-anchored. Restricted to entries that
# look like filenames (they carry an extension) so a gitignored directory name like
# `build` does not make an unrelated file named `build` look generated.
_GITIGNORE_BASENAMES: frozenset[str] = frozenset(
    PurePosixPath(line.strip().rstrip("/")).name
    for line in (ROOT / ".gitignore").read_text(encoding="utf-8").splitlines()
    if (ROOT / ".gitignore").is_file()
    and line.strip()
    and not line.strip().startswith(("#", "!"))
    and not line.strip().endswith("/")
    and "." in PurePosixPath(line.strip()).name
)

# Files that are runtime artifacts or external, not repo sources.
RUNTIME_ARTIFACTS = {
    "manifest.json", "payload.json", "backup.json", "data.json", "index.json",
    "skill-mechanics.md",
    # Spec Kit and Engineering SDD artifacts. These are written into a change
    # directory at run time (`openspec/changes/<slug>/`, `specs/<NNN>-<slug>/`)
    # and are never authored at a fixed repo path, so a backticked mention of one
    # in a skill or doc is a claim about an artifact, not a dead link.
    #
    # `spec.md`, `design.md` and `tasks.md` are also spec-kit feature-directory
    # artifacts, so the same argument covers them.
    #
    # ADR: docs/decisions/2026-10-13-engineering-sdd-workflow.md
    "investigation.md", "spec.md", "model.md", "verification.md",
    "design.md", "tasks.md", "results.md", "conventions.md",
    "plan.md", "research.md", "quickstart.md", "data-model.md",
    # Engineering SDD REPEAT loop artifacts
    "plan-artifact.md", "when-done.md",
}

# A backticked token that looks like a file path.
PATH_RE = re.compile(
    r"`([A-Za-z0-9_][A-Za-z0-9_./@-]*\.(?:kt|py|sh|md|yml|yaml|kts|json|toml|sql))`"
)
# A backticked dated ADR slug, cited without its `.md`: `2026-10-05-some-finding`.
#
# Only dated slugs, never bare kebab tokens. A bare token is not decidable:
# `singularity-todo-cross-feature-navigation` and `koin-compose-navigation3` are
# a skill and a Gradle artifact, both living in the same backticked-kebab form as
# a backlog heading, and a pattern that flagged those would report dozens of live
# references dead. A leading date is what makes an ADR slug distinguishable, and
# a false positive here would train the reader to ignore the gate.
#
# Worth checking because the references are invisible otherwise: build scripts
# were not scanned at all, and `shared/build.gradle.kts` cited a backlog entry
# under a name that exists nowhere in the repo.
ADR_SLUG_RE = re.compile(
    r"`(\d{4}-\d{2}-\d{2}-[a-z0-9][a-z0-9-]*)`"
)
# Historical ADRs are allowed to reference files that no longer exist.
HISTORY_MARKERS = (
    "superseded in part",
    "as written:",
    "was removed",
    "was never added",
    "paths below are historical",
    "no longer",
    "retired",
    "deleted in",
    "removed 2026",
)


def is_historical(text: str, pos: int) -> bool:
    """True when the reference sits inside a passage marked as historical."""
    start = max(0, pos - 600)
    window = text[start:pos].lower()
    return any(m in window for m in HISTORY_MARKERS)


def _tracked_files() -> list[pathlib.Path]:
    """Every file git tracks, as absolute paths.

    This replaces the SRC_DIRS walk. The list was maintained by hand and had to be
    extended every time a top-level directory was added — `.github`, then `infra`,
    then `supabase` — because an unindexed directory makes every reference under it
    report dead. Three separate false positives came from one hand-maintained list,
    and the fourth directory added to this repository would have produced the fourth.
    `git ls-files` is the definition of what the repository contains; a list of it is
    a copy that decays.

    Falls back to the SRC_DIRS walk outside a git checkout (a tarball, a CI cache
    mount), because a gate that silently indexes nothing reports every reference dead.
    """
    try:
        out = subprocess.run(
            ["git", "-C", str(ROOT), "ls-files", "-z"],
            capture_output=True, text=True, check=True,
        ).stdout
    except (subprocess.CalledProcessError, FileNotFoundError):
        return []
    return [ROOT / rel for rel in out.split("\0") if rel]


def build_index() -> tuple[set[str], dict[str, list[pathlib.Path]]]:
    """Index every tracked file by repo-relative path and by basename."""
    rel_paths: set[str] = set()
    by_name: dict[str, list[pathlib.Path]] = {}

    def add(p: pathlib.Path) -> None:
        if any(part in {"build", ".gradle", ".git"} for part in p.parts):
            return
        try:
            rel = p.relative_to(ROOT).as_posix()
        except ValueError:
            return
        rel_paths.add(rel)
        by_name.setdefault(p.name, []).append(p)

    tracked = _tracked_files()
    if not tracked:
        # No git index (a tarball, a CI cache mount). Falling back keeps the gate
        # working, and says so, because the walk cannot see a directory nobody
        # remembered to add to SRC_DIRS — which is how three false positives got here.
        click.echo(
            "check-doc-dead-refs: no git index available, falling back to the SRC_DIRS "
            "walk. A new top-level directory stays invisible to it until SRC_DIRS is "
            "edited — see _tracked_files().",
            err=True,
        )
        for d in SRC_DIRS:
            base = ROOT / d
            if base.exists():
                for p in base.rglob("*"):
                    if p.is_file():
                        add(p)
    else:
        for p in tracked:
            if p.exists():
                add(p)

    for name in TOP_LEVEL_FILES:
        p = ROOT / name
        if p.exists():
            rel_paths.add(name)
            by_name.setdefault(name, []).append(p)
    for name in GLOB_BUILD:
        if name in rel_paths:
            continue
        rel_paths.add(name)
        by_name.setdefault(name.split("/")[-1], []).append(ROOT / name)

    # Dated ADR slugs are cited without their `.md`, so the bare slug has to be
    # resolvable. Without this the check below would flag every such citation in
    # the repo, including the correct ones.
    for md in sorted(DECISIONS_DIR.glob("*.md")):
        if md.name == "DIGEST.md":
            continue
        rel_paths.add(md.stem)
        by_name.setdefault(md.stem, []).append(md)
    return rel_paths, by_name


# Placeholder paths in templates and examples — not claims about existing files.
PLACEHOLDER_PATTERNS = (
    re.compile(r"^path/to/"),
    re.compile(r"YYYY"),
    re.compile(r"^(domain|data|presentation|ui|feature|core|commonMain)/[a-z]+$"),
    re.compile(r"^<.+>$"),
    re.compile(r"^[a-z-]+/$"),
    re.compile(r"^(your|my|some)-"),
    re.compile(r"\.android/jvm\.kt$"),   # shorthand for a .android.kt/.jvm.kt pair
    re.compile(r"^iosMain/"),            # platform not targeted yet
)


def is_placeholder(ref: str) -> bool:
    return any(p.search(ref) for p in PLACEHOLDER_PATTERNS)


def resolve(ref: str, rel_paths: set[str], by_name: dict[str, list[pathlib.Path]]) -> str:
    """Classify a reference: 'ok', 'drift' (file exists, path stale) or 'dead'."""
    if ref.lower() in RUNTIME_ARTIFACTS or is_placeholder(ref):
        return "ok"
    if ref in rel_paths:
        return "ok"
    name = ref.split("/")[-1]
    if "..." in ref or "*" in ref or "<" in ref or ">" in ref:
        # Ellipsis/glob form: match on the trailing segments after the last ...
        tail = ref.split("...")[-1].lstrip("/")
        if not tail:
            return "ok"  # too vague to judge
        hits = by_name.get(tail.split("/")[-1], [])
        return "ok" if any(h.as_posix().endswith(tail) for h in hits) else "dead"
    hits = by_name.get(name, [])
    if not hits:
        return "dead"
    if any(h.as_posix().endswith(ref) for h in hits):
        return "ok"
    # The file exists under a different path — a moved file, not a phantom.
    return "drift"


def scan(path: pathlib.Path, rel_paths, by_name) -> list[tuple[int, str, str]]:
    text = path.read_text(encoding="utf-8", errors="replace")
    return scan_text(str(path), text, rel_paths, by_name)


def scan_text(name: str, text: str, rel_paths, by_name) -> list[tuple[int, str, str]]:
    """Dangling references in `text`, as (line, ref, verdict).

    Takes the text rather than reading the file so that the rules are testable
    without planting a broken file in the repo — the same reason the regexes
    live in named constants.
    """
    out = []
    for pattern in (PATH_RE, ADR_SLUG_RE):
        for m in pattern.finditer(text):
            ref = m.group(1)
            verdict = resolve(ref, rel_paths, by_name)
            if verdict == "ok":
                continue
            if verdict == "drift" and is_historical(text, m.start()):
                continue
            line = text.count("\n", 0, m.start()) + 1
            historical = is_historical(text, m.start())
            out.append((line, ref, "historical" if historical else verdict))
    return out


@click.command()
@click.option("--include-adr", is_flag=True, help="also scan docs/decisions (reports historical refs separately)")
@click.option("--max", type=int, default=40, help="max lines to print per file")
@click.option("--strict", is_flag=True, help="treat path drift as a failure too")
@click.option(
    "--baseline",
    metavar="PATH",
    default=str(ROOT / "config" / "docs" / "dead-refs-baseline.txt"),
    help="file of accepted dead refs, one 'path:ref' per line",
)
@click.option("--update-baseline", is_flag=True, help="rewrite the baseline from the current findings, then exit")
@click.option("--force", is_flag=True, help="with --update-baseline, allow dropping entries that are no longer detected (destructive)")
@click.option("--quiet-generated", is_flag=True, help="suppress the informational list of gitignored (generated) references")
@click.pass_context
def cli(
    ctx: click.Context,
    include_adr: bool,
    max: int,
    strict: bool,
    baseline: str,
    update_baseline: bool,
    force: bool,
    quiet_generated: bool,
) -> None:
    rel_paths, by_name = build_index()
    targets: list[pathlib.Path] = [
        ROOT / "AGENTS.md",
        ROOT / "ARCHITECTURE.md",
        ROOT / "README.md",
        ROOT / "PROGRESS.md",
    ]
    targets += sorted(SKILLS_DIR.glob("*/SKILL.md"))
    targets += sorted((ROOT / "shared/src").rglob("*.kt"))
    # Build scripts cite ADRs too, and a dangling one there is the worst case:
    # the comment outlives the rename, and nothing else in the tree would notice.
    targets += [ROOT / name for name in GLOB_BUILD if (ROOT / name).is_file()]
    if include_adr:
        targets += [p for p in sorted(DECISIONS_DIR.glob("*.md")) if p.name != "DIGEST.md"]

    baseline_path = pathlib.Path(baseline)
    accepted: set[str] = set()
    if baseline_path.exists():
        accepted = {l.strip() for l in baseline_path.read_text().splitlines() if l.strip() and not l.startswith("#")}

    dead_total = 0
    drift_total = 0
    hist_total = 0
    new_dead: list[str] = []
    for path in targets:
        if not path.exists():
            continue
        findings = scan(path, rel_paths, by_name)
        if not findings:
            continue
        rel = path.relative_to(ROOT).as_posix()
        dead = [f for f in findings if f[2] == "dead"]
        drift = [f for f in findings if f[2] == "drift"]
        hist = [f for f in findings if f[2] == "historical"]
        # A dead reference to a gitignored path is a *generated* file, not a
        # broken link. Downgrade before baselining so it is neither reported
        # as debt nor silently added to the baseline.
        generated = [f for f in dead if is_generated(f[1])]
        if generated:
            dead = [f for f in dead if not is_generated(f[1])]
            if not quiet_generated:
                gen_list = ", ".join(sorted({f[1] for f in generated}))
                click.echo(f"{rel}: {len(generated)} generated reference(s) "
                      f"(gitignored, built by a hook — not checked): {gen_list}")
        # Split dead into baselined (accepted debt) and new (must be fixed).
        baselined = [f for f in dead if f"{rel}:{f[1]}" in accepted]
        new = [f for f in dead if f"{rel}:{f[1]}" not in accepted]
        new_dead += [f"{rel}:{ref}" for _, ref, _ in new]
        if not new and not drift and (baselined or hist):
            click.echo(f"\n{rel}: {len(baselined)} baselined dead, {len(hist)} historical — OK")
        for label, group in (("DEAD (new)", new), ("DRIFT", drift)):
            if not group:
                continue
            click.echo(f"\n{rel}: {len(group)} {label} reference(s)")
            for line, ref, _ in group[:max]:
                click.echo(f"  L{line}: {ref}")
            if len(group) > max:
                click.echo(f"  ... and {len(group) - max} more")
        dead_total += len(dead)
        drift_total += len(drift)
        hist_total += len(hist)

    if update_baseline:
        baseline_path.parent.mkdir(parents=True, exist_ok=True)
        all_dead = sorted(
            {f"{path.relative_to(ROOT).as_posix()}:{ref}"
             for path in targets if path.exists()
             for _, ref, kind in scan(path, rel_paths, by_name) if kind == "dead"}
        )
        # A rewrite is destructive: the scan only sees refs in `targets`, so entries
        # baselined earlier (e.g. from --include-adr, or from a source tree that has since
        # been reworded) are silently dropped. On 2026-10-05 an unguarded
        # --update-baseline reduced this file from 328 entries to 39, erasing the record
        # of accepted debt. Require --force when entries would be removed.
        dropped = sorted(accepted - set(all_dead))
        if dropped and not force:
            click.echo(f"REFUSING to rewrite {baseline_path}: it would drop "
                  f"{len(dropped)} existing accepted entr(ies).", err=True)
            click.echo("The scan only covers the current target set, so this is not a "
                  "statement that the refs are fixed.", err=True)
            for entry in dropped[:20]:
                click.echo(f"  would drop: {entry}", err=True)
            if len(dropped) > 20:
                click.echo(f"  ... and {len(dropped) - 20} more", err=True)
            click.echo("\nRe-run with --force to accept the loss, or add the still-valid "
                  "entries back by hand.", err=True)
            raise ctx.exit(VALIDATION_FAILURE)
        if dropped:
            click.echo(f"WARNING: dropping {len(dropped)} accepted dead-ref entr(ies) "
                  f"(--force)", err=True)
        header = (
            "# Accepted dead file references in docs, skills and KDoc.\n"
            "#\n"
            "# Each line is `<file>:<referenced-path>`. A dead reference means the\n"
            "# referenced file does not exist anywhere in the repo. These are accepted\n"
            "# because the surrounding prose describes a design that was written down\n"
            "# but never built; they are catalogued in\n"
            "# docs/decisions/2026-09-27-doc-and-skills-sprint-findings.md.\n"
            "#\n"
            "# New dead references are NOT baselined and fail `just docs-audit`.\n"
            "# Regenerate with: python3 scripts/check-doc-dead-refs.py --update-baseline\n"
            "#   (add --force only if you intend to drop entries that are no longer\n"
            "#    detected; the scan does not see everything this file records).\n"
            "# Remove a line once the file is written or the example is reworded.\n"
        )
        baseline_path.write_text(header + "\n".join(all_dead) + "\n", encoding="utf-8")
        click.echo(f"\nbaseline written: {len(all_dead)} accepted dead refs -> {baseline_path}")
        raise ctx.exit(CLEAN)

    click.echo(f"\nDead refs: {dead_total} dead ({dead_total - len(new_dead)} baselined), "
          f"{drift_total} path-drift, {hist_total} historical")
    if new_dead:
        click.echo("\nNEW dead references (not baselined — fix or reword the example):")
        for entry in new_dead[:max]:
            click.echo(f"  {entry}")
        if len(new_dead) > max:
            click.echo(f"  ... and {len(new_dead) - max} more")
    if dead_total - len(new_dead):
        click.echo("\nBaselined dead refs are accepted debt — see the baseline file header.")
    if drift_total:
        click.echo("\nDRIFT: the file exists but moved. Update the path; basename matches are")
        click.echo("accepted, so drift is a warning unless --strict is passed.")
    ret = VALIDATION_FAILURE if (new_dead or (drift_total and strict)) else CLEAN
    raise ctx.exit(ret)




if __name__ == "__main__":
    raise SystemExit(run(cli))

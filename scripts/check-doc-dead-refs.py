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

import argparse
import pathlib
from pathlib import PurePosixPath
import re
import sys

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
]

# Per-module build files live beside their module's source, not in an indexed dir.
GLOB_BUILD = ["shared/build.gradle.kts", "androidApp/build.gradle.kts",
              "desktopApp/build.gradle.kts", "mcp-server/build.gradle.kts",
              "detekt-rules/build.gradle.kts"]


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


def build_index() -> tuple[set[str], dict[str, list[pathlib.Path]]]:
    """Index every tracked file by repo-relative path and by basename."""
    rel_paths: set[str] = set()
    by_name: dict[str, list[pathlib.Path]] = {}
    for d in SRC_DIRS:
        base = ROOT / d
        if not base.exists():
            continue
        for p in base.rglob("*"):
            if not p.is_file():
                continue
            if any(part in {"build", ".gradle", ".git"} for part in p.parts):
                continue
            try:
                rel = p.relative_to(ROOT).as_posix()
            except ValueError:
                continue
            rel_paths.add(rel)
            by_name.setdefault(p.name, []).append(p)
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


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--include-adr", action="store_true",
                    help="also scan docs/decisions (reports historical refs separately)")
    ap.add_argument("--max", type=int, default=40, help="max lines to print per file")
    ap.add_argument("--strict", action="store_true",
                    help="treat path drift as a failure too")
    ap.add_argument("--baseline", metavar="PATH", default=str(ROOT / "config" / "docs" / "dead-refs-baseline.txt"),
                    help="file of accepted dead refs, one 'path:ref' per line")
    ap.add_argument("--update-baseline", action="store_true",
                    help="rewrite the baseline from the current findings, then exit")
    ap.add_argument("--force", action="store_true",
                    help="with --update-baseline, allow dropping entries that are no "
                         "longer detected (destructive — see the refusal message)")
    ap.add_argument("--skill-symbols", action="store_true",
                    help="check Kotlin symbol references in skill files (detector 8)")
    ap.add_argument("--quiet-generated", action="store_true",
                    help="suppress the informational list of gitignored (generated) references")
    args = ap.parse_args()

    if args.skill_symbols:
        return _skill_symbols_main(args)

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
    if args.include_adr:
        targets += [p for p in sorted(DECISIONS_DIR.glob("*.md")) if p.name != "DIGEST.md"]

    baseline_path = pathlib.Path(args.baseline)
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
            if not args.quiet_generated:
                gen_list = ", ".join(sorted({f[1] for f in generated}))
                print(f"{rel}: {len(generated)} generated reference(s) "
                      f"(gitignored, built by a hook — not checked): {gen_list}")
        # Split dead into baselined (accepted debt) and new (must be fixed).
        baselined = [f for f in dead if f"{rel}:{f[1]}" in accepted]
        new = [f for f in dead if f"{rel}:{f[1]}" not in accepted]
        new_dead += [f"{rel}:{ref}" for _, ref, _ in new]
        if not new and not drift and (baselined or hist):
            print(f"\n{rel}: {len(baselined)} baselined dead, {len(hist)} historical — OK")
        for label, group in (("DEAD (new)", new), ("DRIFT", drift)):
            if not group:
                continue
            print(f"\n{rel}: {len(group)} {label} reference(s)")
            for line, ref, _ in group[: args.max]:
                print(f"  L{line}: {ref}")
            if len(group) > args.max:
                print(f"  ... and {len(group) - args.max} more")
        dead_total += len(dead)
        drift_total += len(drift)
        hist_total += len(hist)

    if args.update_baseline:
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
        if dropped and not args.force:
            print(f"REFUSING to rewrite {baseline_path}: it would drop "
                  f"{len(dropped)} existing accepted entr(ies).", file=sys.stderr)
            print("The scan only covers the current target set, so this is not a "
                  "statement that the refs are fixed.", file=sys.stderr)
            for entry in dropped[:20]:
                print(f"  would drop: {entry}", file=sys.stderr)
            if len(dropped) > 20:
                print(f"  ... and {len(dropped) - 20} more", file=sys.stderr)
            print("\nRe-run with --force to accept the loss, or add the still-valid "
                  "entries back by hand.", file=sys.stderr)
            return 1
        if dropped:
            print(f"WARNING: dropping {len(dropped)} accepted dead-ref entr(ies) "
                  f"(--force)", file=sys.stderr)
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
        print(f"\nbaseline written: {len(all_dead)} accepted dead refs -> {baseline_path}")
        return 0

    print(f"\nDead refs: {dead_total} dead ({dead_total - len(new_dead)} baselined), "
          f"{drift_total} path-drift, {hist_total} historical")
    if new_dead:
        print("\nNEW dead references (not baselined — fix or reword the example):")
        for entry in new_dead[: args.max]:
            print(f"  {entry}")
        if len(new_dead) > args.max:
            print(f"  ... and {len(new_dead) - args.max} more")
    if dead_total - len(new_dead):
        print("\nBaselined dead refs are accepted debt — see the baseline file header.")
    if drift_total:
        print("\nDRIFT: the file exists but moved. Update the path; basename matches are")
        print("accepted, so drift is a warning unless --strict is passed.")
    return 1 if (new_dead or (drift_total and args.strict)) else 0


# ── Detector 8 — skill-dangling-symbol ───────────────────────────────────────


_SYMBOL_RE = re.compile(r"`([^`]+)`")
# A declaration keyword may be preceded by any number of modifiers. This regex
# originally matched only a bare `class Foo` / `object Foo` at column 0, which made
# `data class`, `abstract class`, `sealed interface`, `internal class` and every
# annotated declaration invisible to the index — and `data class` is the dominant
# declaration form in this codebase. The symptom was a `--skill-symbols` baseline
# of 840 entries, most of them symbols that do exist.
# Nested declarations count. `OpenAction.ExitAndOpen` is declared inside a sealed
# class, and a skill documenting it was told it did not exist — the index saw
# only column-0 declarations, so every member of every sealed class, enum or
# companion was invisible. The class name itself is not what documentation
# quotes, so leading indentation is tolerated deliberately rather than by
# accident.
# `[ \t]*(\w+)` — не `[ \t]+`, потому что обобщённый класс пишется без
# пробела: `abstract class FileSystemContract<F : FileSystem>(...)`. С `+`
# такое объявление не матчилось, и гейт объявлял висячей ссылку на реально
# существующий класс FileSystemContract.
_TOP_LEVEL_KT = re.compile(
    r"^[ \t]*(?:@\w+(?:\([^)]*\))?[ \t]*)*"                # annotations
    r"(?:public|internal|private|protected|abstract|final|open|sealed|data|value|"
    r"inner|enum|annotation|expect|actual|companion|inline|infix|operator|suspend|"
    r"const|lateinit|external|tailrec)*[ \t]*"
    r"(?:object|class|interface|fun|val|var|typealias)[ \t]*(\w+)",
    re.M,
)
# Symbols owned by a library or the Kotlin/JDK standard, not by this repository.
# A skill naming `StateFlow` or `ProcessBuilder` is documenting a tool, not
# claiming this repo declares it, so flagging those is noise that trains people
# to ignore the gate. This list is deliberately short and each entry is a type
# a skill would plausibly name; a project symbol never belongs here.
_EXTERNAL_SYMBOLS = frozenset({
    "StateFlow", "MutableStateFlow", "SharedFlow",
    "ProcessBuilder", "TimeoutCancellationException", "CancellationException",
    "KoverProjectExtension", "KoverReport",
    "NavDisplay", "NavBackStack", "Display",       # Navigation 3 compose API
    "Test", "ClassData", "Parameterized",           # JUnit / Kotest
    "KotlinTest", "RunTest", "Dispatchers", "IO", "Default", "Main",
    "CoroutineContext", "CoroutineDispatcher", "CoroutineScope",   # kotlinx.coroutines
    # java.nio and the Kotlin compiler's PSI, which the detekt-rules tests use
    # directly (`compileContentForTest(Path)` wraps the file in a `KtScript`).
    "Path", "KtFile", "KtScript", "KtElement", "KtDeclaration", "KtExpression",
    "KtClass", "KtProperty", "KtParameter", "KtNamedFunction", "KtFileFacade",
    "KtCallExpression", "KtNameReferenceExpression", "KtDotQualifiedExpression",
    "KtAnnotationEntry", "KtValueArgument", "KtTypeReference",
    "CompilationUnit", "Rule", "Config", "Finding", "SourceCode",
    # Compose Material3 overlay types. Documented in the Kiwi/scenario skill
    # because a ModalBottomSheet is a separate semantics root on desktop and
    # therefore invisible to a JVM Compose test — the fact is only actionable if
    # the reader can look the type up.
    "ModalBottomSheet", "AlertDialog", "Dialog", "Popup",
})
# Types that are framework-allocated and never have production call sites.
_FRAMEWORK_ALLOCATED = frozenset({
    "App", "SingularityApp",  # Application/main entry
    "SingularityDatabase",    # Room database
    "PlatformDatabase",       # Koin graph entry
    "MainScreen",             # Desktop main screen
    "androidApp",             # package name
    "desktopApp",             # package name
})
# A rule id as written in `detekt.yml`: either a ruleset id (`naming:`) or a
# rule key in camelCase (`BackingPropertyNaming`). The value pattern accepts any
# boolean as well as list/scalar settings, because a rule declared `active: false`
# is still a *named* rule that documentation may legitimately quote — and those
# are exactly the ones a stricter pattern silently dropped.
#
# Indentation is matched as `[ \t]`, never `\s`: `\s` also matches `\n`, so under
# re.M a match could consume the newline that the next line's `^` needs, and the
# following key was skipped with no visible error. `ImportOrdering` was lost that
# way — it is the first key under `ktlint:` and its predecessor is a comment line.
_DETEKT_RULE_KEY = re.compile(
    r"^[ \t]{0,6}([A-Za-z][A-Za-z0-9]*)[ \t]*:[ \t]*"
    r"(?:true|false|null|\[\]|\{\}|$|[-\w'\"])",
    re.M,
)

def _kt_enum_entries(text: str) -> list[str]:
    """Names declared inside `enum class` bodies.

    `_TOP_LEVEL_KT` sees `enum class A2uiType` but not the constants below it:
    they are bare identifiers at the start of a line, so no declaration keyword
    matches. The consequence was concrete rather than theoretical — the
    genui-catalog skill quotes `STRING`, `INT`, `BOOL` and `TONE` as members of
    `A2uiType`, all four of which exist, and the gate called all four dangling.

    The body is read from the opening brace to the first line that closes it at
    the enum's own indentation. That boundary is what keeps this from indexing
    arbitrary identifiers: a permissive "every identifier that looks like an
    entry" scan would add thousands of names, and a check that accepts more than
    it can prove is the same failure this gate exists to stop.
    """
    names: list[str] = []
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if not re.match(r"^[ \t]*(?:\w+[ \t]+)*enum[ \t]+class[ \t]+\w+", line):
            continue
        if "{" not in line:
            continue
        indent = len(line) - len(line.lstrip())
        close = re.compile(r"^[ \t]{%d}\}" % indent)
        for body in lines[i + 1:]:
            if close.match(body):
                break
            entry = re.match(r"^[ \t]*([A-Za-z_][A-Za-z0-9_]*)\s*(?:,|$)", body)
            if entry and entry.group(1) not in {"companion", "public", "private"}:
                names.append(entry.group(1))
    return names


def _build_kt_symbol_index() -> dict[str, str]:
    """Scan production .kt files; return {symbol_name → file_rel_path}."""
    index: dict[str, str] = {}
    prod_roots = [
        ROOT / "shared/src/commonMain",
        ROOT / "shared/src/androidMain",
        ROOT / "shared/src/jvmMain",
        ROOT / "androidApp/src/main",
        ROOT / "desktopApp/src",
        ROOT / "mcp-server/src/main",
        # The custom detekt rules are production code for this gate's purposes:
        # `singularity-todo-detekt-rules-authoring` documents rule classes and
        # their tests by name, and an index without them reported all of those
        # references as dangling.
        ROOT / "detekt-rules/src",
        # Test sources too. A skill that says "TestTagCoverageTest fails any
        # untagged class" is documenting a real class, and an index that only
        # reads production sources called it a dangling reference — which is the
        # same false positive the project already recorded as 505 baselined
        # entries, now arriving one at a time as new skills land.
        ROOT / "shared/src/commonTest",
        ROOT / "shared/src/androidUnitTest",
        ROOT / "shared/src/jvmTest",
        ROOT / "desktopApp/src/jvmTest",
        ROOT / "mcp-server/src/test",
        ROOT / "detekt-rules/src/test",
    ]
    # The gate is not Kotlin-only in its sources. `singularity-todo-monthly-doc-audit`
    # documents the 1250-line DIGEST budget as `MAX_DIGEST_LINES`, which is a constant
    # in scripts/refresh-decisions-digest.py; a Kotlin-only index reported it as a
    # dangling reference, and the honest reading of that report was "the doc names a
    # constant that does not exist" when the constant was sitting in a .py file two
    # directories away. Module-level SCREAMING_SNAKE_CASE names are the Python
    # equivalent of a top-level declaration.
    _PY_CONST = re.compile(r"^([A-Z][A-Z0-9_]{2,})\s*(?::[^=]+)?=", re.M)
    # Классы и функции верхнего уровня. Методы (с ведущим отступом) и вложенные
    # определения не ловятся: regex требует def/class в начале строки.
    _PY_DEF = re.compile(
        r"^(?:class|def)\s+([A-Za-z_][A-Za-z0-9_]*)", re.M
    )

    for base in prod_roots:
        if not base.exists():
            continue
        for path in base.rglob("*.kt"):
            text = path.read_text(encoding="utf-8", errors="replace")
            declared = [m.group(1) for m in _TOP_LEVEL_KT.finditer(text)]
            declared += _kt_enum_entries(text)
            for name in declared:
                if name in _FRAMEWORK_ALLOCATED or name in _EXTERNAL_SYMBOLS:
                    continue
                # First-wins: commonMain is the canonical declaration
                if name not in index:
                    index[name] = path.relative_to(ROOT).as_posix()

    # Rule ids in `config/detekt/detekt.yml` are names the skills legitimately
    # quote (`BackingPropertyNaming`, `ImportOrdering`, …). They are keys, not
    # Kotlin declarations, so the .kt scan can never see them — but they are
    # checkable: a key that is configured but has no provider is a real defect,
    # which is what `check-detekt-registrations.sh` already enforces.
    detekt_yml = ROOT / "config/detekt/detekt.yml"
    if detekt_yml.exists():
        for name in _DETEKT_RULE_KEY.findall(detekt_yml.read_text(encoding="utf-8")):
            index.setdefault(name, "config/detekt/detekt.yml")
    # Python: не только константы уровня модуля, но и классы/функции верхнего
    # уровня. `singularity-todo-kiwi-tcm-stand` документирует `KiwiError`,
    # `SessionTransport` и подобные имена из infra/kiwi/ — это объявления в
    # реальном коде, и гейт, видящий только SCREAMING_SNAKE_CASE, объявлял их
    # висячими, вынуждая либо базилизовать живой символ, либо убрать точное
    # имя из документации. Оба варианта хуже, чем научить гейт видеть Python.
    #
    # infra/ проиндексирован потому же, что и detekt-rules выше: его скрипты —
    # исполняемый код этого репозитория, а не вспомогательные файлы.
    for base in (
        ROOT / "scripts",
        ROOT / ".agents",
        ROOT / "mcp-server",
        ROOT / "infra",
    ):
        if not base.exists():
            continue
        for path in base.rglob("*.py"):
            text = path.read_text(encoding="utf-8", errors="replace")
            rel = path.relative_to(ROOT).as_posix()
            for m in _PY_CONST.finditer(text):
                index.setdefault(m.group(1), rel)
            for m in _PY_DEF.finditer(text):
                index.setdefault(m.group(1), rel)
    return index


def _scan_skill_symbol_refs(
    skill_md: pathlib.Path,
) -> list[tuple[int, str]]:
    """Return [(line, backtick-quoted-symbol)] from a SKILL.md file."""
    text = skill_md.read_text(encoding="utf-8", errors="replace")

    # Strip code-fence blocks so content inside ``` ``` doesn't pollute symbol scan.
    text = re.sub(r"```[\s\S]*?```", "", text)

    # Skip known non-symbol patterns:
    KOTLIN_BUILTINS = frozenset({
        "println", "print", "readLine", "error", "exit",
        "null", "true", "false", "this", "super",
        "try", "catch", "finally", "throw", "return",
        "when", "is", "as", "in", "out", "by",
        "get", "set", "field", "delegate",
        "init", "combine", "await", "async", "launch", "run", "delay",
        "suspend", "yield", "lock", "synchronized",
    })
    ANDROID_CONSTANTS = frozenset({
        "KEYCODE_MENU", "KEYCODE_WAKEUP", "KEYCODE_BACK", "KEYCODE_HOME",
        "MODE_PRIVATE", "PERMISSION_DENIED", "POST_NOTIFICATIONS",
        "REQUEST_CODE", "RESULT_OK", "RESULT_CANCELED",
    })
    PROSE_WORDS = frozenset({
        "accepted", "completed", "tasks", "sync", "di", "detekt",
        "skills", "build", "test", "feat", "fix", "refactor",
        "docs", "chore", "grilling", "section", "discard",
    })

    refs: list[tuple[int, str]] = []
    for m in _SYMBOL_RE.finditer(text):
        sym = m.group(1)
        # Skip multi-line spans (tables, etc.)
        if "\n" in sym:
            continue
        stripped = sym.strip()
        # Skip empty / code-fence-like / shell-var / special-prefix content
        if (not stripped or stripped.startswith("```") or stripped.startswith("#")
                or stripped.startswith("$") or stripped.startswith("-")):
            continue
        # Skip file-path-like backticks
        if "/" in sym or sym.endswith(".md") or sym.startswith("shared/"):
            continue
        # Skip non-identifier content (punctuation, spaces)
        if not re.match(r"[a-zA-Z_$][a-zA-Z0-9_$]*$", stripped):
            continue
        # Skip known non-symbol categories
        if stripped in KOTLIN_BUILTINS | ANDROID_CONSTANTS | PROSE_WORDS:
            continue
        # Only report PascalCase symbols (class/interface/type names) —
        # these are what skills reference for architecture documentation.
        # Skip mixed-case (camelCase) as these are often testing APIs or prose words.
        if not re.match(r"[A-Z][a-zA-Z0-9_$]*$", stripped):
            continue
        # A negated mention is not a claim that the symbol exists. "There is no
        # `AppNavHost` in this app" is the skill answering this gate's own
        # question, and reporting it as a dangling reference inverts its
        # meaning. Only a negation in the same line and shortly before the
        # symbol counts, so "do not use `Foo`" is skipped while a line that
        # merely mentions a real symbol elsewhere is still checked.
        prefix = text[max(0, m.start() - 60):m.start()]
        # IGNORECASE is load-bearing: a sentence may open with "No `Foo` does not
        # exist" and a case-sensitive pattern misses the capital, so the one
        # sentence shape this rule exists to tolerate was the one it rejected.
        if re.search(
            r"\b(?:no|not|never|without|isn\x27t|aren\x27t|does\s+not|do\s+not|"
            r"cannot|can\x27t|don\x27t|doesn\x27t|instead\s+of|rather\s+than)"
            r"[^\n]{0,40}$",
            prefix,
            re.IGNORECASE,
        ):
            continue
        line = text.count("\n", 0, m.start()) + 1
        refs.append((line, sym))
    return refs


def _skill_symbols_main(_args) -> int:
    """Check that Kotlin symbols referenced in skill files exist in production code."""
    index = _build_kt_symbol_index()
    skill_dir = ROOT / ".agents" / "skills"
    baseline_path = ROOT / "config" / "docs" / "skill-symbol-baseline.txt"
    accepted: set[str] = set()
    if baseline_path.exists():
        accepted = {
            l.strip()
            for l in baseline_path.read_text().splitlines()
            if l.strip() and not l.startswith("#")
        }

    def _rel(path: pathlib.Path) -> str:
        try:
            return path.relative_to(ROOT).as_posix()
        except ValueError:
            return str(path)

    dangling: list[str] = []
    new_findings: list[str] = []
    for skill_md in sorted(skill_dir.glob("*/SKILL.md")):
        refs = _scan_skill_symbol_refs(skill_md)
        for line, sym in refs:
            # Library and JDK types are checked here rather than only while
            # indexing: an external symbol has no declaration to be found, so
            # filtering it at index-build time can never help.
            if sym in index or sym in _EXTERNAL_SYMBOLS:
                continue
            rel = skill_md.relative_to(ROOT).as_posix()
            dangling.append(f"{rel}:{sym}")
            if f"{rel}:{sym}" not in accepted:
                new_findings.append(f"  {_rel(skill_md)}:{line}: `{sym}` — not in production code")

    # `--update-baseline` was accepted by the argument parser but ignored here, so
    # the skill-symbol baseline could only ever grow. That is what left 840 entries
    # in place after `_build_kt_symbol_index` was taught to see `data class`.
    if getattr(_args, "update_baseline", False):
        header = (
            "# Kotlin symbols referenced in skill files that do not exist in\n"
            "# production source. Regenerate with:\n"
            "#   python3 scripts/check-doc-dead-refs.py --skill-symbols --update-baseline\n"
            "# Run it after fixing symbols, never to silence a new one.\n"
            "#\n"
            "# Most of these were accepted because the skill described an architecture\n"
            "# that was never built. The 2026-10-04 index fix (data class / abstract\n"
            "# class / annotated declarations were invisible) removed several hundred\n"
            # false positives in one pass.\n"
        )
        body = "\n".join(sorted(set(dangling)))
        baseline_path.parent.mkdir(parents=True, exist_ok=True)
        baseline_path.write_text(header + body + "\n", encoding="utf-8")
        print(f"baseline rewritten: {len(set(dangling))} dangling symbol(s) -> {baseline_path}")
        return 0

    if new_findings:
        print("NEW skill symbol references (not baselined):")
        for f in new_findings:
            print(f)
        print(f"\n{len(new_findings)} new dangling symbol(s).")
        return 1

    print("No new dangling symbols found in skill files.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""
Module-boundary gate: the light ADR modules must stay light (#542).

`:adr-cli` shipped **127 MB** and started in 0.30 s to read markdown files, because
`adr-cli/build.gradle.kts` said `implementation(project(":shared"))` — and `:shared`
is the whole application: Compose Material3, `material-icons-extended`, Room, SQLite,
Koin and the Koog agent stack. The Python gate the CLI wraps takes 0.08 s.

The split moved the ADR rules into `:adr-core` (which depends only on `:core-base`)
and cut `:shared` out of the CLI's graph: 127 MB -> 4.9 MB, and a distribution
containing no Compose, Room, Koin or Koog jar at all.

That fix has no memory. A dependency line is one line, the next person to need a
helper from `:shared` will write it, and the next release is 127 MB again with every
test green — the tests did not change, the classpath did. So the invariant is stated
here as a gate rather than remembered as a comment.

## What it checks

The *transitive* project-dependency closure of the light modules
(`:adr-cli`, `:adr-core`, `:core-base`) must not reach an application module
(`:shared`, `:androidApp`, `:desktopApp`, `:mcp-server`, `:pro`).

Transitive, not direct: the point is what ends up on the runtime classpath, and a new
module inserted between `:adr-cli` and `:shared` would satisfy a direct-edge check
while shipping exactly the same 127 MB.

## What it deliberately does not check

* **Build-time and test configurations are excluded.** `detektPlugins(project(...))`
  and `testImplementation(project(...))` never reach the shipped distribution, so
  counting them would turn an unrelated future change to `:detekt-rules` into a false
  accusation about the CLI. `detekt-rules` is a leaf today; that is a fact about
  today, not a reason to count a configuration that does not ship.
* **The distribution contents are not read.** That needs a build, and this gate runs
  in the JVM-less `static` job. The classpath edge and the resulting distribution are
  two measurements of the same fact; the cheap one is here on purpose.

## Exit codes

0 = clean, 1 = a light module reaches an application module, 2 = could not measure.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

WORKSPACE = Path(__file__).resolve().parent.parent

# The modules that must stay free of the application runtime.
LIGHT_MODULES = ("adr-cli", "adr-core", "core-base")

# Reaching any one of these puts an application runtime back on the CLI's classpath.
APPLICATION_MODULES = frozenset({"shared", "androidApp", "desktopApp", "mcp-server", "pro"})

# Configurations that do not reach the shipped distribution. Matched as a call name,
# so `detektPlugins` is removed and a comment merely mentioning it is not.
NON_RUNTIME_CONFIGS = (
    "detektPlugins",
    "testImplementation",
    "testRuntimeOnly",
    "testCompileOnly",
    "androidTestImplementation",
    "jvmTestImplementation",
    "jvmTestRuntimeOnly",
    "debugImplementation",
    "releaseImplementation",
)

_INCLUDE_RE = re.compile(r"""include\(\s*["'](:[\w:.-]+)["']""")
_PROJECT_DEP_RE = re.compile(r"""project\(\s*["'](:[\w:.-]+)["']""")

# A parse that silently found nothing would report "no findings" — indistinguishable
# from a clean tree. This tree has 11 top-level includes; below this floor the gate
# says it could not measure instead of passing.
MIN_INCLUDES = 8
MIN_BUILD_FILES = 8


def module_name(gradle_path: str) -> str:
    """`:tools:unwritten-properties` -> `tools:unwritten-properties` (no leading colon)."""
    return gradle_path.removeprefix(":")


def read_includes(settings: Path) -> set[str]:
    """Every module `settings.gradle.kts` declares, as colon-less names."""
    if not settings.is_file():
        return set()
    return {module_name(path) for path in _INCLUDE_RE.findall(settings.read_text(encoding="utf-8"))}


def _remove_call(text: str, name: str) -> str:
    """Delete every `name(...)` call, parens balanced.

    `detektPlugins(project(":detekt-rules"))` has a nested call, so a
    `name\\([^()]*\\)` pattern is not enough, and removing the whole *line* is worse:
    `implementation(project(":x")); detektPlugins(project(":detekt-rules"))` on one
    line is legal Gradle, and the line filter would throw away the runtime edge too.
    That is the failure this gate exists to prevent, produced by the gate.
    """
    out = text
    while True:
        start = out.find(name + "(")
        if start == -1:
            return out
        depth = 0
        end = start + len(name)
        for index in range(start + len(name), len(out)):
            if out[index] == "(":
                depth += 1
            elif out[index] == ")":
                depth -= 1
                if depth == 0:
                    end = index + 1
                    break
        else:
            # Unbalanced — a truncated file or a string literal holding `(`. Drop the
            # rest rather than counting a dependency inside an unclosed call.
            return out[:start]
        out = out[:start] + out[end:]


def _strip_non_runtime(text: str) -> str:
    """
    Drop every dependency declared in a configuration that never ships.

    The configurations are removed as calls, not as lines, so a runtime dependency
    sharing a line with `detektPlugins(...)` survives. A Gradle DSL is not parseable
    by regex and pretending otherwise is how a gate ends up confidently wrong — but
    paren counting is exact for the shape that actually appears here, and the
    unbalanced case degrades to "drop the tail", which fails visible rather than
    silently passing.
    """
    for name in NON_RUNTIME_CONFIGS:
        text = _remove_call(text, name)
    return text


def runtime_project_deps(build_file: Path) -> set[str]:
    """Colon-less names of the modules this build file depends on at runtime."""
    if not build_file.is_file():
        return set()
    body = _strip_non_runtime(build_file.read_text(encoding="utf-8"))
    return {module_name(dep) for dep in _PROJECT_DEP_RE.findall(body)}


def graph(workspace: Path, modules: set[str]) -> dict[str, set[str]]:
    return {name: runtime_project_deps(workspace / name / "build.gradle.kts") for name in modules}


def reachable(edges: dict[str, set[str]], start: str) -> set[str]:
    """
    Every module `start` can reach, excluding itself.

    A project-dependency cycle is legal in Gradle and would otherwise put `start`
    in its own result — which for `:adr-cli` is harmless (it is not an application
    module) and for any future light module with a colliding name is a finding
    about nothing.
    """
    seen: set[str] = set()
    queue = [start]
    while queue:
        current = queue.pop()
        for nxt in edges.get(current, ()):
            if nxt != start and nxt not in seen:
                seen.add(nxt)
                queue.append(nxt)
    return seen


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    included = read_includes(WORKSPACE / "settings.gradle.kts")

    if len(included) < MIN_INCLUDES:
        print(
            f"could not measure: only {len(included)} modules in settings.gradle.kts "
            f"(floor {MIN_INCLUDES}) — treating this as a parse failure, not a pass",
            file=sys.stderr,
        )
        return 2

    edges = graph(WORKSPACE, included)
    present = {name for name in included if (WORKSPACE / name / "build.gradle.kts").is_file()}

    if len(present) < MIN_BUILD_FILES:
        print(
            f"could not measure: only {len(present)} build files found "
            f"(floor {MIN_BUILD_FILES}) — treating this as a parse failure, not a pass",
            file=sys.stderr,
        )
        return 2

    # A light module that is not in `settings.gradle.kts` would make this gate
    # vacuously pass for it, so say so rather than skip it.
    missing = [m for m in LIGHT_MODULES if m not in edges]
    if missing:
        print(
            f"could not measure: {', '.join(missing)} not in settings.gradle.kts — "
            f"the gate would pass on them without examining anything",
            file=sys.stderr,
        )
        return 2

    findings: list[tuple[str, str, list[str]]] = []
    for light in LIGHT_MODULES:
        hit = sorted(reachable(edges, light) & APPLICATION_MODULES)
        if hit:
            findings.append((light, light, hit))

    if not args.quiet:
        for light in LIGHT_MODULES:
            deps = sorted(reachable(edges, light))
            print(f"  {light} -> {', '.join(deps) if deps else '(nothing)'}")

    if findings:
        print("\nlight ADR modules reach the application runtime:", file=sys.stderr)
        for _, light, hit in findings:
            print(f"  {light} -> {', '.join(hit)}", file=sys.stderr)
        print(
            "\n`:shared` carries Compose Material3, material-icons-extended, Room, "
            "SQLite, Koin and Koog — that is the 127 MB. Move what the ADR tooling "
            "needs into `:adr-core` (or `:core-base`) and leave the dependency out; "
            "if it genuinely needs application code, the two front doors are no "
            "longer the two wrappers over one module and Q2 is false.",
            file=sys.stderr,
        )
        return 1

    if not args.quiet:
        print("light ADR modules reach no application module")
    return 0


if __name__ == "__main__":
    sys.exit(main())
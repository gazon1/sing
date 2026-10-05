"""Linkage between a scenario and the automation that verifies it.

Two carriers, one namespace:

| Carrier    | Mechanism                                | Applies to              |
|------------|------------------------------------------|-------------------------|
| Kotlin     | ``@DisplayName("TASK-REC-01 …")``       | user-flow tests only    |
| Maestro    | ``tags: [scenario:TASK-REC-01, smoke]`` | flow header             |

Both declare the id as a **prefix token**, so a reader — human or scanner — can
tell an id from prose.

Why ``@DisplayName`` and not a custom annotation: it already exists in Jupiter
and works for Compose UI tests, so *nothing new is declared*. A custom
annotation would have to be duplicated across ``shared/jvmTest`` and
``desktopApp/jvmTest``, which cannot share a declaration (``desktopApp`` sees
only ``shared``'s *main* sources — there is no ``testFixtures`` anywhere), and
that would mean a new Gradle module and build wiring for tens of methods. It is
also a real compile-checked annotation, so the id cannot be a stale constant,
and it reaches JUnit XML for free — which the Kiwi publish step needs anyway.

Strictness is the point. An id not at the start of the string, a malformed
string, two ids on one method, or an id with no spec file are **hard errors**,
not shapes to accommodate: a regex that starts tolerating whitespace variance
is the first step toward reimplementing a Kotlin parser in Python.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path

from traceability import REPO_ROOT, ValidationError
from traceability.keys import TestKey
from traceability.spec import ID_PATTERN, Level, ScenarioSpec, Target

__all__ = ["Link", "Carrier", "scan_kotlin", "scan_maestro", "scan_all", "SCENARIO_TAG_PREFIX"]

#: The Maestro tag namespace. Disjoint from UI selector ids on purpose: a
#: ``TestTags`` value starting with this prefix, or matching the id pattern, is
#: a bug — :func:`assert_namespace_disjoint` enforces that.
SCENARIO_TAG_PREFIX = "scenario:"

#: ``@DisplayName("TASK-REC-01 create a daily recurring task")`` — the id must
#: be the FIRST token. Anchored, and the id is followed by whitespace or the end
#: of the string, so ``TASK-REC-011`` cannot masquerade as ``TASK-REC-01``.
_DISPLAY_NAME = re.compile(r'@DisplayName\(\s*"([^"]*)"\s*\)')

#: A test method. Used only to attribute a ``@DisplayName`` to a method; the
#: accepted form is a display name on the method, never on the class.
_TEST_FUN = re.compile(r"fun\s+(`?)([A-Za-z_][A-Za-z0-9_]*)\1\s*\(")

class Carrier(StrEnum):
    """Which mechanism carries the linkage."""

    KOTLIN = "kotlin"
    MAESTRO = "maestro"


@dataclass(frozen=True, slots=True)
class Link:
    """One scenario claimed by one test, on one target.

    ``key`` is the :class:`TestKey` the result reader will actually look up, and
    it is built from the **display name**, not the Kotlin method name — measured
    on this repo's own XML, JUnit writes ``<testcase name>`` as the
    ``@DisplayName`` when one is present, so a key built from ``fun`` would
    match nothing while looking entirely correct.

    ``fallback_key`` is the method-name form, registered as a second index entry.
    It costs nothing and covers a build configured to report method names
    instead; the primary key stays the one observed in practice.

    A Maestro flow has no ``TestKey`` at all — its JUnit ``classname`` is
    derived by the reporter — so it is joined on its path instead.
    """

    scenario: str
    target: Target
    level: Level
    carrier: Carrier
    source: Path
    key: TestKey | None = None
    fallback_key: TestKey | None = None
    detail: str = ""


def _scenario_from_prefix_token(text: str) -> str | None:
    """Return the leading id token of ``text``, or ``None``.

    ``"TASK-REC-01 create a daily recurring task"`` -> ``"TASK-REC-01"``.
    An id appearing later in the string is *not* extracted: silently accepting
    it would make the carrier's position meaningless, and prose that happens to
    mention an id would start claiming scenarios.
    """
    stripped = text.strip()
    match = re.match(r"^([A-Za-z0-9-]+)(?=\s|$)", stripped)
    if not match:
        return None
    candidate = match.group(1)
    return candidate if ID_PATTERN.match(candidate) else None


def _kt_root_to_target(rel: str) -> Target | None:
    """Map a Kotlin test source path to the target it runs on.

    Only user-flow source sets map. ``shared`` tests are supporting tests: the
    259 existing unit/domain cases are deliberately **not** migrated, renamed or
    tagged, so a scenario cannot be claimed there. That is a scoping decision,
    not a gap in the mapping.
    """
    if rel.startswith("desktopApp/"):
        return Target.DESKTOP
    if rel.startswith("androidApp/src/androidTest/"):
        return Target.ANDROID
    return None


def _class_fqn(source: str, path: Path) -> str | None:
    """Derive the package-qualified class name from the file's package + name.

    The scanner keys on the outermost class in the file, which is what JUnit
    reports in ``classname`` for a normal test. A file declaring two top-level
    classes would need per-class attribution; that does not occur in this
    repository, and guessing would be worse than refusing.
    """
    match = re.search(r"^package\s+([\w.]+)", source, re.MULTILINE)
    package = match.group(1) if match else ""
    simple = path.stem
    if not re.search(r"^class\s+[`\w]*" + re.escape(simple) + r"\b", source, re.MULTILINE):
        return None
    return f"{package}.{simple}" if package else simple


def scan_kotlin(specs: dict[str, ScenarioSpec], root: Path = REPO_ROOT) -> list[Link]:
    """Find ``@DisplayName`` scenario claims in user-flow test sources.

    Every deviation from the accepted form is an error, collected across the
    whole tree and raised together.
    """
    roots = [
        (root / "desktopApp/src/jvmTest/kotlin", Target.DESKTOP),
        (root / "androidApp/src/androidTest/kotlin", Target.ANDROID),
    ]
    links: list[Link] = []
    errors: list[str] = []

    for source_root, target in roots:
        if not source_root.is_dir():
            continue
        for path in sorted(source_root.rglob("*.kt")):
            text = path.read_text(encoding="utf-8")
            if "@DisplayName" not in text:
                continue
            rel = path.relative_to(root).as_posix()
            fqcn = _class_fqn(text, path)
            if fqcn is None:
                errors.append(f"{rel}: не удалось определить класс верхнего уровня {path.stem}")
                continue

            # Pair each display name with the method it precedes. Walking the
            # annotations and the functions in one pass keeps the two in order
            # without trying to understand Kotlin's grammar.
            for index, line in enumerate(text.splitlines(), start=1):
                found = _DISPLAY_NAME.search(line)
                if not found:
                    continue
                body = found.group(1)
                if body.count("@DisplayName"):  # pragma: no cover - defensive
                    continue

                scenario = _scenario_from_prefix_token(body)
                # A display name with no leading id is ordinary prose, not a
                # malformed claim: plenty of tests name themselves helpfully.
                if scenario is None:
                    if re.match(r"^[A-Za-z]", body) and re.search(r"-[A-Z]", body):
                        errors.append(
                            f"{rel}:{index}: id в @DisplayName должен быть первым токеном: {body!r}"
                        )
                    continue

                # Two ids on one method is ambiguity, not a merge.
                ids = re.findall(r"[A-Z]+(?:-[A-Z]+)*-[0-9]+", body)
                if len(set(ids)) > 1:
                    errors.append(
                        f"{rel}:{index}: несколько id в одном @DisplayName: {sorted(set(ids))}"
                    )
                    continue
                if scenario not in specs:
                    errors.append(
                        f"{rel}:{index}: сценарий '{scenario}' не имеет спека "
                        f"(infra/kiwi/scenarios/…)"
                    )
                    continue

                method = _method_name(text, index)
                if method is None:
                    errors.append(
                        f"{rel}:{index}: @DisplayName прикреплён к строке без 'fun': {body!r}"
                    )
                    continue

                links.append(
                    Link(
                        scenario=scenario,
                        target=target,
                        level=Level.E2E,
                        carrier=Carrier.KOTLIN,
                        source=path,
                        # Primary key: the display name, because that is what
                        # JUnit writes into <testcase name>.
                        key=TestKey(fqcn, body),
                        fallback_key=TestKey(fqcn, method),
                        detail=f"{body} (fun {method})",
                    )
                )
    if errors:
        raise ValidationError(f"нарушения связывания (@DisplayName):\n  - " + "\n  - ".join(errors))
    return links


def _method_name(text: str, index: int) -> str | None:
    """The Kotlin method a display name on line ``index`` names.

    Looks forward from the annotation to the next ``fun``, which is how the
    accepted form is written (``@DisplayName`` immediately above ``@Test fun``).
    Returns ``None`` when the annotation is not on a method at all — a class
    level display name, which the caller reports as an error.
    """
    lines = text.splitlines()
    for offset in range(index, min(index + 4, len(lines))):
        candidate = lines[offset]
        if "class" in candidate or _DISPLAY_NAME.search(candidate):
            continue
        found = _TEST_FUN.search(candidate)
        if found:
            # Deliberately *not* re-normalised through keys.normalise_test_name:
            # this is the method name as written in Kotlin, and `_TEST_FUN`
            # cannot capture a `)` or `[`, so there is nothing to strip. A
            # second copy of the normalisation here is what keys.py explicitly
            # forbids — it could only ever drift from the one the result reader
            # uses.
            return found.group(2)
    return None


def scan_maestro(specs: dict[str, ScenarioSpec], root: Path = REPO_ROOT) -> list[Link]:
    """Find ``scenario:<ID>`` tags in Maestro flow headers.

    Parsed from the header block only. ``scripts/run-maestro.sh`` matches these
    tags by exact string comparison, so no new mechanism is needed to filter on
    them today — the flows are already machine-readable, and this scanner reads
    the same shape the runner does.
    """
    flows_dir = root / "Maestro" / "flows"
    links: list[Link] = []
    errors: list[str] = []
    if not flows_dir.is_dir():
        return links

    for path in sorted(flows_dir.rglob("*.yaml")):
        rel = path.relative_to(root).as_posix()
        in_tags = False
        for index, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            stripped = line.strip()
            if re.fullmatch(r"-{3,}\s*", stripped):
                in_tags = False
                continue
            if stripped.startswith("tags:"):
                in_tags = True
                continue
            if not in_tags or not stripped.startswith("-"):
                continue
            tag = stripped.lstrip("-").strip().strip("'\"")
            if not tag.startswith(SCENARIO_TAG_PREFIX):
                continue
            scenario = tag[len(SCENARIO_TAG_PREFIX) :].strip()
            if not ID_PATTERN.match(scenario):
                errors.append(f"{rel}:{index}: тег '{tag}' не содержит корректный id сценария")
                continue
            if scenario not in specs:
                errors.append(
                    f"{rel}:{index}: сценарий '{scenario}' не имеет спека (infra/kiwi/scenarios/…)"
                )
                continue
            links.append(
                Link(
                    scenario=scenario,
                    target=Target.ANDROID,
                    # A Maestro flow drives a real device through the UI; there
                    # is no unit-level reading of one.
                    level=Level.E2E,
                    carrier=Carrier.MAESTRO,
                    source=path,
                    key=None,
                    detail=tag,
                )
            )
            # A flow run produces ONE result, so it can verify exactly one
            # scenario. Two `scenario:` tags would have to share that single
            # result, and the result index is keyed by flow path — so the second
            # tag would silently overwrite the first and the first scenario
            # would render as `not-run` with no error anywhere. Reject it here
            # where the cause is visible.
            if len(links) > 1 and links[-2].source == path:
                errors.append(
                    f"{rel}: поток заявляет сценарии "
                    f"{sorted({links[-2].scenario, scenario})} — один запуск потока "
                    f"даёт один результат; разделите на два потока"
                )
    if errors:
        raise ValidationError(f"нарушения связывания (теги scenario:):\n  - " + "\n  - ".join(errors))
    return links


def assert_namespace_disjoint(root: Path = REPO_ROOT) -> None:
    """No UI selector id may look like a scenario id or a ``scenario:`` tag.

    The ``scenario:``/``TASK-*`` namespace is new and is kept disjoint from
    ``TestTags`` values. Unifying ``@Tag``, Maestro tags and ``TestTags`` is a
    separate refactor; what matters here is only that the new namespace does not
    quietly collide with selector ids, which would make a scan for scenarios
    match UI strings.
    """
    tags_file = root / "shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt"
    if not tags_file.is_file():
        raise ValidationError(f"не найден файл UI-тегов: {tags_file}")
    problems: list[str] = []
    for index, line in enumerate(tags_file.read_text(encoding="utf-8").splitlines(), start=1):
        for value in re.findall(r'"([^"]+)"', line):
            if value.startswith(SCENARIO_TAG_PREFIX):
                problems.append(f"{tags_file.name}:{index}: значение '{value}' попадает в namespace scenario:")
            elif ID_PATTERN.match(value):
                problems.append(f"{tags_file.name}:{index}: значение '{value}' совпадает с шаблоном id сценария")
    if problems:
        raise ValidationError(
            "namespace сценариев пересекается с UI-селекторами:\n  - " + "\n  - ".join(problems)
        )


def _assert_single_test_per_scenario(links: list[Link]) -> None:
    """One scenario may be claimed by at most one test per target.

    Two tests claiming one scenario make the matrix cell ambiguous: which of
    them is "the" result? This is an error, never a merge — merging would hide
    exactly the disagreement the matrix exists to show.
    """
    seen: dict[tuple[str, Target], Link] = {}
    problems: list[str] = []
    for link in links:
        slot = (link.scenario, link.target)
        first = seen.get(slot)
        if first is not None:
            problems.append(
                f"сценарий '{link.scenario}' на {link.target} уже заявлен в "
                f"{first.source.name} и {link.source.name}"
            )
            continue
        seen[slot] = link
    if problems:
        raise ValidationError("один сценарий заявлен несколькими тестами:\n  - " + "\n  - ".join(problems))


def scan_all(specs: dict[str, ScenarioSpec], root: Path = REPO_ROOT) -> list[Link]:
    """All links from both carriers, with the global invariants enforced.

    A scenario claimed on a target its spec does not claim is an error, not a
    no-op: it means the spec is out of date, and silently ignoring the link
    would make the coverage matrix wrong in a way that reads as a hole.
    """
    assert_namespace_disjoint(root)
    links = scan_kotlin(specs, root) + scan_maestro(specs, root)
    _assert_single_test_per_scenario(links)

    problems: list[str] = []
    for link in links:
        spec = specs.get(link.scenario)
        if spec is None:
            continue
        if link.target not in spec.targets:
            problems.append(
                f"{link.source.name}: сценарий '{link.scenario}' заявлен на {link.target}, "
                f"но в спеке заявлены {', '.join(t.value for t in spec.targets)}"
            )
    if problems:
        raise ValidationError("цель теста не заявлена в спеке:\n  - " + "\n  - ".join(problems))
    return links

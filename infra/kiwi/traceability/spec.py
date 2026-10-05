"""Scenario specs: a user scenario specified in Git, loaded and validated.

A *scenario* is a user-visible behaviour (``TASK-REC-01``), not a test class.
The spec is the unit of traceability; the tests are how it is verified. Keeping
those two apart is the whole point — the alternative (a case per test class)
cannot express "this behaviour is verified on Android and fails on Desktop",
which is the question this system exists to answer.

Taxonomy is stated **once**. The id prefix (``TASK-REC``) and the directory path
(``tasks/recurrence/``) both encode the area, and the validator cross-checks
them against each other; ``area`` is *derived from the path* rather than
declared independently, so there is no third copy free to drift. A spec that
also listed its test class or its Kiwi case id would be a second source of
truth for linkage, which lives in code — so such fields are rejected outright
rather than ignored.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import Any

import yaml

from traceability import ValidationError

__all__ = [
    "SpecStatus",
    "Target",
    "Level",
    "ScenarioSpec",
    "load_specs",
    "parse_spec",
    "ID_PATTERN",
]

#: Scenario ids are immutable and machine-recognisable: an id is a prefix token
#: in a Kotlin ``@DisplayName`` and a Maestro tag, so it must be unmistakable
#: next to prose. Lowercase ids would be indistinguishable from a sentence.
ID_PATTERN = re.compile(r"^[A-Z]+(?:-[A-Z]+)*-[0-9]+$")

#: Kiwi exposes P1..P5. There is no P0, so P0 is a validation error rather than
#: something to map onto a neighbouring priority.
_VALID_PRIORITIES = frozenset({"P1", "P2", "P3", "P4", "P5"})

#: Fields a spec may contain. Anything else is a hard error: an unknown key is
#: usually a typo, but it can also be a duplicate declaration of something this
#: system owns, and silently ignoring it is how a second source of truth forms.
_ALLOWED_FIELDS = frozenset(
    {
        "id",
        "title",
        "preconditions",
        "steps",
        "expected",
        "priority",
        "status",
        "targets",
        "unreachable",
        "area",
    }
)

#: A spec must not reference automation or Kiwi. Linkage lives in code
#: (``@DisplayName`` / ``scenario:`` tags) precisely so that there is nothing to
#: keep in sync here; a spec listing ``tests:`` or ``kiwi_case:`` would recreate
#: the dual source of truth this design removes.
_FORBIDDEN_FIELDS = frozenset(
    {"tests", "test", "automation", "kiwi", "kiwi_id", "kiwi_case", "case_id", "links", "flow", "flows"}
)


class Target(StrEnum):
    """A platform a scenario is claimed on.

    Claimed, not verified: a target in this list is a promise the coverage
    matrix then audits, and a promise with no test behind it shows up as a hole
    rather than as a silent absence.
    """

    ANDROID = "android"
    DESKTOP = "desktop"


class Level(StrEnum):
    """How a scenario is verified on a target."""

    UNIT = "unit"
    E2E = "e2e"


class SpecStatus(StrEnum):
    """Spec lifecycle only — deliberately *not* execution status.

    ``passed``/``failed`` live on Kiwi's TestExecution, a different entity. They
    never appear in a spec: a spec says what should be true, a result says what
    was observed, and conflating them is how a "passing" scenario ends up
    encoding a check that no longer runs.
    """

    PROPOSED = "proposed"
    CONFIRMED = "confirmed"
    DEPRECATED = "deprecated"


@dataclass(frozen=True, slots=True)
class ScenarioSpec:
    """One validated scenario, plus the path it was read from.

    ``area`` is derived, never read from the file — see the module docstring.
    ``id_prefix`` is the id minus the numeric part, used to cross-check the id
    against the directory it lives in.
    """

    id: str
    title: str
    area: str
    id_prefix: str
    priority: str
    status: SpecStatus
    targets: tuple[Target, ...]
    preconditions: str
    steps: tuple[str, ...]
    expected: str
    path: Path | None = None
    #: Claimed targets that **no automated carrier can reach** on this tier —
    #: a second device, a network fault injector, a device farm, or a human.
    #: Must be a subset of `targets`; a target that is not claimed cannot be
    #: unreachable, and an unreachable one is still an obligation.
    unreachable: tuple[Target, ...] = ()

    @property
    def is_claimed(self) -> bool:
        """A deprecated scenario is not a coverage obligation."""
        return self.status is not SpecStatus.DEPRECATED


def _derive_area(spec_path: Path, scenarios_dir: Path) -> str:
    """``scenarios/tasks/recurrence/X.yaml`` -> ``feature.tasks``.

    The directory hierarchy *is* the taxonomy; Kiwi is not asked to mirror it
    (this Kiwi build exposes no requirements tree at all — ``Requirement.*`` is
    not in the RPC surface and ``TestCase.requirement`` is a free-text field).
    """
    rel = spec_path.relative_to(scenarios_dir)
    parts = rel.parts[:-1]
    if not parts:
        raise ValidationError(
            f"{spec_path}: сценарий должен лежать в каталоге области, "
            f"например scenarios/tasks/recurrence/{spec_path.stem}.yaml"
        )
    if parts[0] in ("core", "feature"):
        return ".".join(parts[:2]) if len(parts) >= 2 else parts[0]
    return f"feature.{parts[0]}"


def _require_str(data: dict[str, Any], key: str, where: str) -> str:
    value = data.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ValidationError(f"{where}: поле '{key}' обязательно и должно быть непустой строкой")
    return value.strip()


def _check_prefix_matches_path(spec_id: str, spec_path: Path, scenarios_dir: Path) -> None:
    """The id prefix and the directory must describe the same area.

    ``TASK-REC-01`` in ``tasks/recurrence/`` agrees. ``TASK-REC-01`` in
    ``notes/recurrence/`` does not, and the mismatch is far more likely to be a
    copy-paste than a deliberate reorganisation — so it is an error, not a
    warning.

    The match is a case-insensitive *prefix* rather than equality, because an id
    segment is deliberately terser than the directory it abbreviates:
    ``TASK`` -> ``tasks``, ``REC`` -> ``recurrence``. Ids must stay short enough
    to sit in a Kotlin string literal and a Maestro tag; making them spell out
    the full path would buy nothing and make every rename a breaking change.
    """
    prefix = spec_id.rsplit("-", 1)[0]  # TASK-REC-01 -> TASK-REC
    rel = spec_path.relative_to(scenarios_dir).parts[:-1]
    id_parts = prefix.split("-")
    if len(id_parts) != len(rel):
        raise ValidationError(
            f"{spec_path}: префикс id '{prefix}' и путь "
            f"{'/'.join(rel)} описывают разное число сегментов области"
        )
    for id_part, dir_part in zip(id_parts, rel, strict=True):
        if not dir_part.lower().startswith(id_part.lower()):
            raise ValidationError(
                f"{spec_path}: сегмент '{id_part}' из id не является началом "
                f"каталога '{dir_part}'"
            )


def parse_spec(data: dict[str, Any], path: Path, scenarios_dir: Path) -> ScenarioSpec:
    """Validate one parsed YAML document into a :class:`ScenarioSpec`.

    Every problem is reported, not just the first: a validator that fails once
    per run turns a five-minute fix into a five-run loop.
    """
    where = path.name
    problems: list[str] = []

    forbidden = _FORBIDDEN_FIELDS & set(data)
    if forbidden:
        problems.append(
            f"поле(я) {sorted(forbidden)} запрещены: связь с автоматизацией живёт "
            f"в коде (@DisplayName / scenario:), а не в спеке"
        )
    unknown = set(data) - _ALLOWED_FIELDS - _FORBIDDEN_FIELDS
    if unknown:
        problems.append(f"неизвестное поле(я): {sorted(unknown)}")

    spec_id = _require_str(data, "id", where)
    if not ID_PATTERN.match(spec_id):
        problems.append(
            f"id '{spec_id}' не соответствует шаблону "
            f"'^[A-Z]+(-[A-Z]+)*-[0-9]+$' (например TASK-REC-01)"
        )
    if path.stem != spec_id:
        problems.append(f"имя файла '{path.stem}.yaml' должно совпадать с id '{spec_id}'")

    priority = _require_str(data, "priority", where)
    if priority not in _VALID_PRIORITIES:
        problems.append(f"priority '{priority}' недопустим: Kiwi знает только P1..P5 (P0 не существует)")

    raw_status = _require_str(data, "status", where)
    try:
        status = SpecStatus(raw_status)
    except ValueError:
        problems.append(f"status '{raw_status}' недопустим: {', '.join(s.value for s in SpecStatus)}")
        status = SpecStatus.CONFIRMED

    raw_targets = data.get("targets")
    targets: tuple[Target, ...] = ()
    if not isinstance(raw_targets, list) or not raw_targets:
        problems.append("targets обязателен и должен быть непустым списком")
    else:
        parsed: list[Target] = []
        for item in raw_targets:
            try:
                parsed.append(Target(item))
            except ValueError:
                problems.append(
                    f"target '{item}' недопустим: {', '.join(t.value for t in Target)}"
                )
        if len(set(parsed)) != len(parsed):
            problems.append("targets содержит повтор")
        targets = tuple(dict.fromkeys(parsed))

    # `unreachable` classifies claimed targets the way no carrier can reach. It
    # exists because a hole was ambiguous and both readings were acted on:
    # TASK-TIME-01 was narrowed to android because a probe failed (wrong — the
    # feature was in commonMain and the screen had stopped rendering it), while
    # SYNC-OFFLINE-01 claims both targets and needs a second device and a
    # flapping network (unverifiable on either). The matrix drew both as `○`, so
    # the two errors were indistinguishable and each looked like the other's fix.
    #
    # A subset invariant, and it is the whole point: a target that is not claimed
    # has no obligation and therefore nothing to be unreachable *for*, and
    # declaring one would be the narrow-claim error wearing a new hat.
    raw_unreachable = data.get("unreachable") or []
    unreachable: tuple[Target, ...] = ()
    if not isinstance(raw_unreachable, list):
        problems.append("unreachable должен быть списком target'ов")
    else:
        parsed_unreachable: list[Target] = []
        for item in raw_unreachable:
            try:
                parsed_unreachable.append(Target(item))
            except ValueError:
                problems.append(
                    f"unreachable: target '{item}' недопустим: "
                    f"{', '.join(t.value for t in Target)}"
                )
        if len(set(parsed_unreachable)) != len(parsed_unreachable):
            problems.append("unreachable содержит повтор")
        unreachable = tuple(dict.fromkeys(parsed_unreachable))
        for item in unreachable:
            if item not in targets:
                problems.append(
                    f"unreachable: '{item.value}' не входит в targets "
                    f"({', '.join(t.value for t in targets) or '—'}). "
                    f"Незаявленный target не является недостижимым — "
                    f"он не заявлен."
                )

    # `title` is validated here rather than in the return statement below, so a
    # missing title is reported *together* with the other problems. Validating
    # it late made it the one field that escaped the accumulate-and-raise
    # contract — the exact "one error per run" loop the docstring promises to
    # avoid.
    title = data.get("title")
    if not isinstance(title, str) or not title.strip():
        problems.append("поле 'title' обязательно и должно быть непустой строкой")
        title = ""

    steps_raw = data.get("steps") or []
    steps: tuple[str, ...] = ()
    if not isinstance(steps_raw, list) or not steps_raw:
        problems.append("steps обязателен: сценарий без шагов невозможно проверить")
    else:
        if not all(isinstance(s, str) and s.strip() for s in steps_raw):
            problems.append("steps должен состоять из непустых строк")
        steps = tuple(s.strip() for s in steps_raw if isinstance(s, str))

    if problems:
        raise ValidationError(f"{where}:\n  - " + "\n  - ".join(problems))

    if ID_PATTERN.match(spec_id):
        _check_prefix_matches_path(spec_id, path, scenarios_dir)

    # `area` is optional and always derived from the path. When a spec states it
    # anyway, the value is cross-checked rather than trusted: the taxonomy is
    # said once (in the path) and the id prefix is checked against it, so a
    # third freely-declared copy could only ever drift.
    derived_area = _derive_area(path, scenarios_dir)
    declared_area = data.get("area")
    if declared_area is not None and declared_area != derived_area:
        raise ValidationError(
            f"{where}: area '{declared_area}' не совпадает с путём "
            f"(ожидается '{derived_area}'). Область выводится из пути: "
            f"scenarios/{'/'.join(path.relative_to(scenarios_dir).parts[:-1])}/"
        )

    return ScenarioSpec(
        id=spec_id,
        title=title.strip(),
        area=derived_area,
        id_prefix=spec_id.rsplit("-", 1)[0],
        priority=priority,
        status=status,
        targets=targets,
        unreachable=unreachable,
        preconditions=str(data.get("preconditions") or "").strip(),
        steps=steps,
        expected=str(data.get("expected") or "").strip(),
        path=path,
    )


def load_specs(scenarios_dir: Path) -> dict[str, ScenarioSpec]:
    """Load every scenario spec under ``scenarios_dir``, keyed by id.

    Duplicate ids are an error rather than last-wins: two files claiming
    ``TASK-REC-01`` would make the coverage matrix a function of glob order, and
    the resulting flakiness would look like a rendering bug for far longer than
    it would take to find this check.
    """
    if not scenarios_dir.is_dir():
        raise ValidationError(f"каталог спеков не найден: {scenarios_dir}")

    specs: dict[str, ScenarioSpec] = {}
    errors: list[str] = []
    for path in sorted(scenarios_dir.rglob("*.yaml")):
        try:
            raw = yaml.safe_load(path.read_text(encoding="utf-8"))
        except yaml.YAMLError as exc:
            errors.append(f"{path.name}: YAML не разобран — {exc}")
            continue
        if not isinstance(raw, dict):
            errors.append(f"{path.name}: ожидался словарь, получено {type(raw).__name__}")
            continue
        try:
            spec = parse_spec(raw, path, scenarios_dir)
        except ValidationError as exc:
            errors.append(str(exc))
            continue
        if spec.id in specs:
            first = specs[spec.id].path
            errors.append(f"дубликат id '{spec.id}': {first} и {path}")
            continue
        specs[spec.id] = spec

    if errors:
        raise ValidationError(
            f"невалидных спеков: {len(errors)}\n  - " + "\n  - ".join(errors)
        )
    return specs

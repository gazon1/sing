"""Scenario declarations inside a speckit spec, and the YAML they generate.

Why this module exists
----------------------
A speckit spec (``specs/<NNN>-<slug>/spec.md``) and a scenario spec
(``scripts/src/traceability/scenarios/<area>/<ID>.yaml``) answer the same question at two
granularities, and until now nothing connected them. That is not a missing
convenience — it is a silent hole in the traceability chain:

* A spec is written, names four acceptance scenarios, and is merged.
* The coverage matrix does not move, because the matrix counts scenario specs.
* Nobody is told, because ``validate`` has nothing to compare the spec against.

The failure is invisible in both directions. There is no gate that reads a spec
and asks which behaviours it claims, and there is no gate that reads a scenario
and asks which feature it belongs to.

The direction of ownership, stated once
--------------------------------------
**Git still holds the truth.** This module does not introduce a database, a
manifest, or a third file that has to agree with the other two. It adds a
*section* to the spec that a generator reads and a checker reads, so that the
spec and the scenario set are two views of one declaration rather than two
independent artefacts that drift.

The split of responsibility inside a scenario is the whole design:

============  ==========================================================
spec.md       ``id``, ``title``, ``area``, ``group``, ``priority``,
              ``status``, ``targets`` — the facts a generator can derive
              from the declaration without inventing behaviour.
scenario YAML ``preconditions``, ``steps``, ``expected`` — what a user
              observes. Never generated, because a generated Given/When/Then
              is a fiction that renders in the matrix as though a human wrote it.
============  ==========================================================

``preconditions``/``steps``/``expected`` are **forbidden** in the declaration.
A spec that states them would be a second source of truth for behaviour, and the
checker would then have to choose which copy to believe.

Why a TODO is a gate failure, not a hint
----------------------------------------
A generated skeleton is a claim in the matrix that nobody has verified yet: it
renders as ``○`` — a hole — which is indistinguishable from a hole a human
deliberately left. Worse, the moment a carrier is attached to a half-written
scenario the cell reads ``●`` and the matrix asserts a verification that does
not exist.

So ``TODO`` in a generated body is **blocking**. The intended loop is
generate → fill → check, and the intermediate state is red on purpose: a
committed scenario is a commitment, and this module refuses to let a commitment
be made by running a command and not finishing the job.

Orphans are reported, not failed
--------------------------------
Most existing scenario specs predate this bridge and belong to no spec file.
Making that a gate failure would block every repository until all of them were
back-filled, and a gate that blocks work unrelated to its own defect gets
routed around. So ``check`` *counts* orphans and prints the list. The count is
a migration state that only moves down, and it is visible on every run.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

from traceability import REPO_ROOT, SCENARIOS_DIR, ValidationError
from traceability._paths import rel_to_repo
from traceability.spec import id_prefix_problems

__all__ = [
    "Declaration",
    "SPEC_SOURCES",
    "SpecSource",
    "spec_roots",
    "TODO_MARKER",
    "extract_declarations",
    "iter_specs",
    "load_declarations",
    "scenario_path",
    "render_skeleton",
    "check_bindings",
]

@dataclass(frozen=True, slots=True)
class SpecSource:
    """One directory of authored specs that may carry a ``## Scenario Matrix``.

    A *source* is a place a human writes, not a generated index. That
    distinction is the whole reason ``.specify/issues/`` is not one of these,
    despite holding 633 records against specs/'s 3: the importer rebuilds each
    record from scratch and writes it wholesale
    (``scripts/openspec-import-all.py`` — ``issue_file.write_text(full_content)``),
    so a scenario block hand-added there survives exactly until the next
    ``--replace`` and then vanishes. A declaration in a generated file is not a
    declaration; it is a race.
    """

    #: Shown in error messages so a problem names the corpus it came from.
    label: str
    #: Root-relative directory.
    directory: str
    #: Glob, relative to ``directory``.
    pattern: str


#: The authored spec corpora, in the order they are read.
#:
#: ``openspec/specs/*`` comes second because it is the larger and older corpus
#: (73 requirements across 8 files, versus specs/'s three features), but specs/
#: is the one `/speckit-specify` writes into, so it is reported first when both
#: declare the same id and the error has to pick one.
SPEC_SOURCES: tuple[SpecSource, ...] = (
    SpecSource("spec-kit", "specs", "*/spec.md"),
    SpecSource("openspec", "openspec/specs", "*/spec.md"),
)

#: The marker the generator writes into a body it will not fill. Checked as a
#: substring of the whole file rather than as a field value, so a TODO pasted
#: into ``expected`` is caught the same as one in ``steps``.
TODO_MARKER = "TODO"

#: Keys a declaration may carry. Anything else is rejected for the same reason
#: ``spec.py`` rejects unknown keys: an unrecognised key is usually a typo, but
#: it can also be a duplicate declaration of something this module owns, and
#: silently ignoring it is how a second source of truth forms.
_ALLOWED_KEYS = frozenset({"id", "title", "area", "group", "priority", "status", "targets"})

#: Keys that would duplicate what the YAML owns. See the module docstring.
_FORBIDDEN_KEYS = frozenset({"preconditions", "steps", "expected", "unreachable"})

_VALID_PRIORITIES = frozenset({"P1", "P2", "P3", "P4", "P5"})
_VALID_STATUSES = frozenset({"proposed", "confirmed"})
_VALID_TARGETS = frozenset({"android", "desktop"})

_HEADING = re.compile(r"^##\s+Scenario\s+Matrix\s*$", re.MULTILINE | re.IGNORECASE)

#: The first ```yaml fence at or after the heading. Non-greedy so the *first*
#: block wins: a spec that documents the format further down must not have its
#: example parsed as a declaration.
_FENCE = re.compile(r"```ya?ml[ \t]*\r?\n(.*?)\r?\n```", re.DOTALL)

_ID_PATTERN = re.compile(r"^[A-Z]+(?:-[A-Z]+)*-[0-9]+$")

#: Ids that may appear in the template but never on disk.
#:
#: `spec-template.md` carries a worked example, and anyone who copies the
#: template without editing the block gets a declaration of `EXAMPLE-01` in
#: `feature.example`. That would generate a scenario whose area exists nowhere in
#: the taxonomy — a hole in the matrix for a feature nobody wrote. Rejecting it
#: costs one error line at the moment the author is still in the file, and saves
#: a phantom row that would otherwise sit in a committed matrix looking real.
_RESERVED_IDS = frozenset({"EXAMPLE-01"})

#: ``feature.agenda`` / ``core.sync`` / ``agenda``. The prefix is optional so a
#: declaration can be terse, but it is checked against the id below, which
#: cannot silently disagree: a ``feature.`` prefix on an id whose first segment
#: matches a ``core/`` directory is rejected.
_AREA = re.compile(r"^(?:(feature|core)\.)?([a-z][a-z0-9_]*)$")

#: One directory level inside an area. Deliberately narrower than ``_AREA``:
#: no ``..``, no empty segment, no uppercase — the group is a path fragment
#: built from the area, and a declaration that can escape its own directory has
#: become a second source of truth for where a scenario lives.
_GROUP_SEGMENT = re.compile(r"^[a-z][a-z0-9_]*$")


@dataclass(frozen=True, slots=True)
class Declaration:
    """One scenario a spec claims to describe, as read from its spec.md."""

    id: str
    title: str
    area: str
    priority: str
    status: str
    targets: tuple[str, ...]
    #: Optional directory levels below the area, ``/``-separated.
    #: ``feature.calendar`` + ``sync/connect`` puts the YAML at
    #: ``scenarios/calendar/sync/connect/`` and requires an id prefix of three
    #: segments, because the id and the directory are cross-checked against each
    #: other by :func:`spec.id_prefix_problems`.
    #:
    #: Multi-level because the pre-existing corpus needs it —
    #: ``CAL-SYNC-CONNECT-01`` lives at ``calendar/sync/connect/``, three levels
    #: down, and a single-level group could not describe it. Declaring the path
    #: verbatim instead would have let a declaration point outside its own area,
    #: which is the second source of truth this module exists to avoid.
    group: str = ""
    #: The ``spec.md`` that declared it, for error messages. Kept as a string
    #: relative to the repo root so a Declaration is comparable and printable.
    spec: str = ""


def spec_roots(sources: tuple[SpecSource, ...] = SPEC_SOURCES) -> list[tuple[SpecSource, Path]]:
    """Every configured source directory that exists.

    Missing directories are skipped rather than reported: ``openspec/specs`` is
    absent in a fresh clone with no changes imported yet, and a bridge that
    cannot start because an optional corpus is empty would be a bridge nobody
    enables.
    """
    return [
        (source, REPO_ROOT / source.directory)
        for source in sources
        if (REPO_ROOT / source.directory).is_dir()
    ]


def iter_specs(sources: tuple[SpecSource, ...] = SPEC_SOURCES) -> list[Path]:
    """Every ``spec.md`` across every configured source, sorted.

    Sorted rather than globbed in filesystem order: the output feeds user-facing
    output and error ordering, and two runs on the same tree must produce the
    same bytes. The sort is by repo-relative path, so the two corpora interleave
    deterministically instead of one shadowing the other.
    """
    found: list[Path] = []
    for _source, root in spec_roots(sources):
        found.extend(root.glob("*/spec.md"))
    return sorted(set(found))


def _dirs_for(decl_area: str, group: str) -> list[str]:
    """The directory segments a declaration's YAML belongs in."""
    segments = [decl_area.split(".", 1)[1] if "." in decl_area else decl_area]
    segments.extend(p for p in group.split("/") if p)
    return segments


def scenario_path(decl: Declaration, scenarios_dir: Path = SCENARIOS_DIR) -> Path:
    """Where this declaration's YAML belongs.

    ``feature.agenda`` + ``saved_views`` -> ``scenarios/agenda/saved_views/``.
    The ``feature.``/``core.`` prefix selects a top-level directory and is not
    part of the path, which is the same rule ``spec._derive_area`` reads back.
    """
    return scenarios_dir.joinpath(*_dirs_for(decl.area, decl.group), f"{decl.id}.yaml")


def _check_id_against_dirs(decl_id: str, dirs: list[str]) -> list[str]:
    """One segment per directory, and each directory starts with the segment.

    Delegates to :func:`spec.id_prefix_problems` — the same rule the loader
    applies — so the declaration side and the loader can never disagree about
    what a valid id looks like. The only thing added here is *where* the error is
    reported: against the spec that wrote the id, rather than against a
    generated file it never asked for.
    """
    return id_prefix_problems(decl_id, tuple(dirs))


def _parse_one(raw: Any, spec_rel: str) -> Declaration:
    """Validate one entry of a declaration block.

    Every problem is reported, not the first: the generator is usually run once
    and then edited, and a validator that stops at the first problem turns one
    run into one fix.
    """
    problems: list[str] = []
    if not isinstance(raw, dict):
        raise ValidationError(
            f"{spec_rel}: ожидался словарь сценария, получено {type(raw).__name__}"
        )

    forbidden = _FORBIDDEN_KEYS & set(raw)
    if forbidden:
        problems.append(
            f"поле(я) {sorted(forbidden)} нельзя объявлять здесь: они описывают "
            f"поведение и принадлежат YAML-сценарию. Объявление поведения в "
            f"спеке создало бы второй источник истины."
        )
    unknown = set(raw) - _ALLOWED_KEYS - _FORBIDDEN_KEYS
    if unknown:
        problems.append(f"неизвестное поле(я): {sorted(unknown)}")

    decl_id = str(raw.get("id", "")).strip()
    if not decl_id:
        problems.append("поле 'id' обязательно")
    elif not _ID_PATTERN.match(decl_id):
        problems.append(
            f"id '{decl_id}' не соответствует шаблону "
            f"'^[A-Z]+(-[A-Z]+)*-[0-9]+$' (например TASK-REC-01)"
        )
    elif decl_id in _RESERVED_IDS:
        problems.append(
            f"id '{decl_id}' зарезервирован шаблоном spec-template. Замените "
            f"пример на реальный сценарий — иначе в матрице появится строка для "
            f"области feature.example, которой нет в таксономии."
        )

    title = str(raw.get("title", "")).strip()
    if not title:
        problems.append("поле 'title' обязательно")

    area = str(raw.get("area", "")).strip()
    group = str(raw.get("group") or "").strip()
    dirs: list[str] = []
    if not area:
        problems.append("поле 'area' обязательно (например feature.tasks)")
    elif not _AREA.match(area):
        problems.append(
            f"area '{area}' недопустима: ожидается 'feature.<x>' или 'core.<x>'"
        )
    else:
        dirs = _dirs_for(area, group)
        if decl_id and _ID_PATTERN.match(decl_id):
            problems.extend(_check_id_against_dirs(decl_id, dirs))
        for level in (group.split("/") if group else []):
            if not _GROUP_SEGMENT.match(level):
                problems.append(
                    f"уровень группы '{level}' недопустим: ожидается "
                    f"'[a-z][a-z0-9_]*', уровни разделяются '/'. Нельзя указать "
                    f"'..' — путь выводится только из area и group."
                )

    priority = str(raw.get("priority", "")).strip()
    if priority not in _VALID_PRIORITIES:
        problems.append(f"priority '{priority}' недопустима: {sorted(_VALID_PRIORITIES)}")

    status = str(raw.get("status", "")).strip()
    if status not in _VALID_STATUSES:
        problems.append(f"status '{status}' недопустим: {sorted(_VALID_STATUSES)}")

    raw_targets = raw.get("targets")
    targets: tuple[str, ...] = ()
    if not isinstance(raw_targets, list) or not raw_targets:
        problems.append("targets обязателен и должен быть непустым списком")
    else:
        seen: list[str] = []
        for item in raw_targets:
            value = str(item).strip()
            if value not in _VALID_TARGETS:
                problems.append(f"target '{value}' недопустим: {sorted(_VALID_TARGETS)}")
            elif value in seen:
                problems.append(f"targets содержит повтор '{value}'")
            else:
                seen.append(value)
        targets = tuple(seen)

    declaration = Declaration(
        id=decl_id,
        title=title,
        area=area,
        priority=priority,
        status=status,
        targets=targets,
        group=group,
        spec=spec_rel,
    )
    if problems:
        raise ValidationError(
            f"{spec_rel}: сценарий '{decl_id or '?'}':\n  - " + "\n  - ".join(problems)
        )
    return declaration


def extract_declarations(spec_path: Path, repo_root: Path = REPO_ROOT) -> list[Declaration]:
    """Parse the ``## Scenario Matrix`` block of one spec.

    Returns an empty list when the section is absent — a feature with no
    user-visible scenario is a legitimate spec, and an absent section says so
    without inventing a declaration.
    """
    text = spec_path.read_text(encoding="utf-8")
    heading = _HEADING.search(text)
    if heading is None:
        return []
    fence = _FENCE.search(text, heading.end())
    if fence is None:
        raise ValidationError(
            f"{rel_to_repo(spec_path, repo_root)}: секция '## Scenario Matrix' есть, но "
            f"в ней нет блока ```yaml. Удалите секцию, если сценариев нет, или "
            f"добавьте блок."
        )
    try:
        raw = yaml.safe_load(fence.group(1))
    except yaml.YAMLError as exc:
        raise ValidationError(
            f"{rel_to_repo(spec_path, repo_root)}: блок сценариев не разобран — {exc}"
        ) from exc
    if raw is None:
        return []
    if not isinstance(raw, dict) or "scenarios" not in raw:
        raise ValidationError(
            f"{rel_to_repo(spec_path, repo_root)}: блок должен быть словарём с ключом "
            f"'scenarios' (пустой список допустим)"
        )
    entries = raw["scenarios"]
    if not isinstance(entries, list):
        raise ValidationError(
            f"{rel_to_repo(spec_path, repo_root)}: 'scenarios' должен быть списком"
        )
    rel = rel_to_repo(spec_path, repo_root)
    return [_parse_one(entry, rel) for entry in entries]





def load_declarations(sources: tuple[SpecSource, ...] = SPEC_SOURCES) -> dict[str, Declaration]:
    """Every declaration across every configured source, keyed by scenario id.

    A duplicate id across two specs is an error, not last-wins: the coverage
    matrix would otherwise become a function of glob order, and two specs both
    claiming ``TASK-REC-01`` is a copy-paste rather than a decision. The message
    carries both repo-relative paths, so it says *which* two specs collided
    rather than leaving the reader to search for the other one.
    """
    declarations: dict[str, Declaration] = {}
    errors: list[str] = []
    for spec_path in iter_specs(sources):
        try:
            found = extract_declarations(spec_path, REPO_ROOT)
        except ValidationError as exc:
            errors.append(str(exc))
            continue
        for declaration in found:
            if not declaration.id:
                continue
            if declaration.id in declarations:
                first = declarations[declaration.id]
                errors.append(
                    f"сценарий '{declaration.id}' объявлен и в {first.spec}, "
                    f"и в {declaration.spec}"
                )
                continue
            declarations[declaration.id] = declaration
    if errors:
        raise ValidationError(
            "невалидных объявлений сценариев: "
            f"{len(errors)}\n  - " + "\n  - ".join(errors)
        )
    return declarations


def render_skeleton(decl: Declaration) -> str:
    """The YAML written for a declaration that has no file yet.

    Deliberately unfilled rather than plausible. A ``steps:`` entry invented
    from the title reads like a specification of behaviour nobody agreed to,
    and the matrix would print it under "What we verify" as though it were a
    commitment.
    """
    targets = ", ".join(decl.targets)
    # json.dumps, not yaml.safe_dump: a flow-style dump of a long string
    # appends the document-end marker, so the first version of this wrote
    # `title: ...` — which parses as the literal string "..." and quietly
    # replaced every scenario title in the matrix with an ellipsis.
    title = json.dumps(decl.title, ensure_ascii=False)
    body = "\n".join(
        [
            f"# Scenario {decl.id} — generated from {decl.spec}.",
            "#",
            "# Skeleton, not a specification. The generator writes the facts a",
            "# declaration already states and refuses to invent the rest, because",
            "# a generated Given/When/Then is a claim about behaviour that nobody",
            "# made. Fill in the three TODOs below and this file stops being a",
            "# gate failure (`python -m traceability scenarios --check`).",
            "#",
            "# Reachability is still yours to establish: `just trace-carrier <ID>`",
            "# writes a probe for a tier this scenario claims. See",
            "# Maestro/CONVENTIONS.md for which surfaces only a Maestro flow can reach.",
            "",
            f"id: {decl.id}",
            f"title: {title}",
            f"priority: {decl.priority}",
            f"status: {decl.status}",
            f"targets: [{targets}]",
            "",
            f"preconditions: >-",
            f"  {TODO_MARKER}: what must already be true before the first step.",
            "",
            "steps:",
            # Quoted, and not for tidiness: a bare `- TODO: one action...` is a
            # YAML *mapping*, so the entry parsed as a dict and failed
            # spec.py's "steps must be non-empty strings" check — a complaint
            # about the wrong file, pointing at the wrong problem. Quoted, the
            # step loads as a string and the TODO check below reports it with
            # the message that tells you what to do about it.
            f'  - "{TODO_MARKER}: one action a user can perform."',
            "",
            f"expected: >-",
            f"  {TODO_MARKER}: what the user observes, stated so that a wrong",
            f"  implementation would fail it.",
            "",
        ]
    )
    return body


def check_bindings(
    declarations: dict[str, Declaration],
    specs: dict[str, Any],
    scenarios_dir: Path = SCENARIOS_DIR,
) -> tuple[list[str], list[str]]:
    """Compare declarations against what is on disk.

    Returns ``(problems, orphans)``. ``problems`` are blocking: a declaration
    with no file, a file that disagrees with its declaration, a skeleton that
    still carries a TODO, or two files for one id. ``orphans`` are scenario
    specs no spec file declares — reported, never fatal, because most of them
    predate this bridge.
    """
    problems: list[str] = []
    declared_ids = set(declarations)

    for decl_id, decl in sorted(declarations.items()):
        spec = specs.get(decl_id)
        if spec is None:
            expected = rel_to_repo(scenario_path(decl, scenarios_dir))
            exists = any(
                candidate.name == f"{decl_id}.yaml"
                for candidate in scenarios_dir.rglob(f"{decl_id}.yaml")
            )
            problems.append(
                f"{decl_id}: объявлен в {decl.spec}, но YAML-сценария нет. "
                + (
                    f"Ожидался {expected}. "
                    if not exists
                    else f"Файл лежит не по адресу {expected} — "
                    "путь выводится из area и group, а не задаётся вручную. "
                )
                + "Создайте его: just trace-scenarios"
            )
            continue

        path: Path = spec.path
        if TODO_MARKER in path.read_text(encoding="utf-8"):
            problems.append(
                f"{decl_id}: {rel_to_repo(path)} всё ещё содержит "
                f"{TODO_MARKER}. Незаполненный сценарий заявляет клетку в "
                f"матрице, которую никто не проверял — заполните preconditions, "
                f"steps и expected."
            )

        if spec.title != decl.title:
            problems.append(
                f"{decl_id}: title в спеке и YAML разошлись.\n"
                f"    {decl.spec}: {decl.title!r}\n"
                f"    {rel_to_repo(path)}: {spec.title!r}"
            )
        if spec.priority != decl.priority:
            problems.append(
                f"{decl_id}: priority в спеке {decl.priority!r}, "
                f"в YAML {spec.priority!r}"
            )
        if spec.status.value != decl.status:
            problems.append(
                f"{decl_id}: status в спеке {decl.status!r}, "
                f"в YAML {spec.status.value!r}"
            )
        declared_targets = tuple(t.value for t in spec.targets)
        if declared_targets != decl.targets:
            problems.append(
                f"{decl_id}: targets в спеке {list(decl.targets)}, "
                f"в YAML {list(declared_targets)}"
            )
        if not spec.is_claimed:
            problems.append(
                f"{decl_id}: спек объявляет status '{decl.status}', а YAML помечает "
                f"сценарий как deprecated. Deprecated-сценарий не является "
                f"обязательством — уберите его из объявления."
            )

    orphans = sorted(set(specs) - declared_ids)
    return problems, orphans
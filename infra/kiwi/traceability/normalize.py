"""Normalisation: raw JUnit/Maestro XML → the one canonical result format.

One pure step, no network, fully unit-tested. It emits **only** ``results.json``;
the Kiwi-shaped payload is produced later by the publish adapter, so there is
exactly one representation of the truth and no chance of the two drifting.

Everything here is deterministic, which is what lets the coverage matrix be
committed and checked in CI, and lets the result matrix be regenerated for a
named commit without anyone maintaining it by hand.

The rules that are errors rather than warnings are deliberate:

* a testcase that does not resolve through the links is **dropped** — the 259
  legacy tests stay untracked rather than becoming cases;
* an id with no spec file is an **error**, because a claim nobody can look up is
  not a claim;
* a scenario claimed by two tests is an **error**, not a merge — a merged cell
  hides the disagreement the matrix exists to show;
* a declared target that produced **zero** testcases is an **error** (exit 2).
  A Gradle task that ran nothing reports green, and letting that into the
  matrix is the "quietly green" failure this system exists to catch;
* a scenario with no automation is **not** an error. That is a coverage hole,
  and the hole is the point.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from pathlib import Path

from traceability import REPO_ROOT, ValidationError
from traceability.coverage import Outcome, link_index
from traceability.junit_xml import TestCaseResult, parse_junit
from traceability.keys import TestKey
from traceability.links import Carrier, Link
from traceability.spec import ScenarioSpec, Target

__all__ = [
    "NormalisedResult",
    "NormaliseReport",
    "NoResultsError",
    "normalise",
    "write_results",
    "read_results",
    "EXIT_NO_RESULTS",
]


class NoResultsError(ValidationError):
    """A declared target produced zero testcases.

    Its own type rather than a message match, so the CLI's exit code depends on
    *what* went wrong and not on how the message happens to be worded — a
    reworded message would otherwise silently downgrade a caught failure into a
    generic validation error and lose the "quietly green" signal in CI.
    """

#: Distinct from 1 (a validation error) so CI can tell "the specs are wrong"
#: from "the run produced nothing to say about a target that was claimed".
EXIT_NO_RESULTS = 2

#: JUnit status -> our outcome. ``error`` is a failing outcome: an
#: infrastructure error must never be laundered into a pass.
_STATUS_TO_OUTCOME = {
    "passed": Outcome.PASSED,
    "failed": Outcome.FAILED,
    "error": Outcome.FAILED,
    "skipped": Outcome.SKIPPED,
}

#: A Maestro flow's JUnit ``classname`` is derived by the reporter, not by us,
#: so a flow is joined on its file attribute instead. Anything carrying this is
#: a candidate for a path join.
_FLOW_FILE_ATTR = "file"


@dataclass(frozen=True, slots=True)
class NormalisedResult:
    """One result row — the canonical shape written to ``results.json``.

    ``commit`` is part of every row rather than a wrapper field so a row can
    never be mixed with a matrix built for a different commit: staleness is
    decided by commit identity, not by file age.
    """

    scenario: str
    target: str
    level: str
    outcome: str
    commit: str
    carrier: str
    source: str
    detail: str = ""
    time: float = 0.0

    def to_json(self) -> dict[str, object]:
        return asdict(self)


@dataclass
class NormaliseReport:
    """What normalisation did, including what it refused to do.

    The counters exist so a surprise in Kiwi (unexpected case growth) has a
    first question with a cheap answer: how many testcases were kept, how many
    dropped, how many resolved to a scenario but matched nothing.
    """

    results: list[NormalisedResult] = field(default_factory=list)
    kept: int = 0
    dropped: int = 0
    unmapped: int = 0
    per_target: dict[str, int] = field(default_factory=dict)


def _match_flow(index: dict[TestKey | Path, Link], case: TestCaseResult) -> Link | None:
    """Match a Maestro testcase to a flow link via its ``file`` attribute.

    A flow link carries no ``TestKey``: its JUnit ``classname`` is derived by the
    reporter and is not a path, so ``file`` is the only honest key. Verified in
    Maestro 2.10.0's ``JUnitTestSuiteReporter$TestCase``, which declares a
    ``file: String`` field — without it the Android tier of this system has no
    data source at all, and flow rows would be indistinguishable from the 259
    legacy tests in the ``dropped`` counter.

    The attribute's *base* is tried against several roots rather than assumed.
    The reporter writes whatever path its own workspace logic produced — the
    flow path as given, or resolved against the workspace — and guessing wrong
    would silently drop every flow result while looking like "no Android
    automation ran". Matching any of the plausible bases costs three dictionary
    lookups and removes the guess entirely.

    Returns ``None`` when there is no usable attribute, which sends the row to
    ``unmapped`` rather than guessing: a wrong join here would attach one flow's
    result to another flow's scenario, which is worse than no data.
    """
    if not case.file:
        return None
    raw = Path(case.file)
    if raw.is_absolute():
        return index.get(raw.resolve())
    for base in _FLOW_ROOTS:
        candidate = (base / raw).resolve()
        link = index.get(candidate)
        if link is not None:
            return link
    return None


#: Bases a Maestro ``file`` attribute may be relative to. A flow is addressed in
#: the repository as ``Maestro/flows/<category>/<name>.yaml``, but a reporter
#: invoked from the workspace root may also emit it with or without the leading
#: ``Maestro/`` segment. Both are real shapes; neither is canonical here.
_FLOW_ROOTS: tuple[Path, ...] = (
    REPO_ROOT / "Maestro",
    REPO_ROOT,
    Path.cwd() / "Maestro",
    Path.cwd(),
)
def normalise(
    specs: dict[str, ScenarioSpec],
    links: list[Link],
    result_dirs: dict[Target, list[Path]],
    commit: str,
    expected_commit: str | None = None,
    partial: bool = False,
) -> NormaliseReport:
    """Build the canonical result set from raw XML and the link index.

    ``expected_commit`` guards the caller that read a previously written
    ``results.json``: a report whose commit differs from the requested one is
    rejected outright, so a regenerated-but-old report cannot masquerade as
    current. A 24-hour heuristic would be the wrong tool — it is a guess about
    the clock when the answer is written in the file.

    ``partial`` says the run was tag-filtered rather than complete. It is a
    fact only the caller knows, and guessing it here is impossible: the
    normaliser can see that a carrier exists in the sources and that no result
    mentions it, but it cannot tell a filtered-out class from a broken one. The
    distinction matters because the default local run excludes ``@Tag("slow")``
    and every scenario carrier is slow, so enforcing the per-scenario rule on a
    fast-only local run reports a defect where the developer chose a subset.
    Default is the strict reading, because a rule that silently stops matching
    is worse than one that asks a question the caller has to answer.
    """
    if expected_commit and commit != expected_commit:
        raise ValidationError(
            f"результаты относятся к коммиту {commit}, запрошен {expected_commit}: "
            f"отчёт устарел по идентичности коммита, а не по времени файла"
        )
    if not commit:
        raise ValidationError("не указан коммит: результат без коммита нельзя ни отличить, ни отозвать свежим")

    index = link_index(links)
    report = NormaliseReport()
    seen: dict[tuple[str, Target], NormalisedResult] = {}
    errors: list[str] = []
    #: Targets this invocation actually attempted, as opposed to targets it was
    #: never asked about. Derived from the directories it was handed, so a
    #: desktop-only local run does not claim to have covered Android.
    ran_targets: set[Target] = {t for t, dirs in result_dirs.items() if dirs}
    #: (scenario, target.value) slots that produced a result, for the
    #: per-scenario completeness rule below.
    reported: set[tuple[str, str]] = set()

    for target, directories in sorted(result_dirs.items(), key=lambda kv: kv[0].value):
        raw = parse_junit(directories)
        produced = 0
        for case in raw:
            key = TestKey.from_xml(case.classname, case.name)
            link = index.get(key)
            if link is None:
                # A Maestro flow reports a path, not a class+method pair, so it
                # never resolves through the TestKey index.
                link = _match_flow(index, case)
                if link is None and case.file:
                    report.unmapped += 1
            if link is None:
                report.dropped += 1
                continue
            if link.target is not target:
                errors.append(
                    f"{case.classname}.{case.name}: тест заявлен на {link.target.value}, "
                    f"а его результат пришёл в разделе {target.value}"
                )
                continue
            if link.scenario not in specs:
                errors.append(f"{key}: сценарий '{link.scenario}' не имеет спека")
                continue

            slot = (link.scenario, target)
            if slot in seen:
                errors.append(
                    f"сценарий '{link.scenario}' на {target.value} имеет несколько результатов: "
                    f"{seen[slot].source} и {key}"
                )
                continue

            outcome = _STATUS_TO_OUTCOME[case.status]
            result = NormalisedResult(
                scenario=link.scenario,
                target=target.value,
                level=link.level.value,
                outcome=outcome.value,
                commit=commit,
                carrier=link.carrier.value,
                source=_rel_source(link.source),
                detail=link.detail,
                time=case.time,
            )
            seen[slot] = result
            report.results.append(result)
            report.kept += 1
            produced += 1
            reported.add((link.scenario, target.value))
        report.per_target[target.value] = produced

    if errors:
        raise ValidationError("проблемы нормализации:\n  - " + "\n  - ".join(errors))

    # A claimed target that produced nothing is a hard error, not a warning —
    # but only when it actually RAN. A target with no result directory at all is
    # a target this invocation was not asked to cover (a desktop-only local run,
    # or a CI job where the Android flows live in a different job), and
    # enforcing the rule there would fail a build for the absence of a run
    # nobody scheduled. The distinction is the whole point: "ran and produced
    # nothing" is the quiet-green bug, "was never run" is a fact the matrix
    # already reports as not-run.
    #
    # And on a partial run the rule has no answer at all, for the same reason the
    # per-scenario rule below has none: the run is a subset, so absence is what
    # the caller asked for. This was measured, not designed — `just trace-results`
    # after the documented fast cycle failed here with "produced no testcase",
    # which is exactly the subset it was told to accept. The first version of the
    # `--partial` flag suppressed only the per-scenario rule, on the assumption
    # that the per-target one was immune to tag filtering. It is not: the target
    # runs, produces ten unrelated XMLs, and no *scenario* testcase appears.
    empty: list[str] = []
    for spec in specs.values():
        if partial:
            break
        if not spec.is_claimed:
            continue
        for target in spec.targets:
            directories = result_dirs.get(target) or []
            if not directories:
                continue
            if report.per_target.get(target.value, 0) == 0:
                empty.append(f"{spec.id}/{target.value}")
    if empty:
        raise NoResultsError(
            "заявленная цель не дала ни одного тесткейса (задача Gradle отработала вхолостую "
            f"и отчиталась зелёной): {', '.join(sorted(set(empty)))}"
        )

    # The rule above is per *target*, and that is its blind spot. A target that
    # produced 200 testcases passes it even when the one testcase carrying a
    # scenario id never ran — which is exactly what a tag filter does: the class
    # is skipped rather than executed, the suite is green, and the scenario
    # renders as not-run forever. Nothing is broken and nothing fails.
    #
    # So the same distinction is applied one level down, per *scenario*: a
    # target that actually ran, and a scenario that claims that target and has a
    # carrier for it, must have produced a result. The unclaimed and never-run
    # cases stay out of it for the same reason they stay out of the rule above —
    # a local desktop-only run must not fail over Android, and a hole is a hole,
    # not an error.
    #
    # Skipped entirely on a partial run, which is the tag-filtered case the
    # parameter documents: there, "no result" is what the developer asked for.
    missing: list[str] = []
    for link in [] if partial else links:
        if link.target not in ran_targets:
            continue
        spec = specs.get(link.scenario)
        if spec is None or not spec.is_claimed:
            continue
        # `is_claimed` answers "is this scenario still an obligation" (a
        # deprecated one is not). This rule is about a different question:
        # whether *this target* is claimed. A link for an unclaimed target is
        # the hole case, not a missing result — the coverage matrix already
        # renders it as not-claimed, and failing here would make an
        # intentionally-narrow scenario unaddable.
        if link.target not in spec.targets:
            continue
        slot = (link.scenario, link.target.value)
        if slot not in reported:
            missing.append(f"{link.scenario}/{link.target.value}")
    if missing:
        raise NoResultsError(
            "сценарий заявлен и запускался, но не дал результата на этом коммите "
            f"(отфильтрован тегом или класс не попал в прогон): "
            f"{', '.join(sorted(set(missing)))}"
        )
    return report


def _rel_source(path: Path) -> str:
    """Repo-relative source path where possible.

    An absolute path would make ``results.json`` machine-specific, and the file
    is a CI artifact that gets compared across runners.
    """
    try:
        return path.resolve().relative_to(REPO_ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def write_results(report: NormaliseReport, out_dir: Path) -> Path:
    """Write ``results.json`` — the canonical form the publish adapter consumes.

    Only the JSON is written here. The result *matrix* is derived from the same
    rows by the caller, so there is exactly one representation of the truth and
    no chance of the two drifting.
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    json_path = out_dir / "results.json"
    payload = {
        "commit": report.results[0].commit if report.results else "",
        "kept": report.kept,
        "dropped": report.dropped,
        "results": [r.to_json() for r in sorted(report.results, key=lambda r: (r.scenario, r.target))],
    }
    json_path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return json_path


def read_results(path: Path) -> tuple[str, list[NormalisedResult]]:
    """Read a previously written ``results.json``, keeping its commit.

    Returns the commit alongside the rows so the caller can enforce commit
    identity. Reading a file without its commit is how a stale report gets
    presented as current.

    A malformed file is a :class:`ValidationError`, not a ``JSONDecodeError``
    escaping as a traceback: this is a hand-edited or truncated CI artifact, it
    is entirely predictable, and the caller already has a path for reporting it
    with a non-zero exit.
    """
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as exc:
        raise ValidationError(f"{path}: нечитаемый файл результатов — {exc}") from exc
    if not isinstance(payload, dict):
        raise ValidationError(f"{path}: ожидался объект JSON, получено {type(payload).__name__}")
    try:
        rows = [NormalisedResult(**row) for row in payload.get("results", [])]
    except TypeError as exc:
        raise ValidationError(f"{path}: строка результата не соответствует схеме — {exc}") from exc
    return payload.get("commit", ""), rows

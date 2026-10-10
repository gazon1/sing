"""``python -m traceability <command>`` — the single entry point.

Commands, in dependency order:

* ``validate`` — specs + links + namespace hygiene. No stand, no network, ~ms.
* ``coverage`` — regenerate (or check) the committed coverage matrix.
* ``results``  — normalise JUnit/Maestro XML into ``results.json`` + a matrix.
* ``seed``     — project the specs into Kiwi (a projection, never a gate).
* ``publish``  — push ``results.json`` into Kiwi as execution history.

Exit codes are meaningful, because CI acts on them:

* ``0`` success
* ``1`` validation error (bad spec, bad link, stale commit)
* ``2`` a declared target produced zero testcases — the "quietly green" case
* ``3`` Kiwi was unreachable. Distinct from 1 so a stand outage reads as an
  infrastructure failure rather than as a broken spec.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

from traceability import (
    COVERAGE_MATRIX_PATH,
    REPO_ROOT,
    SCENARIOS_DIR,
    ValidationError,
)
from traceability.coverage import build_coverage, build_results
from traceability.links import Link, scan_all
from traceability.normalize import (
    EXIT_NO_RESULTS,
    NoResultsError,
    normalise,
    write_results,
)
from traceability.render import render_coverage_matrix, render_result_matrix
from traceability.spec import Target, load_specs

__all__ = ["main"]

#: Where normalised artefacts land. Under ``build/`` on purpose: results are a
#: CI artifact, and nothing here should ever be committed.
OUTPUT_DIR = REPO_ROOT / "build" / "traceability"

#: Raw result directories per target. These are the Gradle output locations
#: that exist in this repo, checked rather than assumed — a task that never ran
#: simply contributes nothing, which is what makes the zero-testcase rule the
#: thing that catches a hollow green build.
_RESULT_DIRS: dict[Target, tuple[str, ...]] = {
    Target.ANDROID: (
        "androidApp/build/test-results/connectedDebugAndroidTest",
        "androidApp/build/test-results/testDebugUnitTest",
    ),
    Target.DESKTOP: ("desktopApp/build/test-results/test",),
}

#: Maestro's JUnit output, when the flows were run with --format=JUNIT.
MAESTRO_RESULT_DIR = REPO_ROOT / "build" / "maestro-results"


def _git_commit() -> str:
    """``git rev-parse --short HEAD``, or ``local`` outside a checkout."""
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
        return out.stdout.strip() or "local"
    except (OSError, subprocess.SubprocessError):  # pragma: no cover - defensive
        return "local"


def _result_dirs(target: Target, explicit: str | None, include_maestro: bool) -> list[Path]:
    if explicit:
        return [Path(p) for p in explicit.split(",") if p]
    dirs = [REPO_ROOT / rel for rel in _RESULT_DIRS[target]]
    if target is Target.ANDROID and include_maestro:
        dirs.append(MAESTRO_RESULT_DIR)
    return [d for d in dirs if d.is_dir()]


def _load(args) -> tuple[dict, list[Link]]:
    specs = load_specs(Path(args.specs) if args.specs else SCENARIOS_DIR)
    links = scan_all(specs, REPO_ROOT)
    return specs, links


def cmd_validate(args) -> int:
    specs, links = _load(args)
    holes = build_coverage(specs, links).holes()
    if args.quiet:
        # Used by the CI gate, which only needs the verdict. Errors are still
        # printed by the caller, so a quiet run is never a silent one.
        return 0
    print(f"✓ спеков: {len(specs)}")
    print(f"✓ связей: {len(links)}")
    for link in sorted(links, key=lambda l: (l.scenario, l.target.value)):
        print(f"    {link.scenario} → {link.target.value} ({link.carrier.value}) {link.source.name}")
    if holes:
        print(f"○ дыр в покрытии: {len(holes)} (это не ошибка — это и есть смысл матрицы)")
        for scenario_id, target in holes:
            print(f"    {scenario_id} / {target.value}")
    return 0


def cmd_coverage(args) -> int:
    specs, links = _load(args)
    rendered = render_coverage_matrix(build_coverage(specs, links))
    path = Path(args.out) if args.out else COVERAGE_MATRIX_PATH
    if args.check:
        if not path.exists():
            print(f"✗ матрица покрытия отсутствует: {path}", file=sys.stderr)
            return 1
        current = path.read_text(encoding="utf-8")
        if current != rendered:
            print(
                f"✗ закоммиченная матрица покрытия устарела: {path}\n"
                f"  обновите: just trace-coverage",
                file=sys.stderr,
            )
            return 1
        print(f"✓ матрица покрытия актуальна: {path}")
        return 0
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(rendered, encoding="utf-8")
    print(f"✓ матрица покрытия записана: {path}")
    return 0


def cmd_results(args) -> int:
    specs, links = _load(args)
    # Which targets were actually run in this invocation. A target left out is
    # reported as `not-run` in the matrix and is *not* subject to the
    # zero-testcase rule — that rule exists to catch a task that ran and
    # produced nothing, which is different from a task that was never asked to
    # run. Conflating them would make partial local runs impossible to express
    # and would force a full run before any partial one could be checked.
    selected: tuple[Target, ...]
    if args.targets:
        selected = tuple(Target(t.strip()) for t in args.targets.split(",") if t.strip())
    else:
        selected = tuple(Target)

    result_dirs = {target: _result_dirs(target, args.dirs, args.maestro) for target in selected}
    commit = args.commit or _git_commit()
    report = normalise(specs, links, result_dirs, commit, partial=args.partial)

    # Parse --missing-scenarios: format is "scenario/target,scenario/target,..."
    # These are (scenario, target) pairs that CI claimed but never attempted.
    # Rendered as Outcome.MISSING (❌) in the matrix instead of Outcome.NOT_RUN (⌛).
    missing: set[tuple[str, Target]] = set()
    if args.missing_scenarios:
        for item in args.missing_scenarios.split(","):
            item = item.strip()
            if "/" not in item:
                print(f"WARNING: пропущен элемент без '/': {item!r}", file=sys.stderr)
                continue
            scenario_id, target_str = item.rsplit("/", 1)
            scenario_id = scenario_id.strip()
            target_str = target_str.strip()
            try:
                target = Target(target_str)
            except ValueError:
                print(f"WARNING: неизвестная цель {target_str!r}, пропущено", file=sys.stderr)
                continue
            missing.add((scenario_id, target))

    out_dir = Path(args.out_dir) if args.out_dir else OUTPUT_DIR
    json_path = write_results(report, out_dir)

    matrix = build_results(
        build_coverage(specs, links),
        [
            (
                next(
                    link
                    for link in links
                    if link.scenario == r.scenario and link.target.value == r.target
                ),
                r.outcome,
                r.detail,
            )
            for r in report.results
        ],
        commit,
        kept=report.kept,
        dropped=report.dropped,
        unmapped=report.unmapped,
        missing=missing,
    )
    (out_dir / "result-matrix.md").write_text(render_result_matrix(matrix), encoding="utf-8")

    print(f"✓ коммит: {commit}")
    print(f"✓ результатов: {report.kept} (отброшено не связанных: {report.dropped})")
    print(f"✓ {json_path}")
    for scenario_id, row in sorted(matrix.cells.items()):
        glyphs = " ".join(f"{t.value}={cell.glyph}" for t, cell in sorted(row.items(), key=lambda kv: kv[0].value))
        print(f"    {scenario_id}: {glyphs}")
    return 0


def cmd_seed(args) -> int:  # pragma: no cover - needs a stand
    from traceability.kiwi_seed import run_seed

    specs, _ = _load(args)
    return run_seed(specs, dry_run=args.dry_run, check=args.check)


def cmd_publish(args) -> int:  # pragma: no cover - needs a stand
    from traceability.kiwi_publish import run_publish

    # Validating specs/links here too is deliberate: publishing a results file
    # whose scenario no longer has a valid spec would write history for a claim
    # that can no longer be checked. The adapters still receive only the file.
    _load(args)
    return run_publish(Path(args.results), commit=args.commit, dry_run=args.dry_run)


def cmd_carrier(args) -> int:
    """Write the reachability probe for one scenario/target pair.

    Never overwrites: an existing carrier means the work is done, and silently
    replacing a real test with a generated probe would be the worst outcome this
    command could have.
    """
    from traceability.carrier import CarrierError, plan_carrier, render_probe

    specs, links = _load(args)
    scenario = args.scenario.upper()
    if scenario not in specs:
        known = ", ".join(sorted(specs))
        print(f"ERROR: сценарий '{scenario}' не найден. Известны: {known}")
        return 1

    spec = specs[scenario]
    target = Target(args.target)

    # Checked before the target check, because a deprecated scenario *keeps* the
    # targets it used to have — that is how its row still shows which platforms
    # it covered — so the target check alone waves it through. Writing a probe
    # for a retired scenario re-adds the obligation the deprecation removed, and
    # the matrix would carry a `●` on a row whose glyph is `⊘`.
    if not spec.is_claimed:
        print(
            f"ERROR: {scenario} выведен из эксплуатации (status: deprecated).\n"
            f"Носитель для него не пишется: это вернуло бы снятое обязательство."
        )
        return 1

    if target not in spec.targets:
        claimed = ", ".join(t.value for t in spec.targets) or "—"
        print(
            f"ERROR: {scenario} не заявляет '{target.value}' (заявляет: {claimed}).\n"
            f"Сначала решите, верно ли это — сужение спеки под платформу без "
            f"измерения и есть то, что сделало TASK-TIME-01 неверным."
        )
        return 1

    # The check that would have saved the most time this session. A probe failing
    # is the exact input to two opposite mistakes: narrow the spec (TASK-TIME-01,
    # wrong — the feature was in commonMain) or write the test anyway. A spec that
    # says the target is unreachable is a claim about the *tier*, made on purpose,
    # so the generator refuses instead of producing a probe that cannot pass.
    if target in spec.unreachable:
        print(
            f"ERROR: {scenario} помечает '{target.value}' как недостижимый "
            f"(unreachable).\n"
            f"Зонд тут бесполезен: сценарий заявлен, но ни один автоматический "
            f"носитель на этом тире его не достанет — нужно второе устройство, "
            f"управление сетью или ручной прогон.\n"
            f"Почему это важно: провалившийся зонд раньше читался двумя "
            f"противоположными способами, и оба применялись. Если поле стоит "
            f"не по делу — уберите его из спеки, и тогда разбираться будет с чем."
        )
        return 1

    # The link set, not the filename, is what says a carrier exists. Checking
    # for a file at the computed path found nothing for `TASK-REC-01` and wrote
    # a second, competing carrier next to the real one — the generated name
    # `TaskRec01ScenarioTest` and the hand-written `TaskRecurrenceScenarioTest`
    # have nothing to do with each other, and a filename check cannot see that.
    # A second carrier for one (scenario, target) is rejected by `validate`, so
    # the generator would have produced a tree that does not validate.
    existing = [link for link in links if link.scenario == scenario and link.target is target]
    if existing:
        for link in existing:
            print(f"carrier: {scenario} / {target.value} уже несёт {link.source.name}")
        print("Перезапись запрещена: ваш собственный тест и есть носитель.")
        print("Удалите его осознанно, если хотите начать заново.")
        return 1

    try:
        plan = plan_carrier(spec, target, REPO_ROOT)
        text = render_probe(plan)
    except CarrierError as exc:
        print(f"ERROR: {exc}")
        return 1

    if plan.exists:
        print(f"carrier: {plan.path} уже существует — перезапись запрещена.")
        print("Если это ваш собственный тест, он и есть носитель; удалите его")
        print("осознанно, если хотите заново.")
        return 1

    if args.dry_run:
        print(f"carrier: {plan.path} (dry run, не записан)\n")
        print(text)
        return 0

    plan.path.parent.mkdir(parents=True, exist_ok=True)
    plan.path.write_text(text, encoding="utf-8")
    print(f"carrier: записан {plan.path}")
    print(f"  @DisplayName(\"{plan.display_name}\") — id обязан быть первым токеном")
    print(f"  Замените REPLACE_ME на testTag цели и пройдите навигацию как в тесте.")
    print(f"  Затем: just trace-coverage && just trace-carrier-check")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m traceability",
        description="Spec-first traceability: specs in Git, results from CI, Kiwi as a projection.",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    def common(p: argparse.ArgumentParser) -> None:
        p.add_argument("--specs", help=f"каталог спеков (по умолчанию {SCENARIOS_DIR})")

    p_validate = sub.add_parser("validate", help="проверить спеки и связи (без стенда)")
    common(p_validate)
    p_validate.add_argument(
        "--quiet", action="store_true", help="только код возврата (для гейта в CI)"
    )
    p_validate.set_defaults(func=cmd_validate)

    p_coverage = sub.add_parser("coverage", help="сгенерировать матрицу покрытия")
    common(p_coverage)
    p_coverage.add_argument("--out", help=f"куда писать (по умолчанию {COVERAGE_MATRIX_PATH})")
    p_coverage.add_argument(
        "--check",
        action="store_true",
        help="сравнить с закоммиченной копией и упасть при расхождении (CI)",
    )
    p_coverage.set_defaults(func=cmd_coverage)

    p_carrier = sub.add_parser(
        "carrier",
        help="сгенерировать зонд достижимости для сценария (пишет файл, если его нет)",
    )
    common(p_carrier)
    p_carrier.add_argument("scenario", help="id сценария, например TASK-REC-01")
    p_carrier.add_argument(
        "--target",
        default="desktop",
        choices=[t.value for t in Target],
        help="тир носителя (по умолчанию desktop)",
    )
    p_carrier.add_argument(
        "--dry-run",
        action="store_true",
        help="показать путь и текст, ничего не записывая",
    )
    p_carrier.set_defaults(func=cmd_carrier)

    p_results = sub.add_parser("results", help="нормализовать JUnit/Maestro XML в results.json")
    common(p_results)
    p_results.add_argument("--commit", help="коммит, к которому относятся результаты (по умолчанию git HEAD)")
    p_results.add_argument("--dirs", help="каталоги с XML через запятую, переопределяют умолчания")
    p_results.add_argument(
        "--targets",
        help="цели, которые реально прогонялись, через запятую (android, desktop). "
        "Не указанные цели попадают в матрицу как not-run и не проверяются правилом "
        "'цель не дала ни одного тесткейса'",
    )
    p_results.add_argument("--maestro", action="store_true", help="учесть XML потоков Maestro")
    p_results.add_argument(
        "--partial",
        action="store_true",
        help="прогон был отфильтрован по тегам и не покрывает все сценарии "
        "цели. Без этого флага действует правило 'сценарий заявлен и "
        "запускался, но не дал результата' — оно верно для CI (прогон "
        "fast+slow) и ложно для локального прогона по умолчанию, который "
        "исключает @Tag(\"slow\"), а носители сценариев помечены slow. "
        "Локальный рецепт just trace-results передаёт этот флаг.",
    )
    p_results.add_argument(
        "--missing-scenarios",
        help="сценарии, которые были заявлены в CI, но не запускались (device unavailable и т.п.). "
        "Формат: scenario/target,scenario/target,... например 'smoke-launch-today/android,auth-01/android'. "
        "Эти сценарии попадают в матрицу как 'missing' (glyph ❌) вместо 'not-run' (glyph ⌛), "
        "что позволяет отличить 'CI заявляла цель, но этот сценарий не запустила' "
        "от 'сценарий запустился, но не дал результата'.",
    )
    p_results.add_argument("--out-dir", help=f"куда писать (по умолчанию {OUTPUT_DIR})")
    p_results.set_defaults(func=cmd_results)

    p_seed = sub.add_parser("seed", help="спроецировать спеки в Kiwi")
    common(p_seed)
    p_seed.add_argument("--dry-run", action="store_true", help="ничего не писать")
    p_seed.add_argument("--check", action="store_true", help="упасть при расхождении Kiwi и спеков")
    p_seed.set_defaults(func=cmd_seed)

    p_publish = sub.add_parser("publish", help="загрузить результаты в Kiwi как историю прогонов")
    common(p_publish)
    p_publish.add_argument("--results", default=str(OUTPUT_DIR / "results.json"), help="файл results.json")
    p_publish.add_argument("--commit", help="коммит (по умолчанию — из results.json)")
    p_publish.add_argument("--dry-run", action="store_true", help="ничего не писать")
    p_publish.set_defaults(func=cmd_publish)

    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except NoResultsError as exc:
        # Distinct exit code: "a target ran and produced nothing" is a
        # different diagnosis from "the specs are wrong", and CI acts on it
        # differently.
        print(f"✗ {exc}", file=sys.stderr)
        return EXIT_NO_RESULTS
    except ValidationError as exc:
        print(f"✗ {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:  # pragma: no cover
        return 130


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())

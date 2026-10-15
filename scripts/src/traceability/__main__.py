"""``python -m traceability <command>`` — the single entry point.

Commands, in dependency order:

* ``validate`` — specs + links + namespace hygiene. No stand, no network, ~ms.
* ``coverage`` — regenerate (or check) the committed coverage matrix.
* ``results``  — normalise JUnit/Maestro XML into ``results.json`` + a matrix.
* ``scenarios`` — bridge between spec declarations and YAML files on disk.
* ``carrier``  — generate a reachability probe for one scenario/target pair.

Exit codes are meaningful, because CI acts on them:

* ``0`` success
* ``1`` validation error (bad spec, bad link, stale commit)
* ``2`` a declared target produced zero testcases — the "quietly green" case
* ``3`` Kiwi was unreachable (Kiwi stand was retired; this code is now unreachable)
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import click

from traceability import (
    COVERAGE_MATRIX_PATH,
    DETAILS_PATH,
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
from traceability.render import render_coverage_matrix, render_details, render_result_matrix
from traceability.spec import Target, load_specs

__all__ = ["main", "cli"]

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


# ── helpers ────────────────────────────────────────────────────────────────


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


def _load(specs_dir: Path | None) -> tuple[dict, list[Link]]:
    specs = load_specs(specs_dir if specs_dir else SCENARIOS_DIR)
    links = scan_all(specs, REPO_ROOT)
    return specs, links


# ── Click group / root ─────────────────────────────────────────────────────


@click.group(
    "traceability",
    invoke_without_command=True,
    context_settings=dict(help_option_names=["-h", "--help"]),
)
@click.version_option(version="1.0.0", prog_name="python -m traceability")
def cli() -> None:
    """Spec-first traceability: specs in Git, results from CI."""
    pass


# ── validate ───────────────────────────────────────────────────────────────


@cli.command("validate")
@click.option("--specs", "specs_dir", type=click.Path(exists=True, path_type=Path),
              default=None, metavar="DIR",
              help=f"каталог спеков (по умолчанию {SCENARIOS_DIR})")
@click.option("--quiet", is_flag=True, help="только код возврата (для гейта в CI)")
def cmd_validate(specs_dir: Path | None, quiet: bool) -> int:
    """Проверить спеки и связи (без стенда)."""
    specs, links = _load(specs_dir)
    holes = build_coverage(specs, links).holes()
    if quiet:
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


# ── coverage ───────────────────────────────────────────────────────────────


@cli.command("coverage")
@click.option("--specs", "specs_dir", type=click.Path(exists=True, path_type=Path),
              default=None, metavar="DIR",
              help=f"каталог спеков (по умолчанию {SCENARIOS_DIR})")
@click.option("--out", "out_path", type=click.Path(path_type=Path), default=None, metavar="PATH",
              help=f"куда писать (по умолчанию {COVERAGE_MATRIX_PATH})")
@click.option("--check", is_flag=True,
              help="сравнить с закоммиченной копией и упасть при расхождении (CI)")
def cmd_coverage(specs_dir: Path | None, out_path: Path | None, check: bool) -> int:
    """Сгенерировать или проверить матрицу покрытия."""
    specs, links = _load(specs_dir)
    coverage = build_coverage(specs, links)
    index = render_coverage_matrix(coverage)
    details = render_details(coverage)
    path = out_path if out_path else COVERAGE_MATRIX_PATH
    details_path = DETAILS_PATH
    if check:
        if not path.exists():
            print(f"✗ матрица покрытия отсутствует: {path}", file=sys.stderr)
            return 1
        current = path.read_text(encoding="utf-8")
        if current != index:
            print(
                f"✗ закоммиченная матрица покрытия устарела: {path}\n"
                f"  обновите: just trace-coverage",
                file=sys.stderr,
            )
            return 1
        if not details_path.exists():
            print(f"✗ сценарии детализация отсутствует: {details_path}", file=sys.stderr)
            return 1
        current_details = details_path.read_text(encoding="utf-8")
        if current_details != details:
            print(
                f"✗ закоммиченная детализация сценариев устарела: {details_path}\n"
                f"  обновите: just trace-coverage",
                file=sys.stderr,
            )
            return 1
        print(f"✓ матрица покрытия актуальна: {path}")
        return 0
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(index, encoding="utf-8")
    details_path.parent.mkdir(parents=True, exist_ok=True)
    details_path.write_text(details, encoding="utf-8")
    print(f"✓ матрица покрытия записана: {path}")
    print(f"✓ детализация сценариев записана: {details_path}")
    return 0


# ── results ────────────────────────────────────────────────────────────────


@cli.command("results")
@click.option("--specs", "specs_dir", type=click.Path(exists=True, path_type=Path),
              default=None, metavar="DIR",
              help=f"каталог спеков (по умолчанию {SCENARIOS_DIR})")
@click.option("--commit", "commit", default=None, help="коммит (по умолчанию git HEAD)")
@click.option("--dirs", "dirs", default=None,
              help="каталоги с XML через запятую, переопределяют умолчания")
@click.option("--targets", "targets", default=None,
              help="цели через запятую (android, desktop)")
@click.option("--maestro", is_flag=True, help="учесть XML потоков Maestro")
@click.option("--partial", is_flag=True,
              help="прогон был отфильтрован по тегам и не покрывает все сценарии")
@click.option("--missing-scenarios", "missing_scenarios", default=None,
              help="сценарии заявленные но не запускавшиеся (scenario/target,...)")
@click.option("--out-dir", "out_dir", type=click.Path(path_type=Path), default=None,
              help=f"куда писать (по умолчанию {OUTPUT_DIR})")
def cmd_results(
    specs_dir: Path | None,
    commit: str | None,
    dirs: str | None,
    targets: str | None,
    maestro: bool,
    partial: bool,
    missing_scenarios: str | None,
    out_dir: Path | None,
) -> int:
    """Нормализовать JUnit/Maestro XML в results.json и матрицу."""
    specs, links = _load(specs_dir)

    selected: tuple[Target, ...]
    if targets:
        selected = tuple(Target(t.strip()) for t in targets.split(",") if t.strip())
    else:
        selected = tuple(Target)

    result_dirs = {target: _result_dirs(target, dirs, maestro) for target in selected}
    commit_str = commit or _git_commit()
    report = normalise(specs, links, result_dirs, commit_str, partial=partial)

    missing: set[tuple[str, Target]] = set()
    if missing_scenarios:
        for item in missing_scenarios.split(","):
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

    out = out_dir if out_dir else OUTPUT_DIR
    json_path = write_results(report, out)

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
        commit_str,
        kept=report.kept,
        dropped=report.dropped,
        unmapped=report.unmapped,
        missing=missing,
    )
    (out / "result-matrix.md").write_text(render_result_matrix(matrix), encoding="utf-8")

    print(f"✓ коммит: {commit_str}")
    print(f"✓ результатов: {report.kept} (отброшено не связанных: {report.dropped})")
    print(f"✓ {json_path}")
    for scenario_id, row in sorted(matrix.cells.items()):
        glyphs = " ".join(
            f"{t.value}={cell.glyph}"
            for t, cell in sorted(row.items(), key=lambda kv: kv[0].value)
        )
        print(f"    {scenario_id}: {glyphs}")
    return 0


# ── scenarios ──────────────────────────────────────────────────────────────


@cli.command("scenarios")
@click.option("--specs", "specs_dir", type=click.Path(exists=True, path_type=Path),
              default=None, metavar="DIR",
              help=f"каталог спеков (по умолчанию {SCENARIOS_DIR})")
@click.option("--speckit-specs", "speckit_specs_dir", type=click.Path(exists=True, path_type=Path),
              default=None, metavar="DIR",
              help="каталог спеков (алиас --specs, используется в тестах)")
@click.option("--check", "mode", flag_value="check", default=True,
              help="проверить что все объявления покрыты файлами")
@click.option("--generate", "mode", flag_value="generate", default=False,
              help="создать недостающие файлы сценариев (по умолчанию)")
def cmd_scenarios(
    specs_dir: Path | None,
    speckit_specs_dir: Path | None,
    mode: str,  # "check" or "generate"
) -> int:
    """Связь между объявлениями в спеках и YAML-файлами сценариев.

    Проверяет что все объявленные сценарии имеют соответствующие YAML-файлы.
    Генерирует новые YAML-файлы для объявлений без файлов.
    """
    from traceability.speckit import (
        SCENARIOS_DIR as _SCENARIOS_DIR,
    )
    from traceability.speckit import (
        SpecSource,
        check_bindings,
        load_declarations,
        render_skeleton,
        scenario_path,
    )

    # --speckit-specs replaces the declaration corpora (spec.md sources) only;
    # scenario YAMLs always come from the real scenarios directory (or --specs).
    # Redirecting load_specs to the scratch dir would hide the committed YAMLs
    # and turn a title-drift error into a false "no file" error.
    specs = load_specs(specs_dir if specs_dir else _SCENARIOS_DIR)

    if speckit_specs_dir:
        decl_source = (SpecSource("test", str(speckit_specs_dir), "*/spec.md"),)
        declarations = load_declarations(sources=decl_source)
    else:
        declarations = load_declarations()

    if mode == "check":
        problems, orphans = check_bindings(declarations, specs)
        if problems:
            for p in problems:
                print(f"✗ {p}", file=sys.stderr)
            print(f"\n{len(problems)} problem(s) — сценарии должны совпадать с объявлениями",
                  file=sys.stderr)
            return 1
        print(f"✓ все объявлений покрыты ({len(declarations)} сценариев)")
        if orphans:
            print(f"○ {len(orphans)} сценариев без объявления в спеках (не ошибка)")
            for o in orphans[:10]:
                print(f"    {o}")
            if len(orphans) > 10:
                print(f"    ... и ещё {len(orphans) - 10}")
        return 0

    # generate (default)
    written = 0
    for decl_id, decl in sorted(declarations.items()):
        path = scenario_path(decl)
        if path.exists():
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(render_skeleton(decl), encoding="utf-8")
        print(f"  создан: {path.relative_to(REPO_ROOT)}")
        written += 1
    if written == 0:
        print("нечего писать")
    else:
        print(f"✓ создано сценариев: {written}")
    return 0


# ── carrier ────────────────────────────────────────────────────────────────


@cli.command("carrier")
@click.option("--specs", "specs_dir", type=click.Path(exists=True, path_type=Path),
              default=None, metavar="DIR",
              help=f"каталог спеков (по умолчанию {SCENARIOS_DIR})")
@click.argument("scenario")
@click.option("--target", default="desktop",
              type=click.Choice([t.value for t in Target], case_sensitive=False),
              help="тир носителя (по умолчанию desktop)")
@click.option("--dry-run", is_flag=True, help="показать путь и текст, ничего не записывая")
def cmd_carrier(
    specs_dir: Path | None,
    scenario: str,
    target: str,
    dry_run: bool,
) -> int:
    """Генерировать зонд достижимости для сценария (пишет файл, если его нет)."""
    from traceability.carrier import CarrierError, plan_carrier, render_probe

    specs, links = _load(specs_dir)
    scenario_id = scenario.upper()
    if scenario_id not in specs:
        known = ", ".join(sorted(specs))
        print(f"ERROR: сценарий '{scenario_id}' не найден. Известны: {known}")
        return 1

    spec = specs[scenario_id]
    target_enum = Target(target)

    if not spec.is_claimed:
        print(
            f"ERROR: {scenario_id} выведен из эксплуатации (status: deprecated).\n"
            f"Носитель для него не пишется: это вернуло бы снятое обязательство."
        )
        return 1

    if target_enum not in spec.targets:
        claimed = ", ".join(t.value for t in spec.targets) or "—"
        print(
            f"ERROR: {scenario_id} не заявляет '{target}' (заявляет: {claimed}).\n"
            f"Сначала решите, верно ли это — сужение спеки под платформу без "
            f"измерения и есть то, что сделало TASK-TIME-01 неверным."
        )
        return 1

    if target_enum in spec.unreachable:
        print(
            f"ERROR: {scenario_id} помечает '{target}' как недостижимый (unreachable).\n"
            f"Зонд тут бесполезен: сценарий заявлен, но ни один автоматический "
            f"носитель на этом тире его не достанет — нужно второе устройство, "
            f"управление сетью или ручной прогон.\n"
            f"Почему это важно: провалившийся зонд раньше читался двумя "
            f"противоположными способами, и оба применялись. Если поле стоит "
            f"не по делу — уберите его из спеки, и тогда разбираться будет с чем."
        )
        return 1

    existing = [link for link in links if link.scenario == scenario_id and link.target is target_enum]
    if existing:
        for link in existing:
            print(f"carrier: {scenario_id} / {target} уже несёт {link.source.name}")
        print("Перезапись запрещена: ваш собственный тест и есть носитель.")
        print("Удалите его осознанно, если хотите начать заново.")
        return 1

    try:
        plan = plan_carrier(spec, target_enum, REPO_ROOT)
        text = render_probe(plan)
    except CarrierError as exc:
        print(f"ERROR: {exc}")
        return 1

    if plan.exists:
        print(f"carrier: {plan.path} уже существует — перезапись запрещена.")
        print("Если это ваш собственный тест, он и есть носитель; удалите его")
        print("осознанно, если хотите заново.")
        return 1

    if dry_run:
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


# ── main ───────────────────────────────────────────────────────────────────


def main(argv: list[str] | None = None) -> int:
    """Parse arguments and dispatch to the requested command.

    Exit codes:
        0  success
        1  validation error
        2  a target produced zero testcases
        3  Kiwi was unreachable (Kiwi stand retired — unreachable code path)
      130  KeyboardInterrupt
    """
    try:
        return cli.main(args=argv, standalone_mode=False)
    except NoResultsError as exc:
        print(f"✗ {exc}", file=sys.stderr)
        return EXIT_NO_RESULTS
    except ValidationError as exc:
        print(f"✗ {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:  # pragma: no cover
        return 130


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())

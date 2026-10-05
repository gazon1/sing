#!/usr/bin/env python3
"""sync.py — синх тест-кейсов и результатов из репозитория в Kiwi TCMS.

Зачем это нужно
---------------
Проект уже умеет рассказать, *сколько* тестов написано (`find … -name '*Test.kt'`)
и какой процент кода покрыт (Kover, `scripts/check-coverage.py`). Ни то, ни
другое не отвечает на вопрос «а ЧТО я проверяю и чего не проверяю совсем».
Kover даёт строки кода, а не намерение.

Kiwi TCMS добавляет недостающий слой: кейс в Kiwi — это утверждение о
поведении, у него есть *статус последнего прогона*. Кейс, который ни разу не
запускался, виден как «не проверено» — и это ровно тот список дыр, который
иначе не существует ни в одном отчёте.

Модель
------
    Product  «Singularity Todo»          — приложение целиком
      Plan   «Automatized (JVM)»         — тесты, которые гоняет CI
        TestCase                        — один тестовый класс репозитория
          TestRun                       — один запуск ./gradlew … test
            TestExecution               — результат этого класса

Один кейс = один тестовый класс, а не один тестовый метод. Причина в том,
что Kiwi-идентификатор кейса обязан быть стабильным, а имя класса в Kotlin
меняется заметно реже, чем набор `@Test`-методов внутри. Смена сигнатуры теста
не должна плодить новый кейс и разрывать историю прогонов.

Использование
-------------
    sync.py --plan            # создать/обновить структуру и кейсы
    sync.py --results         # залить результаты последнего Gradle-прогона
    sync.py --plan --results  # оба шага
    sync.py --plan --dry-run  # показать, что изменится, ничего не писать

Опции:
    --url / --user / --password   подключение к стенду
    --product / --plan-name       свои имена (по умолчанию — Singularity Todo)
    --results-dir DIR             каталог с JUnit XML (по умолчанию ищется сам)
    --build-version V             версия для Kiwi Build (по умолчанию — git sha)
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

# `infra/kiwi` — плоский каталог скриптов, а не установленный пакет, поэтому
# пакет `traceability` лежит рядом и не находится без явного пути. Обычно
# достаточно sys.path[0] (скрипт запускают как `./infra/kiwi/sync.py`), но
# gaps.py и тесты грузят модуль через importlib — без этой строки любой такой
# запуск падает на импорте общего JUnit-ридера.
_KIWI_DIR = str(Path(__file__).resolve().parent)
if _KIWI_DIR not in sys.path:
    sys.path.insert(0, _KIWI_DIR)

from kiwi_client import KiwiClient, KiwiError
from traceability.junit_xml import TestCaseResult
from traceability.junit_xml import parse_junit as parse_junit_flat

# The JUnit reader itself lives in traceability/junit_xml.py and is shared with
# the scenario pipeline. Two parsers of one format is how the join between "what
# ran" and "what was claimed" quietly breaks: each side normalises slightly
# differently and the matrix comes out empty rather than loudly wrong. The
# re-export below keeps `sync.parse_junit` and `sync.TestResult` working for
# existing callers and for scripts/tests/test_kiwi_sync.py, which pins the
# parsing behaviour (classname-not-testsuite@name, failure/error/skipped
# precedence, malformed XML tolerated).

REPO_ROOT = Path(__file__).resolve().parents[2]
PKG_PREFIX = "com.singularity.todo"

# Файлы, отсечённые как не-тесты (абстрактные базовые классы, хелперы).
# Наполняется сканером и печатается sync.py: иначе отсев выглядит как
# «пропало 4 кейса» без объяснения, где они.
SKIPPED_NON_TESTS: list[tuple[str, str]] = []

# Source set → Plan. Один план на модуль, а не на source set: commonTest и
# jvmTest в :shared компилируются в ОДИН тестовый набор (commonTest
# исполняется задачей jvmTest — см. AGENTS.md, раздел «Тесты»), и разносить
# их по разным планам значило бы удвоить число кейсов в Kiwi без единого
# нового теста.
TEST_ROOTS: list[tuple[str, Path]] = [
    ("shared", REPO_ROOT / "shared/src/commonTest/kotlin"),
    ("shared-jvm", REPO_ROOT / "shared/src/jvmTest/kotlin"),
    ("shared-host", REPO_ROOT / "shared/src/androidHostTest/kotlin"),
    ("desktop", REPO_ROOT / "desktopApp/src/jvmTest/kotlin"),
    ("detekt-rules", REPO_ROOT / "detekt-rules/src/test/kotlin"),
    ("mcp-server", REPO_ROOT / "mcp-server/src/test/kotlin"),
    ("android", REPO_ROOT / "androidApp/src/androidTest/kotlin"),
]

PLAN_OF_ROOT = {
    "shared": "Automated — shared (commonTest+jvmTest)",
    "shared-jvm": "Automated — shared (commonTest+jvmTest)",
    "shared-host": "Automated — shared (commonTest+jvmTest)",
    "desktop": "Automated — desktop (Compose UI)",
    "detekt-rules": "Automated — detekt-rules",
    "mcp-server": "Automated — mcp-server",
    "android": "Automated — android instrumented",
}

# Где Gradle кладёт JUnit XML. Пути перечислены явно, потому что имена
# каталогов зависят от того, какие задачи запускались, и не существуют
# заранее: на чистом checkout их может не быть вовсе.
RESULT_DIRS = [
    "shared/build/test-results/jvmTest",
    "shared/build/test-results/commonTest",
    "shared/build/test-results/androidHostTest",
    "shared/build/test-results/test",
    "desktopApp/build/test-results/test",
    "detekt-rules/build/test-results/test",
    "mcp-server/build/test-results/test",
    "androidApp/build/test-results/testDebugUnitTest",
    "androidApp/build/test-results/connectedDebugAndroidTest",
]


# ---------------------------------------------------------------------------
# Скан репозитория
# ---------------------------------------------------------------------------


@dataclass
class RepoTest:
    """Один тестовый класс, найденный в исходниках."""

    fqn: str  # com.singularity.todo.feature.tasks.TasksViewModelTest
    simple: str  # TasksViewModelTest
    module: str  # shared
    plan: str
    rel_path: str  # shared/src/commonTest/kotlin/.../TasksViewModelTest.kt
    feature: str  # feature.tasks | core.ui | arch | root
    tag: str  # fast | slow | untagged
    is_arch: bool  # класс из пакета arch — это проверки архитектуры

    @property
    def component(self) -> str:
        return self.feature

    @property
    def summary(self) -> str:
        return self.simple


def _rel(path: Path) -> str:
    try:
        return str(path.relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def _feature_of(fqn: str) -> str:
    """feature.tasks / core.sync / arch — для Component в Kiwi.

    Пакеты верхнего уровня в shared/src/*Test/kotlin/com/singularity/todo:
    feature/<x>, core/<x>, shell, test, arch. Всё, что не начинается с
    feature./core., сводим в одну компоненту: держать отдельный компонент на
    каждый разнотипный пакет верхнего уровня не даёт карты покрытия.
    """
    parts = fqn.split(".")
    try:
        idx = parts.index("todo")
        tail = parts[idx + 1 :]
    except ValueError:
        return "other"
    if not tail:
        return "root"
    if tail[0] in ("feature", "core") and len(tail) > 1:
        return f"{tail[0]}.{tail[1]}"
    return tail[0]


def _read_tag(source: str, class_name: str | None = None) -> str:
    """Тег уровня КЛАССА, а не первый @Tag в файле.

    По правилам проекта каждый тестовый класс обязан иметь тег — иначе
    -Ptest.tags его молча исключит из каждого прогона.

    Раньше здесь стояло `re.search` по всему файлу, что неверно для файлов с
    несколькими классами: `FakeRepositoryFidelityTest.kt` несёт `@Tag("fast")`
    на постороннем объекте, и тестовый класс на строке 41 получал чужой тег.
    Kiwi записал бы в свойство junit_tag значение, которого у кейса нет, —
    и разрез бы отчёт о «медленных» кейсах, которых в этом файле не было.

    Ищем ближайший @Tag над объявлением нужного класса: тот же приём, что в
    TestTagCoverageTest. Тег на отдельном методе класс не делает slow.
    """
    lines = source.split("\n")
    class_re = re.compile(
        r"^(?:@\w+(?:\([^)]*\))?\s*)*"
        r"(?:public |internal |private |protected |abstract |open |final |sealed |data |value )*"
        r"(?:class|object)\s+(\w+)"
    )
    # Полная форма `@org.junit.jupiter.api.Tag("…")` — её пишут файлы, где
    # `com.singularity.todo.feature.tags.Tag` уже импортирован и короткое
    # имя занято доменной сущностью. Ровно тот же приём, что в
    # TestTagCoverageTest; без него «быстрый» класс читался как untagged.
    tag_re = re.compile(r'@(?:org\.junit\.jupiter\.api\.)?Tag\(\s*"([^"]+)"\s*\)')
    for index, line in enumerate(lines):
        header = class_re.match(line.strip())
        if not header:
            continue
        if class_name and header.group(1) != class_name:
            continue
        # Поднимаемся по аннотациям над объявлением.
        for back in range(index - 1, -1, -1):
            stripped = lines[back].strip()
            if stripped.startswith("@"):
                found = tag_re.search(stripped)
                if found:
                    return found.group(1)
                continue
            break
    return "untagged"


# JUnit-аннотации, порождающие прогон. @ParameterizedTest обязателен:
# RecurrenceRuleMapperTest и RruleGeneratorTest — настоящие сюиты без
# @Test в файле, и фильтр только по @Test выкинул бы их.
#
# Необязательный префикс `org.junit.jupiter.api.` — тоже обязателен. Полная
# форма нужна файлам, где короткое имя `Test` уже занято (`kotlin.test.Test`
# импортирован рядом, а второй `Test` нельзя), и RruleGeneratorTest.kt:49
# именно так и написан. Без этого префикса в регулярке класс, у которого
# ЕДИНСТВЕННЫЙ тест — такой полной формой, читался как «тестов нет»:
# прогон есть, а обе реализации предиката (эта и TestTagCoverageTest) о нём
# не знали. Общий набор фикстур: config/test-fixtures/runnable-test-members.txt.
_TEST_MEMBER = re.compile(
    r"^\s*@(?:(?:kotlin\.test|org\.junit\.jupiter\.api)\.)?"
    r"(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b"
)


def _test_classes_in(source: str) -> list[str]:
    """Имена тест-классов верхнего уровня, объявленных в файле.

    Нужна для точного чтения тега (см. _read_tag) и для отсева, но НЕ как
    основание для идентификатора кейса: переход на «один кейс = одно
    объявление» меняет ключ идемпотентности и требует миграции уже залитых
    кейсов, а не починки. Это зафиксировано в ADR и в бэклоге.
    """
    lines = source.split("\n")
    header_re = re.compile(
        r"^(?:@\w+(?:\([^)]*\))?\s*)*"
        r"(?:public |internal |private |protected |abstract |open |final |sealed |data |value )*"
        r"(?:class|object)\s+(\w+)"
    )
    out: list[str] = []
    for index, line in enumerate(lines):
        header = header_re.match(line.strip())
        if not header:
            continue
        name = header.group(1)
        if not name.endswith("Test") or _is_abstract_or_open(lines[index]):
            continue
        if _has_test_member(lines, index):
            out.append(name)
    return out


def _is_abstract_or_open(declaration: str) -> bool:
    return re.match(
        r"^(?:public |internal |private |protected )?(?:abstract|open)\s+", declaration
    ) is not None


def _has_test_member(lines: list[str], class_index: int) -> bool:
    """Есть ли @Test-аннотация в теле класса, начавшемся на [class_index].

    Считает фигурные скобки до закрытия тела, поэтому длина класса значения не
    имеет: у EntityMapperCompletenessTest первая @Test идёт на 340-й строке,
    и фиксированное окно в N строк отсеивало реальный тест как «пустой».
    """
    if "{" not in lines[class_index]:
        return any(
            _TEST_MEMBER.match(lines[class_index + i])
            for i in range(1, 6)
            if class_index + i < len(lines)
        )
    depth = 0
    opened = False
    for i in range(class_index, len(lines)):
        for ch in lines[i]:
            if ch == "{":
                depth += 1
                opened = True
            elif ch == "}":
                depth -= 1
        if _TEST_MEMBER.match(lines[i]):
            return True
        if opened and depth == 0:
            return False
    return False


def has_runnable_test(source: str) -> bool:
    """Есть ли в файле тест, который JUnit действительно выполнит."""
    return bool(_test_classes_in(source))


def scan_repository() -> list[RepoTest]:
    found: list[RepoTest] = []
    for module, root in TEST_ROOTS:
        if not root.exists():
            continue
        for path in sorted(root.rglob("*Test.kt")):
            try:
                source = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                source = ""
            fqn = (
                path.relative_to(root).with_suffix("").as_posix().replace("/", ".")
            )
            if not fqn.startswith(PKG_PREFIX):
                fqn = f"{PKG_PREFIX}.{fqn}"
            if not has_runnable_test(source):
                SKIPPED_NON_TESTS.append((path.stem, _rel(path)))
                continue
            found.append(
                RepoTest(
                    fqn=fqn,
                    simple=fqn.rsplit(".", 1)[-1],
                    module=module,
                    plan=PLAN_OF_ROOT.get(module, f"Automated — {module}"),
                    rel_path=_rel(path),
                    feature=_feature_of(fqn),
                    # Тег берём у класса, чьё имя совпадает с именем файла.
                    # Если в файле объявлен другой тест-класс
                    # (NoopSubscriptionProviderTest.kt → PurchaseStateTest), честный
                    # ответ — untagged, потому что кейс назван по файлу, а тега
                    # у этого имени нет. Это видимый признак расхождения, а не
                    # тихая подстановка чужого тега.
                    tag=_read_tag(source, path.stem),
                    is_arch=".arch." in fqn or fqn.endswith(".arch"),
                )
            )
    return found


# ---------------------------------------------------------------------------
# Чтение JUnit XML
# ---------------------------------------------------------------------------


# Re-exported, not redefined: see the import note above.
TestResult = TestCaseResult


# Свежесть JUnit XML. build/test-results — артефакт, а не источник истины:
# он переживает коммиты, и без проверки `sync --results` записал бы в Kiwi
# результаты недельной давности с текущим git-sha в поле Build. Отчёт о
# покрытии тогда выглядит свежим, а описывает старый код.
MAX_RESULT_AGE_HOURS = 24


def find_result_dirs(explicit: str | None) -> list[Path]:
    if explicit:
        p = Path(explicit)
        return [p] if p.is_dir() else []
    return [REPO_ROOT / d for d in RESULT_DIRS if (REPO_ROOT / d).is_dir()]


def newest_result_mtime(dirs: list[Path]) -> float:
    """Время изменения самого свежего XML во всех каталогах (0, если пусто)."""
    newest = 0.0
    for d in dirs:
        for xml_file in d.glob("TEST-*.xml"):
            try:
                newest = max(newest, xml_file.stat().st_mtime)
            except OSError:
                continue
    return newest


def parse_junit(dirs: list[Path]) -> dict[str, list[TestResult]]:
    """FQN класса → результаты его тестов.

    Класс группируется по FQN из `<testcase classname=…>`, потому что
    именно он записан в свойства кейса в Kiwi (`source_class`) и по нему
    выполняется привязка.

    Разбор делегирован `traceability/junit_xml.parse_junit`: этот кейс
    группирует по классу, сценарийная ветка работает по тесту, и обе должны
    видеть одно и то же XML. Идентификатор класса берётся из
    `<testcase classname="...">`, а НЕ из `<testsuite name="...">`: Gradle
    пишет в testsuite имя с суффиксом целевой платформы
    («AgendaDslTest[jvm]»), и такое имя не совпадает ни с одним FQN
    репозитория — привязка результатов молча давала 0 совпадений.
    """
    by_class: dict[str, list[TestResult]] = defaultdict(list)
    for result in parse_junit_flat(dirs):
        by_class[result.classname].append(result)
    return by_class


def git_version() -> str:
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            timeout=10,
        )
        if out.returncode == 0:
            return out.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        pass
    return "local"


# ---------------------------------------------------------------------------
# Синх
# ---------------------------------------------------------------------------


def ensure_structure(client: KiwiClient, product_name: str) -> tuple[dict, dict]:
    product = client.get_product(product_name) or client.create_product(
        product_name,
        "Тест-кейсы Singularity Todo KMP, синх из репозитория (infra/kiwi/sync.py)",
    )
    plans: dict[str, dict] = {}
    for plan_name in sorted(set(PLAN_OF_ROOT.values())):
        plans[plan_name] = client.get_plan(
            product["id"], plan_name
        ) or client.create_plan(product["id"], plan_name)
    return product, plans


def sync_plan(
    client: KiwiClient,
    tests: list[RepoTest],
    product_name: str,
    dry_run: bool,
) -> dict:
    print(f"==> сканирование репозитория: найдено {len(tests)} тестовых классов")
    if SKIPPED_NON_TESTS:
        # Отсев печатается явно: иначе падение числа кейсов выглядит как
        # «что-то пропало», и агент ищет потерянное там, где его не было.
        names = ", ".join(fqn.rsplit(".", 1)[-1] for fqn, _ in SKIPPED_NON_TESTS)
        print(
            f"    отсечено как не-тесты (abstract-базы и хелперы): "
            f"{len(SKIPPED_NON_TESTS)} — {names}"
        )
    if dry_run:
        print("    (dry-run: в Kiwi ничего не пишется)")
        return {}

    product, plans = ensure_structure(client, product_name)
    print(f"==> product: {product['name']} (id={product['id']}), планов: {len(plans)}")

    # Kiwi-ключ кейса -> его id. Ключ — source_path (путь к файлу), а НЕ FQN:
    # FQN не уникален. com.singularity.todo.test.helpers.CoroutineDiagnosticsTest
    # существует и в shared/src/jvmTest, и в desktopApp/src/jvmTest, и при
    # ключе по FQN один из двух молча схлопывался бы, а вместе с ним —
    # результаты прогона второго модуля. Путь к файлу уникален по построению.
    existing: dict[str, int] = {}
    for plan in plans.values():
        # Свойства всех кейсов плана — одним вызовом вместо одного на кейс.
        bulk = client.get_all_cases_properties([plan["id"]])
        for case_id, props in bulk.items():
            key = props.get("source_path")
            if key:
                existing[key] = case_id

    components: dict[str, dict] = {}
    created = 0
    reused = 0
    for test in tests:
        plan = plans[test.plan]
        if test.feature not in components:
            components[test.feature] = client.get_component(
                product["id"], test.feature
            ) or client.create_component(product["id"], test.feature)
        comp = components[test.feature]

        if test.rel_path in existing:
            reused += 1
            continue

        case = client.create_case(
            product_id=product["id"],
            plan_id=plan["id"],
            summary=test.summary,
            component_id=comp["id"],
        )
        # component тоже кладём в свойства: TestCase.filter НЕ возвращает
        # component__name (его нет в .values(...) на стороне Kiwi), поэтому
        # читать компонент кейса приходится отсюда.
        client.set_property(case["id"], "component", test.feature)
        client.set_property(case["id"], "source_class", test.fqn)
        client.set_property(case["id"], "source_path", test.rel_path)
        client.set_property(case["id"], "module", test.module)
        client.set_property(case["id"], "junit_tag", test.tag)
        if test.tag:
            client.set_tag(case["id"], test.tag)
        existing[test.rel_path] = case["id"]
        created += 1

    print(f"==> кейсы: создано {created}, уже было {reused}")
    return existing


def rollup_class_status(results: list[TestResult]) -> tuple[str, str]:
    """Свести тесты одного класса к паре (Kiwi-статус, комментарий).

    Вынесено из ``sync_results`` без изменения поведения. Раньше сводка жила
    инлайном в ветке цикла, и её нельзя было ни вызвать, ни проверить: писать
    тест на ``sync_results`` требовало бы поднятой Kiwi, а он не поднимается в
    юнит-тестах. Теперь обе таблицы — эта (класс → один статус) и
    ``kiwi_client.status_for`` (один результат → статус) — лежат рядом и
    сравнимы, и их расхождение можно разрешить осознанно, а не унаследовать.

    Два правила, которые легко спутать, поэтому зафиксированы явно:

    - упавший тест важнее пропущенного (``failed`` проверяется первым): класс,
      где один тест упал и два пропущены, — это ``FAILED``, а не ``PASSED``;
    - ``skipped → IDLE`` только когда пропущены **все**, иначе класс прошёл
      частично и это ``PASSED`` с комментарием о пропущенных. Это расходится с
      ``skipped → WAIVED`` в новом паблишере сценариев намеренно (ADR
      2026-10-05-scenario-test-cases-in-kiwi): там пропуск сценария означает
      «человек отказался от проверки», здесь — «Gradle не запустил этот тест».
    """
    failed = [r for r in results if r.status in ("failed", "error")]
    skipped = [r for r in results if r.status == "skipped"]
    if failed:
        comment = (
            f"{len(failed)}/{len(results)} упало. "
            + "\n".join(f"{r.name}: {r.message}" for r in failed[:5])
        )[:2000]
        return "FAILED", comment
    if skipped and len(skipped) == len(results):
        return "IDLE", f"все {len(skipped)} тестов пропущены"
    if skipped:
        return "PASSED", f"{len(skipped)}/{len(results)} пропущено"
    return "PASSED", f"{len(results)} тестов пройдено"


def sync_results(
    client: KiwiClient,
    case_ids: dict[str, int],
    path_of: dict[str, str],
    plan_of: dict[str, str],
    product_name: str,
    results_dir: str | None,
    dry_run: bool,
) -> None:
    """Залить результаты прогонов.

    JUnit XML идентифицирует класс по FQN (`testsuite@name`), Kiwi-кейс — по
    source_path. Поэтому сначала строится обратная таблица FQN -> путь:
    для FQN, встречающегося в двух модулях, она неоднозначна, и такой класс
    отбрасывается с предупреждением, а не назначается первому попавшемуся
    модулю (иначе результаты desktop-теста приписывались бы shared-кейсу).
    """
    dirs = find_result_dirs(results_dir)
    if not dirs:
        print(
            "==> JUnit XML не найден. Сначала прогоните тесты, например:\n"
            "      ./gradlew :shared:jvmTest -Ptest.tags=fast,slow\n"
            "    (ищем: " + ", ".join(RESULT_DIRS) + ")",
            file=sys.stderr,
        )
        return

    newest = newest_result_mtime(dirs)
    if newest:
        import time as _time

        age_h = (_time.time() - newest) / 3600
        if age_h > MAX_RESULT_AGE_HOURS:
            print(
                f"    ! ВНИМАНИЕ: XML старый ({age_h / 24:.1f} сут). "
                f"В Kiwi будет записан Build с текущим git-sha, а результаты "
                f"опишут старый код. Перезапустите тесты перед sync.",
                file=sys.stderr,
            )

    by_class = parse_junit(dirs)
    total_tests = sum(len(v) for v in by_class.values())
    print(f"==> JUnit XML: {len(by_class)} классов, {total_tests} тестов")

    fqn_paths: dict[str, set[str]] = defaultdict(set)
    for path, fqn in path_of.items():
        fqn_paths[fqn].add(path)

    ambiguous = {f for f, paths in fqn_paths.items() if len(paths) > 1}
    if ambiguous:
        print(
            f"    ! пропущено {len(ambiguous)} классов с неоднозначным FQN "
            f"(совпадает в нескольких модулях): {', '.join(sorted(ambiguous)[:3])}"
            + ("…" if len(ambiguous) > 3 else "")
        )

    fqn_to_path = {
        fqn: next(iter(paths))
        for fqn, paths in fqn_paths.items()
        if fqn not in ambiguous
    }

    matched: dict[str, list[TestResult]] = {}
    for fqn, results in by_class.items():
        path = fqn_to_path.get(fqn)
        if path and path in case_ids:
            matched[path] = results
    # Класс может попасть в XML, но без записи в Kiwi (например, тест удалён
    # из исходников, а build/test-results ещё не подчищен).
    # Несовпавшие — это классы, чей FQN не удалось отобразить в путь.
    # Проверяем по fqn_to_path (FQN -> путь), а не по словарю matched:
    # matched keyed путём, и сравнение FQN с ключами-путями давало ложное
    # «223 класса без кейса» при 212 реально привязанных.
    unmatched = sorted(f for f in by_class if f not in fqn_to_path)
    print(
        f"    привязано к кейсам: {len(matched)}; "
        f"без кейса в Kiwi: {len(unmatched)}"
    )
    for name in unmatched[:10]:
        print(f"      ! {name} (нет кейса — сначала sync.py --plan)")
    if len(unmatched) > 10:
        print(f"      … и ещё {len(unmatched) - 10}")

    if dry_run:
        print("    (dry-run: прогоны не создаются)")
        return
    if not matched:
        return

    version = git_version()
    product = client.get_product(product_name)
    if product is None:
        raise KiwiError(f"product '{product_name}' не найден — выполните --plan")
    _, plans = ensure_structure(client, product_name)
    # Build привязан к версии ПЛАНА, а не к продукту: см. get_plan_builds
    # в kiwi_client.py. Все планы созданы на одной версии, поэтому build
    # запрашивается по первому плану и переиспользуется для остальных.
    build = client.ensure_build(version, product["id"], plan=next(iter(plans.values())))

    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    for plan_name, plan in plans.items():
        in_plan = {
            path: res
            for path, res in matched.items()
            if plan_of.get(path) == plan_name
        }
        if not in_plan:
            continue
        summary = f"gradle {version} @ {stamp}"
        run = client.create_run(
            plan_id=plan["id"], build_id=build["id"], summary=summary
        )
        counts = defaultdict(int)
        # sortkey инкрементируется вручную: Kiwi не проставляет его сам, а
        # одинаковый sortkey у всех execution ломает порядок в UI.
        sortkey = 10
        for path, results in in_plan.items():
            case_id = case_ids[path]
            # Свёртка вынесена в rollup_class_status: здесь остаётся только
            # запись, чтобы правка правил статуса не требовала Kiwi-стенда.
            status, comment = rollup_class_status(results)
            client.add_execution(
                run["id"], case_id, status, comment, sortkey=sortkey
            )
            # Отметка «кейс хоть раз выполнялся» — НА САМОМ КЕЙСЕ, а не на
            # прогоне. TestRun.remove каскадит executions/tags/cc/property
            # самого прогона, но TestCase и его свойства не трогает, поэтому
            # отметка переживает ротацию.
            #
            # Без неё метрика «никогда не запускался» вырождается в «не
            # запускался с последней ротации»: prune удалял историю, и кейс,
            # честно отработавший месяц назад, снова попадал в отчёт как
            # непроверенный. Проверено на стенде: после prune --keep 1
            # «дыры» выросли с 47 до 237, не изменившись ни одним тестом.
            #
            # Значение фиксированное намеренно: Kiwi делает get_or_create по
            # (case, name, value), и меняющееся значение создавало бы новую
            # строку на каждый прогон.
            client.set_property(case_id, "ever_run", "1")
            sortkey += 10
            counts[status] += 1
        total = sum(counts.values())
        print(
            f"==> прогон #{run['id']} [{plan_name}]: {total} кейсов "
            f"(PASSED {counts['PASSED']}, FAILED {counts['FAILED']}, "
            f"ERROR {counts['ERROR']}, IDLE {counts['IDLE']})"
        )


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--plan", action="store_true", help="создать/обновить кейсы")
    ap.add_argument("--results", action="store_true", help="залить результаты прогонов")
    ap.add_argument("--dry-run", action="store_true", help="ничего не писать в Kiwi")
    ap.add_argument("--url", default=None, help="RPC endpoint Kiwi")
    ap.add_argument("--user", default=None, help="пользователь Kiwi")
    ap.add_argument("--password", default=None, help="пароль Kiwi")
    ap.add_argument("--product", default="Singularity Todo")
    ap.add_argument("--results-dir", default=None)
    args = ap.parse_args(argv)

    if not args.plan and not args.results:
        ap.error("укажите --plan и/или --results")

    client = KiwiClient()
    if args.url:
        client.url = args.url
    if args.user:
        client.user = args.user
    if args.password:
        client.password = args.password
    client.check_alive()
    print(f"==> подключено к {client.url} как {client.user}")

    tests = scan_repository()
    # План кейса берём из скана исходников, а не угадываем по имени класса:
    # один и тот же FQN встречается и в commonTest, и в jvmTest, и в
    # desktopApp, и распределение по планам различается.
    plan_of: dict[str, str] = {}
    path_of: dict[str, str] = {}
    for t in tests:
        plan_of[t.rel_path] = t.plan
        path_of[t.rel_path] = t.fqn

    case_ids: dict[str, int] = {}
    if args.plan:
        case_ids = sync_plan(client, tests, args.product, args.dry_run)
    if args.results:
        if not case_ids:
            # Без --plan маппинг FQN→id нужно восстановить из Kiwi.
            product = client.get_product(args.product)
            if product is None:
                print(
                    f"product '{args.product}' не найден — сначала sync.py --plan",
                    file=sys.stderr,
                )
                return 1
            plan_ids = [
                p["id"]
                for p in client.call(
                    "TestPlan.filter", {"product__id": product["id"]}
                )
                or []
            ]
            for case_id, props in client.get_all_cases_properties(plan_ids).items():
                key = props.get("source_path")
                if key:
                    case_ids[key] = case_id
            print(f"==> восстановлен маппинг: {len(case_ids)} кейсов")
        sync_results(
            client,
            case_ids,
            path_of,
            plan_of,
            args.product,
            args.results_dir,
            args.dry_run,
        )

    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KiwiError as exc:
        print(f"ОШИБКА: {exc}", file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        sys.exit(130)

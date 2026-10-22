"""scanners.test_classes — the repository's one answer to "is this a runnable test?".

Extracted verbatim from ``infra/kiwi/sync.py`` (which is deleted) so that the
predicative questions the gates ask — ``check-test-runs.py``,
``test_runnable_test_members.py`` — do not lose their implementation with the
stand. The Kiwi TCMS used to carry this code only incidentally: it needed a
class list to project into test cases, and the class list is also what the test
run floors count. Deleting the stand without moving this would have taken the
run floors with it.

The alternative — a second implementation of the predicate next to the Kotlin
one in ``TestTagCoverageTest`` — is the failure this file exists to prevent. The
two sides have already drifted once: the Kotlin gate matched only
``startsWith("@Test")`` and reported ``RecurrenceRuleMapperTest`` and
``RruleGeneratorTest`` as clean while CI silently skipped them. The shared
fixture table ``config/test-fixtures/runnable-test-members.txt`` is the contract,
and ``scripts/tests/test_runnable_test_members.py`` checks this file against it.

Nothing here imports Kiwi, XML-RPC, or the network.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

from scanners.source_text import line_depths, strip_code, strip_comments

# scripts/src/scanners/test_classes.py → [0]=scanners [1]=src [2]=scripts [3]=repo
REPO_ROOT = Path(__file__).resolve().parents[3]
PKG_PREFIX = "com.singularity.todo"



# Source set label → the source roots it owns. One entry per Gradle source set
# that contains tests; `commonTest` is listed separately from `jvmTest` because
# they are different directories, even though the `shared` label groups them (the
# jvmTest task executes both).
TEST_ROOTS: list[tuple[str, Path]] = [
    ("shared", REPO_ROOT / "shared/src/commonTest/kotlin"),
    ("shared-jvm", REPO_ROOT / "shared/src/jvmTest/kotlin"),
    ("shared-host", REPO_ROOT / "shared/src/androidHostTest/kotlin"),
    ("desktop", REPO_ROOT / "desktopApp/src/jvmTest/kotlin"),
    ("detekt-rules", REPO_ROOT / "detekt-rules/src/test/kotlin"),
    ("mcp-server", REPO_ROOT / "mcp-server/src/test/kotlin"),
    ("android", REPO_ROOT / "androidApp/src/androidTest/kotlin"),
]


# ---------------------------------------------------------------------------
# Scan
# ---------------------------------------------------------------------------


@dataclass
class RepoTest:
    """Один тестовый класс, найденный в исходниках."""

    fqn: str  # com.singularity.todo.feature.tasks.TasksViewModelTest
    simple: str  # TasksViewModelTest
    module: str  # shared
    rel_path: str  # shared/src/commonTest/kotlin/.../TasksViewModelTest.kt
    feature: str  # feature.tasks | core.ui | arch | root
    tag: str  # fast | slow | untagged
    is_arch: bool  # класс из пакета arch — это проверки архитектуры
    declared_class: str  # имя класса, объявленного в файле (может отличаться от path.stem)


def _rel(path: Path) -> str:
    try:
        return str(path.relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def _feature_of(fqn: str) -> str:
    """feature.tasks / core.sync / arch — группировка для отчётов.

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


def _read_tags(source: str, class_name: str | None = None) -> list[str]:
    """Все теги уровня КЛАССА, от ближайшего к декларации.

    Класс может нести больше одного тега: с 2026-10-22 медленный класс носит
    и основной тег `slow`, и под-тег из словаря — `slow:konsist`,
    `slow:migration`, `slow:data`, `slow:compose`, `slow:platform`. Под-тег
    говорит, ЧТО медленно; основной тег выбирает прогон
    (`-Ptest.tags=fast,slow` включает класс по нему).

    Поэтому «ближайший» ответ больше не достаточен: класс, у которого
    под-тег стоит последним, читался как `slow:konsist` — и для правила
    «класс помечен slow» выглядел не-slow. Список возвращает все теги, а
    вывод о принадлежности к `slow` принимает решение вызывающий код.
    """
    # Обнулены только комментарии: `@Tag("fast")`, упомянутое в KDoc, — это не
    # аннотация, и читать её как тег нельзя. Содержимое строк обнулять нельзя:
    # имя тега живёт внутри них.
    lines = strip_comments(source).split("\n")
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
        # Поднимаемся по аннотациям над декларацией.
        tags: list[str] = []
        for back in range(index - 1, -1, -1):
            stripped = lines[back].strip()
            if stripped.startswith("@"):
                found = tag_re.search(stripped)
                if found:
                    tags.append(found.group(1))
                continue
            break
        return tags
    return []


def _read_tag(source: str, class_name: str | None = None) -> str:
    """Тег уровня КЛАССА, а не первый @Tag в файле.

    По правилам проекта каждый тестовый класс обязан иметь тег — иначе
    -Ptest.tags его молча исключит из каждого прогона.

    Раньше здесь стояло `re.search` по всему файлу, что неверно для файлов с
    несколькими классами: `FakeRepositoryFidelityTest.kt` несёт `@Tag("fast")`
    на постороннем объекте, и тестовый класс на строке 41 получал чужой тег.

    Ищем ближайший @Tag над объявлением нужного класса: тот же приём, что в
    TestTagCoverageTest. Тег на отдельном методе класс не делает slow.

    Класс может нести несколько тегов (`slow` и `slow:konsist`), и этот
    ответ — по-прежнему один, ближайший. Правилу «класс помечен slow» нужен
    весь список: `_read_tags`.
    """
    tags = _read_tags(source, class_name)
    return tags[0] if tags else "untagged"


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
    """Имена тест-классов верхнего уровня, объявленных в файле."""
    # Скобки считаются по коду: литералы и комментарии обнулены, поэтому
    # фигурная скобка внутри строки или KDoc не может оборвать тело класса.
    lines = strip_code(source).split("\n")
    depths = line_depths(source)
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
        if _has_test_member(lines, depths, index):
            out.append(name)
    return out


def _is_abstract_or_open(declaration: str) -> bool:
    return re.match(
        r"^(?:public |internal |private |protected )?(?:abstract|open)\s+", declaration
    ) is not None


def _has_test_member(code_lines: list[str], depths: list[int], class_index: int) -> bool:
    """Есть ли @Test-аннотация в теле класса, начавшемся на [class_index].

    Считает фигурные скобки до закрытия тела, поэтому длина класса значения не
    имеет: у EntityMapperCompletenessTest первая @Test идёт на 340-й строке,
    и фиксированное окно в N строк отсеивало реальный тест как «пустой».

    [code_lines] — строки файла с обнулёнными литералами и комментариями
    (см. `scanners.source_text.strip_code`). Без этого непарная скобка внутри
    строки ('"', или JSON в сообщении assert) сдвигает глубину, тело класса
    обрывается раньше, и класс читается как «без тестов» — то есть как
    нетегированный и потому никогда не запускаемый.

    Раньше обнуление делалось построчно и не понимало ни raw-строк, ни
    блочных комментариев. Пять реальных тест-классов из-за этого были не видны
    инвентарю: `ApplyOutcomeArmsTest` (строка с `"\\n}"` внутри тела),
    `SharedHarnessConventionsTest` (KDoc с `{`), `TaskTimeSlotFailureTest`,
    `ArchitectureTest`, `EnqueueSiteIsScannedTest`. Kotlin-сторона того же
    вопроса закрыта стражем ClassBodyScannerAgreementTest; здесь это сделано
    сразу, потому что дешевле, чем ещё один страж с эталоном.

    [depths] — глубина скобок на конец каждой строки, от
    `scanners.source_text.line_depths`. Это тот же вопрос, что и «где кончилось
    тело класса», и раньше он отвечался здесь вторым счётчиком. Счётчик был
    не хуже — он считал по уже обнулённому тексту, — но он был вторым
    ответом на тот же вопрос, и мог разойтись с первым.
    """
    if "{" not in code_lines[class_index]:
        return any(
            _TEST_MEMBER.match(code_lines[class_index + i])
            for i in range(1, 6)
            if class_index + i < len(code_lines)
        )
    # Глубина снаружи класса: конец предыдущей строки. Класс может быть
    # вложен в другой, и «внутри» — это не ноль.
    outside = depths[class_index - 1] if class_index > 0 else 0
    opened = False
    for i in range(class_index, len(code_lines)):
        depth = depths[i] if i < len(depths) else outside
        if not opened and depth > outside:
            opened = True
        if _TEST_MEMBER.match(code_lines[i]):
            return True
        if opened and depth <= outside:
            return False
    return False


def has_runnable_test(source: str) -> bool:
    """Есть ли в файле тест, который JUnit действительно выполнит."""
    return bool(_test_classes_in(source))


def scan_repository() -> tuple[list[RepoTest], list[tuple[str, str]]]:
    """Every runnable test class in the repository, in a deterministic order.

    Returns a tuple of (found, skipped), where ``skipped`` is the list of
    (class_name, relative_path) for files that were scanned but contained no
    runnable test members.
    """
    found: list[RepoTest] = []
    skipped: list[tuple[str, str]] = []
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
                skipped.append((path.stem, _rel(path)))
                continue
            # Имя класса, объявленного в файле. Файл может объявлять класс,
            # отличный от path.stem — это видимый признак расхождения
            # (PurchaseStateTest в NoopSubscriptionProviderTest.kt, и т.д.).
            all_classes = _test_classes_in(source)
            file_class = path.stem  # имя файла без расширения
            if file_class in all_classes:
                declared_class = file_class
            elif all_classes:
                declared_class = all_classes[0]
            else:
                declared_class = "unknown"
            found.append(
                RepoTest(
                    fqn=fqn,
                    simple=fqn.rsplit(".", 1)[-1],
                    module=module,
                    rel_path=_rel(path),
                    feature=_feature_of(fqn),
                    # Тег берём у класса, чьё имя совпадает с именем файла.
                    # Если в файле объявлен другой тест-класс
                    # (NoopSubscriptionProviderTest.kt → PurchaseStateTest), честный
                    # ответ — untagged. Это видимый признак расхождения, а не
                    # тихая подстановка чужого тега.
                    tag=_read_tag(source, path.stem),
                    is_arch=".arch." in fqn or fqn.endswith(".arch"),
                    declared_class=declared_class,
                )
            )
    return found, skipped
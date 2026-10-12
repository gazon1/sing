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

# scripts/src/scanners/test_classes.py → [0]=scanners [1]=src [2]=scripts [3]=repo
REPO_ROOT = Path(__file__).resolve().parents[3]
PKG_PREFIX = "com.singularity.todo"

# Файлы, отсечённые как не-тесты (абстрактные базовые классы, хелперы).
# Наполняется сканером: иначе отсев выглядит как «пропало 5 классов» без
# объяснения, где они.
SKIPPED_NON_TESTS: list[tuple[str, str]] = []

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


def _read_tag(source: str, class_name: str | None = None) -> str:
    """Тег уровня КЛАССА, а не первый @Tag в файле.

    По правилам проекта каждый тестовый класс обязан иметь тег — иначе
    -Ptest.tags его молча исключит из каждого прогона.

    Раньше здесь стояло `re.search` по всему файлу, что неверно для файлов с
    несколькими классами: `FakeRepositoryFidelityTest.kt` несёт `@Tag("fast")`
    на постороннем объекте, и тестовый класс на строке 41 получал чужой тег.

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
    """Имена тест-классов верхнего уровня, объявленных в файле."""
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


def _strip_literals_and_comments(line: str) -> str:
    """Строка с обнулёнными литералами и комментариями, длина сохранена.

    Символьный обход, а не регулярка: у регулярки были бы свои представления о
    том, что такое строка, и эталон не смог бы заметить, что реализация ошибается
    — а это единственная работа этого метода.
    """
    out: list[str] = []
    in_string = False
    escaped = False
    i = 0
    n = len(line)
    while i < n:
        ch = line[i]
        if in_string:
            # Each branch appends exactly one character, including the escape
            # ones. Omitting them shortens the line, and the function's contract is
            # that the result is the same length as the input so a caller can map
            # one to the other.
            if escaped:
                escaped = False
                out.append(" ")
            elif ch == "\\":
                escaped = True
                out.append(" ")
            elif ch == '"':
                in_string = False
                out.append(" ")
            else:
                out.append(" ")
        elif ch == '"':
            in_string = True
            out.append(" ")
        elif ch == "/" and i + 1 < n and line[i + 1] == "/":
            out.append(" " * (n - i))
            break
        else:
            out.append(ch)
        i += 1
    return "".join(out)


def _has_test_member(lines: list[str], class_index: int) -> bool:
    """Есть ли @Test-аннотация в теле класса, начавшемся на [class_index].

    Считает фигурные скобки до закрытия тела, поэтому длина класса значения не
    имеет: у EntityMapperCompletenessTest первая @Test идёт на 340-й строке,
    и фиксированное окно в N строк отсеивало реальный тест как «пустой».

    Скобки считаются по строке с вырезанными литералами и комментариями. Без
    этого непарная скобка внутри строки ('"', или JSON в сообщении assert)
    сдвигает глубину, тело класса обрывается раньше, и класс читается как
    «без тестов» — то есть как нетегированный и потому никогда не
    запускаемый. Kotlin-сторона того же вопроса закрыта стражем
    ClassBodyScannerAgreementTest; здесь это сделано сразу, потому что
    дешевле, чем ещё один страж с эталоном.
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
        for ch in _strip_literals_and_comments(lines[i]):
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
    """Every runnable test class in the repository, in a deterministic order.

    ``SKIPPED_NON_TESTS`` accumulates across calls, exactly as it did in
    ``sync.py`` — a caller that scans twice sees the skip list twice. That is
    preserved rather than fixed because every consumer of it read it once per
    process, and changing it here would have made the extraction unprovable
    against the baseline captured before the move.
    """
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
    return found
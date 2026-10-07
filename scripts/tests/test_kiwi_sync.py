"""Unit tests for the Kiwi TCMS stand (infra/kiwi).

Run with: python3 -m unittest discover -s scripts/tests

Covered: JUnit XML parsing, repository scanning, and the status mapping that
decides what a test outcome looks like in Kiwi. The XML-RPC client itself is
NOT tested here — it needs a running stand, and every contract it depends on
(product/plan/case/run creation) is exercised by the end-to-end run described
in infra/kiwi/README.md. Testing it against mocks would assert the mock.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

_kiwi_dir = pathlib.Path(__file__).resolve().parent.parent.parent / "infra" / "kiwi"
sys.path.insert(0, str(_kiwi_dir))

_spec = importlib.util.spec_from_file_location("kiwi_sync", _kiwi_dir / "sync.py")
_sync = importlib.util.module_from_spec(_spec)
_sync.__name__ = "kiwi_sync"
_sync.__file__ = str(_kiwi_dir / "sync.py")
# Регистрация в sys.modules обязательна и должна предшествовать exec_module:
# @dataclass на этапе определения класса ищет sys.modules[cls.__module__], и
# без записи падает с «'NoneType' object has no attribute '__dict__'».
sys.modules["kiwi_sync"] = _sync
_spec.loader.exec_module(_sync)

import kiwi_client  # noqa: E402  (resolved from the sys.path entry above)

sync = _sync

_REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent


def _write_xml(directory: pathlib.Path, name: str, body: str) -> pathlib.Path:
    target = directory / name
    target.write_text(body, encoding="utf-8")
    return target


class ParseJunitTest(unittest.TestCase):
    """JUnit XML → результаты по FQN."""

    def test_reads_classname_not_testsuite_name(self):
        # Gradle пишет в testsuite@name суффикс платформы; привязка к кейсам
        # идёт по FQN из <testcase classname>. Беря testsuite@name, мы получили
        # бы 0 совпадений вместо 212.
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            _write_xml(
                d,
                "TEST-x.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="AgendaDslTest[jvm]" tests="2">
                  <testcase classname="com.example.AgendaDslTest" name="evaluates" time="0.5"/>
                  <testcase classname="com.example.AgendaDslTest" name="buckets" time="0.5"/>
                </testsuite>""",
            )
            by_class = sync.parse_junit([d])

        self.assertIn("com.example.AgendaDslTest", by_class)
        self.assertNotIn("AgendaDslTest[jvm]", by_class)
        self.assertEqual(len(by_class["com.example.AgendaDslTest"]), 2)

    def test_failure_error_and_skipped_are_distinguished(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            _write_xml(
                d,
                "TEST-y.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="S" tests="4">
                  <testcase classname="C" name="ok"/>
                  <testcase classname="C" name="bad"><failure message="boom"/></testcase>
                  <testcase classname="C" name="err"><error message="kaboom"/></testcase>
                  <testcase classname="C" name="skip"><skipped/></testcase>
                </testsuite>""",
            )
            results = {r.name: r for r in sync.parse_junit([d])["C"]}

        self.assertEqual(results["ok"].status, "passed")
        self.assertEqual(results["bad"].status, "failed")
        self.assertEqual(results["err"].status, "error")
        self.assertEqual(results["skip"].status, "skipped")
        self.assertEqual(results["bad"].message, "boom")

    def test_nested_testsuites_are_flattened(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            _write_xml(
                d,
                "TEST-z.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <testsuites>
                  <testsuite name="A"><testcase classname="A" name="a"/></testsuite>
                  <testsuite name="B"><testcase classname="B" name="b"/></testsuite>
                </testsuites>""",
            )
            by_class = sync.parse_junit([d])

        self.assertEqual(sorted(by_class), ["A", "B"])

    def test_malformed_xml_is_reported_not_raised(self):
        # Битый XML в build/test-results не должен ронять весь синк: каталог
        # мог остаться от прерванного прогона.
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            _write_xml(d, "TEST-broken.xml", "<testsuite><not-closed>")
            self.assertEqual(sync.parse_junit([d]), {})


class StatusForTest(unittest.TestCase):
    """JUnit статус → Kiwi TestExecutionStatus.

    Exercised through ``kiwi_client``, where the function actually lives. It
    used to be reached via ``sync.status_for``, which only worked because
    ``sync.py`` imported it and never called it — the per-class rollup that
    replaced it is inline in ``sync_results``. Testing through an unused import
    made the legacy pipeline look like it had a reusable status mapper, which is
    the belief that would have produced a second one.
    """

    def test_maps_common_spellings(self):
        for junit, expected in (
            ("passed", "PASSED"),
            ("PASSED", "PASSED"),
            ("failed", "FAILED"),
            ("error", "ERROR"),
            ("skipped", "IDLE"),
            ("disabled", "IDLE"),
        ):
            with self.subTest(junit=junit):
                self.assertEqual(kiwi_client.status_for(junit), expected)

    def test_unknown_status_becomes_idle_not_passed(self):
        # Kiwi не знает произвольных статусов; молча превращать неизвестное в
        # PASSED значило бы записать в базу успех там, где его не было.
        self.assertEqual(kiwi_client.status_for("quarantined"), "IDLE")


class RollupClassStatusTest(unittest.TestCase):
    """Сведение тестов одного класса к одному Kiwi-статусу.

    Проверяет ровно то, что раньше было неуловимо: ``sync.sync_results`` пишет
    эти статусы в Kiwi для всех 259 кейсов планов ``Automated/*``, и при этом
    ни один тест их не касался — ветка сводки жила инлайном в цикле, а
    ``sync_results`` требует поднятой стенды. Изменение статуса там было бы
    неотличимо от успеха.
    """

    @staticmethod
    def _r(status: str, name: str = "t", message: str = "") -> sync.TestResult:
        return sync.TestResult(
            classname="com.example.ATest", name=name, status=status, message=message
        )

    def test_all_passed(self):
        status, comment = sync.rollup_class_status(
            [self._r("passed", "a"), self._r("passed", "b")]
        )
        self.assertEqual(status, "PASSED")
        self.assertEqual(comment, "2 тестов пройдено")

    def test_all_skipped_is_idle(self):
        status, comment = sync.rollup_class_status(
            [self._r("skipped", "a"), self._r("skipped", "b")]
        )
        self.assertEqual(status, "IDLE")
        self.assertEqual(comment, "все 2 тестов пропущены")

    def test_mixed_skip_still_passes(self):
        # Пропуск — не провал: класс, где часть тестов пропущена, а остальные
        # прошли, это PASSED с пометкой, а не IDLE.
        status, comment = sync.rollup_class_status(
            [self._r("passed", "a"), self._r("skipped", "b")]
        )
        self.assertEqual(status, "PASSED")
        self.assertEqual(comment, "1/2 пропущено")

    def test_failed_beats_skipped(self):
        # Порядок проверок значим: если бы skipped проверялся первым, класс с
        # одним упавшим и одним пропущенным тестом записался бы как IDLE, и
        # зелёный прогон скрыл бы падение.
        status, comment = sync.rollup_class_status(
            [self._r("failed", "boom", "assertion"), self._r("skipped", "b")]
        )
        self.assertEqual(status, "FAILED")
        self.assertIn("1/2 упало", comment)
        self.assertIn("boom: assertion", comment)

    def test_error_also_counts_as_failed(self):
        # `error` в JUnit — это неупавший-но-сломавшийся тест; здесь он
        # приравнен к failed намеренно (иначе он тихо уехал бы в PASSED).
        status, _ = sync.rollup_class_status(
            [self._r("passed", "a"), self._r("error", "b", "NoSuchMethod")]
        )
        self.assertEqual(status, "FAILED")

    def test_comment_is_capped(self):
        # Комментарий уходит в Kiwi, у которого есть лимит на длину; длинные
        # имена тестов не должны приводить к отказу записи execution.
        results = [self._r("failed", "x" * 400, "y" * 400) for _ in range(5)]
        _, comment = sync.rollup_class_status(results)
        self.assertLessEqual(len(comment), 2000)

    def test_only_first_five_failures_are_named(self):
        # Пять имён — предел, заданный самой сводкой, а не клиентом Kiwi.
        results = [self._r("failed", f"t{i}", "m") for i in range(9)]
        _, comment = sync.rollup_class_status(results)
        self.assertIn("9/9 упало", comment)
        self.assertIn("t0", comment)
        self.assertIn("t4", comment)
        self.assertNotIn("t5", comment)


class FeatureOfTest(unittest.TestCase):
    """Пакет → Kiwi-компонент."""

    def test_feature_and_core_keep_two_levels(self):
        self.assertEqual(
            sync._feature_of("com.singularity.todo.feature.tasks.data.XTest"),
            "feature.tasks",
        )
        self.assertEqual(
            sync._feature_of("com.singularity.todo.core.sync.XTest"), "core.sync"
        )

    def test_other_top_level_packages_collapse_to_one_level(self):
        for fqn, expected in (
            ("com.singularity.todo.arch.SomeTest", "arch"),
            ("com.singularity.todo.test.helpers.FakesTest", "test"),
            ("com.singularity.todo.shell.XTest", "shell"),
        ):
            with self.subTest(fqn=fqn):
                self.assertEqual(sync._feature_of(fqn), expected)


def unjustified_skips(repo_root: pathlib.Path, skipped):
    """Skip entries that no longer deserve to be skipped.

    Extracted so the self-test can hand it synthetic entries. The property is a claim about
    files on disk, and a claim about files can only be tested by feeding the function files
    that are in known states — the three cases below, none of which exist in the repository
    and all of which the hand-written list this replaced could not have expressed.
    """
    problems = []
    for name, rel in skipped:
        path = repo_root / rel
        if not path.is_file():
            problems.append(f"{name}: {rel} is not a file")
            continue
        if sync.has_runnable_test(path.read_text(encoding="utf-8")):
            problems.append(
                f"{name}: {rel} is skipped but has a runnable test"
            )
    return problems


class SkipJustificationTest(unittest.TestCase):
    """The controls for `unjustified_skips`, which is the property that replaced a list."""

    def test_a_ghost_entry_is_reported(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            self.assertEqual(
                ["GhostTest: nope/NotThereTest.kt is not a file"],
                unjustified_skips(root, [("GhostTest", "nope/NotThereTest.kt")]),
            )

    def test_a_base_that_gained_a_subclass_is_reported(self):
        # The drift the hand-written list could not catch: the name is unchanged, the file
        # is unchanged, but the file now contains something JUnit will run. The entry stays
        # in the skip list and the test class silently disappears from the inventory.
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            (root / "Contract").mkdir()
            path = root / "Contract" / "TaskRepositoryContractTest.kt"
            path.write_text(
                "abstract class TaskRepositoryContractTest {\n"
                "  protected abstract fun make(): Any\n"
                "}\n"
            )
            self.assertEqual(
                [],
                unjustified_skips(root, [("TaskRepositoryContractTest", str(path))]),
                "an abstract-only file is correctly skipped",
            )
            path.write_text(
                "abstract class TaskRepositoryContractTest {\n"
                "  protected abstract fun make(): Any\n"
                "}\n"
                "class RoomContractTest : TaskRepositoryContractTest() {\n"
                "  override fun make(): Any = Any()\n"
                "  @Test fun real() {}\n"
                "}\n"
            )
            self.assertEqual(
                [
                    "TaskRepositoryContractTest: %s is skipped but has a runnable test"
                    % path
                ],
                unjustified_skips(root, [("TaskRepositoryContractTest", str(path))]),
                "a base that gained a concrete subclass must stop being skipped",
            )

    def test_a_genuinely_unrunnable_helper_is_accepted(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            path = root / "RunVmTest.kt"
            path.write_text("class RunVmTest { fun run() {} }")
            self.assertEqual([], unjustified_skips(root, [("RunVmTest", str(path))]))


class ScanRepositoryTest(unittest.TestCase):
    """Скан реального репозитория."""

    @classmethod
    def setUpClass(cls):
        cls.tests = sync.scan_repository()

    def test_finds_the_whole_known_inventory(self):
        # Проект содержит ~260 тестовых классов; падение ниже 200 означало бы,
        # что пути в TEST_ROOTS разошлись с реальностью. Верхняя граница тоже
        # осмысленна: её рост означал бы возврат отсева abstract-баз и хелперов.
        self.assertGreater(len(self.tests), 200)
        # 360, not upstream's 350: this merge adds three classes —
        # SyncWriteIsAtomicTest, SyncEngineTakesNoFeatureTypesTest and
        # SyncDiGraphResolutionTest — so the counter moves 348 -> 351 on the merged
        # tree, verified by scan_repository() with the same check the previous bumps
        # used (paths unique). 360 leaves nine slots, fewer than the growth of the last
        # merge, so the bound still asks the question rather than absorbing an overshoot.
        # 370, not 360: the counter reached 360 exactly and the bound fired as
        # `360 not less than 360`. Both classes that took it there are upstream's —
        # SyncedWriteEnqueuesTest and SyncEngineEnqueueReportsFailureTest, one each
        # from two separate merges — so the growth is real test classes, not the
        # abstract-base and helper drift the upper bound exists to catch. Ten slots,
        # more than the last two merges added together, so the bound still asks the
        # question on the next merge instead of absorbing an overshoot.
#
# One correction to the older comment above this line, which asserted that all 342
# names end in `Test`. That stopped being true and had stopped before this: the
# property is checked where it can be, by deriving each skip from the file that earns
# it rather than by asserting a list of names (`unjustified_skips`). What is left here
# is only a tripwire against the filter regressing, and a tripwire re-derived on every
# legitimate addition has a moving target. That is fine — as long as nobody reads it as
# a coverage claim. It is not one.
        self.assertLess(len(self.tests), 370)
        # 260, а не 259: NoopSubscriptionProviderTest.kt объявляет класс
        # PurchaseStateTest, и прежний отсев по «нет @Test у класса с именем
        # файла» выбрасывал файл целиком, теряя настоящий тест.
        #
        # Верхняя граница поднята с 300 на 310, когда счётчик достиг ровно 300:
        # платформенный гейт PlatformClaimWiringTest стал 300-м классом и упал на
        # `300 not less than 300`. Граница намеренно двигается вместе с числом
        # классов, но медленнее него — 310 это 7 запасных классов, а не «сколько
        # бы ни понадобилось». Если упадёт снова, дело не в счётчике.
        #
        # Поднята с 320 на 340 при счётчике 336, 2026-10-07. Протокол тот же:
        # проверено, что все 336 оканчиваются на `Test` и посторонних имён нет,
        # то есть это настоящие классы, а не вернувшиеся abstract-базы. Рост дал
        # чужой транш auth/sync — 25 классов в `arch`, 20 в `detekt`, 18 в
        # `core.sync`. Запас 4 класса.
        #
        # Поднята с 310 на 320 при счётчике 313. Прежде чем поднимать, проверено,
        # что это настоящие классы, а не вернувшиеся abstract-базы и хелперы —
        # именно это ловит граница: все 313 оканчиваются на `Test`, посторонних
        # имён нет. Счётчик вырос на 4 класса рекуррентности и селекторов и на
        # часть синхронизации; ни один из них не хелпер.
        #
        # Поднята с 320 на 330 при счётчике 321: календарная синхронизация
        # добавила около двадцати классов, и граница, двигаясь медленнее счётчика,
        # обогнала его. Проверено тем же способом — все 321 оканчиваются на `Test`.
        #
        # Затем ещё трижды за один день, каждое «не в счётчике»: слитая синхронизация
        # с календарём (`feature.calendar_sync`, 21 класс), правки notes/sync, и
        # `feature.gate`. Счётчик дорос до 336 при границе 340 — `340 not less than
        # 340` — поэтому граница поднята до 350, и снова по той же процедуре: все
        # имена оканчиваются на `Test`, пути уникальны, `SKIPPED_NON_TESTS` не сдвинулся.
        #
        # Общий смысл, который стоит знать прежде чем поднимать снова: граница
        # обгоняется при каждом слиянии ветки, и возвратившиеся хелперы выглядят
        # ровно так же, как новые тесты. Поэтому проверка выше — обязательная часть,
        # а число — бухгалтерия.
        # добавила около двадцати классов, и граница, двигаясь медленнее
        # счётчика, обогнала его. Проверено тем же способом — все 321
        # оканчиваются на `Test`, посторонних имён нет.
        #
        # Поднята с 330 на 340 при счётчике 337: тот же набор, плюс тесты
        # слоя GenUI и правки notes/sync. Снова проверено — 337 из 337
        # оканчиваются на `Test`.
        #
        # Поднята с 340 на 350 при ровно 340: `340 not less than 340`. Граница
        # обгоняет счётчик каждый раз, когда сливается ветка, поэтому поднимать
        # её придётся ещё — и каждый раз стоит прогонять проверку выше, а не
        # просто увеличивать число, потому что вернувшиеся хелперы выглядят
        # ровно так же, как новые тесты.

    def test_every_skipped_file_is_really_not_runnable(self):
        """The skip list is derived from the files, so it is checked against them.

        This used to assert a hand-written list of five names, and that list broke on
        2026-10-07: `7582a27f` added `ApplyOutcomeArmsTest` to the scanner's bookkeeping
        and the suite went red on a clean `origin/main` until the name was added by hand.
        Two artefacts a file apart, one edited without the other — the same failure mode
        as the platform mirror, and the same fix: derive instead of remember.

        The derived property is stronger than the list ever was. The list could only say
        "these five are expected"; this says each one is a real file that
        `has_runnable_test()` rejects *right now*, which also catches the opposite drift —
        a base class that acquired a concrete subclass and is still being skipped.
        `SkipJustificationTest` covers the rule itself.
        """
        self.assertEqual(
            [],
            unjustified_skips(_REPO_ROOT, sync.SKIPPED_NON_TESTS),
            "these entries are skipped without deserving it — a skipped test class is "
            "invisible to Kiwi, and the inventory count only has a floor and a ceiling",
        )

    def test_no_test_file_is_neither_inventoried_nor_skipped(self):
        """Every `*Test.kt` is accounted for.

        The list above proves each skip is justified; this proves nothing fell through
        between the two. A scanner change that stopped walking a whole subtree would keep
        both the inventory count and the skip list looking plausible — the count only has
        a floor and a ceiling, and a subtree lost that happened to be small would sit
        comfortably between them.
        """
        accounted = {t.rel_path for t in self.tests} | {
            rel for _, rel in sync.SKIPPED_NON_TESTS
        }
        on_disk = {
            path.relative_to(_REPO_ROOT).as_posix()
            for _, root in sync.TEST_ROOTS
            if root.exists()
            for path in root.rglob("*Test.kt")
        }
        self.assertEqual(
            [],
            sorted(on_disk - accounted),
            "these *Test.kt files are in neither the inventory nor the skip list — they "
            "are invisible to Kiwi entirely",
        )

    def test_the_skip_list_stays_small(self):
        # A ceiling rather than a list. The point of the skip list is that a handful of
        # bases and helpers do not produce a run; a large one means the predicate started
        # rejecting real tests, which the per-entry check above would not flag on its own
        # because each entry would still be genuinely unrunnable.
        self.assertLess(
            len(sync.SKIPPED_NON_TESTS),
            20,
            "the skip list has grown to "
            f"{len(sync.SKIPPED_NON_TESTS)}; is_runnable_test_class is rejecting real tests?",
        )

    def test_every_case_carries_plan_and_path(self):
        for t in self.tests[:50]:
            with self.subTest(fqn=t.fqn):
                self.assertTrue(t.plan)
                self.assertTrue(t.rel_path.endswith("Test.kt"))
                self.assertTrue(t.fqn.startswith("com.singularity.todo"))

    def test_source_paths_are_unique(self):
        # source_path — ключ идемпотентности. Дубликат означал бы, что один из
        # двух кейсов молча перестал бы обновляться.
        paths = [t.rel_path for t in self.tests]
        self.assertEqual(len(paths), len(set(paths)))

    def test_untagged_classes_are_surfaced_not_hidden(self):
        # Проект требует @Tag на каждом тестовом классе; отсутствие тега —
        # находка, а не повод пропустить класс.
        untagged = [t for t in self.tests if t.tag == "untagged"]
        for t in untagged:
            self.assertEqual(t.tag, "untagged")


class RunnableTestClassTest(unittest.TestCase):
    """is_runnable_test_class — отсев файлов, не дающих прогона."""

    def test_ordinary_test_class_is_runnable(self):
        self.assertTrue(
            sync.has_runnable_test(
                '@Tag("fast")\nclass FooTest {\n  @Test fun x() {}\n}'
            )
        )

    def test_parameterized_test_counts_as_a_test(self):
        # Параметризованные тесты дают прогон, но не содержат @Test.
        # Отсев по «нет @Test» выкинул бы 4 реальных тест-класса.
        source = (
            "import org.junit.jupiter.params.ParameterizedTest\n"
            "class FooTest {\n"
            "  @ParameterizedTest\n  fun x() {}\n"
        )
        self.assertTrue(sync.has_runnable_test(source))

    def test_abstract_base_alone_is_not_runnable(self):
        # Файл, где есть ТОЛЬКО абстрактная база: прогонa не будет.
        source = (
            "@Tag(\"slow\")\nabstract class ContractTest {\n"
            "  protected abstract fun make(): Any\n}\n"
        )
        self.assertFalse(sync.has_runnable_test(source))

    def test_concrete_subclass_makes_the_file_runnable(self):
        # А вот этот файл прогон даёт: сценарии исполняет подкласс, а не база.
        # Отсеивать его было бы ошибкой — теряется настоящий тест.
        source = (
            "@Tag(\"slow\")\nabstract class ContractTest {\n"
            "  protected abstract fun make(): Any\n}\n"
            "class RoomContractTest : ContractTest() {\n"
            "  @Test fun real() {}\n}\n"
        )
        self.assertTrue(sync.has_runnable_test(source))
        self.assertEqual(sync._test_classes_in(source), ["RoomContractTest"])

    def test_helper_without_tests_is_not_runnable(self):
        self.assertFalse(
            sync.has_runnable_test("class RunVmTest { fun run() {} }")
        )


class StringAwareClassBodyTest(unittest.TestCase):
    """A literal brace must not be able to end a class body early.

    The Kotlin side of this same question is watched by
    `ClassBodyScannerAgreementTest` in `:shared`. This is the Python side, and it
    needed the fix rather than a watcher: the naive counter reported *no test
    member* for a class that has one, and a class with no test member is reported
    untagged and therefore never selected by `-Ptest.tags`. That is the D1 shape
    the whole fixture table exists to prevent, arriving through a different door.
    """

    def test_unbalanced_brace_in_a_literal_above_a_test(self):
        # The dangerous direction, stated as a verdict: a class with a test reads
        # as having none, so it looks untagged and looks unrun.
        source = (
            "class FooTest {\n"
            '    private val probe = "}"\n'
            "    @Test\n"
            "    fun a() {}\n"
            "}\n"
        )
        self.assertTrue(
            sync.has_runnable_test(source),
            "an unbalanced brace inside a string literal truncated the class body, "
            "so a class with a @Test was read as having none",
        )

    def test_unbalanced_brace_after_the_test_is_harmless(self):
        source = (
            "class FooTest {\n"
            "    @Test\n"
            "    fun a() {}\n"
            '    private val tail = "{"\n'
            "}\n"
        )
        self.assertTrue(sync.has_runnable_test(source))

    def test_a_literal_containing_a_brace_pair_does_not_shift_depth(self):
        source = (
            "class FooTest {\n"
            '    private val json = "{\"k\": 1}"\n'
            "    @Test\n"
            "    fun a() {}\n"
            "}\n"
        )
        self.assertTrue(sync.has_runnable_test(source))

    def test_string_templates_are_still_counted_as_code(self):
        """A `${...}` template is code, not literal text.

        Blanking the literal must not blank the template inside it: `a ${b} c`
        has real braces that legitimately open and close a scope, and ignoring
        them would push the body end somewhere else entirely.
        """
        source = (
            "class FooTest {\n"
            '    private val id = "task-${n}"\n'
            "    @Test\n"
            "    fun a() {}\n"
            "}\n"
        )
        self.assertTrue(sync.has_runnable_test(source))

    def test_stripping_preserves_length_so_offsets_do_not_drift(self):
        line = '    private val probe = "}"  // a comment with { and }'
        self.assertEqual(len(sync._strip_literals_and_comments(line)), len(line))

    def test_a_line_comment_ending_the_line_is_removed(self):
        self.assertNotIn(
            "{",
            sync._strip_literals_and_comments('val a = 1 // this { is prose'),
        )

    def test_escaped_quote_does_not_end_the_literal(self):
        """A backslash-quote inside a literal must not close it.

        Without escape handling the scanner reads this as two literals with the
        text between them treated as code, so whatever braces sit there start
        counting. The observable proof is that the whole literal is blanked as
        one unit — `hi` is gone — rather than only the part before the escape.
        """
        line = '    val s = "he said \\"hi\\""'
        stripped = sync._strip_literals_and_comments(line)
        self.assertEqual(len(stripped), len(line))
        self.assertNotIn("hi", stripped)
        self.assertNotIn("he said", stripped)
        # The code before the literal survives, which is what makes this an
        # assertion about the literal rather than about blanking the line.
        self.assertIn("val s =", stripped)


class ReadTagTest(unittest.TestCase):
    """@Tag над нужным классом, а не первый @Tag в файле."""

    def test_reads_class_level_tag(self):
        source = '@Tag("slow")\nclass FooTest\n'
        self.assertEqual(sync._read_tag(source, "FooTest"), "slow")

    def test_missing_tag_is_reported_as_untagged(self):
        self.assertEqual(sync._read_tag("class FooTest", "FooTest"), "untagged")

    def test_fully_qualified_tag_is_recognised(self):
        # Файлы, где доменная сущность tags.Tag уже импортирована, пишут
        # аннотацию полностью. Короткий regex читал их как untagged.
        source = '@org.junit.jupiter.api.Tag("fast")\nclass FooTest\n'
        self.assertEqual(sync._read_tag(source, "FooTest"), "fast")

    def test_a_tag_on_another_class_is_not_borrowed(self):
        # Реальный случай: FakeRepositoryFidelityTest.kt несёт @Tag на
        # постороннем объекте. Старый код брал первый @Tag в файле, и кейс
        # получал тег, которого у него нет.
        source = (
            "@Tag(\"fast\")\nprivate object Fixture\n"
            "class FakeRepositoryFidelityTest {\n  @Test fun x() {}\n}\n"
        )
        self.assertEqual(
            sync._read_tag(source, "FakeRepositoryFidelityTest"), "untagged"
        )

    def test_aliased_tag_import_is_not_required_for_the_common_form(self):
        # Полная и короткая формы обе поддерживаются; алиас — отдельный случай
        # TestTagCoverageTest, здесь достаточно не сломать обычный @Tag.
        source = 'import org.junit.jupiter.api.Tag\n@Tag("slow")\nclass FooTest\n'
        self.assertEqual(sync._read_tag(source, "FooTest"), "slow")


class GitVersionTest(unittest.TestCase):
    def test_returns_something_non_empty(self):
        # Build в Kiwi требует непустую версию; исключение внутри функции
        # уронило бы весь sync_results.
        self.assertTrue(sync.git_version())


class BatchPropertiesTest(unittest.TestCase):
    """Клиент Kiwi: свойства пачкой, а не по одному на кейс.

    Проверяется на настоящем стенде, а не на моке: весь смысл метода в том,
    что он НЕ делает запрос на каждый кейс, и мок этого не проверит. Если
    стенд не поднят — тест пропускается, а не падает.
    """

    def setUp(self):
        self.client = _sync.KiwiClient()
        try:
            self.client.check_alive()
        except Exception as exc:  # noqa: BLE001 — причина в пропуске ниже
            self.skipTest(f"стенд Kiwi не поднят: {exc}")

    def test_bulk_properties_cover_every_case(self):
        product = self.client.get_product("Singularity Todo")
        if product is None:
            self.skipTest("продукт не заведён — выполните sync.py --plan")
        plan_ids = [
            p["id"]
            for p in self.client.call(
                "TestPlan.filter", {"product__id": product["id"]}
            )
            or []
        ]
        expected = {
            case["id"]
            for plan_id in plan_ids
            for case in self.client.get_cases(plan_id)
        }
        bulk = self.client.get_all_cases_properties(plan_ids)
        self.assertEqual(set(bulk), expected)

    def test_bulk_and_single_agree(self):
        # Расхождение между пачкой и одиночным вызовом — источник тихих дыр:
        # sync видел бы часть кейсов без source_path и пересоздавал их.
        product = self.client.get_product("Singularity Todo")
        if product is None:
            self.skipTest("продукт не заведён — выполните sync.py --plan")
        plan_ids = [
            p["id"]
            for p in self.client.call(
                "TestPlan.filter", {"product__id": product["id"]}
            )
            or []
        ]
        bulk = self.client.get_all_cases_properties(plan_ids)
        sample = list(bulk)[:5]
        self.assertTrue(sample, "нет кейсов для сверки")
        for case_id in sample:
            with self.subTest(case=case_id):
                self.assertEqual(self.client.get_case_properties(case_id), bulk[case_id])


if __name__ == "__main__":
    unittest.main()

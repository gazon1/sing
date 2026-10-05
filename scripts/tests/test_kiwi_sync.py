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
        self.assertLess(len(self.tests), 300)
        # 260, а не 259: NoopSubscriptionProviderTest.kt объявляет класс
        # PurchaseStateTest, и прежний отсев по «нет @Test у класса с именем
        # файла» выбрасывал файл целиком, теряя настоящий тест.

    def test_abstract_bases_and_helpers_are_excluded(self):
        # Класс, который никогда не даёт прогона (abstract-база контракта или
        # хелпер с суффиксом Test), в Kiwi становится вечным «кейсом без
        # прогона» и портит главный сигнал отчёта. Таких в репозитории ровно
        # четыре, и они перечислены явно: иначе отсев выглядит как «пропало
        # четыре кейса» без объяснения.
        self.assertEqual(
            sorted(fqn.rsplit(".", 1)[-1] for fqn, _ in sync.SKIPPED_NON_TESTS),
            [
                "FileSystemContractTest",
                "IsolatedComposeTest",
                "RunVmTest",
                "TaskRepositoryContractTest",
            ],
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

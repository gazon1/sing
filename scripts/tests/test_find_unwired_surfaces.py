"""Unit tests for find-unwired-surfaces.py.

Run with: python3 -m unittest discover -s scripts/tests
"""

import pathlib
import shutil
import tempfile
import unittest

import importlib.util
import sys

# The script is named find-unwired-surfaces.py (hyphens), not find_unwired_surfaces.
_scripts_dir = pathlib.Path(__file__).resolve().parent.parent.parent / "scripts"
_spec = importlib.util.spec_from_file_location(
    "find_unwired_surfaces", _scripts_dir / "find-unwired-surfaces.py"
)
_fus_module = importlib.util.module_from_spec(_spec)
_fus_module.__name__ = "find_unwired_surfaces"
_fus_module.__file__ = str(_scripts_dir / "find-unwired-surfaces.py")
sys.modules["find_unwired_surfaces"] = _fus_module  # must precede exec_module
_spec.loader.exec_module(_fus_module)
import find_unwired_surfaces as fus


class TestStripComments(unittest.TestCase):
    def test_removes_block_comment(self):
        self.assertEqual(fus.strip_comments("a/* b */c"), "ac")

    def test_removes_line_comment(self):
        self.assertEqual(fus.strip_comments("a // b\nc"), "a \nc")

    def test_kdoc_mention_not_counted(self):
        text = "/** See [FooScreen] for details. */\nfun FooScreen() = Unit"
        stripped = fus.strip_comments(text)
        self.assertIn("FooScreen", stripped)


class TestDetectorTable(unittest.TestCase):
    """Smoke test: every detector in DETECTORS is registered and callable."""

    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp())

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _fake_code(self, paths_to_content):
        """Return a code dict (path → stripped text).

        Args:
            paths_to_content: either a dict of {filename: content} or
                              a list of (filename, content) tuples.
        """
        result = {}
        items = (
            paths_to_content.items()
            if isinstance(paths_to_content, dict)
            else paths_to_content
        )
        for name, content in items:
            p = self.tmp / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content)
            result[p] = fus.strip_comments(content)
        return result

    def _corpus(self, code):
        return "\n".join(code.values())

    # ── Detector 1: screen ─────────────────────────────────────────────────

    def test_screen_positive(self):
        code = self._fake_code({"x.kt": "fun FooScreen() = Unit", "y.kt": "fun other() = Unit"})
        findings = fus._check_composable(code, self._corpus(code))
        self.assertTrue(any("FooScreen" in f for _, f in findings), findings)

    def test_screen_negative_wired(self):
        code = self._fake_code({"x.kt": "fun FooScreen() = Unit", "y.kt": "FooScreen()"})
        findings = fus._check_composable(code, self._corpus(code))
        self.assertFalse(any("FooScreen" in f for _, f in findings), findings)

    def test_screen_multi_suffix(self):
        for suffix in ("Screen", "Card", "Section", "Sheet"):
            name = "Foo" + suffix
            code = self._fake_code({"x.kt": "fun " + name + "() = Unit", "y.kt": "fun other() = Unit"})
            findings = fus._check_composable(code, self._corpus(code))
            self.assertTrue(any(name in f for _, f in findings), suffix)

    def test_default_noop_positive(self):
        # The noop param name must also appear as a fallback consumer elsewhere in the corpus.
        code = self._fake_code({"x.kt": "val onClick: () -> Unit = {}", "y.kt": "onClick ?: defaultCallback"})
        findings = fus._check_default_noop(code, self._corpus(code))
        self.assertTrue(any("onClick" in f for _, f in findings), findings)

    def test_default_noop_negative_no_fallback(self):
        code = self._fake_code({"x.kt": "val onClick: () -> Unit = {}", "y.kt": "fun other() = Unit"})
        findings = fus._check_default_noop(code, self._corpus(code))
        self.assertFalse(findings, findings)

    def test_di_binding_positive(self):
        code = self._fake_code({
            "AppDatabase.kt": "abstract fun foo(): FooDao",
            "JvmPlatformModule.kt": "get<AppDatabase>().bar()",
            "AndroidPlatformModule.kt": "get<AppDatabase>().baz()",
        })
        pre = fus._precompute_di_binding(code)
        findings = fus._check_di_binding(code, self._corpus(code), pre)
        self.assertTrue(any("foo" in f for _, f in findings), findings)

    def test_di_binding_negative(self):
        code = self._fake_code({
            "AppDatabase.kt": "abstract fun foo(): FooDao",
            "JvmPlatformModule.kt": "get<AppDatabase>().foo()",
            "AndroidPlatformModule.kt": "get<AppDatabase>().foo()",
        })
        pre = fus._precompute_di_binding(code)
        findings = fus._check_di_binding(code, self._corpus(code), pre)
        self.assertFalse(any("foo" in f for _, f in findings), findings)

    def test_di_binding_reached_through_an_injected_database(self):
        # Injecting the database is a second route to a DAO: the resolver is handed the
        # database and asks it for the DAO itself. No `single { … }` binding is needed,
        # so flagging this would report a DAO the app uses on every account switch.
        code = self._fake_code({
            "AppDatabase.kt": "abstract fun foo(): FooDao",
            "OwnerScopedEraser.kt": (
                "class E(private val database: AppDatabase) { "
                "fun go() = database.foo() }"
            ),
        })
        pre = fus._precompute_di_binding(code)
        findings = fus._check_di_binding(code, self._corpus(code), pre)
        self.assertFalse(any("foo" in f for _, f in findings), findings)

    def test_di_binding_reachable_only_from_a_test_is_still_flagged(self):
        # A test calling the DAO says nothing about whether the app can. That is the
        # whole defect the detector names, so the test source set is excluded.
        code = self._fake_code({
            "AppDatabase.kt": "abstract fun foo(): FooDao",
            "shared/src/jvmTest/kotlin/SomeTest.kt": "val dao = db.foo()",
        })
        pre = fus._precompute_di_binding(code)
        findings = fus._check_di_binding(code, self._corpus(code), pre)
        self.assertTrue(any("foo" in f for _, f in findings), findings)

    def test_log_writer_positive(self):
        code = self._fake_code({"x.kt": "class MyWriter : LogWriter()", "Bootstrap.kt": "Logger.setLogWriters(ColorizedWriter())"})
        registered, _ = fus._precompute_log_writer(code)
        findings = fus._check_log_writer(code, self._corpus(code), (registered, {}))
        self.assertTrue(any("MyWriter" in f for _, f in findings), findings)

    def test_log_writer_negative(self):
        code = self._fake_code({"x.kt": "class MyWriter : LogWriter()", "Bootstrap.kt": "Logger.setLogWriters(MyWriter(dir))"})
        registered, _ = fus._precompute_log_writer(code)
        findings = fus._check_log_writer(code, self._corpus(code), (registered, {}))
        self.assertFalse(any("MyWriter" in f for _, f in findings), findings)

    def test_log_writer_alias(self):
        code = self._fake_code({"x.kt": "class MyWriter : LogWriter()", "Bootstrap.kt": "val w = MyWriter(dir)\nLogger.setLogWriters(w)"})
        registered, _ = fus._precompute_log_writer(code)
        findings = fus._check_log_writer(code, self._corpus(code), (registered, {}))
        self.assertFalse(any("MyWriter" in f for _, f in findings), findings)

    def test_log_writer_test_files_skipped(self):
        code = self._fake_code({"src/jvmTest/FakeWriter.kt": "class FakeWriter : LogWriter()"})
        registered, _ = fus._precompute_log_writer(code)
        findings = fus._check_log_writer(code, self._corpus(code), (registered, {}))
        self.assertFalse(findings, findings)

    def test_expect_no_actual_flagged(self):
        code = self._fake_code({"common.kt": "expect fun rememberX()", "jvm.kt": "actual class Foo"})
        pre = fus._precompute_expect_unwired(code)
        findings = fus._check_expect_unwired(code, self._corpus(code), pre)
        self.assertTrue(any("rememberX" in f for _, f in findings), findings)

    def test_expect_with_actual_not_flagged(self):
        code = self._fake_code({"common.kt": "expect fun rememberX()", "jvm.kt": "actual fun rememberX() {}"})
        pre = fus._precompute_expect_unwired(code)
        findings = fus._check_expect_unwired(code, self._corpus(code), pre)
        self.assertFalse(any("rememberX" in f for _, f in findings), findings)

    def test_orphan_binding_positive(self):
        # single<UnusedStubService> is bound but nothing injects it.
        # "Analytics" is in DECLARED_INTENT so cannot be used here.
        # The orphan-binding detector only scans CoreDiModule.kt, so use that filename.
        code = self._fake_code({"CoreDiModule.kt": "single<UnusedStubService> { Noop() }", "y.kt": "fun other() = Unit"})
        pre = fus._precompute_orphan_binding(code)
        findings = fus._check_orphan_binding(code, self._corpus(code), pre)
        self.assertTrue(any("UnusedStubService" in f for _, f in findings), findings)

    def test_orphan_binding_consumed(self):
        # Analytics is in DECLARED_INTENT so would be suppressed anyway, but the
        # binding must be in CoreDiModule.kt to be scanned.
        code = self._fake_code({"CoreDiModule.kt": "single<Analytics> { NoopAnalytics() }", "y.kt": "val analytics: Analytics = get()"})
        pre = fus._precompute_orphan_binding(code)
        findings = fus._check_orphan_binding(code, self._corpus(code), pre)
        self.assertFalse(any("Analytics" in f for _, f in findings), findings)

    # ── Full run ──────────────────────────────────────────────────────────

    def test_full_run_exit_0_clean(self):
        """With no real problems the script exits 0."""
        p = self.tmp / "Clean.kt"
        p.write_text("fun clean() = Unit")
        orig_files = fus.kotlin_files
        orig_argv = sys.argv
        fus.kotlin_files = lambda: [p]
        sys.argv = ["find-unwired-surfaces.py"]
        try:
            rc = fus.main()
        finally:
            fus.kotlin_files = orig_files
            sys.argv = orig_argv
        self.assertEqual(rc, 0)

    def test_full_run_exit_1_with_finding(self):
        """With an unwired screen the script exits 1."""
        p = self.tmp / "Unwired.kt"
        p.write_text("fun UnwiredScreen() = Unit")
        orig_files = fus.kotlin_files
        orig_argv = sys.argv
        fus.kotlin_files = lambda: [p]
        sys.argv = ["find-unwired-surfaces.py"]
        try:
            rc = fus.main()
        finally:
            fus.kotlin_files = orig_files
            sys.argv = orig_argv
        self.assertEqual(rc, 1)


if __name__ == "__main__":
    unittest.main()


class TestStartupUnwiredDetector(unittest.TestCase):
    """The startup-entry-point check, in both directions.

    This one was written, declared working, and did not fire: a function's own
    declaration put its name into the corpus, so subtracting the name also deleted
    every call site. A gate that cannot fail is worse than no gate, so each case here
    asserts that the detector *reports*, not merely that it is registered.
    """

    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp())

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _run(self, paths_to_content):
        code = {}
        for name, content in paths_to_content.items():
            p = self.tmp / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content)
            code[p] = fus.strip_comments(content)
        pre = fus._precompute_startup(code)
        return fus._check_startup_unwired(code, "\n".join(code.values()), pre)

    def test_a_called_startup_function_is_not_reported(self):
        findings = self._run({
            "shared/src/commonMain/A.kt": "fun installThing() = Unit\n",
            "androidApp/src/main/B.kt": "fun app() { installThing() }\n",
        })
        self.assertEqual([], findings)

    def test_an_uncalled_startup_function_is_reported(self):
        findings = self._run({
            "shared/src/commonMain/A.kt": "fun installThing() = Unit\n",
        })
        self.assertEqual(1, len(findings), findings)
        self.assertIn("installThing", findings[0][1])

    def test_the_declaration_alone_never_counts_as_a_call(self):
        # The exact defect: a name in the corpus from its own declaration.
        findings = self._run({
            "shared/src/commonMain/A.kt": "fun installThing() = Unit\n",
            "shared/src/commonMain/B.kt": "// installThing is documented here\n",
        })
        self.assertEqual(1, len(findings), findings)

    def test_an_import_alone_never_counts_as_a_call(self):
        # A half-reverted wiring: the import survives, the call does not.
        findings = self._run({
            "shared/src/commonMain/A.kt": "fun installThing() = Unit\n",
            "androidApp/src/main/B.kt": "import com.x.installThing\n",
        })
        self.assertEqual(1, len(findings), findings)

    def test_a_test_only_call_is_reported(self):
        # Wired by its own test and nothing else — the `debugInfo` shape.
        findings = self._run({
            "shared/src/commonMain/A.kt": "fun installThing() = Unit\n",
            "shared/src/commonTest/ATest.kt": "fun t() { installThing() }\n",
        })
        self.assertEqual(1, len(findings), findings)
        self.assertIn("only from tests", findings[0][1])

    def test_a_composable_is_not_a_startup_entry_point(self):
        findings = self._run({
            "shared/src/commonMain/A.kt": (
                "@Composable\nfun StartDateRow() = Unit\n"
            ),
        })
        self.assertEqual([], findings)

    def test_a_declared_startup_point_with_no_call_is_reported(self):
        findings = self._run({
            "shared/src/commonMain/A.kt": "fun flushLogs() = Unit\n",
        })
        self.assertEqual(1, len(findings), findings)

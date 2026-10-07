"""Unit tests for find-unwired-surfaces.py.

Run with: python3 -m unittest discover -s scripts/tests
"""

import pathlib
import shutil
import tempfile
import unittest
from unittest import mock

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


class TestDeadSymbolClassifiesBySourceSet(unittest.TestCase):
    """The classification the dead-symbol rule depends on, which used to be inlined.

    `_precompute_dead_symbol` and `_check_dead_symbol` each carried their own copy of
    `"/test/" in str(path) or "/jvmTest/" in str(path)` while `_is_test_source` — the
    helper the other detectors use, and which already knew about `commonTest` — sat
    further down the same file. Neither copy matched `commonTest`, because `/commonTest/`
    contains no `/test/`. All 171 `commonTest` files were therefore counted as
    **production**, every reference in them inflated the production count, and the rule
    never fired for a symbol only `commonTest` touches.

    That is how `setInheritedForProject` went unreported (#228): its only other reference
    is an `override` in `TagGroupsViewModelTest`.

    These controls name the behaviour, not the implementation — a second copy of the
    decision is what the defect was, so the thing to pin is the answer, from the helper
    every detector shares.
    """

    def test_common_test_is_a_test(self):
        """The regression itself: `commonTest` is the source set the copies missed."""
        self.assertTrue(
            fus._is_test_source(pathlib.Path("shared/src/commonTest/kotlin/Foo.kt")),
            "commonTest must be classified as a test source set",
        )

    def test_the_other_test_source_sets_are_tests(self):
        for src in ("jvmTest", "androidTest", "androidHostTest", "test"):
            self.assertTrue(
                fus._is_test_source(pathlib.Path(f"shared/src/{src}/kotlin/Foo.kt")),
                f"{src} must be classified as a test source set",
            )

    def test_production_source_sets_are_not_tests(self):
        # jvmMain/androidMain are named in the helper's set on purpose: their paths
        # must not be read as tests by any part of the rule.
        for src in ("commonMain", "jvmMain", "androidMain"):
            self.assertFalse(
                fus._is_test_source(pathlib.Path(f"shared/src/{src}/kotlin/Foo.kt")),
                f"{src} is a production source set",
            )

    def test_a_production_file_merely_named_like_a_test_is_production(self):
        # The name fallback is `endswith("Test.kt")`, so `Contest.kt` and `Protests.kt`
        # stay production. A substring rule would get this backwards.
        for name in ("Foo.kt", "Contest.kt", "Protests.kt", "Latest.kt"):
            self.assertFalse(
                fus._is_test_source(
                    pathlib.Path(f"shared/src/commonMain/kotlin/com/x/{name}")
                ),
                f"{name} is production and merely contains 'test'",
            )

    def test_the_fakes_directory_is_a_test_source(self):
        # `test/fakes/` holds the doubles the dead-symbol rule talks *about*, and they
        # are test code. Reading them as production would count each double's own
        # declaration as a production caller of everything it overrides.
        self.assertTrue(
            fus._is_test_source(
                pathlib.Path(
                    "shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/"
                    "FakeRepositories.kt"
                )
            ),
            "test doubles are test sources wherever they live",
        )


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

    # ── Detector 1: preview-only — the composable wired only into its own @Preview ──
    #
    # Positive control. Before this check existed, a component called only from its own
    # preview had a call site, satisfied the "does anything reference it" count, and was
    # reported as wired — while never rendering for a user. ReminderTile and
    # AttachmentTile were both in that state.

    def test_preview_only_positive(self):
        # The fixture names are deliberately not real symbols. A fixture that reused a
        # shipped name passed for the wrong reason the moment that symbol was baselined
        # — these two failed that way on 2026-10-07, when ReminderTile was exempted.
        code = self._fake_code(
            {
                "Tile.kt": (
                    "@Composable\nfun PreviewOnlyTile() = Unit\n"
                    "@Preview\n@Composable\nfun P() { PreviewOnlyTile() }\n"
                ),
            }
        )
        findings = fus._check_composable(code, self._corpus(code))
        self.assertTrue(
            any(k == "preview-only" and "PreviewOnlyTile" in f for k, f in findings),
            findings,
        )

    def test_preview_only_positive_for_the_PreviewSamples_convention(self):
        # The annotation-free shape this project actually uses: a preview function is
        # named for its role and calls PreviewThemed. Without matching the name, the
        # only signal there is, ReminderTile's two previews read as real call sites.
        code = self._fake_code(
            {
                "Tile.kt": (
                    "@Composable\nfun ThemedOnlyTile() = Unit\n"
                    "private fun ThemedOnlyTileLightPreview() = PreviewThemed { ThemedOnlyTile() }\n"
                    "private fun ThemedOnlyTileDarkPreview() = PreviewThemed { ThemedOnlyTile() }\n"
                ),
            }
        )
        findings = fus._check_composable(code, self._corpus(code))
        self.assertTrue(
            any(k == "preview-only" and "ThemedOnlyTile" in f for k, f in findings),
            findings,
        )

    def test_a_caller_in_the_same_file_is_a_caller(self):
        # TagCard, found by running this check against the real tree.
        #
        # TagCard is called by `TagList` in the same file, and `TagList` is called by the
        # screen. An earlier version compared against "references in some other file"
        # and reported it — a false positive that would have been resolved by deleting
        # a working component. Co-location is not preview-ness.
        code = self._fake_code(
            {
                "TagsScreen.kt": (
                    "@Composable\nfun TagList() { TagCard() }\n"
                    "@Composable\nfun TagCard() = Unit\n"
                ),
            }
        )
        findings = fus._check_composable(code, self._corpus(code))
        self.assertEqual([], [f for _, f in findings if "TagCard" in f], findings)

    def test_blanking_a_body_does_not_disturb_the_rest_of_the_file(self):
        # The preview stripper rewrites the text every other count in this detector is
        # computed from. Two properties matter: the preview body's references are gone,
        # and everything else is untouched down to the newlines.
        source = (
            "@Composable\nfun Screen() = Unit\n"
            "@Preview\nfun P() { Screen(); After() }\n"
            "\nfun After() = Unit\n"
        )
        stripped = fus._without_previews(source)
        self.assertNotIn("Screen();", stripped)
        self.assertIn("fun After() = Unit", stripped)
        self.assertEqual(
            source.count("\n"),
            stripped.count("\n"),
            "line count must not shift",
        )

    def test_a_composable_called_from_another_file_is_not_reported(self):
        # The negative control: the check must not fire on a normally wired component,
        # and it must not fire on one merely *co-located* with its caller in a different
        # file. This is the assertion that keeps it from becoming a duplicate of the
        # "no call site" detector.
        code = self._fake_code(
            {
                "ReminderTile.kt": "@Composable\nfun ReminderTile() = Unit\n",
                "ReminderScreen.kt": (
                    "@Composable\nfun ReminderScreen() { ReminderTile() }\n"
                ),
            }
        )
        findings = fus._check_composable(code, self._corpus(code))
        self.assertEqual([], [f for _, f in findings if "ReminderTile" in f], findings)

    def test_preview_only_is_exemptable_through_the_baseline(self):
        # The exemption must change the kind, exactly as it does for `screen` — or the
        # only way to make the gate green would be deleting working code.
        code = self._fake_code(
            {
                "TagCard.kt": (
                    "@Composable\nfun TagCard() = Unit\n"
                    "@Preview\n@Composable\nfun P() { TagCard() }\n"
                ),
            }
        )
        with mock.patch.object(fus, "_load_baseline", return_value={"TagCard": "reason"}):
            findings = fus._check_composable(code, self._corpus(code))
        self.assertEqual(["exempt"], [k for k, _ in findings], findings)

    def test_tile_and_dialog_suffixes_are_scanned(self):
        # The suffix list is the gate's entry condition. Tile and Dialog were not on it,
        # so a component named for either shape could not be reported at all.
        code = self._fake_code(
            {
                "a.kt": "@Composable\nfun ColorTile() = Unit\n",
                "b.kt": "@Composable\nfun ResetDialog() = Unit\n",
                "c.kt": "@Composable\nfun HeaderRow() = Unit\n",
            }
        )
        findings = fus._check_composable(code, self._corpus(code))
        reported = {f.split(": ", 1)[1].split("(")[0] for _, f in findings}
        for name in ("ColorTile", "ResetDialog", "HeaderRow"):
            self.assertIn(name, reported, findings)

    def test_screen_a_baseline_row_exempts_the_finding(self):
        # A baseline row has to change the *kind* of the finding, not just decorate it.
        # Before 2026-10-06 it was read into the message and the detector still returned
        # the screen, so an honest, perfectly-formed exemption left the gate red and the
        # only way to get it green was to delete code.
        code = self._fake_code({"x.kt": "fun FooScreen() = Unit", "y.kt": "fun other() = Unit"})
        with mock.patch.object(fus, "_load_baseline", return_value={"FooScreen": "reason"}):
            findings = fus._check_composable(code, self._corpus(code))
        self.assertEqual(["exempt"], [k for k, _ in findings], findings)

    def test_screen_an_unlisted_symbol_is_still_a_finding(self):
        # The exemption must be per-symbol, not a blanket switch.
        code = self._fake_code({"x.kt": "fun FooScreen() = Unit", "y.kt": "fun other() = Unit"})
        with mock.patch.object(fus, "_load_baseline", return_value={"BarScreen": "reason"}):
            findings = fus._check_composable(code, self._corpus(code))
        self.assertEqual(["screen"], [k for k, _ in findings], findings)

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

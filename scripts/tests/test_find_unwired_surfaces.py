"""Unit tests for find-unwired-surfaces.py.

Run with: python3 -m unittest discover -s scripts/tests
"""

import importlib.util
import pathlib
import shutil
import sys
import tempfile
import unittest
from unittest import mock

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
    """The script reads Kotlin through `scanners.source_text`, not its own regexes.

    The canonical reader *blanks* a comment instead of deleting it: the text
    keeps its length and its line numbers, so a detector that reports a line
    still points at the line the reader saw.
    """

    def test_blanks_block_comment(self):
        self.assertEqual(fus.strip_comments("a/* b */c"), "a       c")

    def test_blanks_line_comment(self):
        self.assertEqual(fus.strip_comments("a // b\nc"), "a     \nc")

    def test_kdoc_mention_not_counted(self):
        text = "/** See [FooScreen] for details. */\nfun FooScreen() = Unit"
        stripped = fus.strip_comments(text)
        # The KDoc mention is blanked; the declaration is not.
        self.assertEqual(stripped.count("FooScreen"), 1)
        self.assertNotIn("[FooScreen]", stripped)

    def test_nested_block_comment_is_one_comment(self):
        """A `/* /* */ */` comment ends at the second `*/`, not the first.

        The regex this replaced stopped at the first `*/`, so the tail of a
        nested comment was read as code.
        """
        text = "a /* outer /* inner */ still comment */ b"
        stripped = fus.strip_comments(text)
        self.assertNotIn("outer", stripped)
        self.assertNotIn("still comment", stripped)
        self.assertTrue(stripped.startswith("a "))
        self.assertTrue(stripped.endswith(" b"))

    def test_line_comment_inside_string_literal_is_not_a_comment(self):
        text = 'val url = "https://example.com" // trailing'
        self.assertIn("https://example.com", fus.strip_comments(text))


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

    # ── Detector 6: the type behind a bare `get()` ──────────────────────────
    #
    # `gateway = get()` names no type: Koin takes it from the parameter it
    # fills. A scan that only knew `get<Name>()` called every such binding an
    # orphan, and the only available fix was an allowlist of claims. These
    # three tests pin the resolution in both directions, so the allowlist can
    # shrink instead of grow.

    def test_constructor_injection_counts_as_consumption(self):
        code = self._fake_code(
            {
                "CoreDiModule.kt": (
                    "single<AuthRepository> {\n"
                    "    SupabaseAuthRepository(\n"
                    "        log = Logger.withTag(\"AuthRepository\"),\n"
                    "        gateway = get(),\n"
                    "    )\n"
                    "}\n"
                ),
                "AuthRepository.kt": (
                    "class SupabaseAuthRepository(\n"
                    "    private val log: Logger,\n"
                    "    private val gateway: AuthGateway,\n"
                    ")\n"
                ),
            }
        )
        pre = fus._precompute_orphan_binding(code)
        findings = fus._check_orphan_binding(code, self._corpus(code), pre)
        self.assertFalse(any("AuthGateway" in f for _, f in findings), findings)

    def test_positional_constructor_injection_counts_as_consumption(self):
        # The argument is positional, so the type comes from the parameter's
        # position rather than its name.
        code = self._fake_code(
            {
                "CoreDiModule.kt": "single<Session> { GenuiSession(get(), get()) }",
                "Session.kt": (
                    "class GenuiSession(\n"
                    "    private val transport: GenuiTransport,\n"
                    "    private val parser: A2uiParser,\n"
                    ")\n"
                ),
            }
        )
        pre = fus._precompute_orphan_binding(code)
        findings = fus._check_orphan_binding(code, self._corpus(code), pre)
        self.assertFalse(any("GenuiTransport" in f for _, f in findings), findings)

    def test_a_binding_nothing_injects_is_still_reported(self):
        # The resolution must not become a blanket excuse: a binding that no
        # `get()` fills is still an orphan.
        code = self._fake_code(
            {
                "CoreDiModule.kt": (
                    "single<NeverInjected> { NeverInjected(get()) }\n"
                ),
                "NeverInjected.kt": "class NeverInjected(private val log: Logger)\n",
            }
        )
        pre = fus._precompute_orphan_binding(code)
        findings = fus._check_orphan_binding(code, self._corpus(code), pre)
        self.assertTrue(any("NeverInjected" in f for _, f in findings), findings)

    def test_a_test_only_injection_does_not_count_as_consumption(self):
        # A binding injected only by a test is unwired in production, which is
        # the whole point of the shape.
        code = self._fake_code(
            {
                "CoreDiModule.kt": "single<TestOnlyPort> { TestOnlyPort(get()) }",
                "TestOnlyPort.kt": "class TestOnlyPort(private val log: Logger)\n",
                "src/jvmTest/Test.kt": "val port: TestOnlyPort = get()",
            }
        )
        pre = fus._precompute_orphan_binding(code)
        findings = fus._check_orphan_binding(code, self._corpus(code), pre)
        self.assertTrue(any("TestOnlyPort" in f for _, f in findings), findings)

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


class TestSourceRootsCoverProduction(unittest.TestCase):
    """Every production module is scanned — the gap #543 recorded.

    `SOURCE_ROOTS` was three entries, and the modules missing from it were not a
    milder version of the same gap: no symbol in `:adr-core`, `:core-base` or
    `:mcp-server` could be *found*, so none could be reported unwired. The
    detector looked complete and covered a third of the tree.

    Extending a list is one line and easy to regress, so every root is asserted
    here — including that the directory it names exists in this repository, which
    is the half that turns "the path is spelled right" into "the code is read".
    """

    def test_every_declared_root_exists_in_this_repository(self):
        for root in fus.SOURCE_ROOTS:
            with self.subTest(root=root):
                self.assertTrue(
                    (fus.ROOT / root).is_dir(),
                    f"{root} is listed in SOURCE_ROOTS but does not exist — the gate would "
                    f"scan nothing there and still report success",
                )

    def test_no_kotlin_module_is_missing_from_the_list(self):
        # The other half of the same gap. `test_each_root_is_actually_read` can only
        # assert that a listed root is read; it cannot notice a module that was never
        # listed, because the list is the specification it reads. So the specification
        # is checked against `settings.gradle.kts` instead — which is the file a new
        # module is added to.
        settings = (fus.ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
        listed = {line.split('"')[1] for line in settings.splitlines() if line.startswith("include(") and '"' in line}
        # A Gradle module `:tools:unwritten-properties` and a root
        # `tools/unwritten-properties/src` name the same tree; the suffix is not a
        # difference in coverage, so it is stripped before comparing.
        covered = {
            r[: -len("/src")] if r.endswith("/src") else r
            for r in fus.SOURCE_ROOTS
        }

        # `:detekt-rules` is deliberately not covered: it is a Gradle plugin whose
        # rules are instantiated through `RuleSetProvider`, which a call-site scan
        # cannot see, so listing it would produce findings that are all noise.
        excluded = {"detekt-rules", "build-logic"}
        missing = sorted(
            name for name in listed
            if name.lstrip(":").replace(":", "/") not in covered
            and name.lstrip(":").split("/")[0] not in excluded
        )
        self.assertEqual([], missing, f"Gradle modules not covered by SOURCE_ROOTS: {missing}")

    def test_each_root_is_actually_read(self):
        # The proof #543 asked for, per root: plant an unwired symbol under that root
        # and require the detector to report it. A root in the list that is never
        # globbed produces no finding, which is indistinguishable from the symbol
        # being wired.
        for root in fus.SOURCE_ROOTS:
            with self.subTest(root=root):
                src = fus.ROOT / root
                probe_dir = next(
                    (d for d in src.rglob("main") if d.is_dir()), None
                ) or next((d for d in src.rglob("kotlin") if d.is_dir()), None)
                self.assertIsNotNone(probe_dir, f"{root} has no main/kotlin source dir")
                probe = probe_dir / "ZzProbeCoverage.kt"
                probe.write_text(
                    "package singularity.todo.probe\n\nfun ZzProbeCoverageCard() = Unit\n",
                    encoding="utf-8",
                )
                try:
                    files = fus.kotlin_files()
                    self.assertTrue(
                        any(p == probe for p in files),
                        f"{root} is in SOURCE_ROOTS but kotlin_files() did not return a file "
                        f"planted under it — the root is listed and ignored",
                    )
                finally:
                    probe.unlink()



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


class TestUnreadPublicDetector(unittest.TestCase):
    """A public declaration production code never reads (#1916).

    The defect: `ImportOptions.overwriteExisting` was declared, handed a value by
    the builder, and asserted by a test, and production code never read it — so
    setting it to `false` still overwrote. `dead-symbol` could not see it: the
    declaration counts as a production reference, and being *written* never made
    it look unused on its own.

    Each case below is one of the shapes the detector must report, or one of the
    four it must stay silent on (a read, a serialized field, prose, a baselined
    symbol). The last case pins the conservative rule that keeps it honest.
    """

    OPTIONS = (
        "data class ImportOptions(\n"
        "    val overwriteExisting: Boolean = true,\n"
        ")\n"
    )

    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp())

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _code(self, files):
        code = {}
        for name, content in files.items():
            p = self.tmp / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content)
            code[p] = fus.strip_comments(content)
        return code

    def _run(self, files):
        code = self._code(files)
        pre = fus._precompute_unread_public(code)
        return fus._check_unread_public(code, "\n".join(code.values()), pre)

    # ── The shapes it reports ──────────────────────────────────────────────

    def test_a_builder_written_option_nobody_reads_is_write_only(self):
        findings = self._run({
            "shared/src/commonMain/Options.kt": self.OPTIONS,
            "shared/src/commonMain/Builder.kt": (
                "fun importOptions(): ImportOptions = ImportOptions(overwriteExisting = false)\n"
            ),
            "shared/src/commonTest/ImportTest.kt": (
                "fun t(o: ImportOptions) { assertTrue(o.overwriteExisting) }\n"
            ),
        })
        self.assertEqual(["write-only"], [k for k, _ in findings], findings)
        self.assertIn("ImportOptions.overwriteExisting", findings[0][1])

    def test_a_value_class_nobody_constructs_is_reported(self):
        findings = self._run({
            "shared/src/commonMain/Id.kt": "value class UnusedId(val value: String)\n",
        })
        self.assertEqual(["unread-public"], [k for k, _ in findings], findings)
        self.assertIn("UnusedId", findings[0][1])

    def test_a_constant_nobody_consults_is_reported(self):
        # The brief's own example: a constant on an internal object. The object's
        # visibility does not rescue a constant nothing reads.
        findings = self._run({
            "shared/src/commonMain/Keys.kt": (
                "internal object Keys {\n"
                "    const val UNUSED_SLOT = \"slot\"\n"
                "}\n"
            ),
        })
        self.assertEqual(["unread-public"], [k for k, _ in findings], findings)
        self.assertIn("UNUSED_SLOT", findings[0][1])

    def test_a_computed_property_nobody_reads_is_reported(self):
        findings = self._run({
            "shared/src/commonMain/Flags.kt": (
                "class FeatureFlags(private val enabled: Boolean) {\n"
                "    val isRetired: Boolean\n"
                "        get() = !enabled\n"
                "}\n"
            ),
        })
        self.assertEqual(["unread-public"], [k for k, _ in findings], findings)
        self.assertIn("isRetired", findings[0][1])

    # ── The shapes it must not report ──────────────────────────────────────

    def test_a_property_production_reads_is_not_reported(self):
        findings = self._run({
            "shared/src/commonMain/Options.kt": self.OPTIONS,
            "shared/src/commonMain/Importer.kt": (
                "fun run(o: ImportOptions) {\n"
                "    if (!o.overwriteExisting) return\n"
                "}\n"
            ),
        })
        self.assertEqual([], findings)

    def test_a_serializable_field_is_not_reported(self):
        # kotlinx.serialization reads it through generated code that names neither
        # the property nor a receiver, so a static scan cannot see the read.
        findings = self._run({
            "shared/src/commonMain/Wire.kt": (
                "@Serializable\n"
                "data class WireDto(\n"
                "    val overwriteExisting: Boolean = true,\n"
                ")\n"
            ),
            "shared/src/commonMain/Builder.kt": (
                "fun wire(): WireDto = WireDto(overwriteExisting = false)\n"
            ),
        })
        self.assertEqual([], findings)

    def test_a_kdoc_link_and_a_log_tag_are_not_reads(self):
        # Prose about a property is not a use of it. `strip_code` blanks the
        # comment and the literal, so what is left is the declaration alone.
        findings = self._run({
            "shared/src/commonMain/Options.kt": self.OPTIONS,
            "shared/src/commonMain/Docs.kt": (
                "/**\n"
                " * See [ImportOptions.overwriteExisting] for what it promises.\n"
                " */\n"
                "fun docs() {\n"
                "    log.withTag(\"overwriteExisting\") { \"doc\" }\n"
                "}\n"
            ),
        })
        self.assertEqual(["unread-public"], [k for k, _ in findings], findings)

    def test_a_baselined_symbol_is_exempt(self):
        code = self._code({
            "shared/src/commonMain/Id.kt": "value class SavedId(val value: String)\n",
        })
        decl_path = next(iter(code))
        # The exemption is keyed by (symbol, declaring file) and a plain dict would hide
        # exactly the type change the detector depends on — so the row is built the way
        # the loader builds it, through `Baseline`.
        row = {("SavedId", fus.rel(decl_path)): "kept for a named reason"}
        with mock.patch.object(fus, "_load_baseline", return_value=fus.Baseline(row)):
            findings = fus._check_unread_public(
                code, "\n".join(code.values()), fus._precompute_unread_public(code)
            )
        self.assertEqual(["exempt"], [k for k, _ in findings], findings)
        self.assertIn("kept for a named reason", findings[0][1])

    def test_an_exemption_for_another_file_does_not_exempt_this_one(self):
        code = self._code({
            "shared/src/commonMain/Id.kt": "value class SavedId(val value: String)\n",
        })
        row = {("SavedId", "shared/src/commonMain/Elsewhere.kt"): "someone else's row"}
        with mock.patch.object(fus, "_load_baseline", return_value=fus.Baseline(row)):
            findings = fus._check_unread_public(
                code, "\n".join(code.values()), fus._precompute_unread_public(code)
            )
        self.assertEqual(["unread-public"], [k for k, _ in findings], findings)

    def test_a_function_local_of_the_same_name_is_not_a_read(self):
        # `val overwriteExisting = false` declares a new symbol. Counting it as a read
        # was how a local shadow kept a lying option green.
        findings = self._run({
            "shared/src/commonMain/Options.kt": self.OPTIONS,
            "shared/src/commonMain/Builder.kt": (
                "fun build(): ImportOptions {\n"
                "    val overwriteExisting = false\n"
                "    return ImportOptions(overwriteExisting = overwriteExisting)\n"
                "}\n"
            ),
        })
        self.assertEqual(["write-only"], [k for k, _ in findings], findings)


class TestUnreadPublicReportAndFloor(unittest.TestCase):
    """The reporting the write-only shape leans on (#1916).

    Three things the shape is judged by: a read the corpus cannot show is still a
    read, a run's counts must not call a note an exemption, and the floor header is
    shipped into the checked-in floor file, so a paragraph pasted into it twice is
    pasted into the repository twice.
    """

    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def _run(self, files):
        code = {}
        for name, content in files.items():
            p = self.tmp / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content)
            code[p] = fus.strip_comments(content)
        pre = fus._precompute_unread_public(code)
        return fus._check_unread_public(code, "\n".join(code.values()), pre)

    def test_a_string_template_read_is_a_read(self):
        # `log("$overwriteExisting")` evaluates the property, so the option is
        # consulted. The corpus is built with `strip_code`, which blanks the contents
        # of a literal, so the template is invisible to it — without counting the
        # template the property was reported as unread.
        findings = self._run({
            "shared/src/commonMain/Options.kt": (
                "data class ImportOptions(val overwriteExisting: Boolean = true)\n"
            ),
            "shared/src/commonMain/Logger.kt": (
                "fun log(message: String) {}\n"
                "fun use(o: ImportOptions) {\n"
                '    log("$overwriteExisting")\n'
                "}\n"
            ),
        })
        self.assertEqual([], findings)

    def test_a_plain_literal_mention_is_not_a_read(self):
        # The other half of the template rule: a log *tag* is a literal, not a
        # template, and it does not consult the property.
        findings = self._run({
            "shared/src/commonMain/Options.kt": (
                "data class ImportOptions(val overwriteExisting: Boolean = true)\n"
            ),
            "shared/src/commonMain/Logger.kt": (
                "fun log(tag: String, message: String) {}\n"
                "fun use(o: ImportOptions) {\n"
                '    log("overwriteExisting", "done")\n'
                "}\n"
            ),
        })
        self.assertEqual(["unread-public"], [k for k, _ in findings], findings)

    def test_the_summary_counts_exempt_and_note_apart(self):
        # A note is information, not an exemption: calling it one claims a decision
        # was taken that nobody took.
        summary = fus._summary([
            ("note", "3 public name(s) skipped as ambiguous"),
            ("exempt", "SavedId is accepted debt"),
            ("unread-public", "UnusedId is declared and never read"),
        ])
        self.assertEqual("1 blocking finding(s); 1 exempted; 1 note(s).", summary)

    def test_the_summary_of_a_note_only_run_says_no_exemption(self):
        summary = fus._summary([("note", "3 public name(s) skipped as ambiguous")])
        self.assertEqual("0 blocking finding(s); 1 note(s).", summary)
        self.assertNotIn("exempted", summary)

    def test_the_floor_header_has_no_duplicated_paragraph(self):
        # The header is written into config/docs/unwired-surfaces-floor.txt, so a
        # paragraph pasted into it twice is shipped to the repository twice.
        lines = [line for line in fus.FLOOR_HEADER.splitlines() if line.strip()]
        self.assertEqual(len(lines), len(set(lines)))

    def test_a_property_declared_inside_a_default_lambda_is_not_an_option(self):
        # The `val` belongs to the default value's lambda, not to the constructor, so
        # `Holder.tmp` never existed as an option.
        findings = self._run({
            "shared/src/commonMain/Holder.kt": (
                "data class Holder(\n"
                "    val runOnce: () -> Int = run { val tmp: Int = 1; tmp },\n"
                ")\n"
            ),
        })
        self.assertNotIn("Holder.tmp", " ".join(m for _, m in findings), findings)

    def test_ambiguous_names_are_reported_instead_of_dropped_silently(self):
        findings = self._run({
            "shared/src/commonMain/A.kt": (
                "data class Alpha(val overwriteExisting: Boolean = true)\n"
            ),
            "shared/src/commonMain/B.kt": (
                "data class Beta(val overwriteExisting: Boolean = true)\n"
            ),
        })
        notes = [m for k, m in findings if k == "note"]
        self.assertEqual(1, len(notes), findings)
        self.assertIn("2 public declaration(s) skipped as ambiguous", notes[0])
        self.assertIn("overwriteExisting", notes[0])

    def test_a_name_declared_twice_is_not_attributed(self):
        # Two declarations, one name: a receiver-less occurrence cannot be
        # attributed to either, so neither is reported. The detector stays silent
        # rather than guess — `id` and `name` are declared on dozens of classes.
        findings = self._run({
            "shared/src/commonMain/A.kt": (
                "data class Alpha(val overwriteExisting: Boolean = true)\n"
            ),
            "shared/src/commonMain/B.kt": (
                "data class Beta(val overwriteExisting: Boolean = true)\n"
            ),
        })
        self.assertEqual([], findings)

    def test_the_detector_is_registered(self):
        # A shape that is written but not in the table reports nothing, forever.
        self.assertIn("unread-public", {d.kind for d in fus.DETECTORS})


class TestFloor(unittest.TestCase):
    """The ratchet: an advisory gate needs a recorded number, or it can only grow.

    The floor is the whole reason this gate is actionable. `unwired surfaces` is
    advisory because it is knowingly red; without a recorded count, "4 findings"
    in a future session carries no information — nobody can tell a new unwired
    surface from the four that were always there.

    Each branch below is a failure mode a ratchet can have, and each is asserted
    with the input that produces it rather than described.
    """

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp)
        self.floor = pathlib.Path(self.tmp) / "floor.txt"
        patch = mock.patch.object(fus, "FLOOR_FILE", self.floor)
        patch.start()
        self.addCleanup(patch.stop)

    def _run(self, argv):
        with mock.patch.object(sys, "argv", ["find-unwired-surfaces.py", *argv]):
            return fus.main()

    def _write(self, text):
        self.floor.write_text(text, encoding="utf-8")

    def test_update_floor_writes_the_blocking_count_and_not_the_exempt_rows(self):
        # The repository currently reports 4 blocking rows and 2 exempted ones. Writing
        # 6 would make the floor a function of how loudly exemptions are phrased.
        with mock.patch.object(fus, "kotlin_files", lambda: []), \
             mock.patch.object(fus, "DETECTORS", []):
            self.assertEqual(0, self._run(["--update-floor"]))
        self.assertEqual("0", [l for l in self.floor.read_text().splitlines()
                               if l.strip() and not l.startswith("#")][0])

    def test_a_missing_floor_is_an_error_rather_than_zero(self):
        # Zero would be the friendliest default and the wrong one: it silently makes
        # every existing finding a regression and turns a bookkeeping omission into
        # a wall of red that gets "fixed" by raising the floor to whatever is there.
        with mock.patch.object(fus, "kotlin_files", lambda: []), \
             mock.patch.object(fus, "DETECTORS", []):
            with mock.patch.object(sys, "argv", ["x"]):
                with self.assertRaises(SystemExit) as ctx:
                    fus.main()
        self.assertEqual(2, ctx.exception.code)

    def test_a_floor_that_cannot_be_read_fails_loudly(self):
        for text in ("", "x", "1 2", "# only a comment\n"):
            with self.subTest(text=text):
                self._write(text)
                with mock.patch.object(fus, "kotlin_files", lambda: []), \
                     mock.patch.object(fus, "DETECTORS", []):
                    with mock.patch.object(sys, "argv", ["x"]):
                        with self.assertRaises(SystemExit) as ctx:
                            fus.main()
                self.assertEqual(2, ctx.exception.code)

    def test_rising_above_the_floor_fails(self):
        self._write("# floor\n0\n")
        with mock.patch.object(fus, "kotlin_files", lambda: []), \
             mock.patch.object(fus, "DETECTORS", []):
            with mock.patch.object(sys, "argv", ["x"]):
                with mock.patch.object(fus, "DETECTORS", [_one_finding("boom")]):
                    self.assertEqual(1, fus.main())

    def test_a_finding_of_kind_exempt_does_not_count_against_the_floor(self):
        self._write("0\n")
        with mock.patch.object(fus, "kotlin_files", lambda: []):
            with mock.patch.object(sys, "argv", ["x"]):
                with mock.patch.object(fus, "DETECTORS", [_one_finding("exempted", "exempt")]):
                    self.assertEqual(0, fus.main())

    def test_falling_below_the_floor_passes_and_says_so(self):
        # Silently passing is how a stale floor becomes permission to rise back to the
        # old number. The count dropping is news; it is printed.
        self._write("7\n")
        with mock.patch.object(fus, "kotlin_files", lambda: []), \
             mock.patch.object(fus, "DETECTORS", []):
            with mock.patch.object(sys, "argv", ["x"]):
                self.assertEqual(0, fus.main())


class _FakeDetector:
    """A detector that always returns the same rows, to drive the floor arithmetic."""

    def __init__(self, message, kind="screen"):
        self._rows = [(kind, message)]

    def precompute(self, code):
        return None

    def check(self, code, corpus, pre):
        return list(self._rows)


def _one_finding(message, kind="screen"):
    return _FakeDetector(message, kind)


class TestFloorArithmeticAtTheCli(unittest.TestCase):
    """The floor comparison, end to end — the arithmetic #1916 actually got wrong.

    Every other test in this file drives a detector's `precompute`/`check` over a
    synthetic tree. None of them runs `cli()`, which is where the blocking count is
    compared with the recorded floor. A branch can therefore pass every detector test
    and still leave the gate red: #1916 recorded a floor of 7 while its own shape pushed
    the blocking count to 149, and no test here could have noticed. These tests run the
    command the gate runs, with the detectors stubbed out, so the subject is the floor
    arithmetic and nothing else.
    """

    def setUp(self):
        import pathlib
        import tempfile

        self._orig_detectors = fus.DETECTORS
        self._orig_floor_file = fus.FLOOR_FILE
        self._orig_kotlin_files = fus.kotlin_files

        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.floor_file = pathlib.Path(self._tmp.name) / "unwired-surfaces-floor.txt"
        fus.FLOOR_FILE = self.floor_file
        # The floor arithmetic is the subject, not the detectors: an empty tree keeps
        # the real source out of the result and makes the finding set exact.
        fus.kotlin_files = lambda: []
        self.addCleanup(self._restore)

    def _restore(self):
        fus.DETECTORS = self._orig_detectors
        fus.FLOOR_FILE = self._orig_floor_file
        fus.kotlin_files = self._orig_kotlin_files

    def _run(self, findings, floor, *args):
        fus.DETECTORS = [
            fus.Detector(
                kind="screen",
                check=lambda _code, _corpus, _pre, _f=tuple(findings): list(_f),
            )
        ]
        self.floor_file.write_text(f"# floor\n{floor}\n", encoding="utf-8")
        return fus.main(["--quiet", *args])

    def test_one_blocking_finding_above_a_floor_of_zero_fails(self):
        rc = self._run([("screen", "a screen nothing composes")], floor=0)
        self.assertEqual(rc, 1, "a blocking finding above the floor must fail the run")

    def test_a_blocking_count_equal_to_the_floor_passes(self):
        rc = self._run([("screen", "a screen nothing composes")], floor=1)
        self.assertEqual(rc, 0, "the floor is a maximum, not a target to beat")

    def test_informational_rows_are_printed_but_not_counted(self):
        rc = self._run(
            [("exempt", "accepted debt"), ("note", "could not decide")], floor=0
        )
        self.assertEqual(
            rc, 0,
            "`exempt` and `note` rows do not fail the build, so they must not be "
            "counted against the floor",
        )

    def test_update_floor_records_the_blocking_count_and_not_the_total(self):
        rc = self._run(
            [("screen", "blocking"), ("exempt", "accepted")], floor=0, "--update-floor"
        )
        self.assertEqual(rc, 0)
        self.assertEqual(
            fus.read_floor(), 1,
            "--update-floor must record the count that can fail the build, so the next "
            "run measures the same thing this one did",
        )

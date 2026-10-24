"""Tests for `scripts/changed-modules.py` — what a change makes worth running.

The mapper answers a question with consequences: it decides which checks a change
needs. Two ways to be wrong, and both are quiet:

* too few — a change that breaks a downstream module passes the lane and fails in
  CI, and the lane taught the developer that it is enough;
* too many — the lane costs what the full loop costs, and nobody uses it.

So the edges are pinned: `:core-base` reaches `:adr-core`, then `:shared`, then the
four modules that compile against it; `:adr-cli` depends on `:adr-core` and on
nothing else, because it was deliberately cut off from `:shared`.

The module LIST is read out of `settings.gradle.kts`, and that is the part that
was wrong: the list used to be a table in the script, the table knew eight
modules, and the build declares ten. The two it did not know were the two
nobody judged — `:tools:unwritten-properties` (nested, two directory segments
deep) and `:pro` (included only behind `-PwithPro`). A module the graph does
not know is a module nothing runs, and nothing says so.
"""

import importlib.util
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent


def _load(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


changed = _load('changed_modules', 'changed-modules.py')


class PlanTest(unittest.TestCase):
    """What the plan says for a change."""

    def test_a_change_in_shared_reaches_the_modules_that_compile_against_it(self):
        tasks, _reasons = changed.plan(['shared/src/commonMain/kotlin/Foo.kt'])

        self.assertIn(':shared:jvmTest', tasks)
        self.assertIn(':androidApp:assembleDebug', tasks)
        self.assertIn(':desktopApp:test', tasks)
        self.assertIn(':mcp-server:test', tasks)

    def test_a_change_in_shared_does_not_reach_adr_cli(self):
        """:adr-cli depends on :adr-core, not on :shared — it was cut off on purpose."""
        tasks, _reasons = changed.plan(['shared/src/commonMain/kotlin/Foo.kt'])

        self.assertNotIn(':adr-cli:test', tasks)
        self.assertNotIn(':adr-cli:detekt', tasks)

    def test_a_change_in_core_base_reaches_everything_downstream(self):
        tasks, _reasons = changed.plan(['core-base/src/commonMain/kotlin/Foo.kt'])

        for task in (
            ':core-base:detekt',
            ':adr-core:jvmTest',
            ':adr-cli:test',
            ':shared:jvmTest',
            ':mcp-server:test',
        ):
            self.assertIn(task, tasks)

    def test_a_change_in_the_rules_repeats_the_detekt_of_their_consumers(self):
        """:detekt-rules changes what the other modules report, not what they do."""
        tasks, _reasons = changed.plan(['detekt-rules/src/main/kotlin/Foo.kt'])

        self.assertIn(':detekt-rules:test', tasks)
        self.assertIn(':shared:detekt', tasks)
        self.assertIn(':adr-cli:detekt', tasks)
        self.assertNotIn(
            ':shared:jvmTest',
            tasks,
            "the shared tests do not read the detekt rules, so they are not the answer",
        )

    def test_a_change_in_the_gates_runs_the_gate_suite(self):
        tasks, _reasons = changed.plan(['scripts/check-gate-wiring.py'])

        self.assertIn('scripts/ci/static-gates.sh', tasks)

    def test_a_change_under_the_gate_tests_runs_them(self):
        tasks, _reasons = changed.plan(['scripts/tests/test_check_gate_wiring.py'])

        self.assertIn('scripts/tests', tasks)

    def test_a_change_in_the_build_runs_everything(self):
        tasks, _reasons = changed.plan(['gradle.properties'])

        self.assertIn(':shared:jvmTest', tasks)
        self.assertIn(':mcp-server:test', tasks)
        self.assertIn(':adr-core:jvmTest', tasks)
        self.assertIn('scripts/ci/static-gates.sh', tasks)

    def test_a_change_to_the_convention_plugins_runs_everything(self):
        """`build-logic/` holds the convention plugins every module uses.

        A change to one of them changes how every module is built, so the plan
        has to be the whole tree. The area was missing, and the plan for such a
        change was "nothing runs" — while the pre-push verdict, which knows
        the same fact, judged every module.
        """
        tasks, _reasons = changed.plan(['build-logic/src/main/kotlin/Convention.kt'])

        self.assertIn(':shared:jvmTest', tasks)
        self.assertIn(':desktopApp:test', tasks)
        self.assertIn(':detekt-rules:test', tasks)
        self.assertIn('scripts/ci/static-gates.sh', tasks)

    def test_a_path_no_module_owns_runs_nothing(self):
        tasks, reasons = changed.plan(['README.md'])

        self.assertEqual([], tasks)
        self.assertTrue(reasons, 'the reason it ran nothing has to be given')

    def test_the_same_task_is_never_listed_twice(self):
        tasks, _reasons = changed.plan([
            'shared/src/commonMain/kotlin/Foo.kt',
            'core-base/src/commonMain/kotlin/Bar.kt',
            'gradle.properties',
        ])

        self.assertEqual(len(tasks), len(set(tasks)))


class SettingsParsingTest(unittest.TestCase):
    """The module list is read out of the build file, not written here.

    The reader is a scanner, not a Kotlin parser, and a scanner has two ways to
    be wrong quietly: it can read a string as code (the settings file holds a
    Maven URL, and a `//` inside that string is not a comment), and it can read
    a function whose name starts with `include` as an include call.
    """

    def test_the_graph_knows_every_module_the_build_declares(self):
        modules = changed.load_modules(changed.SETTINGS)

        for name in (
            'shared',
            'androidApp',
            'desktopApp',
            'mcp-server',
            'core-base',
            'adr-core',
            'adr-cli',
            'detekt-rules',
            'tools:unwritten-properties',
        ):
            self.assertIn(name, modules, f'the build declares :{name}')

    def test_a_nested_module_owns_the_directory_gradle_maps_it_to(self):
        """`:tools:unwritten-properties` lives in `tools/unwritten-properties`.

        Two segments, which is the whole reason the CI annotation glob
        `*/build/test-results/**/*.xml` skipped its JUnit XML.
        """
        module = changed.load_modules(changed.SETTINGS)['tools:unwritten-properties']

        self.assertEqual('tools/unwritten-properties', module.directory)
        self.assertEqual(':tools:unwritten-properties', module.gradle_path)

    def test_a_conditional_include_is_known_and_marked_as_conditional(self):
        """:pro is included only behind `-PwithPro` — known, and marked as such."""
        modules = changed.load_modules(changed.SETTINGS)

        self.assertIn('pro', modules)
        self.assertTrue(modules['pro'].conditional)

    def test_an_unconditional_include_is_not_marked_as_conditional(self):
        modules = changed.load_modules(changed.SETTINGS)

        self.assertFalse(modules['shared'].conditional)

    def test_an_include_inside_a_condition_is_conditional(self):
        modules = changed.parse_modules(
            'if (withPro) {\n'
            '    include(":pro")\n'
            '}\n'
            'include(":free")\n',
        )

        self.assertTrue(modules[0].conditional)
        self.assertFalse(modules[1].conditional)

    def test_a_url_in_a_string_is_not_a_comment(self):
        """The `//` in a Maven URL must not cut the line in half."""
        modules = changed.parse_modules(
            'maven { url = uri("https://example.com/maven") }\n'
            'include(":after")\n',
        )

        self.assertEqual(['after'], [module.name for module in modules])
        self.assertFalse(any(module.conditional for module in modules))

    def test_a_brace_in_a_string_is_not_a_block(self):
        modules = changed.parse_modules(
            'val label = "a } b"\n'
            'include(":after")\n',
        )

        self.assertEqual(['after'], [module.name for module in modules])

    def test_a_function_whose_name_starts_with_include_is_not_a_module(self):
        """`includeGroupAndSubgroups("androidx")` is not `include(":androidx")`."""
        modules = changed.parse_modules(
            'mavenContent { includeGroupAndSubgroups("androidx") }\n'
            'include(":real")\n',
        )

        self.assertEqual(['real'], [module.name for module in modules])

    def test_an_include_with_a_name_that_is_not_a_literal_is_not_recorded(self):
        """A module the graph cannot name is a module it cannot judge."""
        modules = changed.parse_modules('include(someVariable)\n')

        self.assertEqual([], modules)


class GraphConsistencyTest(unittest.TestCase):
    """The graph and the build have to agree.

    A module the build declares and the graph does not judge is a module nobody
    runs — the defect that left `:tools:unwritten-properties` without a verdict.
    A module the graph judges and the build does not declare is a module that
    cannot be built at all. Both are said out loud instead of absorbed.
    """

    def test_the_graph_and_the_build_agree(self):
        self.assertEqual([], changed.consistency_errors())

    def test_a_module_the_build_declares_and_the_graph_ignores_is_reported(self):
        errors = changed.consistency_errors(
            changed.load_modules(changed.SETTINGS),
            changed.ModuleChecks({'shared': [':shared:detekt']}),
        )

        self.assertTrue(
            any('tools:unwritten-properties' in error for error in errors),
            errors,
        )

    def test_a_conditional_module_is_not_a_module_nobody_judges(self):
        """:pro is judged by the configuration that has it — that is the decision."""
        errors = changed.consistency_errors(
            changed.load_modules(changed.SETTINGS),
            changed.ModuleChecks({name: [f':{name}:detekt'] for name in changed.TASKS.all_modules()}),
        )

        self.assertEqual([], errors)

    def test_a_module_the_build_does_not_declare_is_reported(self):
        errors = changed.consistency_errors(
            {'shared': changed.Module(name='shared', gradle_path=':shared', directory='shared', conditional=False)},
            changed.TASKS,
        )

        self.assertTrue(any('does not declare it' in error for error in errors), errors)


class NestedModulePlanTest(unittest.TestCase):
    """A nested module is judged, and it is a leaf.

    `:tools:unwritten-properties` is a KSP processor that no module compiles
    against, so a change in it breaks it and nothing else. The depth of the
    directory makes no difference to that rule: what a change reaches is a fact
    about the dependency edges, not about how deep the module sits.
    """

    def test_a_change_in_the_nested_module_runs_its_tests(self):
        tasks, _reasons = changed.plan(['tools/unwritten-properties/src/main/kotlin/Foo.kt'])

        self.assertEqual([':tools:unwritten-properties:test'], tasks)

    def test_a_change_in_the_nested_module_reaches_nothing_else(self):
        tasks, _reasons = changed.plan(['tools/unwritten-properties/src/main/kotlin/Foo.kt'])

        self.assertNotIn(':shared:jvmTest', tasks)
        self.assertNotIn(':shared:detekt', tasks)

    def test_a_build_wide_change_judges_the_nested_module_too(self):
        tasks, _reasons = changed.plan(['gradle.properties'])

        self.assertIn(':tools:unwritten-properties:test', tasks)

    def test_the_nested_module_has_no_detekt_task_to_invent(self):
        """The pre-push verdict turns module names into detekt tasks.

        `:tools:unwritten-properties` applies the plain `kotlin("jvm")` plugin,
        so it has no detekt task — and a graph that invented one would hand the
        hook a task the build cannot resolve.
        """
        self.assertNotIn('tools:unwritten-properties', list(changed.TASKS))
        self.assertIn('tools:unwritten-properties', changed.TASKS.all_modules())


class ConditionalModulePlanTest(unittest.TestCase):
    """A conditional module is known, and the decision is written down.

    `:pro` exists only in the `-PwithPro` build, so its checks cannot be named
    by a plan that runs in the free build. The module is still recognized — a
    change under `pro/` is a change in a module, not a path nobody owns — and
    the plan says why it schedules nothing.
    """

    def test_a_change_in_a_conditional_module_is_recognized_as_a_module(self):
        tasks, reasons = changed.plan(['pro/src/main/kotlin/Foo.kt'])

        self.assertEqual([], tasks)
        self.assertFalse(
            any('no module owns it' in reason for reason in reasons),
            reasons,
        )

    def test_a_change_in_a_conditional_module_says_why_it_runs_nothing(self):
        _tasks, reasons = changed.plan(['pro/src/main/kotlin/Foo.kt'])

        self.assertTrue(any('pro' in reason for reason in reasons), reasons)


class FallbackTest(unittest.TestCase):
    """A tree without a build file still has to be judgeable.

    The hook fixture in `scripts/tests/test_pre_push_hook.py` copies this
    script into an empty repository, and the verdict reads the graph from
    there. There is no `settings.gradle.kts` in that tree, and a graph that
    gave up would judge nothing — which reads as a clean push.
    """

    def test_a_tree_without_a_build_file_knows_the_judged_modules(self):
        modules = changed.load_modules(pathlib.Path('/nonexistent/settings.gradle.kts'))

        self.assertIn('shared', modules)
        self.assertIn('tools:unwritten-properties', modules)

    def test_a_tree_without_a_build_file_still_maps_a_path_to_its_module(self):
        modules = changed.load_modules(pathlib.Path('/nonexistent/settings.gradle.kts'))
        prefixes = changed.path_modules(modules)

        self.assertEqual('shared', prefixes['shared/'])
class TheGraphIsCompleteTest(unittest.TestCase):
    """The graph cannot be allowed to fall behind the build.

    Two tables have to agree: the table that says which module owns a path, and
    the table that says what has to compile for that module. When they disagree
    a module has no owner of its compile, and the commit gate skips it in
    silence.
    """

    def test_every_module_the_graph_names_has_a_compile_entry(self):
        """A module with no entry is a graph that has fallen behind the build."""
        missing = sorted(set(changed.PATH_MODULES.values()) - set(changed.COMPILE_TASKS))

        self.assertEqual(
            [], missing,
            f'these modules own paths but have no compile entry: {missing}',
        )

    def test_a_module_that_is_not_compiled_locally_says_why(self):
        """An empty compile list needs a reason, or the skip is silent."""
        silent = sorted(
            module for module, tasks in changed.COMPILE_TASKS.items()
            if not tasks and module not in changed.NOT_COMPILED_LOCALLY
        )

        self.assertEqual(
            [], silent,
            f'these modules are not compiled locally and do not say why: {silent}',
        )

    def test_every_compile_task_belongs_to_a_module_of_the_graph(self):
        """A compile task for a module nobody owns is a typo waiting to happen."""
        owners = set(changed.PATH_MODULES.values())
        orphans = sorted(
            module for module in changed.COMPILE_TASKS
            if module not in owners
        )

        self.assertEqual([], orphans, f'these modules are compiled but own no path: {orphans}')


class TheLintStepSeesTheSamePathsTest(unittest.TestCase):
    """The lint step reads the paths of the change too, and it reads them raw.

    Git quotes a path with a space, a quote or a backslash, and a quoted path
    does not name a file — so the lint would not look at the file that was
    just written.
    """

    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='lint-changed-'))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self._git('init', '-q')
        self._git('config', 'user.email', 'test@example.com')
        self._git('config', 'user.name', 'Test')
        self._git('commit', '--allow-empty', '-m', 'base', '-q')
        (self.tmp / 'shared').mkdir()
        (self.tmp / 'shared' / 'My File.kt').write_text('val a = 1\n', encoding='utf-8')

    def _git(self, *args: str) -> None:
        subprocess.run(
            ['git', *args], cwd=self.tmp, check=True, capture_output=True, text=True,
        )

    def test_a_kotlin_file_with_a_space_is_listed(self):
        """The file that was just written is the file the lint looks at."""
        lint = _load_lint()
        original_root = lint.ROOT
        lint.ROOT = self.tmp
        self.addCleanup(self._restore_root, lint, original_root)

        paths = lint.changed_paths('origin/main')

        self.assertIn('shared/My File.kt', paths)

    def test_a_tracked_file_with_a_space_is_listed_after_it_changed(self):
        """The working-tree diff names the file too, not only the untracked list.

        A file that is already tracked and was changed is the common case; the
        lint step reads it through `git diff HEAD`, and that path has to be
        read in the format that does not quote.
        """
        lint = _load_lint()
        original_root = lint.ROOT
        lint.ROOT = self.tmp
        self.addCleanup(self._restore_root, lint, original_root)
        self._git('add', '-A')
        self._git('commit', '-m', 'add the file', '-q')
        (self.tmp / 'shared' / 'My File.kt').write_text('val a = 2\n', encoding='utf-8')

        paths = lint.changed_paths('origin/main')

        self.assertIn('shared/My File.kt', paths)

    def test_a_committed_file_with_a_space_is_listed(self):
        """The committed diff names the file as well."""
        lint = _load_lint()
        original_root = lint.ROOT
        lint.ROOT = self.tmp
        self.addCleanup(self._restore_root, lint, original_root)
        self._git('add', '-A')
        self._git('commit', '-m', 'add the file', '-q')
        base = subprocess.run(
            ['git', 'rev-parse', 'HEAD'], cwd=self.tmp, check=True,
            capture_output=True, text=True,
        ).stdout.strip()
        (self.tmp / 'shared' / 'My File.kt').write_text('val a = 3\n', encoding='utf-8')
        self._git('commit', '-am', 'change the file', '-q')

        paths = lint.changed_paths(base)

        self.assertIn('shared/My File.kt', paths)

    @staticmethod
    def _restore_root(lint, original_root) -> None:
        lint.ROOT = original_root


def _load_lint():
    """`scripts/lint-changed.py`, loaded the way the hook loads it."""
    path = pathlib.Path(__file__).resolve().parent.parent / 'lint-changed.py'
    spec = importlib.util.spec_from_file_location('lint_changed', path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules['lint_changed'] = module
    spec.loader.exec_module(module)
    return module


class TheGraphRefusesToPlanWhenItDisagreesWithTheBuild(unittest.TestCase):
    """A plan built from a graph that contradicts the build means nothing.

    The command must REFUSE to build a plan when the check fails. The check
    itself is left real here: the disagreement is produced by the graph the
    command reads, not by a stubbed check — a stubbed check would pass even if
    the check had been turned into a no-op.

    The base is `HEAD`, not the default one. The default base is
    `origin/main`, and a CI checkout is a detached, depth-1 commit with no
    such ref — there the base cannot be measured against, the command returns
    2 for THAT reason, and the test would pass whatever the check does. With
    `HEAD` the base always resolves, so the only way to code 2 is the
    disagreement the test stages.
    """

    def test_the_command_stops_when_the_graph_and_the_build_disagree(self):
        ghost = changed.Module(
            name='ghost',
            gradle_path=':ghost',
            directory='ghost',
            conditional=False,
        )
        original = changed.MODULES
        changed.MODULES = {**original, 'ghost': ghost}
        self.addCleanup(self._restore_modules, original)

        code = changed.main(['--base', 'HEAD'])

        self.assertEqual(2, code)

    @staticmethod
    def _restore_modules(original) -> None:
        changed.MODULES = original


class TheBaseIsUsableTest(unittest.TestCase):
    """A base that cannot be measured against is a failure, not an empty plan."""

    def test_a_base_that_does_not_resolve_is_not_usable(self):
        self.assertFalse(changed.base_usable('origin/there-is-no-such-remote'))
        self.assertTrue(changed.base_usable('HEAD'))

    def test_the_command_stops_when_the_base_cannot_be_used(self):
        """The command that reads the graph makes the same check.

        A plan that covers only the working tree while looking like a plan for
        the whole change is the silent loss the check exists to prevent.
        """
        code = changed.main(['--base', 'origin/there-is-no-such-remote'])

        self.assertEqual(2, code)

    def test_a_base_without_a_merge_base_is_not_usable(self):
        """Unrelated histories: the three-dot diff fails, and the error is quiet.

        The ref resolves, so a check that only looks at the ref says yes — and
        the committed part of the change is not counted.
        """
        tmp = pathlib.Path(tempfile.mkdtemp(prefix='changed-modules-'))
        self.addCleanup(shutil.rmtree, tmp, ignore_errors=True)

        def git(*args: str) -> None:
            subprocess.run(
                ['git', *args], cwd=tmp, check=True, capture_output=True, text=True,
            )

        git('init', '-q')
        git('config', 'user.email', 'test@example.com')
        git('config', 'user.name', 'Test')
        git('commit', '--allow-empty', '-m', 'base', '-q')
        # A history of its own: it shares no commit with this one.
        orphan = tmp / 'orphan'
        orphan.mkdir()
        subprocess.run(
            ['git', 'init', '-q'], cwd=orphan, check=True, capture_output=True,
        )
        subprocess.run(
            ['git', '-c', 'user.email=t@e.com', '-c', 'user.name=T',
             'commit', '--allow-empty', '-m', 'orphan', '-q'],
            cwd=orphan, check=True, capture_output=True,
        )
        sha = subprocess.run(
            ['git', 'rev-parse', 'HEAD'], cwd=orphan, check=True,
            capture_output=True, text=True,
        ).stdout.strip()
        subprocess.run(
            ['git', 'fetch', '-q', '--no-tags', str(orphan),
             f'{sha}:refs/remotes/origin/other'],
            cwd=tmp, check=True, capture_output=True, text=True,
        )

        original_root = changed.ROOT
        self._original_root = original_root
        changed.ROOT = tmp
        self.addCleanup(self._restore_root)

        # The ref resolves ...
        self.assertEqual(
            0,
            subprocess.run(
                ['git', 'rev-parse', '--verify', '--quiet', 'origin/other^{commit}'],
                cwd=tmp, capture_output=True, text=True, check=False,
            ).returncode,
            'the ref itself resolves',
        )
        # ... but the change cannot be measured against it.
        self.assertFalse(changed.base_usable('origin/other'))

    def _restore_root(self) -> None:
        changed.ROOT = self._original_root


class ThePathsComeOutOfGitTest(unittest.TestCase):
    """The paths are read out of git, and git has two ways to hide them.

    A rename is reported as one path, and a path with a non-ASCII character is
    quoted. Both hide a change from the module that owns it, and both do it
    quietly.
    """

    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='changed-modules-'))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self._git('init', '-q')
        self._git('config', 'user.email', 'test@example.com')
        self._git('config', 'user.name', 'Test')
        self._git('commit', '--allow-empty', '-m', 'base', '-q')
        self._original_root = changed.ROOT
        changed.ROOT = self.tmp
        self.addCleanup(self._restore_root)

    def _restore_root(self):
        changed.ROOT = self._original_root

    def _git(self, *args: str) -> None:
        subprocess.run(
            ['git', *args], cwd=self.tmp, check=True, capture_output=True, text=True,
        )

    def test_a_rename_names_the_old_path_and_the_new_one(self):
        """A file that moved is a change of the module it moved OUT of.

        The old path is the module that lost a file, and the new path is the
        module that gained one. Both are changes.
        """
        (self.tmp / 'shared').mkdir()
        (self.tmp / 'shared' / 'A.kt').write_text('val a = 1\n', encoding='utf-8')
        self._git('add', '.')
        self._git('commit', '-m', 'add', '-q')
        self._git('mv', 'shared/A.kt', 'shared/B.kt')

        paths = changed.changed_paths('origin/main')

        self.assertIn('shared/A.kt', paths, 'the module that LOST the file is named')
        self.assertIn('shared/B.kt', paths, 'the module that GAINED the file is named')

    def test_a_rename_in_a_commit_names_the_old_path_and_the_new_one(self):
        """A committed rename is a change of the module it moved OUT of.

        The diff is asked for renames as renames turned off, so git reports the
        move as what it is to the module graph — a deletion in one place and an
        addition in another. Without that, the diff would name only the new
        path, and the module that lost the file would never learn of it.
        """
        (self.tmp / 'shared').mkdir()
        (self.tmp / 'shared' / 'A.kt').write_text('val a = 1\n', encoding='utf-8')
        self._git('add', '.')
        self._git('commit', '-m', 'add', '-q')
        base = subprocess.run(
            ['git', 'rev-parse', 'HEAD'], cwd=self.tmp, check=True,
            capture_output=True, text=True,
        ).stdout.strip()
        self._git('mv', 'shared/A.kt', 'shared/B.kt')
        self._git('commit', '-m', 'rename', '-q')

        paths = changed.changed_paths(base)

        self.assertIn('shared/A.kt', paths, 'the module that LOST the file is named')
        self.assertIn('shared/B.kt', paths, 'the module that GAINED the file is named')

    def test_a_path_with_a_non_ascii_character_is_not_quoted(self):
        """Git quotes a non-ASCII path unless it is asked not to.

        A quoted path starts with a quote character, so no module claims it and
        the change is silently unowned.
        """
        (self.tmp / 'shared').mkdir()
        (self.tmp / 'shared' / 'Ünïcode.kt').write_text('val a = 1\n', encoding='utf-8')

        paths = changed.changed_paths('origin/main')

        self.assertIn('shared/Ünïcode.kt', paths)

    def test_a_path_with_a_space_is_not_quoted(self):
        """Git quotes a path with a space whatever `quotePath` says.

        A quoted path starts with a quote character, so no module claims it
        and the change is silently unowned — the commit passes without
        compiling anything.
        """
        (self.tmp / 'shared').mkdir()
        (self.tmp / 'shared' / 'My File.kt').write_text('val a = 1\n', encoding='utf-8')

        paths = changed.changed_paths('origin/main')

        self.assertIn('shared/My File.kt', paths)

    def test_a_path_with_a_space_is_owned_by_its_module(self):
        """The path that is read is the path the module graph can match."""
        (self.tmp / 'shared').mkdir()
        (self.tmp / 'shared' / 'My File.kt').write_text('val a = 1\n', encoding='utf-8')

        paths = changed.changed_paths('origin/main')
        owner = next(
            (module for prefix, module in changed.PATH_MODULES.items()
             if any(path.startswith(prefix) for path in paths)),
            None,
        )

        self.assertEqual('shared', owner)

    def test_a_path_with_an_arrow_in_it_is_a_path(self):
        """`->` is a separator in the old format and a character in a name."""
        (self.tmp / 'shared').mkdir()
        (self.tmp / 'shared' / 'a -> b.kt').write_text('val a = 1\n', encoding='utf-8')

        paths = changed.changed_paths('origin/main')

        self.assertIn('shared/a -> b.kt', paths)

    def test_a_non_ascii_path_is_owned_by_its_module(self):
        """The path that is read is the path the module graph can match."""
        (self.tmp / 'shared').mkdir()
        (self.tmp / 'shared' / 'Ünïcode.kt').write_text('val a = 1\n', encoding='utf-8')

        paths = changed.changed_paths('origin/main')
        owner = next(
            (module for prefix, module in changed.PATH_MODULES.items()
             if any(path.startswith(prefix) for path in paths)),
            None,
        )

        self.assertEqual('shared', owner)


if __name__ == '__main__':
    unittest.main()


class DetektTasksTest(unittest.TestCase):
    """The list of detekt modules CI judges, and where it comes from.

    The list used to be written out by hand in the CI workflow, in the
    auto-fix script and in the hook's record — three copies of one fact, and a
    module added to the build had to be remembered in all three. The list is
    read from the build now, and this pins what that list contains.
    """

    def test_the_list_is_the_modules_that_have_a_detekt_verdict(self):
        import io
        import contextlib

        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            tasks = changed.main(['--detekt-tasks'])
        self.assertEqual(0, tasks)

    def test_it_names_a_detekt_task_per_module_that_has_one(self):
        import io
        import contextlib

        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            changed.main(['--detekt-tasks'])
        printed = [line for line in buffer.getvalue().splitlines() if line]

        expected = [f':{module}:detekt' for module in changed.TASKS.detekt_modules()]
        self.assertEqual(sorted(expected), sorted(printed))

    def test_a_module_without_a_detekt_task_is_not_in_it(self):
        """`:tools:unwritten-properties` has no detekt task of its own.

        Its verdict is its test suite. A list that named a detekt task for it
        would be a task the build does not have, and CI would fail on it.
        """
        import io
        import contextlib

        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            changed.main(['--detekt-tasks'])
        printed = buffer.getvalue()

        self.assertNotIn('unwritten-properties', printed)

    def test_nothing_but_the_tasks_reaches_stdout(self):
        """The caller puts this list into a Gradle invocation."""
        import io
        import contextlib

        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            changed.main(['--detekt-tasks'])
        for line in buffer.getvalue().splitlines():
            self.assertRegex(line, r'^:[\w-]+:detekt$')

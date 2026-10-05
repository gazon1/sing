"""Tests for check-pro-licence-boundary.py.

The gate exists so proprietary code cannot creep back into code published under
Apache-2.0. That is not a hypothetical: `ru.ok.tracer` was a direct
`implementation` dependency of both `:shared` and `:androidApp`, and the
`Application` class implemented its interface, until 2026-10-05.

Two properties matter and both are ways the gate could pass while measuring
nothing:

  - each rule fires on a tree that violates it
  - the APK reader distinguishes a free build from a pro build

The second is the one that actually settles the question. Every other rule is
static analysis, and all of them could be true while a transitive dependency put
`ru/ok/tracer` into the free APK anyway. Reading the dex is the only check that
sees the artifact rather than the intention.
"""

import importlib.util
import pathlib
import tempfile
import unittest
import zipfile

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'check-pro-licence-boundary.py'
spec = importlib.util.spec_from_file_location('check_pro_licence_boundary', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

FSL_HEADER = '// SPDX-License-Identifier: FSL-1.1-ALv2\n'


def make_apk(path: pathlib.Path, dex: bytes) -> pathlib.Path:
    with zipfile.ZipFile(path, 'w') as zf:
        zf.writestr('AndroidManifest.xml', '<manifest/>')
        zf.writestr('classes.dex', dex)
    return path


class DexScanTest(unittest.TestCase):
    """The APK reader is a substring search over the dex — prove both directions."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_a_free_dex_is_accepted(self):
        dex = b'dex\n035\0' + b'Lcom/singularity/todo/core/observability/FileCrashReportingPort;'
        apk = make_apk(self.dir / 'free.apk', dex)
        self.assertEqual(mod.verify_apk(apk, 'free'), [])

    def test_a_vendor_class_in_a_free_dex_is_caught(self):
        dex = b'dex\n035\0' + b'Lru/ok/tracer/crash/report/TracerCrashReport;'
        apk = make_apk(self.dir / 'free.apk', dex)
        violations = mod.verify_apk(apk, 'free')
        self.assertTrue(any('ru/ok/tracer' in v.args[0] for v in violations), violations)

    def test_the_pro_class_in_a_free_dex_is_caught(self):
        """The vendor *string* is not enough — the project's own pro class counts too."""
        dex = b'dex\n035\0' + b'Lcom/singularity/todo/pro/observability/TracerCrashReportingPort;'
        apk = make_apk(self.dir / 'free.apk', dex)
        self.assertTrue(mod.verify_apk(apk, 'free'))

    def test_pro_singularity_app_in_a_free_dex_is_caught(self):
        dex = b'dex\n035\0' + b'Lcom/singularity/todo/pro/ProSingularityApp;'
        apk = make_apk(self.dir / 'free.apk', dex)
        self.assertTrue(mod.verify_apk(apk, 'free'))

    def test_a_free_dex_with_no_reporter_is_caught(self):
        """A free build with no crash reporter is a different bug, and still a finding."""
        apk = make_apk(self.dir / 'free.apk', b'dex\n035\0' + b'Lcom/singularity/todo/App;')
        violations = mod.verify_apk(apk, 'free')
        self.assertTrue(any('FileCrashReportingPort' in v.args[0] for v in violations), violations)

    def test_a_pro_dex_is_accepted(self):
        dex = (b'dex\n035\0Lru/ok/tracer/x;'
               b'Lcom/singularity/todo/pro/observability/TracerCrashReportingPort;'
               b'Lcom/singularity/todo/pro/ProSingularityApp;')
        apk = make_apk(self.dir / 'pro.apk', dex)
        self.assertEqual(mod.verify_apk(apk, 'pro'), [])

    def test_an_empty_pro_dex_is_caught(self):
        """Expecting the vendor SDK and finding none means :pro was not compiled in."""
        apk = make_apk(self.dir / 'pro.apk', b'dex\n035\0' + b'Lcom/singularity/todo/App;')
        self.assertTrue(mod.verify_apk(apk, 'pro'))

    def test_a_missing_apk_is_reported_not_crashed(self):
        self.assertTrue(mod.verify_apk(self.dir / 'nope.apk', 'free'))

    def test_an_archive_with_no_dex_is_reported(self):
        path = self.dir / 'empty.apk'
        with zipfile.ZipFile(path, 'w') as zf:
            zf.writestr('AndroidManifest.xml', '<manifest/>')
        self.assertTrue(mod.verify_apk(path, 'free'))


class SourceRuleTest(unittest.TestCase):
    """The five source rules, each against a compliant tree and a violating one."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        mod._make_compliant(self.root)

    def tearDown(self):
        self.tmp.cleanup()

    def test_the_compliant_tree_is_clean(self):
        for check in (mod.check_no_pro_imports, mod.check_no_denied_dependencies,
                      mod.check_fsl_files_declare_themselves, mod.check_settings_gates_pro,
                      mod.check_licence_files, mod.check_not_vacuous):
            with self.subTest(check=check.__name__):
                self.assertEqual(check(self.root), [])

    def test_core_importing_pro_is_caught(self):
        p = self.root / 'shared/src/commonMain/kotlin/com/singularity/todo/Thing.kt'
        p.write_text('package p\n\nimport com.singularity.todo.pro.observability.Thing\n',
                     encoding='utf-8')
        self.assertTrue(mod.check_no_pro_imports(self.root))

    def test_a_pro_mention_in_a_comment_is_not_an_import(self):
        """The rule matches import/package lines, so prose about pro/ does not trip it."""
        p = self.root / 'shared/src/commonMain/kotlin/com/singularity/todo/Thing.kt'
        p.write_text('package p\n\n// the pro catalogue lives in com.singularity.todo.pro\nclass T\n',
                     encoding='utf-8')
        self.assertEqual(mod.check_no_pro_imports(self.root), [])

    def test_a_commented_out_vendor_dependency_is_not_reported(self):
        p = self.root / 'shared/build.gradle.kts'
        p.write_text('dependencies {\n'
                     '    // implementation("ru.ok.tracer:tracer-crash-report:1.4.0") — moved to pro\n'
                     '}\n', encoding='utf-8')
        self.assertEqual(mod.check_no_denied_dependencies(self.root), [])

    def test_a_vendor_dependency_is_caught(self):
        p = self.root / 'shared/build.gradle.kts'
        p.write_text('dependencies { implementation("ru.ok.tracer:tracer-crash-report:1.4.0") }\n',
                     encoding='utf-8')
        self.assertTrue(mod.check_no_denied_dependencies(self.root))

    def test_an_empty_denied_list_is_itself_a_violation(self):
        """A gate whose deny list is empty reports 'no vendor code' by default."""
        original = mod.DENIED_GROUPS
        try:
            mod.DENIED_GROUPS = ()
            violations = mod.check_no_denied_dependencies(self.root)
        finally:
            mod.DENIED_GROUPS = original
        self.assertTrue(any('empty' in v.args[0] for v in violations), violations)

    def test_an_fsl_file_without_the_spdx_tag_is_caught(self):
        p = self.root / 'pro/src/main/kotlin/com/singularity/todo/pro/Thing.kt'
        p.write_text('package com.singularity.todo.pro\n\nclass Thing\n', encoding='utf-8')
        self.assertTrue(mod.check_fsl_files_declare_themselves(self.root))

    def test_an_unconditional_pro_include_is_caught(self):
        (self.root / 'settings.gradle.kts').write_text('include(":pro")\n', encoding='utf-8')
        self.assertTrue(mod.check_settings_gates_pro(self.root))

    def test_an_unguarded_pro_source_set_is_caught(self):
        (self.root / 'androidApp/build.gradle.kts').write_text(
            'android { sourceSets { getByName("main").kotlin.directories.add("src/pro/kotlin") } }\n',
            encoding='utf-8')
        self.assertTrue(mod.check_settings_gates_pro(self.root))

    def test_an_unguarded_pro_dependency_is_caught(self):
        (self.root / 'androidApp/build.gradle.kts').write_text(
            'dependencies { implementation(project(":pro")) }\n', encoding='utf-8')
        self.assertTrue(mod.check_settings_gates_pro(self.root))

    def test_a_licence_without_the_future_grant_is_caught(self):
        (self.root / 'LICENSE.pro').write_text(
            '# Functional Source License, Version 1.1, ALv2 Future License\nperpetual.\n',
            encoding='utf-8')
        violations = mod.check_licence_files(self.root)
        self.assertTrue(any('Future License' in v.args[0] for v in violations), violations)

    def test_an_empty_apache_tree_is_vacuous(self):
        for p in (self.root / 'shared/src').rglob('*.kt'):
            p.unlink()
        violations = mod.check_not_vacuous(self.root)
        self.assertTrue(any('vacuous' in v.args[0] for v in violations), violations)

    def test_an_empty_fsl_tree_is_reported(self):
        """The direction that matters: an empty pro/ means the boundary is untested."""
        for p in list((self.root / 'pro/src').rglob('*.kt')) + \
                list((self.root / 'androidApp/src/pro').rglob('*.kt')):
            p.unlink()
        violations = mod.check_not_vacuous(self.root)
        self.assertTrue(any('FSL' in v.args[0] for v in violations), violations)


class SelfTestEntryPointTest(unittest.TestCase):
    def test_self_test_passes(self):
        self.assertEqual(mod.self_test(), 0)


if __name__ == '__main__':
    unittest.main()

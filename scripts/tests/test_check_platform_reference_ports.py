"""Tests for check-platform-reference-ports.py.

The gate exists because `docs/PLATFORM-REFERENCE.md` is the map of the seam:
which port is bound to which implementation on which platform. The document
said so itself — "nothing here is generated … no gate will notice" — and it had
rotted: it still named a port that was deleted and did not name the twenty
ports the seam has.

So the properties that matter are the ways the map can lie:

  - a port the code binds and the map does not name (the code moved, the map
    did not);
  - a row that names a port the code does not bind (the map moved, the code
    did not);
  - an implementation a row attributes to a platform that does not exist in
    that platform's source set;
  - a row that does not say whether the gate checks it, so a reader cannot
    tell what is verified and what is prose.

Each of them has a test here that mutates an input in the way the rule hunts
for and asserts that the gate reports it.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

MODULE_PATH = (
    pathlib.Path(__file__).resolve().parent.parent
    / 'check-platform-reference-ports.py'
)
spec = importlib.util.spec_from_file_location('check_platform_reference_ports', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
sys.modules['check_platform_reference_ports'] = mod  # must precede exec_module
spec.loader.exec_module(mod)


JVM_MODULE = 'shared/src/jvmMain/kotlin/com/singularity/todo/core/di/PlatformModule.jvm.kt'
ANDROID_MODULE = (
    'shared/src/androidMain/kotlin/com/singularity/todo/core/di/PlatformModule.android.kt'
)
PORTS = 'shared/src/commonMain/kotlin/com/singularity/todo/core/ports/Ports.kt'
JVM_IMPLS = 'shared/src/jvmMain/kotlin/com/singularity/todo/core/ports/Jvm.kt'
ANDROID_IMPLS = 'shared/src/androidMain/kotlin/com/singularity/todo/core/ports/Android.kt'
SEAMS = 'shared/src/jvmTest/resources/platform-seams.tsv'

#: The code of the fixture tree: one port bound on both platforms, one bound
#: on one platform only, and one binding that is infrastructure.
JVM_MODULE_SOURCE = '''import org.koin.dsl.module

val platformModule = module {
    single<ClockPort> { JvmClockPort() }
    single<StoragePort> { JvmStoragePort() }
    single<AppDatabase> { AppDatabase() }
}
'''

ANDROID_MODULE_SOURCE = '''import org.koin.dsl.module

val platformModule = module {
    single<ClockPort> { AndroidClockPort() }
    single<StoragePort> { AndroidStoragePort() }
    single<AlarmPort> { AndroidAlarmPort() }
}
'''

PORTS_SOURCE = '''package com.singularity.todo.core.ports

interface ClockPort
interface StoragePort
interface AlarmPort
class AppDatabase
'''

JVM_IMPLS_SOURCE = '''package com.singularity.todo.core.ports

class JvmClockPort : ClockPort
class JvmStoragePort : StoragePort
'''

ANDROID_IMPLS_SOURCE = '''package com.singularity.todo.core.ports

class AndroidClockPort : ClockPort
class AndroidStoragePort : StoragePort
class AndroidAlarmPort : AlarmPort
'''

SEAMS_SOURCE = '''# port | kind | androidImpl | jvmImpl
ClockPort | seam | AndroidClockPort | JvmClockPort
StoragePort | seam | AndroidStoragePort | JvmStoragePort
AlarmPort | seam | AndroidAlarmPort | -
AppDatabase | infra | - | -
'''

THE_ROWS = [
    '| `ClockPort` | `JvmClockPort` | `AndroidClockPort` | checked |',
    '| `StoragePort` | `JvmStoragePort` | `AndroidStoragePort` | checked |',
    '| `AlarmPort` | - | `AndroidAlarmPort` | checked |',
    '| `AttachmentStorage` | - | - | prose |',
]


def tree(tmp):
    """The fixture tree: the code the gate reads."""
    root = pathlib.Path(tmp)
    files = {
        JVM_MODULE: JVM_MODULE_SOURCE,
        ANDROID_MODULE: ANDROID_MODULE_SOURCE,
        PORTS: PORTS_SOURCE,
        JVM_IMPLS: JVM_IMPLS_SOURCE,
        ANDROID_IMPLS: ANDROID_IMPLS_SOURCE,
        SEAMS: SEAMS_SOURCE,
    }
    for relative, text in files.items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')
    return root


def document(rows):
    """A reference document whose ports table has the given rows."""
    body = '\n'.join(rows)
    return (
        '# Platform reference\n\n'
        '## Ports\n\n'
        '| Port | jvmMain | androidMain | Checked |\n'
        '|---|---|---|---|\n'
        f'{body}\n'
    )


class ReferenceTest(unittest.TestCase):
    """The reference as it stands: it agrees with the code."""

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = tree(tmp.name)
        self.ports = mod.code_ports(self.root)

    def test_the_ports_are_the_interfaces_the_platform_modules_bind(self):
        self.assertEqual(self.ports, {'ClockPort', 'StoragePort', 'AlarmPort'})

    def test_an_infrastructure_binding_is_not_a_port(self):
        self.assertNotIn('AppDatabase', self.ports)

    def test_the_reference_that_matches_the_code_has_no_findings(self):
        rows = mod.parse_table(document(THE_ROWS))
        findings = mod.check(rows, self.ports, self.root)
        self.assertEqual([finding.render() for finding in findings], [])


class DriftTest(unittest.TestCase):
    """Every way the reference can lie, and the finding each one earns."""

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = tree(tmp.name)
        self.ports = mod.code_ports(self.root)

    def kinds(self, rows):
        findings = mod.check(mod.parse_table(document(rows)), self.ports, self.root)
        return [finding.kind for finding in findings]

    def test_a_port_the_reference_does_not_name_is_a_finding(self):
        rows = [row for row in THE_ROWS if 'StoragePort' not in row]
        self.assertIn('not-in-the-reference', self.kinds(rows))

    def test_a_row_naming_a_port_the_code_lacks_is_a_finding(self):
        rows = THE_ROWS + [
            '| `NotificationPort` | `JvmNotifier` | `AndroidNotifier` | checked |',
        ]
        self.assertIn('not-in-the-code', self.kinds(rows))

    def test_an_implementation_that_does_not_exist_is_a_finding(self):
        rows = [
            row.replace('`JvmClockPort`', '`JvmAlarmClockPort`') if 'ClockPort' in row else row
            for row in THE_ROWS
        ]
        self.assertIn('implementation-not-found', self.kinds(rows))

    def test_a_row_that_does_not_say_whether_it_is_checked_is_a_finding(self):
        rows = [
            row.replace('| checked |', '| yes |') if 'ClockPort' in row else row
            for row in THE_ROWS
        ]
        self.assertIn('unknown-verdict', self.kinds(rows))

    def test_a_prose_row_is_not_checked(self):
        rows = THE_ROWS + [
            '| `NotificationPort` | `JvmNotifier` | `AndroidNotifier` | prose |',
        ]
        self.assertNotIn('not-in-the-code', self.kinds(rows))

    def test_a_reference_without_the_table_is_not_vacuous(self):
        findings = mod._not_vacuous([], self.ports)
        self.assertIn('scan-found-nothing', [finding.kind for finding in findings])


class RepositoryTest(unittest.TestCase):
    """The reference and the code the repository actually has."""

    ROOT = pathlib.Path(__file__).resolve().parents[2]

    def test_the_repository_reference_agrees_with_the_repository_code(self):
        document_text = (self.ROOT / mod.DOCUMENT).read_text(encoding='utf-8')
        rows = mod.parse_table(document_text)
        ports = mod.code_ports(self.ROOT)
        findings = mod.check(rows, ports, self.ROOT)
        self.assertEqual([finding.render() for finding in findings], [])


if __name__ == '__main__':
    unittest.main()

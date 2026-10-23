"""Tests for check-navigation-doc-sync.py.

The gate compares the navigation doc against the code. The doc is a pointer —
it is what an agent reads to learn which route opens which screen — so a row
that names a type the code does not name sends every reader the wrong way.

Two rows went false on 2026-10-22 and nothing noticed: the deep-link row for
`singularity://view/{viewId}` still named `SavedAgendaEdit` after the code
moved to `SavedAgendaResults`, and the platform row still said the desktop
tasks stack pushes the `Detail` start onto the stack after the code changed to
seed the stack with the requested route.

These tests are about the comparison: which claims are reported, and which
rows of the doc are not claims at all.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS / "src"))

MODULE_PATH = SCRIPTS / "check-navigation-doc-sync.py"
spec = importlib.util.spec_from_file_location("check_navigation_doc_sync", MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
# The module must be in sys.modules before it is executed: a dataclass looks
# its own module up there, and a module that was never registered has no
# entry to find. This is the convention the other gate tests follow.
sys.modules["check_navigation_doc_sync"] = mod
spec.loader.exec_module(mod)

DOC = "docs/navigation.md"
MATCHER = "shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/nav/AgendaDeepLink.kt"
SPEC = "shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/AgendaLinkSpec.kt"
JVM_ENTRIES = "shared/src/jvmMain/kotlin/com/singularity/todo/feature/nav/JvmNavEntries.kt"

MATCHER_SOURCE = """package com.singularity.todo.feature.agenda.presentation.nav

class AgendaDeepLink : AppDeepLink {
    override fun match(request: DeepLinkRequest): DeepLinkTarget? {
        return DeepLinkTarget(
            tab = AppDestination.AgendaGraph(AgendaStartRoute.Today),
            screen = AppDestination.AgendaGraph(
                AgendaStartRoute.SavedAgendaResults(SavedAgendaViewId.fromString(hit.key.viewId)),
            ),
        )
    }
}
"""

SPEC_SOURCE = """package com.singularity.todo.feature.agenda

object AgendaLinkSpec {
    const val URI = "singularity://view/{viewId}"
}
"""

DOC_ROW = (
    "| `singularity://view/{viewId}` | agenda — `AgendaLinkSpec` | `AgendaGraph(Today)` | "
    "`AgendaGraph(SavedAgendaResults(viewId))` |"
)


def write(root: pathlib.Path, relative: str, text: str) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


class DeepLinkRowsTest(unittest.TestCase):
    def build(self, doc: str, matcher: str = MATCHER_SOURCE, spec: str = SPEC_SOURCE) -> list[mod.Finding]:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write(root, DOC, doc)
            write(root, MATCHER, matcher)
            write(root, SPEC, spec)
            write(root, JVM_ENTRIES, "    entry<AppDestination.TasksGraph>(clazzContentKey = { it.stableContentKey() }) { route ->\n        TasksNavGraph(start = route.start.toTasksRoute(route.initialDueDate))\n    }\n")
            return mod.mismatches(root)

    def test_a_row_the_code_supports_has_no_findings(self):
        self.assertEqual(self.build(DOC_ROW), [])

    def test_a_row_naming_a_type_the_matcher_does_not_name_is_reported(self):
        stale = DOC_ROW.replace("SavedAgendaResults", "SavedAgendaEdit")
        findings = self.build(stale)
        self.assertEqual(len(findings), 1)
        self.assertIn("SavedAgendaEdit", findings[0].message)
        self.assertEqual(findings[0].severity, mod.BLOCKING)

    def test_a_link_the_code_declares_and_the_doc_does_not_is_reported(self):
        findings = self.build("| `singularity://task/{taskId}` | tasks — `TaskLinkSpec` | `AgendaGraph(Today)` | `TasksGraph(Detail(taskId))` |\n")
        self.assertTrue(any("no deep-link row" in finding.message for finding in findings))


class TasksStackClaimTest(unittest.TestCase):
    def build(self, doc: str, entries: str) -> list[mod.Finding]:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write(root, DOC, doc)
            write(root, MATCHER, MATCHER_SOURCE)
            write(root, SPEC, SPEC_SOURCE)
            write(root, JVM_ENTRIES, entries)
            return mod.mismatches(root)

    def test_a_claim_of_a_push_the_code_does_not_make_is_reported(self):
        doc = DOC_ROW + "\n" + (
            "| `TasksGraph` inner stack | graph dispatch by stack top | seeded "
            "`rememberInMemoryNavBackStack`, `Detail` start pushed eagerly |\n"
        )
        entries = "    entry<AppDestination.TasksGraph> { route ->\n        TasksNavGraph(start = route.start.toTasksRoute())\n    }\n"
        findings = self.build(doc, entries)
        self.assertEqual(len(findings), 1)
        self.assertIn("the code seeds the tasks stack", findings[0].message)

    def test_a_push_claim_is_advisory_and_not_blocking(self):
        """A claim read out of prose is a guess, and a guess does not fail a build.

        The gate looks for a paragraph that mentions the task stack and a push.
        A sentence that says "the tasks stack is seeded, and the push policy is
        Push" would be read as a claim of a push — and it is not one. Blocking
        on that would make the build red for a sentence nobody has to fix.
        """
        doc = DOC_ROW + "\n" + (
            "| `TasksGraph` inner stack | graph dispatch by stack top | seeded "
            "`rememberInMemoryNavBackStack`, `Detail` start pushed eagerly |\n"
        )
        entries = "    entry<AppDestination.TasksGraph> { route ->\n        TasksNavGraph(start = route.start.toTasksRoute())\n    }\n"
        findings = self.build(doc, entries)
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].severity, mod.ADVISORY)

    def test_a_row_that_does_not_claim_a_push_is_not_a_claim(self):
        doc = DOC_ROW + "\n" + (
            "| `TasksGraph` inner stack | graph dispatch by stack top | seeded "
            "`rememberInMemoryNavBackStack` with the requested route |\n"
        )
        entries = "    entry<AppDestination.TasksGraph> { route ->\n        TasksNavGraph(start = route.start.toTasksRoute())\n    }\n"
        self.assertEqual(self.build(doc, entries), [])

    def test_a_push_in_the_code_makes_the_claim_true(self):
        doc = DOC_ROW + "\n" + (
            "| `TasksGraph` inner stack | graph dispatch by stack top | seeded "
            "`rememberInMemoryNavBackStack`, `Detail` start pushed eagerly |\n"
        )
        entries = (
            "    entry<AppDestination.TasksGraph> { route ->\n"
            "        val stack = rememberInMemoryNavBackStack(route.start)\n"
            "        stack.add(route.start.toTasksRoute())\n"
            "    }\n"
        )
        self.assertEqual(self.build(doc, entries), [])


if __name__ == "__main__":
    unittest.main()

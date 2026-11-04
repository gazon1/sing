"""Tests for check-maestro-behaviour-ownership.py.

The gate keeps two documents in step with each other and with the source:

  * `SKILL.md` teaches how to write a flow, and must not restate what the app
    does — that lives once, in the runbook's "App behaviour facts flows encode"
    section;
  * every product identifier that section names (a test tag, a UI event, a
    screen class) must still exist, because a flow written from a renamed tag
    fails on a device rather than here.

Each test mutates a synthetic tree in the way one rule hunts for, and asserts
what the gate does with it: that it reports a claim the runbook owns, and that
it stays quiet about a sentence that names the same vocabulary without claiming
a behaviour. A rule nobody has seen fail can be broken rather than clean.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS / "src"))

MODULE_PATH = SCRIPTS / "check-maestro-behaviour-ownership.py"
spec = importlib.util.spec_from_file_location(
    "check_maestro_behaviour_ownership", MODULE_PATH
)
mod = importlib.util.module_from_spec(spec)
# The module must be registered before it is executed: a dataclass looks its own
# module up in `sys.modules`, and a module that was never registered has no
# entry to find. This is the convention the other gate tests follow.
sys.modules["check_maestro_behaviour_ownership"] = mod
spec.loader.exec_module(mod)

SKILL = mod.SKILL.as_posix()
RUNBOOK = mod.RUNBOOK.as_posix()

#: The source the section's identifiers have to be found in. One file, because
#: the gate reads every Kotlin file of the product as one text.
SOURCE = '''package com.singularity.todo

object TestTags {
    const val TASK_CONTEXT_MENU_SHEET = "task_context_menu_sheet"
    const val TASK_EDITOR_SAVE = "task_editor_save"
    const val TOP_BAR_BACK_BUTTON = "top_bar_back_button"

    fun taskItem(title: String) = "task_item_${slug(title)}"
    fun noteAction(name: String) = "note_action_${slug(name)}"
}

sealed interface NotesUiEvent {
    data class NavigateToEditor(val noteId: String) : NotesUiEvent
}

class SavedAgendaScreen
class SyncSettingsScreen
'''

#: A source that names `task_editor_save` only in a comment beside the code. A
#: comment is prose, so the tag it quotes is not a tag the product declares.
SOURCE_NAMING_THE_OLD_TAG_ONLY_IN_A_COMMENT = '''package com.singularity.todo

object TestTags {
    const val OTHER = "other_tag"
}

/** The old name was `task_editor_save`, before the screen was renamed. */
class Detail
'''


def skill(*bullets: str) -> str:
    """A skill body with the given bullets."""
    return "# Maestro UI Flows\n\n## Authoring\n\n" + "\n".join(bullets) + "\n"


def runbook(*bullets: str, section: bool = True) -> str:
    """A runbook whose behaviour section carries the given bullets."""
    head = "# Diagnosing a Maestro flow failure\n\n## Seed fixtures\n\nSeed, never type.\n\n"
    if not section:
        return head + "## Recon before writing a flow\n\nProbe first.\n"
    body = "\n".join(bullets)
    return (
        head
        + "## App behaviour facts flows encode\n\n"
        + body
        + "\n\n## Recon before writing a flow\n\nProbe first.\n"
    )


def write(root: pathlib.Path, relative: str, text: str) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def findings(skill_body: str, runbook_body: str, source: str = SOURCE) -> list:
    """The findings the gate reports for a synthetic pair and source."""
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        write(root, SKILL, skill_body)
        write(root, RUNBOOK, runbook_body)
        write(root, "shared/TestTags.kt", source)
        return mod.mismatches(root)


class RestatementTest(unittest.TestCase):
    """The skill body must not restate a behaviour the runbook owns."""

    def test_a_clean_pair_has_no_findings(self):
        self.assertEqual(
            findings(
                skill("- **Pre-filled fields append.** `inputText` does not clear."),
                runbook("- **Long-press opens a sheet** (`task_context_menu_sheet`)."),
            ),
            [],
        )

    def test_a_restated_save_button_names_the_sentence(self):
        found = findings(
            skill("- **The task detail screen has no save button.** Edits autosave."),
            runbook("- **A save pops the editor** (`SavedAgendaScreen`)."),
        )
        self.assertEqual(len(found), 1)
        self.assertIn("no save button", found[0].message)
        self.assertIn("save-button-on-a-screen", found[0].message)
        self.assertTrue(found[0].is_blocking)

    def test_a_restated_quick_add_destination_is_reported(self):
        found = findings(
            skill("- **The notes quick-add lands on the preview.**"),
            runbook("- **Notes open their preview** (`note_action_edit`)."),
        )
        self.assertTrue(any("quick-add" in f.message for f in found))

    def test_an_unreachable_feature_list_in_the_skill_body_is_reported(self):
        found = findings(
            skill("- **A feature can be modelled but unreachable.** Check it first."),
            runbook("- **A save pops the editor** (`SavedAgendaScreen`)."),
        )
        self.assertTrue(any("unreachable-feature-list" in f.message for f in found))

    def test_a_quirk_that_quotes_the_same_vocabulary_is_not_a_restatement(self):
        """`inputText` appending and `hideKeyboard` saving are authoring rules.

        Both use the vocabulary of the behaviours the runbook owns, and neither
        states one: the markers are anchored on the claim, not on the word.
        """
        found = findings(
            skill(
                '- **Pre-filled fields append.** `inputText` does not clear, so a field '
                'seeded with "Today" becomes "TodayFocus". `eraseText` first.',
                "- **`hideKeyboard` is a commit, not a dismiss.** It triggers the "
                "editor's autosave-on-dismiss and closes the screen, so it saves and "
                "navigates away.",
            ),
            runbook("- **A save pops the editor** (`SavedAgendaScreen`)."),
        )
        self.assertEqual([f.message for f in found], [])

    def test_a_quick_add_named_for_an_authoring_reason_is_not_a_restatement(self):
        """Naming the quick-add field is an authoring rule; where it lands is not.

        The marker is anchored on the destination claim, so a rule that says how
        to seed the field stays in the skill.
        """
        found = findings(
            skill("- **The quick-add field is a `BasicTextField`, seed it.**"),
            runbook("- **A save pops the editor** (`SavedAgendaScreen`)."),
        )
        self.assertEqual([f.message for f in found], [])

    def test_a_preview_named_for_an_authoring_reason_is_not_a_restatement(self):
        """A Compose preview is a tool for looking at the screen, not a claim.

        The marker wants a destination claim — a tap that opens the preview — so
        naming the preview tool stays in the skill.
        """
        found = findings(
            skill(
                "- **`render-compose-preview` renders the screen** so a flow author can "
                "check the list before writing a step."
            ),
            runbook("- **A save pops the editor** (`SavedAgendaScreen`)."),
        )
        self.assertEqual([f.message for f in found], [])

    def test_a_restated_preview_destination_is_reported(self):
        """The other side of the same marker: a claim about a tap is the runbook's."""
        found = findings(
            skill("- **A tap on a note row opens the note's preview.**"),
            runbook("- **Notes open their preview** (`note_action_edit`)."),
        )
        self.assertEqual(len(found), 1)
        self.assertIn("preview-versus-editor", found[0].message)

    def test_a_pointer_to_the_runbook_is_not_a_restatement(self):
        found = findings(
            skill(
                "The behaviours a flow author would otherwise guess wrong are in the "
                "runbook's [App behaviour facts flows encode](diagnosing-failures.md)."
            ),
            runbook("- **A save pops the editor** (`SavedAgendaScreen`)."),
        )
        self.assertEqual([f.message for f in found], [])


class IdentifierTest(unittest.TestCase):
    """Every identifier the runbook section names must exist in the source."""

    def test_a_renamed_tag_is_reported_by_name(self):
        found = findings(
            skill("Seed, never type."),
            runbook(
                "- **Long-press opens a sheet** (`task_context_menu_sheet`), with rows "
                "tagged `sheet_item_<label>`."
            ),
        )
        self.assertEqual(len(found), 1)
        self.assertIn("sheet_item_", found[0].message)

    def test_an_identifier_the_source_declares_is_not_reported(self):
        found = findings(
            skill("Seed, never type."),
            runbook(
                "- **Long-press opens a sheet** (`task_context_menu_sheet`), and the "
                "detail has no save button (`task_editor_save`)."
            ),
        )
        self.assertEqual([f.message for f in found], [])

    def test_a_name_that_is_the_tail_of_a_live_one_is_reported(self):
        """`bar_back_button` is not declared by `top_bar_back_button`.

        The name has to stand alone: a renamed tag whose old name survives as the
        tail of a live one is a stale identifier, and a substring search would
        have let it through.
        """
        found = findings(
            skill("Seed, never type."),
            runbook("- **The back control is tagged** `bar_back_button`."),
        )
        self.assertEqual(len(found), 1)
        self.assertIn("bar_back_button", found[0].message)

    def test_a_name_that_survives_only_in_a_comment_is_not_declared(self):
        """A KDoc that quotes the old name is prose beside the code, not a declaration.

        The source index drops comments, so a tag only a comment names is a tag
        the product does not have.
        """
        found = findings(
            skill("Seed, never type."),
            runbook("- **The detail autosaves** — there is `task_editor_save` nowhere."),
            source=SOURCE_NAMING_THE_OLD_TAG_ONLY_IN_A_COMMENT,
        )
        self.assertEqual(len(found), 1)
        self.assertIn("task_editor_save", found[0].message)

    def test_a_parameterised_tag_is_checked_by_its_family(self):
        """`note_action_edit` is `note_action_` plus a slug: the family is the declaration."""
        found = findings(
            skill("Seed, never type."),
            runbook("- **The preview's Edit action** is `note_action_edit`."),
        )
        self.assertEqual([f.message for f in found], [])

    def test_a_placeholder_names_the_family_before_it(self):
        found = findings(
            skill("Seed, never type."),
            runbook("- **A row is addressed** as `task_item_<slug>`."),
        )
        self.assertEqual([f.message for f in found], [])

    def test_a_backticked_word_of_prose_is_not_an_identifier(self):
        found = findings(
            skill("Seed, never type."),
            runbook("- **The editor autosaves** — saving does not pop it; `back` does."),
        )
        self.assertEqual([f.message for f in found], [])

    def test_a_behaviour_stated_twice_inside_the_section_is_reported_once(self):
        """A cross-reference inside one section is a second statement of one tag."""
        found = findings(
            skill("Seed, never type."),
            runbook(
                "- **A tag that went away** (`sheet_item_archive`) is gone.",
                "- **The same tag again**, for the reader who jumped here: "
                "`sheet_item_archive`.",
            ),
        )
        self.assertEqual(len(found), 1)

    def test_a_section_that_names_nothing_is_not_vacuous(self):
        found = findings(
            skill("Seed, never type."),
            runbook("No identifiers at all in this sentence."),
        )
        self.assertTrue(any("names no identifier" in f.message for f in found))


class NonVacuityTest(unittest.TestCase):
    """The scan has to have read something, or every rule above is vacuous."""

    def test_a_runbook_without_the_section_is_reported(self):
        found = findings(skill("Seed, never type."), runbook(section=False))
        self.assertTrue(
            any("App behaviour facts flows encode" in f.message for f in found)
        )

    def test_a_missing_skill_body_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            write(
                root,
                RUNBOOK,
                runbook("- **A save pops the editor** (`SavedAgendaScreen`)."),
            )
            write(root, "shared/TestTags.kt", SOURCE)
            found = mod.mismatches(root)
        self.assertTrue(any(SKILL in f.message for f in found))


class RepositoryTest(unittest.TestCase):
    """The pair and the source the repository actually has."""

    ROOT = pathlib.Path(__file__).resolve().parents[2]

    def test_the_repository_pair_is_clean(self):
        self.assertEqual([f.message for f in mod.mismatches(self.ROOT)], [])


if __name__ == "__main__":
    unittest.main()

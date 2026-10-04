package com.singularity.todo.feature.flows.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.SavedAgendaViewFactory
import com.singularity.todo.feature.agenda.domain.model.toSectionsJson
import com.singularity.todo.feature.agenda.domain.port.SavedAgendaViewsRepository
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.awaitTagGone
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.koin.core.Koin
import kotlin.time.Instant
import org.junit.jupiter.api.Tag

/**
 * The "add section" configurator: pick a section *type*, then — for the types
 * that mean nothing without values — pick the values.
 *
 * Before this existed the editor could only add seven parameterless types, which
 * is why the plan's known-gaps listed "selector parameters not configurable":
 * `Selector.Tags(emptySet())` matches no task, so the editor had no way to offer
 * a tag-, project-, priority- or status-filtered section at all.
 *
 * The mapping from chosen values to a [Selector] is covered by
 * `SelectorTemplateTest` in commonTest; this covers the UI that feeds it.
 */
@Tag("slow")
@OptIn(ExperimentalTestApi::class)
class SavedAgendaSelectorConfiguratorFlowTest {

    private val fixedNow = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val viewId = SavedAgendaViewId.fromString("v-cfg")

    /**
     * Seeds a tag under the *scoped* user, not a guessed id. `TagsRepository` is
     * user-scoped, so a tag stamped with the wrong owner is invisible to
     * `observeAll()` and the picker opens empty — which looks exactly like the
     * configurator being broken.
     */
    private suspend fun seedTag(koin: Koin, id: String, name: String) {
        val userId = koin.get<ProfileAwareCurrentUser>().scopedUserId.value
        koin.get<TagsRepository>().create(
            Tag(
                id = TagId(id),
                name = name,
                color = 0xFFE91E63.toInt(),
                createdAt = fixedNow,
                updatedAt = fixedNow,
                userId = userId,
            ),
        )
    }

    /** An empty view, so every section in it is one this test added. */
    private suspend fun createView(koin: Koin) {
        val definition = AgendaDefinition("Configurable", emptyList())
        koin.get<SavedAgendaViewsRepository>().upsert(
            SavedAgendaViewFactory.create(
                userId = UserId.anonymous,
                name = "Configurable",
                sectionsJson = definition.toSectionsJson(),
                now = fixedNow,
            ),
        )
    }

    /** Inbox tab → Saved Views → the card's overflow → Edit. */
    private fun androidx.compose.ui.test.DesktopComposeUiTest.openEditorForConfigurable() {
        tapTab("Inbox")
        awaitTag(TestTags.AGENDA_SAVED_VIEWS_BUTTON).performClick()
        assertTextDisplayed("Configurable")
        clickText("⋮")
        onAllNodes(hasText("Edit") and hasClickAction()).onFirst().performClick()
        awaitTag(TestTags.SAVED_AGENDA_NAME_INPUT).assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.DesktopComposeUiTest.openAddSectionSheet() {
        // The view under test has no sections, so the editor shows its empty
        // state and the button is labelled "Add first section". onNodeWithText is
        // exact-match, not substring.
        clickText("Add first section")
    }

    @Test
    fun `a parameterless type is added in one step`() = runDesktopAppTest(checkA11y = true) { koin ->
        createView(koin)
        openEditorForConfigurable()
        openAddSectionSheet()

        // "Due today" needs no values, so picking it adds the section directly —
        // no second sheet.
        onAllNodes(hasText("Due today") and hasClickAction()).onFirst().performClick()

        awaitTag(TestTags.SAVED_AGENDA_SAVE_BUTTON).assertIsDisplayed().performClick()
        awaitTagGone(TestTags.SAVED_AGENDA_NAME_INPUT)
    }

    @Test
    fun `a parameterized type opens a second step whose confirm is disabled until a value is chosen`() =
        runDesktopAppTest(checkA11y = true) { koin ->
            seedTag(koin, id = "tag-work", name = "work")
            createView(koin)
            openEditorForConfigurable()
            openAddSectionSheet()

            onAllNodes(hasText("By tag") and hasClickAction()).onFirst().performClick()

            // Step 2. The confirm button is disabled rather than silently doing
            // nothing — that was the failure this step exists to prevent.
            awaitTag(TestTags.agendaSelectorOption("tag-work")).assertIsDisplayed()
            awaitTag(TestTags.SAVED_AGENDA_ADD_SECTION_CONFIRM).assertIsNotEnabled()

            awaitTag(TestTags.agendaSelectorOption("tag-work")).performClick()
            awaitTag(TestTags.SAVED_AGENDA_ADD_SECTION_CONFIRM).assertIsEnabled()
        }

    @Test
    fun `the chosen value reaches the section, not just the picker`() = runDesktopAppTest(checkA11y = true) { koin ->
        seedTag(koin, id = "tag-work", name = "work")
        createView(koin)
        openEditorForConfigurable()
        openAddSectionSheet()

        onAllNodes(hasText("By tag") and hasClickAction()).onFirst().performClick()
        awaitTag(TestTags.agendaSelectorOption("tag-work")).performClick()
        awaitTag(TestTags.SAVED_AGENDA_ADD_SECTION_CONFIRM).performClick()

        // The section row renders the *resolved* selector's description, so this
        // is where a dropped or empty selection would show up: a section built
        // from Selector.Tags(emptySet()) would read "Any tag: 0", not "Any tag: 1".
        // The section row renders the description twice — as the section name
        // and as the row subtitle — so match the first, not "exactly one".
        onAllNodes(hasText("Any tag: 1")).onFirst().assertIsDisplayed()

        awaitTag(TestTags.SAVED_AGENDA_SAVE_BUTTON).assertIsDisplayed().performClick()
        awaitTagGone(TestTags.SAVED_AGENDA_NAME_INPUT)

        // And it survives the round trip: reopen and the section is still there.
        assertTextDisplayed("Configurable")
        clickText("⋮")
        onAllNodes(hasText("Edit") and hasClickAction()).onFirst().performClick()
        // The section row renders the description twice — as the section name
        // and as the row subtitle — so match the first, not "exactly one".
        onAllNodes(hasText("Any tag: 1")).onFirst().assertIsDisplayed()
    }
}

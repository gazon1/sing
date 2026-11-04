package com.singularity.todo.feature.agenda

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.agenda.domain.model.AgendaUiState
import com.singularity.todo.feature.agenda.domain.model.RenderedSection
import com.singularity.todo.feature.agenda.presentation.screen.AgendaContent
import com.singularity.todo.test.helpers.assertContentDescriptionDisplayed
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.countNodes
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The agenda list must survive a saved view whose sections share a display name,
 * and the section header's '+' button must stay on the row.
 *
 * Two sections of one saved view can carry the same name: a single-project
 * selector renders as "Project", a tag selector as "Any tag: N". The list used to
 * key its items by that name, so the second section repeated the first section's
 * key — `Key "header-Project" was already used` — and the whole screen was gone.
 * The key is the section's id, which is unique.
 *
 * The header's own tag follows the same rule: it is keyed by the section id, so a
 * selector for one section cannot match the other (#2197).
 *
 * The second defect is the header's own layout: the label claimed the whole row,
 * so a long section name pushed the '+' button past the right edge, where it was
 * neither visible nor tappable — and it must not take the badge with it.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class AgendaSectionKeyUiTest {
    @Test
    fun two_sections_with_the_same_name_both_render_under_their_own_tags() =
        runIsolatedComposeTest {
            setContent {
                AgendaContent(
                    state =
                        loaded(
                            section("section_0", "Project"),
                            section("section_1", "Project"),
                        ),
                    title = "Agenda",
                    onIntent = {},
                )
            }

            val first = TestTags.agendaSection("section_0")
            val second = TestTags.agendaSection("section_1")
            assertNotEquals(first, second, "two sections must not share a test tag")

            assertEquals(
                1,
                countNodes(first),
                "the first 'Project' section's tag must match one header, not both",
            )
            assertEquals(
                1,
                countNodes(second),
                "the second 'Project' section's tag must match one header, not both",
            )
            assertEquals(
                0,
                countNodes(TestTags.agendaSection("Project")),
                "the tag is keyed by the section id, so a name-keyed selector must " +
                    "not address either same-named header",
            )
        }

    /**
     * The button has to answer the tap, not merely exist: a button pushed past
     * the right edge is composed but unreachable, and a test that only looked for
     * the node would call that a pass.
     *
     * The name is the issue's own example — long enough to push the button off a
     * 360 px row, short enough not to be entirely ellipsized — and the section
     * carries a badge, which the row must keep visible.
     */
    @Test
    fun a_long_section_name_keeps_the_add_button_and_the_badge_on_the_row() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val intents = mutableListOf<AgendaIntent>()
            setContent {
                AgendaContent(
                    state = loaded(section("section_0", LONG_NAME, badge = 7)),
                    title = "Agenda",
                    onIntent = { intents += it },
                )
            }

            assertContentDescriptionDisplayed("Create in $LONG_NAME")

            onNodeWithText(BADGE.toString(), substring = true).assertIsDisplayed()

            clickContentDescription("Create in $LONG_NAME")

            assertEquals(
                listOf(AgendaIntent.CreateInSection("section_0")),
                intents,
                "the '+' button of a long-named section must stay on the row and answer the tap",
            )
        }

    private fun loaded(vararg sections: RenderedSection) =
        AgendaUiState.Loaded(
            sections = persistentListOf(*sections),
            today = TODAY,
        )

    private fun section(
        id: String,
        name: String,
        badge: Int? = null,
    ) = RenderedSection(
        id = id,
        name = name,
        tasks = persistentListOf(),
        badge = badge,
    )

    private companion object {
        val TODAY: LocalDate = LocalDate(2026, 10, 15)

        /**
         * The section name the issue reports: long enough that the old label took
         * the whole row and pushed the '+' button off a 360 px phone, and short
         * enough that it is not entirely ellipsized.
         */
        const val LONG_NAME = "Status: Active, Completed, Cancelled, Deferred, Waiting"

        /** The badge count the row must keep visible next to that name. */
        const val BADGE = 7
    }
}

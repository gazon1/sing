package com.singularity.todo.feature.agenda

import androidx.compose.ui.test.ExperimentalTestApi
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
 * The second defect is the header's own layout: the label claimed the whole row,
 * so a long section name pushed the '+' button past the right edge, where it was
 * neither visible nor tappable.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
@Tag("slow:compose")
class AgendaSectionKeyUiTest {
    @Test
    fun two_sections_with_the_same_name_both_render() =
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

            assertEquals(
                2,
                countNodes(TestTags.agendaSection("Project")),
                "two sections with the same name are two headers — the list must not " +
                    "take the second one for a repeated key",
            )
        }

    /**
     * The button has to answer the tap, not merely exist: a button pushed past
     * the right edge is composed but unreachable, and a test that only looked for
     * the node would call that a pass.
     */
    @Test
    fun a_long_section_name_keeps_the_add_button_on_the_row() =
        runIsolatedComposeTest(width = 360, height = 800) {
            val intents = mutableListOf<AgendaIntent>()
            setContent {
                AgendaContent(
                    state = loaded(section("section_0", LONG_NAME)),
                    title = "Agenda",
                    onIntent = { intents += it },
                )
            }

            assertContentDescriptionDisplayed("Create in $LONG_NAME")

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
    ) = RenderedSection(
        id = id,
        name = name,
        tasks = persistentListOf(),
    )

    private companion object {
        val TODAY: LocalDate = LocalDate(2026, 10, 15)

        /**
         * A name long enough to push the '+' button off a 360 px row on its own —
         * the width of a phone.
         */
        const val LONG_NAME =
            "Status: Active, Completed, Cancelled, Deferred, Waiting, Blocked, " +
                "Recurring, Repeated, Someday, Never, Later, Overdue, Archived"
    }
}

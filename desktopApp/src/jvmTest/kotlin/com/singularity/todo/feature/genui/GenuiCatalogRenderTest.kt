@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.genui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.genui.schema.UiPath
import com.singularity.todo.feature.genui.surface.SurfaceId
import com.singularity.todo.test.helpers.GenuiPress
import com.singularity.todo.test.helpers.GenuiSurfaceId
import com.singularity.todo.test.helpers.runGenuiRenderTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Does a generated surface actually draw?
 *
 * The rest of the GenUI suite proves a message parses, validates and lands in the store. None of
 * that says anything appears on screen, and the two halves fail in opposite directions: a component
 * whose renderer draws an empty box passes every store-level test and shows the user a blank card.
 *
 * The fixtures are the corpus's own screens — the same JSON `GenuiSurfaceCorpusTest` plays through
 * the store — so the two suites cover one surface from both ends and cannot drift apart about what
 * the app asks a model for.
 */
@Tag("slow")
class GenuiCatalogRenderTest {

    @Test
    fun aTaskListDrawsItsTaskCards() = runGenuiRenderTest(TASK_LIST) {
        // Tagged by the title each card resolved, so this asserts both cards drew *and* that each
        // one drew the right task.
        nodeTagged(cardTag("Buy_groceries")).assertExists()
        nodeTagged(cardTag("Send_the_invoice")).assertExists()
    }

    /**
     * The title arrives after the components that bind it — the ordering a streaming response has —
     * so this also asserts the templates resolved against a data model that was empty at parse time.
     */
    @Test
    fun theTitleTheDataModelCarriesIsTheTitleOnScreen() = runGenuiRenderTest(TASK_LIST) {
        assertEquals(JsonPrimitive("Buy groceries"), valueAt("/t/0/title"))
        nodeTagged(cardTag("Buy_groceries")).assertExists("The card is tagged by the title it resolved")
    }

    /**
     * A press reaches the screen as the named action.
     *
     * This step was wired to nothing for a while: the card looked pressable and the tap went into a
     * lambda that discarded it. Asserting the *name* rather than "something happened" keeps the
     * action identifiable, which is the only reason a surface's control carries one.
     */
    @Test
    fun pressingATaskCardReportsTheActionItWasBoundTo() = runGenuiRenderTest(TASK_LIST) {
        nodeTagged(cardTag("Buy_groceries")).performClick()

        assertEquals(1, presses.size, "The press reached the layer: $presses")
        val press: GenuiPress = presses.first()
        assertEquals("open_task", press.name, "Named by the model, not invented by the client")
        assertEquals(SURFACE_ID, press.surfaceId)
    }

    /**
     * Editing a field writes the data model *and* says so.
     *
     * The two halves are separate obligations: the write is what a later `updateComponents` reads,
     * and the notification is how a screen learns there is something to persist. A binding that only
     * writes is invisible to the user; one that only notifies is a lie.
     */
    @Test
    fun typingInAFieldWritesTheDataModelAndAnnouncesIt() = runGenuiRenderTest(ADD_TASK_FORM) {
        node("textfield_What_needs_doing?").performTextClearance()
        node("textfield_What_needs_doing?").performTextInput("Ship it")

        assertEquals(JsonPrimitive("Ship it"), valueAt("/draft/title"), "The model holds what was typed")
        // Clearing and typing are two edits, so two announcements — what matters is that each one
        // arrived at the path the field is bound to, not that there was a single one.
        assertTrue(writes.isNotEmpty(), "The screen is told: $writes")
        assertTrue(
            writes.all { it.path == UiPath.parse("/draft/title") },
            "Every announcement names the bound path: $writes",
        )
    }

    /** A field shows what an earlier message wrote, rather than falling back to its own initial. */
    @Test
    fun aFieldShowsTheValueAnEarlierMessageSent() = runGenuiRenderTest(ADD_TASK_FORM) {
        node("textfield_What_needs_doing?").assertTextContains("Water the plants")
    }

    /**
     * A surface draws before its data arrives.
     *
     * The components are created first and the data follows, always — a surface that refused to
     * compose until both arrived would flicker, and a renderer that read the data model without
     * observing it would show nothing at all.
     */
    @Test
    fun aSurfaceDrawsBeforeItsDataArrives() = runGenuiRenderTest(listOf(TASK_LIST.first())) {
        nodes("task_card").assertCountEquals(2)
        assertTrue(presses.isEmpty(), "Nothing was pressed")
    }

    /**
     * A form round trip: type into fields, press submit, and the payload carries what was typed.
     *
     * This is the case the payload had no way to express before. The values a user produces exist
     * in the surface's data model and nowhere else — a model cannot write them, because it never
     * saw them — so a button whose payload was passed through verbatim could only ever submit what
     * the model already knew, which makes "fill it in and send it" impossible at any level of
     * catalog.
     */
    @Test
    fun aSubmitCarriesWhatTheUserTyped() = runGenuiRenderTest(SUBMITTABLE_FORM) {
        node("textfield_Title").performTextClearance()
        node("textfield_Title").performTextInput("Ship it")
        node("textfield_When").performTextClearance()
        node("textfield_When").performTextInput("tomorrow")
        node("btn_Add").performClick()

        assertEquals(1, presses.size, "One press: $presses")
        val press: GenuiPress = presses.first()
        assertEquals("create_task", press.name)
        val payload: JsonObject = press.data ?: error("The payload is what the form exists to send")
        assertEquals(JsonPrimitive("Ship it"), payload["title"])
        assertEquals(JsonPrimitive("tomorrow"), payload["when"])
    }

    /** A literal payload with no templates in it is passed through unchanged. */
    @Test
    fun aPayloadWithoutTemplatesIsUntouched() = runGenuiRenderTest(SUBMITTABLE_FORM) {
        node("btn_Fixed").performClick()

        assertEquals(JsonPrimitive("carried"), presses.single().data?.get("kind"))
    }

    private companion object {
        /** The tag a task card carries for [title]; the renderer builds it from the title it drew. */
        fun cardTag(title: String): String = TestTags.genUi("task_card_$title")

        /** The corpus screen, unchanged. */
        val TASK_LIST: List<String> = listOf(
            """{"createSurface":{"surfaceId":"s1","rootId":"root","components":[""" +
                """{"id":"root","kind":"column","children":["head","list"]},""" +
                """{"id":"head","kind":"heading","text":"Today","level":2},""" +
                """{"id":"list","kind":"list","children":["c1","c2"],"direction":"Vertical"},""" +
                """{"id":"c1","kind":"task_card","title":"${'$'}{/t/0/title}","action":"open_task"},""" +
                """{"id":"c2","kind":"task_card","title":"${'$'}{/t/1/title}"}]}}""",
            """{"updateData":{"surfaceId":"s1","path":"/t/0/title","value":"Buy groceries"}}""",
            """{"updateData":{"surfaceId":"s1","path":"/t/1/title","value":"Send the invoice"}}""",
        )

        val ADD_TASK_FORM: List<String> = listOf(
            """{"createSurface":{"surfaceId":"form","rootId":"root","components":[""" +
                """{"id":"root","kind":"column","children":["title","submit"]},""" +
                """{"id":"title","kind":"text_field","label":"What needs doing?","path":"/draft/title"},""" +
                """{"id":"submit","kind":"button","label":"Add","action":"create_task"}]}}""",
            """{"updateData":{"surfaceId":"form","path":"/draft/title","value":"Water the plants"}}""",
        )

        /**
         * A form a user can actually fill in and submit.
         *
         * The fields bind to paths under /draft; the buttons read those same paths in their payloads.
         * The
         * screen never had to know what a form is — it draws two fields and two buttons, and the
         * wiring is entirely in the model's messages.
         */
        val SUBMITTABLE_FORM: List<String> = listOf(
            """{"createSurface":{"surfaceId":"form","rootId":"root","components":[""" +
                """{"id":"root","kind":"column","children":["t","w","add","fixed"]},""" +
                """{"id":"t","kind":"text_field","label":"Title","path":"/draft/title"},""" +
                """{"id":"w","kind":"text_field","label":"When","path":"/draft/when"},""" +
                """{"id":"add","kind":"button","label":"Add","action":"create_task",""" +
                """"data":{"title":"${'$'}{/draft/title}","when":"${'$'}{/draft/when}"}},""" +
                """{"id":"fixed","kind":"button","label":"Fixed","action":"noop",""" +
                """"data":{"kind":"carried"}}]}}""",
        )

        val SURFACE_ID: SurfaceId = GenuiSurfaceId
    }
}

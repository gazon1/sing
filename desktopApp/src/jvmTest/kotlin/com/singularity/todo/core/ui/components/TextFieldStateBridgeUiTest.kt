package com.singularity.todo.core.ui.components

import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clearTag
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * The state-based text field and the string a ViewModel keeps, side by side.
 *
 * The migration moved every text field in the app to the state-based API, and this
 * is the seam that made it possible: the field owns its text, the ViewModel owns a
 * `String`, and a bridge keeps the two in agreement. The bridge is where a
 * keystroke can be lost or a value can be duplicated, so its contract is pinned
 * here rather than only in the screens that use it.
 *
 * These are the directions the contract has to hold in:
 *
 * 1. The field shows what the user typed.
 * 2. A value the upstream rewrote from the outside reaches the field.
 * 3. The field shows the value the upstream started with — a field that started
 *    empty and pushed its emptiness upstream would wipe what was loaded.
 * 4. A value the user cleared reaches the upstream.
 * 5. The value the field pushed is not written back to it.
 * 6. An upstream that clears its value clears the field — the direction the chat
 *    screen needs when it empties its input after a message is sent. That case is
 *    covered here rather than on the chat screen itself: sending a message in the
 *    desktop harness starts a real model call, and the Compose clock never goes
 *    idle while that call is in flight.
 * 7. An upstream that lags behind does not overwrite the field.
 * 8. The edit observer fires on edits, and not on the field's seed.
 * 9. An anchored field follows the value it is anchored to.
 * 10. An upstream that *owns* the value rewrites it even when the value is one the
 *     field produced — the case a static upstream cannot express.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class TextFieldStateBridgeUiTest {

    @Test
    fun the_field_shows_what_the_user_typed() = runIsolatedComposeTest {
        setContent { BridgeField(Upstream()) }

        awaitTag(FIELD).performTextInput("Buy milk")

        assertTagTextEquals(FIELD, "Buy milk")
    }

    @Test
    fun the_field_shows_the_value_the_upstream_rewrote() = runIsolatedComposeTest {
        val upstream = Upstream(initial = "first")
        setContent { BridgeField(upstream) }

        assertTagTextEquals(FIELD, "first")
        upstream.rewrite("second")
        waitForIdle()

        assertTagTextEquals(FIELD, "second")
    }

    @Test
    fun the_field_starts_with_the_value_the_upstream_already_holds() = runIsolatedComposeTest {
        val upstream = Upstream(initial = "loaded title")
        setContent { BridgeField(upstream) }

        // The upstream must not be wiped: the field's first emission is its seed, and
        // an empty field that pushed that emptiness upstream would clear a value the
        // ViewModel had already loaded.
        assertTagTextEquals(FIELD, "loaded title")
        assert(upstream.value == "loaded title") {
            "the upstream must still hold the value it started with, got: ${upstream.value}"
        }
    }

    @Test
    fun clearing_the_field_reaches_the_upstream() = runIsolatedComposeTest {
        val upstream = Upstream(initial = "Buy milk")
        setContent { BridgeField(upstream) }

        clearTag(FIELD)
        waitForIdle()

        assertTagTextEquals(FIELD, "")
        assert(upstream.value == "") {
            "a cleared field must reach the upstream, got: ${upstream.value}"
        }
    }

    @Test
    fun the_upstream_is_not_written_back_the_value_the_field_pushed() = runIsolatedComposeTest {
        val upstream = Upstream()
        setContent { BridgeField(upstream) }

        awaitTag(FIELD).performTextInput("abc")
        waitForIdle()

        // The upstream echoing the field's own value back is the case the pull guard
        // exists for. It must not re-enter the field: a re-entry moves the cursor to
        // the end of the text, and a field whose cursor jumps while the user types is
        // a field that eats the next keystroke.
        assert(upstream.pushes == 1) {
            "the field's own value must not be pushed back a second time, pushes: ${upstream.pushes}"
        }
        assertTagTextEquals(FIELD, "abc")
    }

    @Test
    fun an_upstream_that_clears_its_value_clears_the_field() = runIsolatedComposeTest {
        val upstream = Upstream(initial = "What is on my agenda?")
        setContent { BridgeField(upstream) }

        assertTagTextEquals(FIELD, "What is on my agenda?")

        // This is what the chat screen does when a message is sent: the ViewModel
        // clears its copy of the input, and the field has to show the cleared value.
        // A field that kept the old text would let the user send the same message
        // twice by accident — and, in the other direction, a field that ignored the
        // upstream would keep showing text the app no longer holds.
        upstream.rewrite("")
        waitForIdle()

        assertTagTextEquals(FIELD, "")
    }

    @Test
    fun an_upstream_that_lags_behind_does_not_overwrite_the_field() = runIsolatedComposeTest {
        val upstream = Upstream()
        setContent { BridgeField(upstream) }

        awaitTag(FIELD).performTextInput("abc")
        waitForIdle()
        awaitTag(FIELD).performTextReplacement("a")
        waitForIdle()
        assertTagTextEquals(FIELD, "a")

        // The upstream is behind: it hands back "abc", a value the field pushed a moment
        // ago and no longer holds. Applying it would undo the user's latest edit with a
        // value the app already moved past.
        upstream.rewrite("abc")
        waitForIdle()

        assertTagTextEquals(FIELD, "a")
    }

    @Test
    fun the_edit_observer_fires_on_edits_and_not_on_the_seed() = runIsolatedComposeTest {
        val edits = mutableListOf<String>()
        setContent { ObservedField(edits) }

        // The seed is not an edit: a screen that cleared its error on the seed would
        // clear an error the user has not touched yet.
        waitForIdle()
        assert(edits.isEmpty()) { "the field's seed must not count as an edit, got: $edits" }

        awaitTag(FIELD).performTextInput("x")
        waitForIdle()

        assert(edits.isNotEmpty()) { "an edit must be reported, got: $edits" }
    }

    @Test
    fun an_anchored_field_follows_the_value_it_is_anchored_to() = runIsolatedComposeTest {
        var value by mutableStateOf("first")
        setContent { AnchoredField(value) }

        assertTagTextEquals(FIELD, "first")

        // The anchored value changes under the field — a reloaded provider, a changed
        // setting — and the field has to show the new value.
        value = "second"
        waitForIdle()

        assertTagTextEquals(FIELD, "second")
    }

    @Test
    fun the_last_edit_reaches_the_upstream_when_the_field_leaves_the_composition() =
        runIsolatedComposeTest {
            val upstream = Upstream()
            var visible by mutableStateOf(true)
            setContent {
                if (visible) {
                    BridgeField(upstream)
                }
            }

            awaitTag(FIELD).performTextInput("Buy milk")
            // The field leaves the composition. The push is asynchronous — it lands on
            // the next frame — so the bridge flushes the pending edit on the way out.
            visible = false
            waitForIdle()

            assert(upstream.value == "Buy milk") {
                "the last edit must reach the upstream even though the field is gone, " +
                    "got: ${upstream.value}"
            }
        }

    @Test
    fun a_discarded_draft_is_not_resurrected_when_the_field_leaves_the_composition() =
        runIsolatedComposeTest {
            val upstream = Upstream()
            var visible by mutableStateOf(true)
            setContent {
                if (visible) {
                    BridgeField(upstream)
                }
            }

            awaitTag(FIELD).performTextInput("Buy milk")
            waitForIdle()

            // The upstream discards the draft from the outside — a cancelled dialog, a
            // reset form — and the field leaves in the same pass. The text on screen is
            // the text that was thrown away, and the flush must not push it back.
            upstream.rewrite("")
            visible = false
            waitForIdle()

            assert(upstream.value == "") {
                "a discarded draft must not come back, got: ${upstream.value}"
            }
        }

    @Test
    fun an_owned_upstream_rewrites_a_value_the_field_produced() = runIsolatedComposeTest {
        val upstream = OwnedUpstream()
        setContent { OwnedBridgeField(upstream) }

        awaitTag(FIELD).performTextInput("abc")
        waitForIdle()
        awaitTag(FIELD).performTextReplacement("a")
        waitForIdle()
        assertTagTextEquals(FIELD, "a")

        // The upstream replaces the field's text with "abc" — a value the field
        // produced a moment ago and no longer holds. From the field's side that is
        // indistinguishable from a lagging upstream, and the static guard drops it
        // (see the test above). The owned source carries the count of the
        // replacements, so the guard is lifted and the rewrite lands.
        upstream.rewrite("abc")
        waitForIdle()

        assertTagTextEquals(FIELD, "abc")
    }

    /**
     * The upstream a bridge talks to: a plain string plus a counter of the writes
     * the field made to it. The string is snapshot state, so a rewrite from the
     * outside recomposes the field the way a ViewModel rewrite does.
     */
    private class Upstream(initial: String = "") {
        var value: String by mutableStateOf(initial)

        var pushes: Int = 0

        /**
         * The value as the field's source.
         *
         * The upstream only loads the value or clears it, so it never replaces the field's text
         * with one the field produced — the static shape is the honest one here, and there is no
         * counter to keep.
         */
        val source: StaticText get() = StaticText(value)

        /** Replaces the value from the outside, the way a ViewModel rewrite does. */
        fun rewrite(text: String) {
            value = text
        }
    }

    /**
     * An upstream that *owns* the value: it may replace the field's text with anything,
     * including a value the field produced, and it counts the replacements.
     *
     * The count moves only for a replacement from the outside. The field's own push is
     * the upstream taking the value, not the upstream rewriting it — the same rule the
     * app's drafts follow.
     */
    private class OwnedUpstream(initial: String = "") {
        var value: String by mutableStateOf(initial)

        var revision: Long by mutableStateOf(0L)

        /** The value as the field's source; the count of the replacements travels with it. */
        val source: OwnedText get() = OwnedText(value, revision)

        /** The field's own write: the upstream takes the value, and the count does not move. */
        fun push(text: String) {
            value = text
        }

        /** A replacement from the outside: the count moves, so the bridge knows it is a rewrite. */
        fun rewrite(text: String) {
            value = text
            revision += 1
        }
    }

    /** A field bound to [upstream] through the shared bridge helper. */
    @Composable
    private fun BridgeField(upstream: Upstream) {
        val state = rememberBridgedTextFieldState(
            source = upstream.source,
            onTextChanged = { newText ->
                upstream.pushes++
                upstream.rewrite(newText)
            },
        )
        OutlinedTextField(
            state = state,
            modifier = Modifier.testTag(FIELD),
        )
    }

    /** A field bound to an [OwnedUpstream] through the shared bridge helper. */
    @Composable
    private fun OwnedBridgeField(upstream: OwnedUpstream) {
        val state = rememberBridgedTextFieldState(
            source = upstream.source,
            onTextChanged = { newText -> upstream.push(newText) },
        )
        OutlinedTextField(
            state = state,
            modifier = Modifier.testTag(FIELD),
        )
    }

    /** A local field that reports every edit to [edits]. */
    @Composable
    private fun ObservedField(edits: MutableList<String>) {
        val state = androidx.compose.foundation.text.input.rememberTextFieldState()
        ObserveTextFieldEdits(state) { edits += "edit" }
        OutlinedTextField(
            state = state,
            modifier = Modifier.testTag(FIELD),
        )
    }

    /** A field that only shows [value] and cannot be edited. */
    @Composable
    private fun AnchoredField(value: String) {
        val state = rememberAnchoredTextFieldState(value)
        OutlinedTextField(
            state = state,
            readOnly = true,
            modifier = Modifier.testTag(FIELD),
        )
    }

    private companion object {
        const val FIELD = "bridge_field"
    }
}

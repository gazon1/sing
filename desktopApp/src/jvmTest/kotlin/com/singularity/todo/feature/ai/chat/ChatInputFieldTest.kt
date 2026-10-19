package com.singularity.todo.feature.ai.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import co.touchlab.kermit.Logger
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.fakes.FakeIdGenerator
import com.singularity.todo.test.fakes.FakeTextGen
import com.singularity.todo.test.helpers.ScreenWithOwners
import com.singularity.todo.test.helpers.assertTagTextEquals
import com.singularity.todo.test.helpers.awaitTagTextEquals
import com.singularity.todo.test.helpers.awaitText
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.typeIntoTag
import org.junit.jupiter.api.Tag
import kotlin.test.Test

/**
 * The chat input field, and the one direction a field-level test cannot reach.
 *
 * The chat screen is the app's clearest case of the *app state → field* direction: when
 * a message is sent, the ViewModel empties its copy of the input, and the field has to
 * show that emptiness. A field that kept the text would let the user send the same
 * message twice by accident, and a field that ignored the upstream would keep showing
 * text the app no longer holds.
 *
 * This test needs a model, and a real one is not available: the JVM binds
 * [com.singularity.todo.feature.ai.KoogAgentService], which would reach for a
 * configured provider and — worse — leave a call in flight, so the Compose clock never
 * goes idle. The fake is bound in its place, and that binding is what makes the screen
 * testable at all: the reply is instant and local.
 *
 * The screen is driven through [ChatScreenContent] rather than navigated to: the chat
 * route is not part of the app's navigation graph — `AppDestination.AiChat` exists and
 * the menu offers it, but no screen is bound to it — so there is no way into it from a
 * flow test. The content composable takes the ViewModel as a parameter, which is the
 * house pattern for exactly this case.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class ChatInputFieldTest {

    @Test
    fun the_typed_text_reaches_the_view_model() = runIsolatedComposeTest {
        setContent { ChatScreenUnderTest() }

        typeIntoTag(TestTags.CHAT_INPUT, "What is on my agenda?")

        // The field shows what was typed ...
        assertTagTextEquals(TestTags.CHAT_INPUT, "What is on my agenda?")

        // ... and the ViewModel received it: the reply only happens for a turn that
        // carries a question. A text that stayed in the field would leave the ViewModel
        // with an empty input, the turn would end before it started, and the screen
        // would answer nothing.
        clickContentDescription("Send")
        awaitText(REPLY)
    }

    @Test
    fun sending_a_message_empties_the_field() = runIsolatedComposeTest {
        setContent { ChatScreenUnderTest() }

        typeIntoTag(TestTags.CHAT_INPUT, "What is on my agenda?")
        assertTagTextEquals(TestTags.CHAT_INPUT, "What is on my agenda?")

        clickContentDescription("Send")

        // The ViewModel emptied its copy of the input when the message went out, and the
        // field followed it. A field that kept the text would send the same message
        // twice on the next Enter.
        awaitTagTextEquals(TestTags.CHAT_INPUT, "")
    }

    /**
     * The chat screen with a local model behind it.
     *
     * The ViewModel is constructed here rather than resolved from Koin: the screen's
     * public entry point is a Koin wrapper, and the content composable below it takes
     * the ViewModel as a parameter. The fake model is what keeps the test from reaching
     * for a provider.
     */
    @Composable
    private fun ChatScreenUnderTest() {
        val viewModel = ChatViewModel(
            log = Logger.withTag("ChatInputFieldTest"),
            agent = FakeTextGen(success = REPLY),
            idGen = FakeIdGenerator("msg"),
        )
        ScreenWithOwners {
            ChatScreenContent(vm = viewModel)
        }
    }

    private companion object {
        /** What the fake model answers. */
        const val REPLY = "You have three tasks due today."
    }
}

package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A tap on a link opens it; a tap on ordinary text reaches the field's owner.
 *
 * The detail renders the description with `RichText` and puts a `clickable` on
 * the same node to enter editing. The library's link handler consumes the tap on
 * a link, and everything else must fall through to the parent — otherwise either
 * every link opens the editor or the whole description stops being editable.
 *
 * This pins the boundary the document-style detail depends on. If the parent
 * starts swallowing link taps, the test fails here rather than on a device.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("fast")
class RichTextLinkTapTest {
    @Test
    fun `a link tap opens the link and a text tap reaches the parent`() =
        runIsolatedComposeTest {
            var opened: String? = null
            var parentClicks = 0
            val handler =
                object : UriHandler {
                    override fun openUri(uri: String) {
                        opened = uri
                    }
                }

            setContent {
                SingularityTheme {
                    val state =
                        remember {
                            RichTextState().apply {
                                setHtml("""<p><a href="https://example.com">link</a> and text</p>""")
                            }
                        }
                    CompositionLocalProvider(LocalUriHandler provides handler) {
                        RichText(
                            state = state,
                            modifier =
                                Modifier
                                    .testTag(TAG)
                                    .clickable { parentClicks++ },
                        )
                    }
                }
            }

            // The link is at the start of the line, the ordinary text after it.
            onNodeWithTag(TAG).performTouchInput { click(Offset(4f, center.y)) }
            runOnIdle {
                assertEquals("https://example.com", opened, "the link tap did not open the link")
                assertEquals(0, parentClicks, "the link tap also entered editing")
            }

            onNodeWithTag(TAG).performTouchInput { click(Offset(width - 4f, center.y)) }
            runOnIdle {
                assertEquals(1, parentClicks, "a tap on ordinary text did not reach the parent")
            }
        }

    private companion object {
        const val TAG = "rich-description"
    }
}

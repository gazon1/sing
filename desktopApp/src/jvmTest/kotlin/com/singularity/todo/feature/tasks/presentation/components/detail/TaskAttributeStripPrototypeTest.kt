package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.text.OwnedText
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.TouchTarget
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.state.TaskEditorSheet
import com.singularity.todo.test.helpers.assertNodeDisplayed
import com.singularity.todo.test.helpers.assertTagDisplayed
import com.singularity.todo.test.helpers.assertTagNotExists
import com.singularity.todo.test.helpers.assertTouchTargetHeight
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.unclippedBoundsOfTag
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.jupiter.api.Tag
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * PROTOTYPE (#2736): the full-row attribute layout against the compact chip strip.
 *
 * Both variants mount the real [TaskEditorContent] with the same model and the same
 * callbacks at the same window size, so the only difference between a `rows` and a
 * `chips` capture is the presentation. The window is the first viewport at phone width
 * (360 dp) and at a recorded wider width (600 dp); nothing is cropped to a component.
 *
 * Captures land in `build/diagnostics/strip-prototype/`, which is git-ignored, and the
 * verdict is recorded in `docs/decisions/2026-11-10-*.md`. Regenerate with:
 *
 * ```
 * ./gw :desktopApp:test --tests '*TaskAttributeStripPrototypeTest'
 * ```
 *
 * The desktop test density is 1, so a window width in pixels is a width in dp — the
 * same convention as `TaskDetailFoldFlowTest`.
 */
@Tag("slow")
@Tag("slow:compose")
@OptIn(ExperimentalTestApi::class)
class TaskAttributeStripPrototypeTest {
    @Test
    fun `the 360 dp comparison`() = captureComparison(width = PHONE_WIDTH_DP)

    @Test
    fun `the 600 dp comparison`() = captureComparison(width = WIDE_WIDTH_DP)

    /**
     * Every chip is one tap, the size the app requires, and inside the window — the
     * three acceptance checks that a screenshot cannot make.
     */
    @Test
    fun `the chips are one-tap, 48 dp tall and stay inside a phone screen`() =
        runIsolatedComposeTest(width = PHONE_WIDTH_DP, height = VIEWPORT_HEIGHT_DP) {
            setContent {
                editor(state = AttributeState.LongNames, compact = true)
            }

            listOf(
                TestTags.TASK_EDITOR_DUE_ROW,
                TestTags.TASK_EDITOR_PRIORITY_ROW,
                TestTags.TASK_EDITOR_PROJECT_ROW,
                TestTags.TASK_EDITOR_TAGS_ROW,
            ).forEach { tag ->
                assertTagDisplayed(tag)
                // The visual chip is 32 dp; the touch target height is the 48 dp floor.
                // The two are different measurements, and only the second one is the
                // requirement.
                assertTouchTargetHeight(tag, TouchTarget.MinSize)
                assertTrue(
                    unclippedBoundsOfTag(tag).right <= PHONE_WIDTH_DP.dp,
                    "the chip at $tag ends past the ${PHONE_WIDTH_DP}dp window",
                )
                // One tap: the chip is a clickable control, not a label. Its callback is
                // the row's — the sheet path is covered where the sheet has a Koin graph.
                assertNodeDisplayed(
                    hasTestTag(tag) and hasClickAction(),
                    "chip $tag is tappable",
                )
            }
        }

    /**
     * The strip keeps the rows' visibility contract: no project callbacks, no project
     * chip; no tag callbacks, no tags chip. A chip that opens nothing is worse than a
     * missing chip — the same rule the rows follow.
     */
    @Test
    fun `the strip hides the attributes the editor hides`() =
        runIsolatedComposeTest(width = PHONE_WIDTH_DP, height = VIEWPORT_HEIGHT_DP) {
            setContent {
                TaskEditorContent(
                    model = model(AttributeState.Populated),
                    titleSource = TITLE,
                    descriptionSource = OwnedText(text = "", revision = 0),
                    onTitleChange = {},
                    onDescriptionChange = {},
                    callbacks = callbacks().copy(project = null, tags = null),
                    now = NOW,
                    sheets = rememberDialogState<TaskEditorSheet>(),
                    compactAttributes = true,
                )
            }

            assertTagNotExists(TestTags.TASK_EDITOR_PROJECT_ROW)
            assertTagNotExists(TestTags.TASK_EDITOR_TAGS_ROW)
            assertTagDisplayed(TestTags.TASK_EDITOR_PRIORITY_ROW)
            assertTagDisplayed(TestTags.TASK_EDITOR_DUE_ROW)
        }

    private fun captureComparison(width: Int) =
        runIsolatedComposeTest(width = width, height = VIEWPORT_HEIGHT_DP) {
            val state = mutableStateOf(AttributeState.Unset)
            val compact = mutableStateOf(false)
            setContent {
                TaskEditorContent(
                    model = model(state.value),
                    titleSource = TITLE,
                    descriptionSource = OwnedText(text = "", revision = 0),
                    onTitleChange = {},
                    onDescriptionChange = {},
                    callbacks = callbacks(),
                    now = NOW,
                    sheets = rememberDialogState<TaskEditorSheet>(),
                    compactAttributes = compact.value,
                )
            }

            AttributeState.entries.forEach { case ->
                listOf(false to "rows", true to "chips").forEach { (isCompact, variant) ->
                    runOnIdle {
                        state.value = case
                        compact.value = isCompact
                    }
                    waitForIdle()
                    capture("${case.slug}-$variant-${width}dp")
                }
            }
        }

    private suspend fun DesktopComposeUiTest.capture(name: String) {
        val file = File("build/diagnostics/strip-prototype/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(captureToImage().toAwtImage(), "png", file)
        println("strip prototype: ${file.absolutePath}")
    }

    @Composable
    private fun editor(
        state: AttributeState,
        compact: Boolean,
    ) = TaskEditorContent(
        model = model(state),
        titleSource = TITLE,
        descriptionSource = OwnedText(text = "", revision = 0),
        onTitleChange = {},
        onDescriptionChange = {},
        callbacks = callbacks(),
        now = NOW,
        sheets = rememberDialogState<TaskEditorSheet>(),
        compactAttributes = compact,
    )

    private fun model(state: AttributeState): TaskEditorModel =
        when (state) {
            AttributeState.Unset -> {
                TaskEditorModel()
            }

            AttributeState.Populated -> {
                TaskEditorModel(
                    priority = TaskPriority.High,
                    dueDate = DUE,
                    dueTime = LocalTime(14, 30),
                    project = PROJECT,
                    projectName = "Roadmap",
                    tags = persistentListOf(TagId("t1"), TagId("t2")),
                    tagNames = persistentListOf("Work", "Home"),
                )
            }

            AttributeState.LongNames -> {
                TaskEditorModel(
                    priority = TaskPriority.Medium,
                    dueDate = DUE,
                    project = PROJECT,
                    projectName = "Very long project name for the redesign",
                    tags = persistentListOf(TagId("t1"), TagId("t2")),
                    tagNames =
                        persistentListOf(
                            "extremely-long-tag-name-that-keeps-going",
                            "another-long-tag-name",
                        ),
                )
            }

            AttributeState.ManyTags -> {
                TaskEditorModel(
                    priority = TaskPriority.Urgent,
                    dueDate = DUE,
                    dueTime = LocalTime(9, 0),
                    project = PROJECT,
                    projectName = "Personal",
                    tags = persistentListOf(*Array(6) { TagId("t$it") }),
                    tagNames =
                        persistentListOf("Work", "Home", "Errands", "Health", "Finance", "Reading"),
                )
            }
        }

    private fun callbacks() =
        TaskEditorCallbacks(
            onBack = {},
            onCheckToggle = null,
            priority = RowCallbacks(onChange = { _ -> }),
            dueDate = DateRowCallbacks(onChangeDate = { _ -> }, onChangeTime = { _ -> }),
            startDate = null,
            project = RowCallbacks(onChange = { _ -> }),
            tags = RowCallbacks(onChange = { _ -> }),
            recurrence = null,
            pin = null,
            dependencies = null,
            checklist = null,
            attachments = null,
            estimate = null,
            onTimeEntryAdd = null,
            onTimeEntrySave = null,
        )

    private enum class AttributeState(val slug: String) {
        Unset("unset"),
        Populated("populated"),
        LongNames("long-names"),
        ManyTags("many-tags"),
    }

    private companion object {
        /** Mid-month, so no label straddles a boundary. */
        val NOW: Instant = Instant.parse("2026-11-10T12:00:00Z")
        val DUE: LocalDate = LocalDate(2026, 11, 12)
        val PROJECT: ProjectId = ProjectId.fromString("P-1eea7616-fb58-41e1-8ea5-2ab8d1819c81")
        val TITLE: OwnedText = OwnedText(text = "Redesign the settings screen", revision = 0)

        /** 360 x 800 dp — a 1080x2400 phone at 3x. */
        const val PHONE_WIDTH_DP = 360

        /** The recorded wider width for the comparison. */
        const val WIDE_WIDTH_DP = 600

        const val VIEWPORT_HEIGHT_DP = 800
    }
}

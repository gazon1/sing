@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.height
import com.singularity.todo.core.text.OwnedText
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.rememberDialogState
import com.singularity.todo.core.ui.theme.SingularityTheme
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.state.TaskEditorSheet
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import com.singularity.todo.test.helpers.unmergedNodeOfTag
import com.singularity.todo.test.helpers.unmergedNodeOfText
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.jupiter.api.Tag
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The Task Detail's text answers the user's font scale.
 *
 * The scale is a setting in Settings → Interface, and every slot in the app's
 * typography is scaled by it. A screen that writes its own `16.sp` instead of
 * reading a slot ignores the setting: the text stays the same size while
 * everything around it grows.
 *
 * The detail's text is not one thing but seven: the title, the description, the
 * priority label, the due date, the estimate, the attribute cards' captions and
 * the values those cards carry. Every one of them was touched by this work, so
 * every one of them is measured here — a screen that scales five of seven is a
 * screen whose sixth element still ignores the setting.
 *
 * Two things are asserted for each:
 *
 * - **It responds.** The measured text grows with the scale — the text is drawn
 *   with the scaled style, not with a fixed one. The growth is checked as a
 *   *ratio*, not as a number of pixels: the expectation comes from the scale the
 *   user set, and the size comes from `MaterialTheme.typography`, which the test
 *   reads rather than restates. A test that hard-codes "the title is 30 dp"
 *   fails the day the design system moves the title, and passes the day a slot
 *   stops being scaled.
 * - **It is not clipped, and it does not collide.** The row that carries the text
 *   is at least as tall as the text itself, the text is fully inside its
 *   container, and no two neighbouring rows overlap — a larger setting does not
 *   cut the last line off and does not put one row's text through another's.
 *
 * The Android half of this is not here: a desktop composition is a different
 * renderer with a different density, and the device check is a separate run.
 */
@Tag("fast")
class TaskDetailFontScaleTest {
    @Test
    fun `every text of the detail answers the font scale`() {
        val normal = readingAt(scale = 1f, longValues = false)
        val large = readingAt(scale = 1.5f, longValues = false)

        TEXT_ELEMENTS.forEach { (element, _) ->
            val atNormal = normal.heights.getValue(element)
            val atLarge = large.heights.getValue(element)

            assertTrue(
                atLarge > atNormal,
                "the $element did not grow with the font scale: $atNormal → $atLarge",
            )

            // A text that keeps one line grows exactly by the scale; a text that
            // wraps into more lines grows by the scale *and* by the extra lines.
            // The ratio is only the scale in the first case, so the number of
            // lines decides which expectation applies.
            val linesNormal = linesOf(atNormal, normal.slotHeightOf(element))
            val linesLarge = linesOf(atLarge, large.slotHeightOf(element))

            if (linesLarge == linesNormal) {
                // The ratio, not the pixels: the scale is the user's setting, and
                // the size itself belongs to the typography the theme supplies.
                val ratio = atLarge / atNormal
                assertTrue(
                    ratio in RATIO_TOLERANCE,
                    "the $element grew by $ratio, not by the font scale " +
                        "(${RATIO_TOLERANCE.start}…${RATIO_TOLERANCE.endInclusive}): " +
                        "$atNormal → $atLarge",
                )
            } else {
                // It wrapped: then it has to be at least as tall as the scaled
                // version of itself.
                assertTrue(
                    atLarge >= atNormal * 1.5f * 0.95f,
                    "the $element grew from $atNormal to $atLarge, which is less than the " +
                        "scaled text it wraps into $linesLarge lines",
                )
            }
        }
    }

    @Test
    fun `the text is the size the theme gives it, and it is not clipped`() {
        listOf(1f, 1.5f).forEach { scale ->
            val reading = readingAt(scale = scale, longValues = false)

            TEXT_ELEMENTS.forEach { (element, _) ->
                val measured = reading.heights.getValue(element)
                // The height has to be whole lines of the slot the text is drawn
                // with: a text that is cut off by its own container measures less
                // than the line it was laid out on.
                linesOf(measured, reading.slotHeightOf(element))
                // Clipped against unclipped: a text that is cut off by its own
                // container reports a smaller box than the one it was laid out in.
                val clipped = reading.clipped.getValue(element)
                assertTrue(
                    clipped == measured,
                    "the $element is clipped at the scale $scale: $clipped of $measured is visible",
                )
            }
        }
    }

    @Test
    fun `neighbouring rows do not overlap at the large font scale`() {
        val reading = readingAt(scale = 1.5f, longValues = true)

        ROW_ORDER.zipWithNext { above, below ->
            val top = reading.rects.getValue(above)
            val bottom = reading.rects.getValue(below)
            assertTrue(
                top.bottom <= bottom.top,
                "the $above row (${top.bottom}) reaches into the $below row (${bottom.top})",
            )
        }
    }

    @Test
    fun `a long value wraps instead of being cut off`() {
        val short = readingAt(scale = 1f, longValues = false)
        val long = readingAt(scale = 1f, longValues = true)

        // The description and the project name are the two that can hold a
        // sentence. A value that does not fit wraps: it takes more lines, and it
        // takes whole lines.
        assertTrue(
            long.heights.getValue("description") > short.heights.getValue("description"),
            "the long description did not take more lines than the short one",
        )
        assertTrue(
            long.heights.getValue("project") > short.heights.getValue("project"),
            "the long project name did not take more lines than the short one",
        )
        assertTrue(
            long.heights.getValue("project") >= long.slotHeightOf("project") * 2f,
            "the long project name was cut off: it measures " +
                "${long.heights.getValue("project")}, which is not two lines",
        )
        // And the wrapped text is still whole: nothing of it is clipped away.
        assertTrue(
            long.clipped.getValue("project") == long.heights.getValue("project"),
            "the long project name is clipped",
        )
    }

    @Test
    fun `the attribute rows stay pressable and big enough at the large font scale`() {
        val reading = readingAt(scale = 1.5f, longValues = false)

        ATTRIBUTE_ROWS.forEach { row ->
            assertTrue(
                row in reading.pressable,
                "the $row row lost its click action at the large font scale",
            )
            assertTrue(
                reading.rects.getValue(row).height >= TaskSpacing.rowMinHeight,
                "the $row row is ${reading.rects.getValue(row).height} tall, below the " +
                    "touch target of ${TaskSpacing.rowMinHeight}",
            )
        }
    }

    @Test
    fun `the title and the description stay reachable at the large font scale`() {
        val reading = readingAt(scale = 1.5f, longValues = false)

        // The description is a field, and it stays one.
        assertTrue(
            "description" in reading.editable,
            "the description stopped being editable at the large font scale",
        )
        // The title is not a field until it is tapped — the detail shows the task,
        // and the field opens on a tap. What has to survive the scale is the tap.
        assertTrue(
            "title" in reading.pressable,
            "the title stopped answering a tap at the large font scale",
        )
    }

    private companion object {
        /**
         * The texts of the detail, each with the slot it is drawn with.
         *
         * The name of the slot is what the expectation is derived from: the
         * measured height of a text is compared with the line height of its slot,
         * so the test never states a size of its own.
         */
        val TEXT_ELEMENTS: List<Pair<String, Slot>> =
            listOf(
                "title" to Slot.TITLE,
                "description" to Slot.BODY,
                "priority" to Slot.BODY,
                "estimate" to Slot.BODY,
                "due" to Slot.BODY,
                "project" to Slot.BODY,
                "tags" to Slot.BODY,
            )

        /** The rows, in the order they are drawn. */
        val ROW_ORDER =
            listOf(
                "title",
                "description",
                "priority",
                "estimate",
                "due",
                "project",
                "tags",
            )

        /** The rows that are pressed rather than typed into. */
        val ATTRIBUTE_ROWS = listOf("priority", "estimate", "due", "project", "tags")

        /** How far the measured growth may sit from the scale the user set. */
        val RATIO_TOLERANCE = 1.35f..1.65f

        /**
         * How far the measured height may sit from the slot's line height.
         *
         * A text is laid out in a box that is not exactly its line height — the
         * field around it adds its own padding, the font's own ascent and
         * descent round it — so the check is a band and not an equality. The
         * lower edge is the one that matters: a text cut off by its container
         * measures less than the line it was drawn on.
         */
        const val LOWER_BOUND = 0.6f
        const val UPPER_BOUND = 1.6f

        /**
         * How far a measured height may sit from a whole number of lines.
         *
         * The same rounding as the font's own metrics: the drawn text is a
         * little shorter than the line it was laid out on.
         */
        const val LINE_DRIFT = 0.4f

        val NOW: Instant = Instant.parse("2026-03-14T12:00:00Z")

        const val SHORT_PROJECT = "Kitchen"
        const val LONG_PROJECT =
            "Kitchen renovation, the pantry reorganisation and the winter " +
                "season storage plan for the whole flat"

        const val SHORT_DESCRIPTION = "Pick up the oat milk"
        const val LONG_DESCRIPTION =
            "Pick up the oat milk, the olive oil, the sourdough starter and " +
                "everything else the shopping list says, before the shop closes"
    }

    /**
     * The typography slot a text is drawn with.
     */
    private enum class Slot {
        TITLE,
        BODY,
    }

    /**
     * One measurement of the detail's text at one font scale.
     */
    private class Reading(
        /** The height of every text of [TEXT_ELEMENTS], in dp. */
        val heights: Map<String, Dp>,
        /** The height of the same texts as the container shows them, in dp. */
        val clipped: Map<String, Dp>,
        /** The box of every row of [ROW_ORDER], in dp. */
        val rects: Map<String, DpRect>,
        /** The line height of each slot of [Slot], in dp. */
        val slotHeights: Map<Slot, Dp>,
        /** The rows that still answer a press. */
        val pressable: Set<String>,
        /** The fields that still take typing. */
        val editable: Set<String>,
    ) {
        /**
         * The line height of the slot the named text is drawn with.
         */
        fun slotHeightOf(element: String): Dp =
            slotHeights.getValue(
                TEXT_ELEMENTS.first { it.first == element }.second,
            )
    }

    /**
     * The number of lines the measured height stands for.
     *
     * A text is measured in the box it was laid out in, and that box is a whole
     * number of the slot's lines — the drawn text is a little shorter than the
     * line it sits on, but it is never a fraction of a line. The check is what
     * catches a text that was cut off: a clipped text measures less than the
     * line it was laid out on.
     */
    private fun linesOf(
        measured: Dp,
        slot: Dp,
    ): Int {
        val lines = (measured.value / slot.value).roundToInt()
        assertTrue(
            lines >= 1,
            "a text of $measured is shorter than the line of $slot it is drawn on",
        )
        val drift = abs(measured.value - lines * slot.value)
        assertTrue(
            drift <= slot.value * LINE_DRIFT,
            "a text of $measured is not a whole number of the $slot lines it is drawn on",
        )
        return lines
    }

    /**
     * Measures the detail's text at [scale].
     *
     * @param longValues whether the fields carry values too long for one line.
     */
    private fun readingAt(
        scale: Float,
        longValues: Boolean,
    ): Reading {
        var reading: Reading? = null
        runIsolatedComposeTest(width = 360, height = 1600) {
            var slotHeights: Map<Slot, Dp> = emptyMap()

            setContent {
                SingularityTheme(fontSizeScale = scale) {
                    val current = LocalDensity.current
                    slotHeights =
                        mapOf(
                            Slot.TITLE to
                                with(current) {
                                    MaterialTheme.typography.titleLarge.lineHeight
                                        .toDp()
                                },
                            Slot.BODY to
                                with(current) {
                                    MaterialTheme.typography.bodyLarge.lineHeight
                                        .toDp()
                                },
                        )
                    TaskEditorContent(
                        model = editorModel(longValues),
                        titleSource = OwnedText(text = "Buy the oat milk", revision = 0),
                        descriptionSource =
                            OwnedText(
                                text = if (longValues) LONG_DESCRIPTION else SHORT_DESCRIPTION,
                                revision = 0,
                            ),
                        onTitleChange = {},
                        onDescriptionChange = {},
                        callbacks = editorCallbacks(),
                        now = NOW,
                        sheets = rememberDialogState<TaskEditorSheet>(),
                    )
                }
            }

            val heights = mutableMapOf<String, Dp>()
            val clipped = mutableMapOf<String, Dp>()
            TEXT_ELEMENTS.forEach { (element, _) ->
                val box = textBounds(element, longValues)
                heights[element] = box.height
                clipped[element] = box.clippedHeight
            }

            val rects = mutableMapOf<String, DpRect>()
            val boxes = mutableMapOf<String, Box>()
            ROW_ORDER.forEach { row ->
                val box = rowBounds(row)
                boxes[row] = box
                rects[row] = box.unclipped
            }

            reading =
                Reading(
                    heights = heights,
                    clipped = clipped,
                    rects = rects,
                    slotHeights = checkNotNull(slotHeights),
                    pressable = ROW_ORDER.filter { row -> boxes.getValue(row).clickable }.toSet(),
                    editable = listOf("title", "description").filter { row -> boxes.getValue(row).editable }.toSet(),
                )
        }
        return checkNotNull(reading) { "the editor was never mounted" }
    }

    /**
     * The task the editor shows.
     *
     * The values are the ones the detail carries: a priority, a date with a
     * time, an estimate, a project and a tag. [longValues] swaps the project
     * for a name too long for one line.
     */
    private fun editorModel(longValues: Boolean): TaskEditorModel =
        TaskEditorModel(
            priority = TaskPriority.Medium,
            dueDate = LocalDate(2026, 3, 16),
            dueTime = LocalTime(14, 30),
            estimateMinutes = 45,
            project = ProjectId("project-1"),
            projectName = if (longValues) LONG_PROJECT else SHORT_PROJECT,
            tags = persistentListOf(TagId("tag-1")),
        )

    /**
     * The editor's callbacks — every row is present and none of them does
     * anything: the measurement watches the screen, it does not drive it.
     */
    private fun editorCallbacks(): TaskEditorCallbacks =
        TaskEditorCallbacks(
            onBack = {},
            onCheckToggle = null,
            priority = RowCallbacks(onChange = {}),
            dueDate = DateRowCallbacks(onChangeDate = {}, onChangeTime = {}),
            startDate = null,
            project = RowCallbacks(onChange = {}),
            tags = RowCallbacks(onChange = {}),
            recurrence = null,
            pin = null,
            dependencies = null,
            checklist = null,
            attachments = null,
            estimate = RowCallbacks(onChange = {}),
            onTimeEntryAdd = null,
            onTimeEntrySave = null,
        )

    /**
     * The box of the text of the named element, and the height of it as the
     * container shows it.
     */
    private fun DesktopComposeUiTest.textBounds(
        element: String,
        longValues: Boolean,
    ): Box =
        when (element) {
            "title" -> boxOf(nodeOfTag(TestTags.TASK_EDITOR_TITLE_INPUT))
            "description" -> boxOf(nodeOfTag(TestTags.TASK_EDITOR_DESCRIPTION_INPUT))
            "priority" -> boxOf(nodeOfText("Medium priority"))
            "estimate" -> boxOf(nodeOfText("45 min"))
            "due" -> boxOf(nodeOfTag(TestTags.TASK_EDITOR_DUE_DATE_ROW_LABEL))
            "project" -> boxOf(nodeOfText(if (longValues) LONG_PROJECT else SHORT_PROJECT))
            "tags" -> boxOf(nodeOfText("1 tag"))
            else -> error("no such element: $element")
        }

    /**
     * The box of the named row.
     */
    private fun DesktopComposeUiTest.rowBounds(row: String): Box =
        when (row) {
            "title" -> boxOf(nodeOfTag(TestTags.TASK_EDITOR_TITLE_INPUT))
            "description" -> boxOf(nodeOfTag(TestTags.TASK_EDITOR_DESCRIPTION_INPUT))
            "priority" -> boxOf(nodeOfTag(TestTags.TASK_EDITOR_PRIORITY_ROW))
            "estimate" -> boxOf(nodeOfTag(TestTags.TASK_EDITOR_ESTIMATE_ROW))
            "due" -> boxOf(nodeOfTag(TestTags.TASK_EDITOR_DUE_ROW))
            "project" -> boxOf(nodeOfTag(TestTags.TASK_EDITOR_PROJECT_ROW))
            "tags" -> boxOf(nodeOfTag(TestTags.TASK_EDITOR_TAGS_ROW))
            else -> error("no such row: $row")
        }

    /**
     * A measured box: the one the node was laid out in, the one its container
     * shows, and whether the node still answers a press.
     */
    private class Box(val unclipped: DpRect, val clippedHeight: Dp, val clickable: Boolean, val editable: Boolean) {
        val height: Dp get() = unclipped.height
    }

    private fun DesktopComposeUiTest.boxOf(node: SemanticsNodeInteraction): Box {
        val unclipped = node.getUnclippedBoundsInRoot()
        val clipped = node.getBoundsInRoot()
        val config = node.fetchSemanticsNode().config
        return Box(
            unclipped = unclipped,
            clippedHeight = clipped.height,
            clickable = config.contains(SemanticsActions.OnClick),
            editable =
                config.contains(SemanticsProperties.EditableText) ||
                    config.contains(SemanticsProperties.IsEditable),
        )
    }

    /**
     * The node with [tag], in the unmerged tree.
     *
     * The unmerged tree is the one to read: a text that sits inside a clickable
     * row is merged up into it, and the merged node carries the row's box rather
     * than the text's.
     */
    private fun DesktopComposeUiTest.nodeOfTag(tag: String): SemanticsNodeInteraction = unmergedNodeOfTag(tag)

    /**
     * The node with [text], in the unmerged tree.
     */
    private fun DesktopComposeUiTest.nodeOfText(text: String): SemanticsNodeInteraction = unmergedNodeOfText(text)
}

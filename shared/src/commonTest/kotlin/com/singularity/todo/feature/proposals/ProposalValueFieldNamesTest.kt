package com.singularity.todo.feature.proposals

import com.singularity.todo.feature.proposals.domain.model.NoteValue
import com.singularity.todo.feature.proposals.domain.model.ProjectValue
import com.singularity.todo.feature.proposals.domain.model.ProposalValue
import com.singularity.todo.feature.proposals.domain.model.ReadResult
import com.singularity.todo.feature.proposals.domain.model.TagValue
import com.singularity.todo.feature.proposals.domain.model.TaskValue
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The field names a value declares and the field names a reader knows.
 *
 * ## Why this is a test
 *
 * Every dictionary says the name of its field twice: once in the variant
 * (`fieldName`) and once in the reader (`read`). The two were written side by
 * side and nothing kept them together: a field added to a variant and not to the
 * reader is a field that does not read — the value comes back as "not a task
 * field", and the change turns into a value the user never asked for. The
 * opposite — a name the reader knows and no variant declares — is a name that
 * reads as some other field.
 *
 * Neither mistake is loud. So the two lists are compared here: every name a
 * value declares is a name its reader reads, and it reads it as itself.
 *
 * The values that cannot be read by design — the [ProposalValue] dictionaries'
 * own "Unreadable" — are left out: their whole purpose is to be a field the
 * reader does not know.
 */
@Tag("fast")
class ProposalValueFieldNamesTest {

    @Test
    fun `every field a task value names, the task reader reads`() {
        assertEveryNameIsRead(
            dictionary = "task",
            values = taskValues,
            read = TaskValue::read,
        )
    }

    @Test
    fun `every field a note value names, the note reader reads`() {
        assertEveryNameIsRead(
            dictionary = "note",
            values = noteValues,
            read = NoteValue::read,
        )
    }

    @Test
    fun `every field a project value names, the project reader reads`() {
        assertEveryNameIsRead(
            dictionary = "project",
            values = projectValues,
            read = ProjectValue::read,
        )
    }

    @Test
    fun `every field a tag value names, the tag reader reads`() {
        assertEveryNameIsRead(
            dictionary = "tag",
            values = tagValues,
            read = TagValue::read,
        )
    }

    /**
     * Every name a value declares is a name the reader reads, and it reads it as
     * that very value.
     */
    private fun assertEveryNameIsRead(
        dictionary: String,
        values: List<ProposalValue>,
        read: (String, String) -> ReadResult<out ProposalValue>,
    ) {
        for (value in values) {
            val name = value.fieldName
            val result = read(name, value.text)

            val readValue = when (result) {
                is ReadResult.Ok -> result.value

                is ReadResult.Rejected -> fail(
                    "the $dictionary reader knows the name «$name»: ${result.reason}",
                )
            }
            assertEquals(
                name,
                readValue.fieldName,
                "the $dictionary reader reads «$name» as that very field",
            )
        }
    }

    @Test
    fun `a name no value declares is not read as some other field`() {
        // The other direction: the reader must not answer for a name that no value
        // declares. A reader that answers for an unknown name is a reader that
        // turns a typo into a change the user never asked for.
        val result = TaskValue.read("Headline", "Buy milk")

        val reason = assertIs<ReadResult.Rejected>(result, "an unknown name does not read").reason
        assertTrue(
            reason.contains("Headline"),
            "the reason names the field the reader did not know: $reason",
        )
    }

    private companion object {

        /** The task's readable fields — the "Unreadable" one is left out on purpose. */
        val taskValues: List<TaskValue> = listOf(
            TaskValue.Title("Buy milk"),
            TaskValue.Description("From the shop"),
            TaskValue.Priority(TaskPriority.High),
            TaskValue.DueDate(LocalDate(2026, 1, 2)),
            TaskValue.DueTime(LocalTime(9, 30)),
            TaskValue.Status(TaskStatus.Active),
            TaskValue.EstimateMinutes(30),
            TaskValue.Kind(TaskKind.Task),
            TaskValue.Project("project-1"),
            TaskValue.Someday(true),
        )

        /** The note's readable fields. */
        val noteValues: List<NoteValue> = listOf(
            NoteValue.Title("Shopping"),
            NoteValue.Body("<p>Milk</p>"),
            NoteValue.Summary("A short list"),
        )

        /** The project's readable fields. */
        val projectValues: List<ProjectValue> = listOf(
            ProjectValue.Name("Home"),
            ProjectValue.Description("Everything at home"),
            ProjectValue.Color(0xFF00FF00.toInt()),
            ProjectValue.Icon("house"),
        )

        /** The tag's readable fields. */
        val tagValues: List<TagValue> = listOf(
            TagValue.Name("chore"),
            TagValue.Color(0xFF112233.toInt()),
        )
    }
}

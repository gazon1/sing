package com.singularity.todo.feature.proposals.data

import com.singularity.todo.feature.proposals.domain.model.NoteValue
import com.singularity.todo.feature.proposals.domain.model.ProjectValue
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposedNote
import com.singularity.todo.feature.proposals.domain.model.ProposedProject
import com.singularity.todo.feature.proposals.domain.model.ProposedTag
import com.singularity.todo.feature.proposals.domain.model.ProposedTask
import com.singularity.todo.feature.proposals.domain.model.ProposedTimeEntry
import com.singularity.todo.feature.proposals.domain.model.TagValue
import com.singularity.todo.feature.proposals.domain.model.TaskValue
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The vocabulary has to survive the trip through the database.
 *
 * ## What this is a control for
 *
 * The kind is stored as a flat JSON payload and read back by a codec that decodes on
 * a **string** — the type name of the kind. So a kind the codec was not taught to
 * read does not fail: it decodes to nothing, the item is dropped, and the proposal
 * simply loses a change. The compiler cannot help here — the `when`s over
 * `ProposalItemKind` in the domain are exhaustive, and the codec is not a `when` over
 * the type.
 *
 * The vocabulary grew from 15 kinds to 19 while this repository was being worked on,
 * so this is not a hypothetical.
 *
 * ## How the test knows what the vocabulary is
 *
 * From the sealed serializer, which lists every kind this build knows. So the list of
 * samples cannot quietly fall behind the list of kinds: a kind that arrives without a
 * sample fails the coverage test, loudly, before it can fail silently in the app.
 */
@Tag("fast")
class ProposalMapperTest {

    @Test
    fun `every kind survives the round trip`() {
        samples().forEach { kind ->
            val encoded = kind.toJsonString()
            val decoded = decodeKind(encoded)
            assertNotNull(decoded, "the kind ${kind::class.simpleName} does not decode: $encoded")
            assertEquals(
                kind,
                decoded,
                "the kind ${kind::class.simpleName} does not survive the round trip: $encoded",
            )
        }
    }

    @Test
    fun `every kind this build knows has a sample`() {
        assertEquals(
            ProposalItemKind.serializer().sealedNames(),
            samples().map { it::class.simpleName }.toSet(),
            "a kind arrived without a sample — it would not be covered by the round trip",
        )
    }

    @Test
    fun `every task value this build knows has a sample`() {
        assertEquals(
            TaskValue.serializer().sealedNames(),
            samples().filterIsInstance<ProposalItemKind.SetTaskField>()
                .map { it.value::class.simpleName }
                .toSet(),
            "a task field arrived without a sample — it would not be covered by the round trip",
        )
    }

    @Test
    fun `every note value this build knows has a sample`() {
        assertEquals(
            NoteValue.serializer().sealedNames(),
            samples().filterIsInstance<ProposalItemKind.SetNoteField>()
                .map { it.value::class.simpleName }
                .toSet(),
            "a note field arrived without a sample — it would not be covered by the round trip",
        )
    }

    @Test
    fun `every project value this build knows has a sample`() {
        assertEquals(
            ProjectValue.serializer().sealedNames(),
            samples().filterIsInstance<ProposalItemKind.SetProjectField>()
                .map { it.value::class.simpleName }
                .toSet(),
            "a project field arrived without a sample — it would not be covered by the round trip",
        )
    }

    @Test
    fun `every tag value this build knows has a sample`() {
        assertEquals(
            TagValue.serializer().sealedNames(),
            samples().filterIsInstance<ProposalItemKind.SetTagField>()
                .map { it.value::class.simpleName }
                .toSet(),
            "a tag field arrived without a sample — it would not be covered by the round trip",
        )
    }

    @Test
    fun `a cleared date survives the round trip`() {
        // "No due date" is a value the task can hold, and it is written as an empty
        // string — the shape the rows written before the typed dictionary already have.
        // A codec that could not read it would drop the change from the card.
        val cleared = listOf(
            ProposalItemKind.SetTaskField(TaskValue.DueDate(null)),
            ProposalItemKind.SetTaskField(TaskValue.DueTime(null)),
        )

        cleared.forEach { kind ->
            val encoded = kind.toJsonString()
            val decoded = decodeKind(encoded)
            assertEquals(kind, decoded, "a cleared date does not survive the round trip: $encoded")
        }
    }
}

/**
 * One sample of every kind, and of every value a kind can carry.
 *
 * The list is the coverage: a kind added to the vocabulary without a sample here
 * fails the coverage test above.
 */
private fun samples(): List<ProposalItemKind> = listOf(
    // Every task field.
    ProposalItemKind.SetTaskField(TaskValue.Title("Buy milk")),
    ProposalItemKind.SetTaskField(TaskValue.Description("Semi-skimmed")),
    ProposalItemKind.SetTaskField(TaskValue.Priority(TaskPriority.High)),
    ProposalItemKind.SetTaskField(TaskValue.DueDate(LocalDate(2026, 10, 22))),
    ProposalItemKind.SetTaskField(TaskValue.DueTime(LocalTime(9, 30))),
    ProposalItemKind.SetTaskField(TaskValue.Status(TaskStatus.Active)),
    ProposalItemKind.SetTaskField(TaskValue.EstimateMinutes(30)),
    ProposalItemKind.SetTaskField(TaskValue.Kind(TaskKind.Note)),
    ProposalItemKind.SetTaskField(TaskValue.Project("project-1")),
    ProposalItemKind.SetTaskField(TaskValue.Someday(true)),

    // Every note field.
    ProposalItemKind.SetNoteField(NoteValue.Title("Agenda")),
    ProposalItemKind.SetNoteField(NoteValue.Body("<p>Buy milk</p>")),
    ProposalItemKind.SetNoteField(NoteValue.Summary("Milk")),

    // Every project field.
    ProposalItemKind.SetProjectField(ProjectValue.Name("Kitchen")),
    ProposalItemKind.SetProjectField(ProjectValue.Description("Renovation")),
    ProposalItemKind.SetProjectField(ProjectValue.Color(0xFF2196F3.toInt())),
    ProposalItemKind.SetProjectField(ProjectValue.Icon("kitchen")),

    // Every tag field.
    ProposalItemKind.SetTagField(TagValue.Name("chore")),
    ProposalItemKind.SetTagField(TagValue.Color(0xFF9E9E9E.toInt())),

    // The collection kinds.
    ProposalItemKind.SetTaskTags(listOf("tag-1", "tag-2")),
    ProposalItemKind.AddTags(listOf("chore", "errand")),
    ProposalItemKind.RemoveTags(listOf("tag-3")),
    ProposalItemKind.AddChecklistItems(listOf("buy", "cook")),
    ProposalItemKind.AddSubtasks(listOf("Pick up milk")),
    ProposalItemKind.AddTimeEntries(listOf(ProposedTimeEntry(1_000L, 3_600L, "walk"))),

    // The creations.
    ProposalItemKind.CreateTask(
        ProposedTask(
            title = "Buy milk",
            description = "Semi-skimmed",
            priority = TaskPriority.High,
            kind = TaskKind.Task,
            projectId = "project-1",
            parentTaskId = "task-0",
            tagIds = listOf("tag-1"),
            dueDate = LocalDate(2026, 10, 22),
            dueTime = LocalTime(9, 30),
            someday = false,
        ),
    ),
    ProposalItemKind.CreateProject(
        ProposedProject(name = "Kitchen", description = "Renovation", color = 0xFF2196F3.toInt(), icon = "kitchen"),
    ),
    ProposalItemKind.CreateNote(
        ProposedNote(title = "Agenda", bodyHtml = "<p>Buy milk</p>", isFolder = false, parentNoteId = "note-0"),
    ),
    ProposalItemKind.CreateTag(ProposedTag(name = "chore", color = 0xFF9E9E9E.toInt())),
    ProposalItemKind.ExtractActions(listOf("Buy milk", "Call the vet")),

    // The deletions.
    ProposalItemKind.DeleteNote("it is a draft"),
    ProposalItemKind.DeleteTask("it is done"),
    ProposalItemKind.DeleteProject("it is empty"),
    ProposalItemKind.DeleteTag("it is unused"),
)

/**
 * The names of the variants a sealed serializer knows.
 *
 * A sealed serializer's own descriptor is shaped like a polymorphic one — a "type"
 * and a "value" — and the variants are the elements of the "value" descriptor, named
 * by their serial name. So this walks to the "value" rather than assuming an index.
 */
private fun <T : Any> KSerializer<T>.sealedNames(): Set<String> {
    val valueIndex = (0 until descriptor.elementsCount).first { descriptor.getElementName(it) == "value" }
    return descriptor.getElementDescriptor(valueIndex).elementNames.toSet()
}

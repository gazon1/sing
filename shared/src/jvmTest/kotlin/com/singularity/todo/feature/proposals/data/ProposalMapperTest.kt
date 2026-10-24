package com.singularity.todo.feature.proposals.data

import com.singularity.todo.feature.proposals.domain.model.NoteValue
import com.singularity.todo.feature.proposals.domain.model.ProjectValue
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.isPending
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The vocabulary has to survive the trip through the database.
 *
 * ## What this is a control for
 *
 * The kind is stored as JSON and read back by a codec. A kind the codec was not
 * taught to read used to decode to nothing: the item was dropped, and the proposal
 * simply lost a change. The compiler cannot help there — the `when`s over
 * `ProposalItemKind` in the domain are exhaustive, and the codec is not a `when` over
 * the type.
 *
 * Now the codec always answers with a kind, and the kind it answers with for a blob
 * it cannot read is `ProposalItemKind.Unknown` — the change stays on the card with
 * the reason it cannot be applied, and the user can still turn it down.
 *
 * The vocabulary grew from 15 kinds to 19 while this repository was being worked on,
 * so this is not a hypothetical.
 *
 * ## What the format is
 *
 * The sealed format: the model's own shape, written by the model's own serializer.
 * A field that is renamed in the model is a field the serializer changed with, so a
 * value cannot read back as something else. The flat format the builds before this
 * one wrote is still read — a row they wrote is a row the user still has.
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

    @Test
    fun `a kind this build does not know keeps the change`() {
        // A row written by a build that knew a kind this one does not. The change
        // must not vanish: it is still a change, and the user has to be able to
        // decide about it — and to turn it down.
        val encoded = """{"type":"ArchiveEverything","targetId":"t1"}"""

        val decoded = decodeKind(encoded)

        assertEquals(
            ProposalItemKind.Unknown(encoded),
            decoded,
            "the change is kept as the change this build cannot read",
        )
        assertNotNull(
            (decoded as ProposalItemKind.Unknown).readFailure,
            "the reason the change cannot be read is with the change",
        )
    }

    @Test
    fun `a blob that is not a blob keeps the change`() {
        val encoded = "not json at all"

        val decoded = assertNotNull(
            decodeKind(encoded) as? ProposalItemKind.Unknown,
            "the change is kept — as the change this build cannot read",
        )

        assertEquals(encoded, decoded.raw, "the blob is kept exactly as it was found")
        assertTrue(
            decoded.reason.contains("not stored in a form"),
            "the reason says the blob is not stored in a form this version can read: ${decoded.reason}",
        )
    }

    @Test
    fun `a change a newer build wrote with a field this one does not know is refused`() {
        // A newer build added a field to a kind this build knows. Reading the blob
        // without that field would fill in a default and show the user a change
        // nobody proposed — so the reader refuses the blob, and the change stays on
        // the card with the reason it cannot be read.
        val encoded = """{"type":"SetTaskField","value":{"type":"Title","value":"Buy milk","marker":"x"}}"""

        val decoded = decodeKind(encoded)

        assertTrue(
            decoded is ProposalItemKind.Unknown,
            "a blob with a field this build does not know is not read as something else: $decoded",
        )
        assertNotNull(
            decoded.readFailure,
            "the reason the change cannot be read is with the change",
        )
    }

    @Test
    fun `a sealed blob is never read as a flat one`() {
        // A kind a newer build knew, in the sealed shape. The flat reader would
        // have read it as a change with an empty field — a change that says
        // something entirely different from what it is.
        val encoded = """{"type":"ArchiveEverything","value":{"type":"Everything","targetId":"t1"}}"""

        val decoded = decodeKind(encoded)

        assertTrue(
            decoded is ProposalItemKind.Unknown,
            "a sealed blob this build cannot read is not read as a flat one: $decoded",
        )
        assertNotNull(
            decoded.readFailure,
            "the reason the change cannot be read is with the change",
        )
    }

    @Test
    fun `the flat format of the builds before this one still reads`() {
        // The flat format is what the builds before this one wrote, and a row they
        // wrote is a row the user still has. So the flat reader stays until the
        // oldest build in the user's hands is this one.
        assertEquals(
            ProposalItemKind.SetTaskField(TaskValue.Title("Buy milk")),
            decodeKind("""{"type":"SetTaskField","field":"Title","value":"Buy milk"}"""),
        )
        assertEquals(
            ProposalItemKind.SetTaskTags(listOf("tag-1", "tag-2")),
            decodeKind("""{"type":"SetTaskTags","tagIds":"tag-1\u001Ftag-2"}"""),
        )
        assertEquals(
            ProposalItemKind.AddChecklistItems(listOf("buy", "cook")),
            decodeKind("""{"type":"AddChecklistItems","texts":"buy\u001Fcook"}"""),
        )
        assertEquals(
            ProposalItemKind.AddSubtasks(listOf("Pick up milk")),
            decodeKind("""{"type":"AddSubtasks","titles":"Pick up milk"}"""),
        )
        assertEquals(
            ProposalItemKind.CreateTask(ProposedTask(title = "Buy milk", priority = TaskPriority.High)),
            decodeKind("""{"type":"CreateTask","title":"Buy milk","priority":"High"}"""),
        )
        assertEquals(
            ProposalItemKind.DeleteNote(reason = "it is old"),
            decodeKind("""{"type":"DeleteNote","reason":"it is old"}"""),
        )
    }

    @Test
    fun `a status this build does not know is not read as waiting`() {
        // A row written by a build that knew a status this one does not. Reading it
        // as "waiting" would make the change applicable again — and it may already
        // have been applied, so applying it a second time is the one thing this
        // must not do.
        val entity = ProposalItemEntity(
            id = "pi_1",
            proposalId = "prop_1",
            kindJson = ProposalItemKind.SetTaskField(TaskValue.Title("Buy milk")).toJsonString(),
            targetId = "t1",
            humanSummary = "Set the title to Buy milk",
            status = "Applied",
            fingerprint = "fp_1",
            sortOrder = 0,
        )

        val item = entity.toDomain()

        assertEquals(
            ProposalItemStatus.Unknown("Applied"),
            item.status,
            "a status this build does not know is not read as another one",
        )
        assertFalse(item.status.isPending, "a change this build cannot place is not waiting")
        assertNotNull(
            item.readFailure,
            "the reason the change cannot be applied is with the change",
        )
    }

    @Test
    fun `a value this build cannot read keeps the change`() {
        // A row written by a build that knew the priority, read by one that does not.
        // The change must not vanish: it is still a change, and the user has to be
        // able to decide about it.
        val encoded = """{"type":"SetTaskField","field":"Priority","value":"very high"}"""

        val decoded = assertNotNull(decodeKind(encoded), "the change is not dropped")

        assertEquals(
            ProposalItemKind.SetTaskField(TaskValue.Unreadable("Priority", "very high")),
            decoded,
            "the change is kept, with the value it could not read",
        )
        assertNotNull(
            (decoded as ProposalItemKind.SetTaskField).readFailure,
            "the reason the value does not read is with the change",
        )
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

    // A value this build cannot read, for each of the four dictionaries. The field
    // is one this build does not know — which is what a row written by an older
    // build looks like. The change stays; the value carries the reason.
    ProposalItemKind.SetTaskField(TaskValue.Unreadable("Priority", "very high")),
    ProposalItemKind.SetNoteField(NoteValue.Unreadable("Colour", "blue")),
    ProposalItemKind.SetProjectField(ProjectValue.Unreadable("Colour", "blue")),
    ProposalItemKind.SetTagField(TagValue.Unreadable("Colour", "blue")),

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

    // The change this build cannot read. A kind that arrives in the vocabulary has
    // to be covered here too: the round trip has to hold for it, or the change a
    // user cannot read would be a change that disappears on the way to the database.
    ProposalItemKind.Unknown("""{"type":"ArchiveEverything"}"""),

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

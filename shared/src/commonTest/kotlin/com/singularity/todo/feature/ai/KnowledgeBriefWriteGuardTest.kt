@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.ai

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.feature.ai.tools.GetKnowledgeBriefInput
import com.singularity.todo.feature.ai.tools.UpdateNoteInput
import com.singularity.todo.feature.ai.tools.UpdateNoteTool
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.notes.NoteKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A knowledge brief is a note attached to a project or a task, written through the
 * proposal pipeline.
 *
 * ## What these tests are about
 *
 * The issue asks for a brief that *reuses* the note model — no parallel document
 * system — and that cannot destroy the approved brief. Those two claims are what is
 * pinned here, at the level a user experiences them:
 *
 * - the attachment is the `project_id` / `task_id` column, so the brief is a note and
 *   the note is findable by the same read the app already has;
 * - the proposal path holds the change until the user confirms it, so a refresh is a
 *   *candidate*, not a write;
 * - a change proposed against a note that has since moved is refused, so the model's
 *   summary of an older note cannot overwrite the user's edit;
 * - a note that is not there is never replaced by an empty brief — "no brief yet" and
 *   "no such project" are different answers.
 */
@Tag("fast")
class KnowledgeBriefWriteGuardTest : KnowledgeBriefTestBase() {
    @Test
    fun `refreshing a brief proposes a candidate and leaves the approved note alone`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)
            val brief = note(title = "Brief", html = "<p>approved</p>", projectId = project.id)
            notes.seed(brief)

            val output =
                decodeUpdate(
                    UpdateNoteTool(proposals, notes, currentUser, clock)
                        .execute(
                            UpdateNoteInput(
                                noteId = brief.id.value,
                                // A refreshed brief keeps its provenance: the section is
                                // what makes it a brief, so an extraction that dropped it
                                // would leave a note the reader no longer recognises.
                                bodyMarkdown = "refreshed\n\n## Sources\n\n- https://example.com",
                                expectedUpdatedAt = brief.updatedAt.toEpochMilliseconds(),
                            ),
                        ),
                )

            assertTrue(output.proposalCreated, "the refresh was proposed: ${output.error}")
            assertTrue(
                notes.notes[brief.id.value]?.bodyHtml?.startsWith("<p>approved</p>") == true,
                "the approved brief is unchanged until the user confirms",
            )

            apply.confirm(singleItemId(output.proposalId), user)
            assertNotNull(notes.notes[brief.id.value]?.bodyHtml, "the confirmed refresh left a body")
        }

    @Test
    fun `a refresh against a note that moved is refused instead of overwriting it`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)
            val brief = note(title = "Brief", html = "<p>approved</p>", projectId = project.id)
            notes.seed(brief)

            val output =
                decodeUpdate(
                    UpdateNoteTool(proposals, notes, currentUser, clock)
                        .execute(
                            UpdateNoteInput(
                                noteId = brief.id.value,
                                bodyMarkdown = "refreshed from an older read",
                                // The note has since moved: this is the read before the edit.
                                expectedUpdatedAt = brief.updatedAt.toEpochMilliseconds() - 1,
                            ),
                        ),
                )

            assertFalse(output.proposalCreated, "no proposal was made: ${output.error}")
            assertNotNull(output.error, "the model is told the note moved")
            assertTrue(
                notes.notes[brief.id.value]?.bodyHtml?.startsWith("<p>approved</p>") == true,
                "the user's edit was not overwritten",
            )
            assertTrue(
                proposals.watchWaitingProposals().first().isEmpty(),
                "nothing is waiting for a decision about a stale read",
            )
        }

    @Test
    fun `an edit between the proposal and the confirmation is not overwritten`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)
            val brief = note(title = "Brief", html = "<p>approved</p>", projectId = project.id)
            notes.seed(brief)

            val output =
                decodeUpdate(
                    UpdateNoteTool(proposals, notes, currentUser, clock)
                        .execute(
                            UpdateNoteInput(
                                noteId = brief.id.value,
                                // A refreshed brief keeps its provenance: the section is
                                // what makes it a brief.
                                bodyMarkdown = "refreshed\n\n## Sources\n\n- https://example.com",
                                expectedUpdatedAt = brief.updatedAt.toEpochMilliseconds(),
                            ),
                        ),
                )
            assertTrue(output.proposalCreated, "the refresh was proposed: ${output.error}")

            // The user edits the note after the model proposed and before they confirm.
            clock.advance(kotlin.time.Duration.parse("1m"))
            notes.seed(brief.copy(bodyHtml = "<p>the user's own words</p>", updatedAt = clock.now()))

            val result = apply.confirm(singleItemId(output.proposalId), user)

            // The proposal re-reads the row and finds a version it was not written
            // against: the change is refused, not applied over the edit.
            assertTrue(result.isFailure, "the stale proposal was refused")
            assertEquals(
                "<p>the user's own words</p>",
                notes.notes[brief.id.value]?.bodyHtml,
                "the user's edit survived the confirmation",
            )
        }

    @Test
    fun `a title-only change is guarded like a body change`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)
            val brief = note(title = "Brief", html = "<p>approved</p>", projectId = project.id)
            notes.seed(brief)

            val output =
                decodeUpdate(
                    UpdateNoteTool(proposals, notes, currentUser, clock)
                        .execute(
                            UpdateNoteInput(
                                noteId = brief.id.value,
                                // No body at all: the guard must not belong to the body alone.
                                title = "Refreshed brief",
                                expectedUpdatedAt = brief.updatedAt.toEpochMilliseconds(),
                            ),
                        ),
                )
            assertTrue(output.proposalCreated, "the change was proposed: ${output.error}")

            clock.advance(kotlin.time.Duration.parse("1m"))
            notes.seed(brief.copy(title = "The user renamed it", updatedAt = clock.now()))

            val result = apply.confirm(singleItemId(output.proposalId), user)

            assertTrue(result.isFailure, "the stale title change was refused")
            assertEquals(
                "The user renamed it",
                notes.notes[brief.id.value]?.title,
                "the user's title survived the confirmation",
            )
        }

    @Test
    fun `applying a refresh advances the version, so the same read cannot land twice`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)
            val brief = note(title = "Brief", html = "<p>approved</p>", projectId = project.id)
            notes.seed(brief)
            val readAt = brief.updatedAt.toEpochMilliseconds()
            // The write is later in wall-clock terms, as a real refresh is.
            clock.advance(kotlin.time.Duration.parse("1m"))

            val first =
                decodeUpdate(
                    UpdateNoteTool(proposals, notes, currentUser, clock)
                        .execute(
                            UpdateNoteInput(
                                noteId = brief.id.value,
                                bodyMarkdown = "first refresh",
                                expectedUpdatedAt = readAt,
                            ),
                        ),
                )
            assertTrue(apply.confirm(singleItemId(first.proposalId), user).isSuccess, "the first refresh applied")

            val afterFirst = assertNotNull(notes.notes[brief.id.value], "the note is still there")

            // A second proposal built from the same read. Without the version moving on
            // write, this would land on top of the first refresh.
            val second =
                UpdateNoteTool(proposals, notes, currentUser, clock)
                    .execute(
                        UpdateNoteInput(
                            noteId = brief.id.value,
                            bodyMarkdown = "second refresh",
                            expectedUpdatedAt = readAt,
                        ),
                    )

            assertTrue(
                decodeUpdate(second).error != null,
                "a proposal from a superseded read is refused: ${decodeUpdate(second).error}",
            )
            assertEquals(
                afterFirst.bodyHtml,
                notes.notes[brief.id.value]?.bodyHtml,
                "the first refresh is still the note",
            )
        }

    @Test
    fun `the brief is the note the store calls newest, not the one with the largest id`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)

            // Same created_at: the tie the ordering has to break the same way the store
            // does — `created_at DESC, id ASC`.
            val tied = clock.now()
            val lowId =
                note(
                    title = "oldest id",
                    html = "<p>a</p>",
                    projectId = project.id,
                ).copy(id = NoteId("aaa"), createdAt = tied)
            val highId =
                note(
                    title = "largest id",
                    html = "<p>b</p>",
                    projectId = project.id,
                ).copy(id = NoteId("zzz"), createdAt = tied)
            notes.seed(highId)
            notes.seed(lowId)

            val output = decodeBrief(readTool.execute(GetKnowledgeBriefInput(projectId = project.id.value)))

            assertTrue(output.found, "the brief was found: ${output.reason}")
            assertEquals(
                "oldest id",
                output.title,
                "the tie breaks on ascending id, so the store's first row is the brief",
            )
        }

    @Test
    fun `a confirmed refresh advances the note version even inside one millisecond`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)
            val brief = note(title = "Brief", html = "<p>approved</p>", projectId = project.id)
            notes.seed(brief)
            val readAt = brief.updatedAt.toEpochMilliseconds()

            // No clock advance at all: the write lands in the same millisecond as the
            // read. A version that did not move strictly would make the note look
            // untouched, and the guard would accept a proposal aimed at the older text.
            val output =
                decodeUpdate(
                    UpdateNoteTool(proposals, notes, currentUser, clock)
                        .execute(
                            UpdateNoteInput(
                                noteId = brief.id.value,
                                bodyMarkdown = "refreshed",
                                expectedUpdatedAt = readAt,
                            ),
                        ),
                )
            assertTrue(apply.confirm(singleItemId(output.proposalId), user).isSuccess, "the refresh applied")

            val after = assertNotNull(notes.notes[brief.id.value], "the note is still there")
            assertTrue(
                after.updatedAt.toEpochMilliseconds() > readAt,
                "the version moved strictly: ${after.updatedAt.toEpochMilliseconds()} vs $readAt",
            )
        }

    @Test
    fun `a project's daily note is not its brief`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)

            // A daily note carries the same project_id as a brief does. It is a journal,
            // not a research summary, and answering with it would put the extraction on
            // top of the user's day.
            val daily =
                note(title = "2026-03-10", html = "<p>today</p>", projectId = project.id)
                    .copy(kind = NoteKind.Daily)
            notes.seed(daily)

            val output = decodeBrief(readTool.execute(GetKnowledgeBriefInput(projectId = project.id.value)))

            assertFalse(output.found, "a daily note is not a brief: ${output.noteId}")
            assertNotNull(output.reason, "the answer says there is no brief yet")
        }

    @Test
    fun `a task's daily note is not its brief`() =
        runTest {
            val task = task("Draft the brief")
            tasks.seed(task)
            notes.seed(
                note(title = "2026-03-10", html = "<p>today</p>", taskId = task.id)
                    .copy(kind = NoteKind.Daily),
            )

            val output = decodeBrief(readTool.execute(GetKnowledgeBriefInput(taskId = task.id.value)))

            assertFalse(output.found, "a daily note is not a brief: ${output.noteId}")
        }

    @Test
    fun `a title and body update applies as one change, not in part`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)
            val brief = note(title = "Brief", html = "<p>approved</p>", projectId = project.id)
            notes.seed(brief)

            val output =
                decodeUpdate(
                    UpdateNoteTool(proposals, notes, currentUser, clock)
                        .execute(
                            UpdateNoteInput(
                                noteId = brief.id.value,
                                title = "Refreshed brief",
                                bodyMarkdown = "refreshed",
                                expectedUpdatedAt = brief.updatedAt.toEpochMilliseconds(),
                            ),
                        ),
                )
            assertTrue(output.proposalCreated, "the change was proposed: ${output.error}")

            // One change, so one decision: split per field, the first write would advance
            // the version and the second would then look stale.
            val stored = assertNotNull(proposals.watchProposal(ProposalId(output.proposalId)).first(), "the proposal")
            assertEquals(1, stored.items.size, "a title and body edit is one change")

            val result = apply.confirm(singleItemId(output.proposalId), user)
            assertTrue(result.isSuccess, "the change applied: ${result.exceptionOrNull()?.message}")

            val after = assertNotNull(notes.notes[brief.id.value], "the note is still there")
            assertEquals("Refreshed brief", after.title, "the title applied")
            assertNotNull(after.bodyHtml, "the body applied too, not half the change")
        }

    @Test
    fun `a write that finds the note moved is refused by the statement, not by a prior read`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)
            val brief = note(title = "Brief", html = "<p>approved</p>", projectId = project.id)
            notes.seed(brief)

            // The guarded write is the last line of defence: even if a caller read the
            // note and passed its own check, the statement must refuse a row that moved
            // in between. Here the note moves *after* the tool's check, which is exactly
            // the window a read-then-write leaves open.
            val output =
                decodeUpdate(
                    UpdateNoteTool(proposals, notes, currentUser, clock)
                        .execute(
                            UpdateNoteInput(
                                noteId = brief.id.value,
                                // A refreshed brief keeps its provenance: the section is
                                // what makes it a brief.
                                bodyMarkdown = "refreshed\n\n## Sources\n\n- https://example.com",
                                expectedUpdatedAt = brief.updatedAt.toEpochMilliseconds(),
                            ),
                        ),
                )
            assertTrue(output.proposalCreated, "the refresh was proposed: ${output.error}")

            clock.advance(kotlin.time.Duration.parse("1m"))
            notes.seed(brief.copy(bodyHtml = "<p>the user's own words</p>", updatedAt = clock.now()))

            val result = apply.confirm(singleItemId(output.proposalId), user)

            assertTrue(result.isFailure, "the stale write was refused")
            assertEquals(
                "<p>the user's own words</p>",
                notes.notes[brief.id.value]?.bodyHtml,
                "the user's edit survived",
            )
        }

    @Test
    fun `a refresh replaces the body a reader gets, not just the HTML field`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)

            // A note that still carries the legacy markdown projection next to the HTML.
            val brief =
                note(title = "Brief", html = "<p>approved</p>", projectId = project.id)
                    .copy(bodyMarkdown = "approved\n\n## Sources\n\n- https://example.com")
            notes.seed(brief)

            val output =
                decodeUpdate(
                    UpdateNoteTool(proposals, notes, currentUser, clock)
                        .execute(
                            UpdateNoteInput(
                                noteId = brief.id.value,
                                // A refreshed brief keeps its provenance: the section is
                                // what makes it a brief.
                                bodyMarkdown = "refreshed\n\n## Sources\n\n- https://example.com",
                                expectedUpdatedAt = brief.updatedAt.toEpochMilliseconds(),
                            ),
                        ),
                )
            assertTrue(apply.confirm(singleItemId(output.proposalId), user).isSuccess, "the refresh applied")

            // The reader must answer with the refreshed text. Left behind, the old
            // markdown would win and the confirmed refresh would be invisible.
            val after = decodeBrief(readTool.execute(GetKnowledgeBriefInput(projectId = project.id.value)))
            assertTrue(after.found, "the brief is still there: ${after.reason}")
            assertTrue(
                after.bodyMarkdown?.contains("refreshed") == true,
                "the reader returns the refreshed body, not the previous one: ${after.bodyMarkdown}",
            )
            assertTrue(
                after.bodyMarkdown?.contains("approved") != true,
                "the replaced text is gone: ${after.bodyMarkdown}",
            )
        }

    @Test
    fun `an update to a brief must state the version it was read at`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)
            val brief = note(title = "Brief", html = "<p>approved</p>", projectId = project.id)
            notes.seed(brief)

            // Without a version there is no guard at all: the proposal would be applied
            // to whatever the note became while the card waited.
            val output =
                decodeUpdate(
                    UpdateNoteTool(proposals, notes, currentUser, clock)
                        .execute(UpdateNoteInput(noteId = brief.id.value, bodyMarkdown = "refreshed")),
                )

            assertFalse(output.proposalCreated, "no proposal was made: ${output.error}")
            assertNotNull(output.error, "the model is told what is missing")
        }

    @Test
    fun `an ordinary note can still be updated without a version`() =
        runTest {
            // A note that is not a brief has no read to state — the in-app editor is the
            // one that proposes these, and a human confirming it is the decision.
            val plain = note(title = "Scratch", html = "<p>a</p>")
            notes.seed(plain)

            val output =
                decodeUpdate(
                    UpdateNoteTool(proposals, notes, currentUser, clock)
                        .execute(UpdateNoteInput(noteId = plain.id.value, bodyMarkdown = "b")),
                )

            assertTrue(output.proposalCreated, "the plain note was proposed: ${output.error}")
        }

    @Test
    fun `a folder attached to a project is not its brief`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)
            notes.seed(
                note(title = "Sources", html = "", projectId = project.id)
                    .copy(isFolder = true),
            )

            val output = decodeBrief(readTool.execute(GetKnowledgeBriefInput(projectId = project.id.value)))

            assertFalse(output.found, "a folder is not a brief: ${output.noteId}")
        }

    @Test
    fun `a summary is guarded like a body change`() =
        runTest {
            val project = project("Kitchen")
            projects.seed(project)
            val brief = note(title = "Brief", html = "<p>approved</p>", projectId = project.id)
            notes.seed(brief)

            val kind =
                com.singularity.todo.feature.proposals.domain.model.ProposalItemKind.SetNoteField(
                    com.singularity.todo.feature.proposals.domain.model.NoteValue.Summary(
                        "<p>a summary</p>",
                        expectedUpdatedAt = brief.updatedAt.toEpochMilliseconds(),
                    ),
                )
            val (built, _) =
                com.singularity.todo.feature.proposals.domain.logic.ProposalBuilder
                    .multiForTarget(
                        items =
                            listOf(
                                com.singularity.todo.feature.proposals.domain.logic.ProposalBuilder
                                    .item(kind, brief.id.value),
                            ),
                        targetId = brief.id.value,
                        userId = user,
                        source =
                            com.singularity.todo.feature.proposals.domain.model.ProposalSource.Agent,
                        clock = clock,
                    )
            proposals.save(built).getOrThrow()

            clock.advance(kotlin.time.Duration.parse("1m"))
            notes.seed(brief.copy(bodyHtml = "<p>the user's own words</p>", updatedAt = clock.now()))

            val result = apply.confirm(singleItemId(built.id.value), user)

            assertTrue(result.isFailure, "a summary written from an older note is refused")
            assertEquals(
                "<p>the user's own words</p>",
                notes.notes[brief.id.value]?.bodyHtml,
                "the user's edit survived",
            )
        }

    @Test
    fun `editing the value on the card keeps the version it was proposed against`() =
        runTest {
            val value =
                com.singularity.todo.feature.proposals.domain.model.NoteValue.Body(
                    "<p>refreshed</p>",
                    expectedUpdatedAt = 1_763_800_000_000,
                )

            val edited =
                com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
                    .SetNoteField(value)
                    .editableProposal()
                    ?.change
                    ?.invoke("<p>the user retyped it</p>")

            val editedKind =
                (edited as? com.singularity.todo.feature.proposals.domain.model.ReadResult.Ok)?.value
                    as? com.singularity.todo.feature.proposals.domain.model.ProposalItemKind.SetNoteField
            val editedValue =
                editedKind?.value as? com.singularity.todo.feature.proposals.domain.model.NoteValue.Body
            assertEquals(
                1_763_800_000_000,
                editedValue?.expectedUpdatedAt,
                "a value edited on the card is still written against the note it was proposed for",
            )
        }
}

package com.singularity.todo.feature.ai.tools

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.ai.McpToolCatalog
import com.singularity.todo.feature.ai.WriteBehavior
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.ProposalStatus
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeProposalRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * No tool writes the user's data. Every tool that changes something proposes it,
 * and the write happens when the user confirms the proposal.
 *
 * ## What this replaces
 *
 * The claim used to live in a file — `config/docs/ai-direct-writes.txt` — that
 * listed the tools writing directly, with the reason each one did. It was a
 * registry somebody had to remember to update, and the gate that read it could
 * only prove that the registry and the code agreed with each other.
 *
 * A test is a better home for the same claim. It runs the tools against fakes and
 * asserts on what the user's data looks like afterwards: a tool that writes fails
 * the test, and no list has to be kept in step with the code.
 *
 * ## Why there is a source scan as well
 *
 * The behavioural tests name the tools they exercise. A *new* tool that writes
 * would not be in that list, so the scan closes the gap: no tool's source may
 * call a write on a repository of the user's own data. The proposal repository is
 * the one exception — saving a proposal is how a tool proposes, not how it writes.
 */
@Tag("fast")
class WriteToolsOnlyProposeTest {

    private val clock: Clock = Clock.System
    private val userId = UserId("test-user")
    private val profileAwareUser = FakeProfileAwareCurrentUser(
        authRepository = FakeAuthRepository(initialSession = Session.Anonymous(userId)),
    )

    private val proposals = FakeProposalRepository(clock)
    private val tasks = FakeTaskRepository(explicitCurrentUser = profileAwareUser)
    private val notes = FakeNotesRepository(currentUser = profileAwareUser)
    private val projects = FakeProjectsRepository(currentUser = profileAwareUser)
    private val tags = FakeTagsRepository(currentUser = profileAwareUser)

    // ─── The behavioural half ───────────────────────────────────────────────────

    @Test
    fun `CreateTaskTool proposes a task instead of creating one`() = runTest {
        CreateTaskTool(proposals, profileAwareUser, clock)
            .execute(CreateTaskInput(title = "Buy groceries"))

        assertTrue(tasks.tasks.value.isEmpty(), "the task was not created")
        assertEquals(1, pendingProposals().size, "the change was proposed")
    }

    @Test
    fun `UpdateTaskTool proposes a change instead of making one`() = runTest {
        val existing = task()
        tasks.seed(existing)

        UpdateTaskTool(proposals, tasks, profileAwareUser, clock)
            .execute(UpdateTaskInput(taskId = existing.id.value, title = "Renamed"))

        assertEquals("Existing", tasks.tasks.value[existing.id.value]?.title, "the task was not renamed")
        assertEquals(1, pendingProposals().size, "the change was proposed")
    }

    @Test
    fun `CreateProjectTool proposes a project instead of creating one`() = runTest {
        CreateProjectTool(proposals, profileAwareUser, clock)
            .execute(CreateProjectInput(name = "Kitchen"))

        assertTrue(projects.observeAll().first().isEmpty(), "the project was not created")
        assertEquals(1, pendingProposals().size, "the change was proposed")
    }

    @Test
    fun `UpdateProjectTool proposes a change instead of making one`() = runTest {
        val existing = project()
        projects.seed(existing)

        UpdateProjectTool(proposals, projects, profileAwareUser, clock)
            .execute(UpdateProjectInput(projectId = existing.id.value, name = "Renamed"))

        assertEquals("Existing", projects.get(existing.id)?.name, "the project was not renamed")
        assertEquals(1, pendingProposals().size, "the change was proposed")
    }

    @Test
    fun `CreateNoteTool proposes a note instead of creating one`() = runTest {
        CreateNoteTool(proposals, profileAwareUser, clock)
            .execute(CreateNoteInput(title = "Agenda"))

        assertTrue(notes.notes.isEmpty(), "the note was not created")
        assertEquals(1, pendingProposals().size, "the change was proposed")
    }

    @Test
    fun `UpdateNoteTool proposes a change instead of making one`() = runTest {
        val existing = note()
        notes.seed(existing)

        UpdateNoteTool(proposals, notes, profileAwareUser, clock)
            .execute(UpdateNoteInput(noteId = existing.id.value, title = "Renamed"))

        assertEquals("Existing", notes.notes[existing.id.value]?.title, "the note was not renamed")
        assertEquals(1, pendingProposals().size, "the change was proposed")
    }

    @Test
    fun `CreateTagTool proposes a tag instead of creating one`() = runTest {
        CreateTagTool(proposals, profileAwareUser, clock)
            .execute(CreateTagInput(name = "chore"))

        assertTrue(tags.observeAll().first().isEmpty(), "the tag was not created")
        assertEquals(1, pendingProposals().size, "the change was proposed")
    }

    // ─── The structural half ────────────────────────────────────────────────────

    @Test
    fun `no tool source writes the user's data directly`() {
        val offences = mutableListOf<String>()

        Files.list(toolsDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".kt") }.forEach { file ->
                val text = Files.readString(file)
                repositoryWrite.findAll(text).forEach { match ->
                    val receiver = match.groupValues[1]
                    if (receiver.lowercase() !in PROPOSAL_RECEIVERS) {
                        offences += "${file.fileName}: $receiver.${match.groupValues[2]}"
                    }
                }
            }
        }

        assertTrue(
            offences.isEmpty(),
            "these tools write the user's data instead of proposing it: ${offences.joinToString(", ")}. " +
                "A proposal is a change nobody has agreed to yet; the write happens when the user confirms it.",
        )
    }

    // ─── The declaration against the behaviour ───────────────────────────────────

    @Test
    fun `every tool that writes is declared, and every declared exception writes`() {
        val declared = McpToolCatalog.catalog.associateBy { it.toolClass.simpleName }
        val findings = mutableListOf<String>()

        for ((className, writes) in scan) {
            val entry = declared[className]
            if (entry == null) {
                findings += "$className writes (${writes.all.joinToString()}) and the catalogue does not declare it"
                continue
            }
            if (entry.writeBehavior != WriteBehavior.DIRECT_WRITE_EXCEPTION && writes.all.isNotEmpty()) {
                findings += "${entry.name} writes (${writes.all.joinToString()}) and is declared " +
                    "${entry.writeBehavior}. A write that is not declared is a change the user never saw."
            }
        }

        for (entry in McpToolCatalog.catalog) {
            val className = entry.toolClass.simpleName
            if (entry.writeBehavior != WriteBehavior.DIRECT_WRITE_EXCEPTION || className == null) continue
            val writes = scan[className]
            if (writes == null) {
                findings += "${entry.name} is declared a direct-write exception, and its class is not among the tools"
            } else if (writes.all.isEmpty()) {
                findings += "${entry.name} is declared a direct-write exception and no longer writes. " +
                    "A stale exception is a promise nobody keeps."
            }
        }

        assertTrue(
            findings.isEmpty(),
            "the catalogue's declaration and the tools' behaviour disagree: ${findings.joinToString("; ")}",
        )
    }

    @Test
    fun `the exceptions the scan cannot see are named`() {
        val unreachable = McpToolCatalog.catalog
            .filter { it.writeBehavior == WriteBehavior.DIRECT_WRITE_EXCEPTION }
            .filter { entry ->
                val className = entry.toolClass.simpleName ?: return@filter false
                scan[className]?.userData.isNullOrEmpty()
            }
            .map { it.name }
            .toSet()

        assertEquals(
            UNVERIFIABLE_EXCEPTIONS,
            unreachable,
            "the exceptions the scan cannot verify changed. The scan recognises a write on a " +
                "repository of the user's own data; anything else is invisible to it, and an " +
                "invisible write has to be a named one. Update the list only after reading why " +
                "the tool writes the way it does.",
        )
    }

    @Test
    fun `the catalogue's classification is well formed`() {
        for (entry in McpToolCatalog.catalog) {
            if (entry.writeBehavior == WriteBehavior.DIRECT_WRITE_EXCEPTION) {
                assertTrue(
                    !entry.exceptionReason.isNullOrBlank(),
                    "${entry.name} writes directly and says nothing about why it sits outside the " +
                        "proposal pipeline. An exception without a reason is not an exception, it is a hole.",
                )
            } else {
                assertTrue(
                    entry.exceptionReason.isNullOrBlank(),
                    "${entry.name} is ${entry.writeBehavior} and still carries an exception reason. " +
                        "A reason that does not apply is a reason nobody will read.",
                )
            }
        }
    }

    // ─── The scan ───────────────────────────────────────────────────────────────

    /**
     * The write calls each tool class makes, by the class they sit in.
     *
     * The owner of a call is the class declared above it. One file holds several
     * tools — `AdrTools.kt` holds the readers and the writers side by side — so
     * blaming the whole file for one writer would blame the readers too.
     *
     * Two nets are cast. The first recognises a write on a repository of the
     * user's own data — the write the promise is about. The second recognises a
     * write through any port at all, and exists so that a write the first net
     * cannot see is still *seen*: an undeclared write is a finding whichever port
     * it goes through.
     */
    private val scan: Map<String, Writes> by lazy { scanSources() }

    private fun scanSources(): Map<String, Writes> {
        val found = mutableMapOf<String, Writes>()
        for (path in toolFiles()) {
            var current: String? = null
            for (line in Files.readAllLines(path)) {
                val declaration = classDeclaration.find(line)
                val owner = current
                if (declaration != null) {
                    current = declaration.groupValues[1]
                } else if (owner != null) {
                    val userData = repositoryWrite.findAll(line)
                        .filter { it.groupValues[1].lowercase() !in PROPOSAL_RECEIVERS }
                        .map { it.groupValues[1] + "." + it.groupValues[2] }
                        .toList()
                    val other = anyWrite.findAll(line)
                        .filter { it.groupValues[1].lowercase() !in PROPOSAL_RECEIVERS }
                        .map { it.groupValues[1] + "." + it.groupValues[2] }
                        .filterNot { it in userData }
                        .toList()
                    if (userData.isNotEmpty() || other.isNotEmpty()) {
                        val existing = found[owner] ?: Writes()
                        found[owner] = Writes(
                            userData = (existing.userData + userData).distinct().sorted(),
                            other = (existing.other + other).distinct().sorted(),
                        )
                    }
                }
            }
        }
        return found
    }

    /** The write calls one tool class makes. */
    private data class Writes(
        /** Calls on a repository of the user's own data. */
        val userData: List<String> = emptyList(),
        /** Calls that look like a write through any port. */
        val other: List<String> = emptyList(),
    ) {
        /** Every call, without repeats. */
        val all: List<String> get() = (userData + other).distinct().sorted()
    }

    // ─── Fixtures ──────────────────────────────────────────────────────────────

    private suspend fun pendingProposals(): List<AiProposal> =
        proposals.watchProposalsByStatus(ProposalStatus.Pending).first()

    private fun note() = Note(
        id = NoteId.generate(),
        userId = userId,
        title = "Existing",
        createdAt = clock.now(),
        updatedAt = clock.now(),
    )

    private fun project() = Project(
        id = ProjectId.generate(),
        name = "Existing",
        color = 0,
        createdAt = clock.now(),
        updatedAt = clock.now(),
        userId = userId,
    )

    private fun task() = Task(
        id = TaskId.generate(),
        title = "Existing",
        priority = TaskPriority.Medium,
        kind = TaskKind.Task,
        tags = emptyList(),
        createdAt = clock.now(),
        updatedAt = clock.now(),
        userId = userId,
    )

    /**
     * Every file that holds a tool class.
     */
    private fun toolFiles(): List<Path> =
        Files.list(toolsDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".kt") }.sorted().toList()
        }

    private companion object {
        /**
         * A class declaration. The scan attributes each call to the class it sits
         * in, because one file holds several tools.
         */
        val classDeclaration = Regex("""\bclass\s+(\w*Tool)\b""")

        /**
         * A call on a repository. The receiver is captured so the proposal
         * repository can be told apart from the user's own data.
         */
        val repositoryWrite = Regex("""\b(\w*[Rr]epository)\.(create|update|delete|upsert|save)\(""")

        /**
         * A call that looks like a write, on any receiver.
         *
         * The net is wider than [repositoryWrite] on purpose: a write the user's
         * data lives behind is still a write, and an undeclared one has to be a
         * finding whichever port it goes through.
         */
        val anyWrite = Regex(
            """\b(\w+)\.(create|update|delete|upsert|save|write|writeAdr|updateAdr|insert|put)\(""",
        )

        /**
         * The exceptions the scan cannot verify.
         *
         * The scan recognises a write on a repository of the user's own data. The
         * ADR tools write a decision file through a storage port, and that write is
         * not the user's data — it is a decision the user asked for by name. The
         * scan cannot see it, so the exception is named here rather than verified:
         * a write nobody checked has to be a write everybody can see.
         */
        val UNVERIFIABLE_EXCEPTIONS = setOf("write_adr", "update_adr")

        /** The proposal repository, by the names it is given in the tools. */
        val PROPOSAL_RECEIVERS = setOf("proposals", "proposalrepository")

        /**
         * Where the tools live.
         *
         * The sources are inside this module, so they are a compile input of the
         * test task: change a tool and this test runs again.
         */
        val toolsDir: Path = run {
            var dir: Path? = Path.of("").toAbsolutePath()
            while (dir != null) {
                val candidate = dir.resolve("src/commonMain/kotlin/com/singularity/todo/feature/ai/tools")
                if (Files.isDirectory(candidate)) return@run candidate
                dir = dir.parent
            }
            error("the tools directory was not found above ${Path.of("").toAbsolutePath()}")
        }
    }
}

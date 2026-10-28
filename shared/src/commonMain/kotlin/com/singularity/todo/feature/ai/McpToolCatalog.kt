package com.singularity.todo.feature.ai

import ai.koog.agents.core.tools.Tool
import com.singularity.todo.feature.ai.tools.ClusterNotesTool
import com.singularity.todo.feature.ai.tools.ClusterTasksTool
import com.singularity.todo.feature.ai.tools.CreateNoteTool
import com.singularity.todo.feature.ai.tools.CreateProjectTool
import com.singularity.todo.feature.ai.tools.CreateTagTool
import com.singularity.todo.feature.ai.tools.CreateTaskTool
import com.singularity.todo.feature.ai.tools.DecomposeAndCreateTool
import com.singularity.todo.feature.ai.tools.DecomposeTaskTool
import com.singularity.todo.feature.ai.tools.DeleteNoteTool
import com.singularity.todo.feature.ai.tools.DeleteProjectTool
import com.singularity.todo.feature.ai.tools.DeleteTagTool
import com.singularity.todo.feature.ai.tools.DeleteTaskTool
import com.singularity.todo.feature.ai.tools.ExtractActionsTool
import com.singularity.todo.feature.ai.tools.FirstStepTool
import com.singularity.todo.feature.ai.tools.GenerateChecklistTool
import com.singularity.todo.feature.ai.tools.GenerateDescriptionTool
import com.singularity.todo.feature.ai.tools.GetDailyNoteTool
import com.singularity.todo.feature.ai.tools.GetNoteTool
import com.singularity.todo.feature.ai.tools.GetProjectTool
import com.singularity.todo.feature.ai.tools.GetTaskTool
import com.singularity.todo.feature.ai.tools.ImproveNoteTool
import com.singularity.todo.feature.ai.tools.ListAdrsTool
import com.singularity.todo.feature.ai.tools.ListLinkedTasksTool
import com.singularity.todo.feature.ai.tools.ListOpenDeferredTool
import com.singularity.todo.feature.ai.tools.ListProjectsTool
import com.singularity.todo.feature.ai.tools.ListTasksTool
import com.singularity.todo.feature.ai.tools.PickTimeTool
import com.singularity.todo.feature.ai.tools.ProjectReviewTool
import com.singularity.todo.feature.ai.tools.ReadAdrTool
import com.singularity.todo.feature.ai.tools.RefineTaskTool
import com.singularity.todo.feature.ai.tools.RewriteNoteTool
import com.singularity.todo.feature.ai.tools.SearchDecisionsTool
import com.singularity.todo.feature.ai.tools.SearchDocsTool
import com.singularity.todo.feature.ai.tools.SearchTasksTool
import com.singularity.todo.feature.ai.tools.SmartRewriteTool
import com.singularity.todo.feature.ai.tools.SuggestTagsTool
import com.singularity.todo.feature.ai.tools.SummarizeNoteTool
import com.singularity.todo.feature.ai.tools.UpdateAdrTool
import com.singularity.todo.feature.ai.tools.UpdateNoteTool
import com.singularity.todo.feature.ai.tools.UpdateProjectTool
import com.singularity.todo.feature.ai.tools.UpdateTaskTool
import com.singularity.todo.feature.ai.tools.WeeklyPlanTool
import com.singularity.todo.feature.ai.tools.WriteAdrTool
import com.singularity.todo.feature.ai.tools.WriteTaskProposalTool
import kotlin.reflect.KClass

/**
 * How a tool writes.
 *
 * ## Why this is declared rather than inferred
 *
 * The application's promise is that a write to the user's data asks first. The promise is
 * only as good as the exception list, and an exception list nobody can see is a belief.
 * Declaring the behaviour per tool turns it into something the build checks: a tool added
 * without choosing one does not compile.
 *
 * The three values, and what each one promises:
 *
 * - [READ_ONLY] — nothing changes.
 * - [PROPOSAL_WRITE] — the user's data changes only after the user confirms a proposal.
 * - [DIRECT_WRITE_EXCEPTION] — the tool writes immediately, and **must** say why it sits
 *   outside the pipeline. A reason is not decoration: it is what makes the exception
 *   reviewable by somebody reading the registry rather than the tool's code.
 */
enum class WriteBehavior {
    /** Does not change state. */
    READ_ONLY,

    /** Changes the user's data by proposing first. */
    PROPOSAL_WRITE,

    /** Writes directly. Requires a stated reason. */
    DIRECT_WRITE_EXCEPTION,
}

/**
 * Declarative registry of every MCP tool.
 *
 * Every tool that can be called by an MCP client is declared here with its
 * Koog [toolClass], the snake_case [name] the client uses, and the
 * [ToolAnnotations] that describe its behaviour to the LLM.
 *
 * ## What this object owns — and what it does not
 *
 * This catalogue owns the **declared surface**: which tools exist, which class
 * implements each one, which domain it belongs to, and the annotations the
 * client sees.  It does **not** own the prose.
 *
 * The one-line description of a tool lives on the tool class itself, as
 * `const val DESCRIPTION` next to `const val NAME`.  That is the text the MCP
 * server puts into the tool schema, so it is the text the agent actually
 * reads.  The catalogue used to carry a second copy of every description, and
 * the two copies drifted apart in 32 of 39 tools — including five cases where
 * the catalogue described a *different capability* than the tool implements
 * (`delete_task` promised a permanent delete while the tool creates a
 * proposal that needs confirmation).  A duplicated fact that nobody diffs is
 * a documentation bug waiting to happen, so the duplicate is gone: one owner
 * per fact.
 *
 * ## The DI lists are not derived from this catalogue
 *
 * The registration lists in `aiToolsModule` (one per platform) are maintained
 * by hand.  Adding a tool therefore means touching the catalogue **and** both
 * DI modules.  That duplication is real and is now policed rather than
 * assumed away: the runtime wiring test resolves the actual Koin list and
 * compares it with this catalogue, and `scripts/check-skill-drift.py` compares
 * the sources.  A tool in one list and missing from the other fails the build.
 *
 * The list *could* be derived from this catalogue — the entries hold the class
 * itself — and it is not, for a reason worth knowing: the Koin compiler plugin
 * checks every definition request against the *static* type it sees, and in a
 * heterogeneous list that type is the projection `Tool<*, *>`, for which no
 * definition exists.  The plugin reports a missing definition for a type nobody
 * asked for, and it follows the call into a helper function, so the resolution
 * cannot be moved out of the way either.  The compile-time check that every
 * tool has a definition is therefore paid in the test suite instead.
 * See ADR `2026-10-21-the-toolbox-surface-from-the-compiler-down`.
 *
 * ## Tool naming convention
 *
 * Names are snake_case as exposed to the MCP client.  They must match the
 * [ai.koog.agents.core.tools.Tool.descriptor.name] of the corresponding
 * Koog tool class.
 */
object McpToolCatalog {

    /**
     * The version of the contract an agent reads.
     *
     * The agent-facing surface has three parts — the catalogue of tools, the payload
     * `describe_toolbox` returns, and the resource URIs — and a client is not upgraded
     * with the server. So the surface carries a version, and a client that knows a
     * different one can say so instead of reading a field it does not understand as
     * if it did.
     *
     * The rules are the same as everywhere else in this surface: a **new** field is
     * allowed, a **renamed** or **removed** field is not. The version is bumped when
     * the shape changes in a way a client can observe — a renamed field, a removed
     * field, a changed meaning.
     *
     * Version 3: `create_tag` proposes like the rest of the write tools, so its
     * answer lost the `tagId` field — an id that named a tag nobody had agreed to
     * exist — and answers with `proposalCreated` and `proposalId` instead.
     */
    const val SURFACE_CONTRACT_VERSION = 3

    /**
     * A single tool entry in the catalog.
     *
     * @param name       snake_case name as seen by the MCP client.
     * @param toolClass  the Koog tool class itself. The class *is* the reference —
     *                   a name in a string would be a second copy of a fact that
     *                   could drift, and the catalogue is the place the tools are
     *                   declared.
     * @param domain     logical domain: "tasks", "projects", "notes", "tags", "adr".
     * @param annotations behaviour hints for the LLM.
     * @param writeBehavior how this tool writes. **No default**: a tool added without
     *   declaring it does not compile, and the exception is a red build rather than a
     *   tool that quietly writes.
     * @param exceptionReason why a direct write sits outside the proposal pipeline.
     *   Required for [WriteBehavior.DIRECT_WRITE_EXCEPTION] and forbidden otherwise.
     */
    data class ToolEntry(
        val name: String,
        val toolClass: KClass<out Tool<*, *>>,
        val domain: String,
        val annotations: ToolAnnotations = ToolAnnotations(),
        val writeBehavior: WriteBehavior,
        val exceptionReason: String? = null,
    )

    /**
     * Every MCP tool registered by this application.
     *
     * The description of each tool is not here — it belongs to the tool class.
     * See the object documentation above.
     */
    val catalog: List<ToolEntry> = listOf(
        // ── Tasks ──────────────────────────────────────────────────────────────
        ToolEntry(
            name = "create_task",
            writeBehavior = WriteBehavior.PROPOSAL_WRITE,
            toolClass = CreateTaskTool::class,
            domain = "tasks",
            // The tool proposes: two calls with the same input are two proposals,
            // each naming the task it would create. It is not idempotent any more.
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "update_task",
            writeBehavior = WriteBehavior.PROPOSAL_WRITE,
            toolClass = UpdateTaskTool::class,
            domain = "tasks",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "delete_task",
            writeBehavior = WriteBehavior.PROPOSAL_WRITE,
            toolClass = DeleteTaskTool::class,
            domain = "tasks",
            annotations = ToolAnnotations(destructiveHint = true),
        ),
        ToolEntry(
            name = "list_tasks",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = ListTasksTool::class,
            domain = "tasks",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "get_task",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = GetTaskTool::class,
            domain = "tasks",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "write_task_proposal",
            writeBehavior = WriteBehavior.PROPOSAL_WRITE,
            toolClass = WriteTaskProposalTool::class,
            domain = "tasks",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "search_tasks",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = SearchTasksTool::class,
            domain = "tasks",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "first_step",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = FirstStepTool::class,
            domain = "tasks",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "decompose_and_create",
            writeBehavior = WriteBehavior.PROPOSAL_WRITE,
            toolClass = DecomposeAndCreateTool::class,
            domain = "tasks",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "list_linked_tasks",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = ListLinkedTasksTool::class,
            domain = "tasks",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),

        // ── Projects ─────────────────────────────────────────────────────────
        ToolEntry(
            name = "create_project",
            writeBehavior = WriteBehavior.PROPOSAL_WRITE,
            toolClass = CreateProjectTool::class,
            domain = "projects",
            // The tool proposes: two calls with the same input are two proposals.
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "update_project",
            writeBehavior = WriteBehavior.PROPOSAL_WRITE,
            toolClass = UpdateProjectTool::class,
            domain = "projects",
            // The tool proposes: two calls with the same input are two proposals.
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "delete_project",
            writeBehavior = WriteBehavior.PROPOSAL_WRITE,
            toolClass = DeleteProjectTool::class,
            domain = "projects",
            annotations = ToolAnnotations(destructiveHint = true),
        ),
        ToolEntry(
            name = "list_projects",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = ListProjectsTool::class,
            domain = "projects",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "get_project",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = GetProjectTool::class,
            domain = "projects",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),

        // ── Notes ─────────────────────────────────────────────────────────────
        ToolEntry(
            name = "create_note",
            writeBehavior = WriteBehavior.PROPOSAL_WRITE,
            toolClass = CreateNoteTool::class,
            domain = "notes",
            // The tool proposes: two calls with the same input are two proposals.
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "update_note",
            writeBehavior = WriteBehavior.PROPOSAL_WRITE,
            toolClass = UpdateNoteTool::class,
            domain = "notes",
            // The tool proposes: two calls with the same input are two proposals.
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "delete_note",
            writeBehavior = WriteBehavior.PROPOSAL_WRITE,
            toolClass = DeleteNoteTool::class,
            domain = "notes",
            annotations = ToolAnnotations(destructiveHint = true),
        ),
        ToolEntry(
            name = "get_note",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = GetNoteTool::class,
            domain = "notes",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "get_daily_note",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = GetDailyNoteTool::class,
            domain = "notes",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),

        // ── Tags ─────────────────────────────────────────────────────────────
        ToolEntry(
            name = "create_tag",
            writeBehavior = WriteBehavior.PROPOSAL_WRITE,
            toolClass = CreateTagTool::class,
            domain = "tags",
            // The tool proposes: two calls with the same name are two proposals,
            // each naming the tag it would create, and nothing enforces that a tag
            // name is unique. It is not idempotent any more.
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "delete_tag",
            writeBehavior = WriteBehavior.PROPOSAL_WRITE,
            toolClass = DeleteTagTool::class,
            domain = "tags",
            annotations = ToolAnnotations(destructiveHint = true),
        ),

        // ── ADR ──────────────────────────────────────────────────────────────
        ToolEntry(
            name = "write_adr",
            writeBehavior = WriteBehavior.DIRECT_WRITE_EXCEPTION,
            exceptionReason = "Writes a decision file under the project's decisions directory, not user data.",
            toolClass = WriteAdrTool::class,
            domain = "adr",
            annotations = ToolAnnotations(openWorldHint = true),
        ),
        ToolEntry(
            name = "list_adrs",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = ListAdrsTool::class,
            domain = "adr",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "update_adr",
            writeBehavior = WriteBehavior.DIRECT_WRITE_EXCEPTION,
            exceptionReason = "Writes a decision file under the project's decisions directory, not user data.",
            toolClass = UpdateAdrTool::class,
            domain = "adr",
            annotations = ToolAnnotations(openWorldHint = true),
        ),
        ToolEntry(
            name = "read_adr",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = ReadAdrTool::class,
            domain = "adr",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "list_open_deferred",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = ListOpenDeferredTool::class,
            domain = "adr",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "search_decisions",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = SearchDecisionsTool::class,
            domain = "adr",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),

        // ── Documents ───────────────────────────────────────────────────────
        ToolEntry(
            name = "search_docs",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = SearchDocsTool::class,
            domain = "docs",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),

        // ── AI Write Tools ──────────────────────────────────────────────────
        ToolEntry(
            name = "refine_task",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = RefineTaskTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "smart_rewrite",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = SmartRewriteTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "generate_description",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = GenerateDescriptionTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "decompose_task",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = DecomposeTaskTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "generate_checklist",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = GenerateChecklistTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "pick_time",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = PickTimeTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "cluster_tasks",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = ClusterTasksTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "cluster_notes",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = ClusterNotesTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "project_review",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = ProjectReviewTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "weekly_plan",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = WeeklyPlanTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "improve_note",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = ImproveNoteTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "summarize_note",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = SummarizeNoteTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "extract_actions",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = ExtractActionsTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "rewrite_note",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = RewriteNoteTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "suggest_tags",
            writeBehavior = WriteBehavior.READ_ONLY,
            toolClass = SuggestTagsTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
    ).also { checkClassification(it) }

    /**
     * The classification has to be well-formed before anything reads it.
     *
     * The rule is the one this object's own documentation states: a direct write
     * carries the reason it sits outside the proposal pipeline, and nothing else
     * carries a reason. It was written down and not checked, and the first thing
     * the absence bought was a tool that had been converted to the proposal path
     * while the catalogue still called it a direct writer — the declaration was
     * the stale half, and nothing noticed.
     *
     * The check is here rather than in a test because the catalogue is what every
     * other thing reads: a malformed entry is a promise that cannot be read, and
     * it should fail at the moment the catalogue is loaded rather than in a
     * report somebody has to remember to open.
     */
    private fun checkClassification(entries: List<ToolEntry>) {
        for (entry in entries) {
            val reason = entry.exceptionReason
            if (entry.writeBehavior == WriteBehavior.DIRECT_WRITE_EXCEPTION) {
                check(!reason.isNullOrBlank()) {
                    "${entry.name} writes directly and says nothing about why it sits outside the " +
                        "proposal pipeline. An exception without a reason is not an exception, it is " +
                        "a hole."
                }
            } else {
                check(reason.isNullOrBlank()) {
                    "${entry.name} is ${entry.writeBehavior} and still carries an exception reason. " +
                        "A reason that does not apply is a reason nobody will read."
                }
            }
        }
    }

    /**
     * All tool names registered in this catalog.
     */
    val toolNames: Set<String> = catalog.mapTo(mutableSetOf()) { it.name }

    /**
     * Returns the [ToolEntry] for [name], or null if not found.
     */
    fun byName(name: String): ToolEntry? = catalog.find { it.name == name }
}

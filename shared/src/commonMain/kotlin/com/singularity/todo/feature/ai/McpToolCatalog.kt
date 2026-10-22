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
     */
    const val SURFACE_CONTRACT_VERSION = 1

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
     */
    data class ToolEntry(
        val name: String,
        val toolClass: KClass<out Tool<*, *>>,
        val domain: String,
        val annotations: ToolAnnotations = ToolAnnotations(),
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
            toolClass = CreateTaskTool::class,
            domain = "tasks",
            annotations = ToolAnnotations(idempotentHint = true),
        ),
        ToolEntry(
            name = "update_task",
            toolClass = UpdateTaskTool::class,
            domain = "tasks",
            annotations = ToolAnnotations(idempotentHint = true),
        ),
        ToolEntry(
            name = "delete_task",
            toolClass = DeleteTaskTool::class,
            domain = "tasks",
            annotations = ToolAnnotations(destructiveHint = true),
        ),
        ToolEntry(
            name = "list_tasks",
            toolClass = ListTasksTool::class,
            domain = "tasks",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "get_task",
            toolClass = GetTaskTool::class,
            domain = "tasks",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "write_task_proposal",
            toolClass = WriteTaskProposalTool::class,
            domain = "tasks",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "search_tasks",
            toolClass = SearchTasksTool::class,
            domain = "tasks",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "first_step",
            toolClass = FirstStepTool::class,
            domain = "tasks",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "decompose_and_create",
            toolClass = DecomposeAndCreateTool::class,
            domain = "tasks",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "list_linked_tasks",
            toolClass = ListLinkedTasksTool::class,
            domain = "tasks",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),

        // ── Projects ─────────────────────────────────────────────────────────
        ToolEntry(
            name = "create_project",
            toolClass = CreateProjectTool::class,
            domain = "projects",
            annotations = ToolAnnotations(idempotentHint = true),
        ),
        ToolEntry(
            name = "update_project",
            toolClass = UpdateProjectTool::class,
            domain = "projects",
            annotations = ToolAnnotations(idempotentHint = true),
        ),
        ToolEntry(
            name = "delete_project",
            toolClass = DeleteProjectTool::class,
            domain = "projects",
            annotations = ToolAnnotations(destructiveHint = true),
        ),
        ToolEntry(
            name = "list_projects",
            toolClass = ListProjectsTool::class,
            domain = "projects",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "get_project",
            toolClass = GetProjectTool::class,
            domain = "projects",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),

        // ── Notes ─────────────────────────────────────────────────────────────
        ToolEntry(
            name = "create_note",
            toolClass = CreateNoteTool::class,
            domain = "notes",
            annotations = ToolAnnotations(idempotentHint = true),
        ),
        ToolEntry(
            name = "update_note",
            toolClass = UpdateNoteTool::class,
            domain = "notes",
            annotations = ToolAnnotations(idempotentHint = true),
        ),
        ToolEntry(
            name = "delete_note",
            toolClass = DeleteNoteTool::class,
            domain = "notes",
            annotations = ToolAnnotations(destructiveHint = true),
        ),
        ToolEntry(
            name = "get_note",
            toolClass = GetNoteTool::class,
            domain = "notes",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),

        // ── Tags ─────────────────────────────────────────────────────────────
        ToolEntry(
            name = "create_tag",
            toolClass = CreateTagTool::class,
            domain = "tags",
            annotations = ToolAnnotations(idempotentHint = true),
        ),
        ToolEntry(
            name = "delete_tag",
            toolClass = DeleteTagTool::class,
            domain = "tags",
            annotations = ToolAnnotations(destructiveHint = true),
        ),

        // ── ADR ──────────────────────────────────────────────────────────────
        ToolEntry(
            name = "write_adr",
            toolClass = WriteAdrTool::class,
            domain = "adr",
            annotations = ToolAnnotations(openWorldHint = true),
        ),
        ToolEntry(
            name = "list_adrs",
            toolClass = ListAdrsTool::class,
            domain = "adr",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "update_adr",
            toolClass = UpdateAdrTool::class,
            domain = "adr",
            annotations = ToolAnnotations(openWorldHint = true),
        ),
        ToolEntry(
            name = "read_adr",
            toolClass = ReadAdrTool::class,
            domain = "adr",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "list_open_deferred",
            toolClass = ListOpenDeferredTool::class,
            domain = "adr",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "search_decisions",
            toolClass = SearchDecisionsTool::class,
            domain = "adr",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),

        // ── Documents ───────────────────────────────────────────────────────
        ToolEntry(
            name = "search_docs",
            toolClass = SearchDocsTool::class,
            domain = "docs",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),

        // ── AI Write Tools ──────────────────────────────────────────────────
        ToolEntry(
            name = "refine_task",
            toolClass = RefineTaskTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "smart_rewrite",
            toolClass = SmartRewriteTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "generate_description",
            toolClass = GenerateDescriptionTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "decompose_task",
            toolClass = DecomposeTaskTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "generate_checklist",
            toolClass = GenerateChecklistTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "pick_time",
            toolClass = PickTimeTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "cluster_tasks",
            toolClass = ClusterTasksTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "cluster_notes",
            toolClass = ClusterNotesTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "project_review",
            toolClass = ProjectReviewTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "weekly_plan",
            toolClass = WeeklyPlanTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "improve_note",
            toolClass = ImproveNoteTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "summarize_note",
            toolClass = SummarizeNoteTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "extract_actions",
            toolClass = ExtractActionsTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "rewrite_note",
            toolClass = RewriteNoteTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "suggest_tags",
            toolClass = SuggestTagsTool::class,
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
    )

    /**
     * All tool names registered in this catalog.
     */
    val toolNames: Set<String> = catalog.mapTo(mutableSetOf()) { it.name }

    /**
     * Returns the [ToolEntry] for [name], or null if not found.
     */
    fun byName(name: String): ToolEntry? = catalog.find { it.name == name }
}

package com.singularity.todo.mcp

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
 * ## Tool naming convention
 *
 * Names are snake_case as exposed to the MCP client.  They must match the
 * [ai.koog.agents.core.tools.Tool.descriptor.name] of the corresponding
 * Koog tool class.
 */
object McpToolCatalog {

    /**
     * A single tool entry in the catalog.
     *
     * @param name       snake_case name as seen by the MCP client.
     * @param toolClass  simple name of the Koog tool class (no package prefix).
     *                   Used only for documentation and cross-reference.
     * @param domain     logical domain: "tasks", "projects", "notes", "tags", "adr".
     * @param annotations behaviour hints for the LLM.
     */
    data class ToolEntry(
        val name: String,
        val toolClass: String,
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
            toolClass = "CreateTaskTool",
            domain = "tasks",
            annotations = ToolAnnotations(idempotentHint = true),
        ),
        ToolEntry(
            name = "update_task",
            toolClass = "UpdateTaskTool",
            domain = "tasks",
            annotations = ToolAnnotations(idempotentHint = true),
        ),
        ToolEntry(
            name = "delete_task",
            toolClass = "DeleteTaskTool",
            domain = "tasks",
            annotations = ToolAnnotations(destructiveHint = true),
        ),
        ToolEntry(
            name = "list_tasks",
            toolClass = "ListTasksTool",
            domain = "tasks",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "get_task",
            toolClass = "GetTaskTool",
            domain = "tasks",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "search_tasks",
            toolClass = "SearchTasksTool",
            domain = "tasks",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "decompose_and_create",
            toolClass = "DecomposeAndCreateTool",
            domain = "tasks",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "list_linked_tasks",
            toolClass = "ListLinkedTasksTool",
            domain = "tasks",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),

        // ── Projects ─────────────────────────────────────────────────────────
        ToolEntry(
            name = "create_project",
            toolClass = "CreateProjectTool",
            domain = "projects",
            annotations = ToolAnnotations(idempotentHint = true),
        ),
        ToolEntry(
            name = "update_project",
            toolClass = "UpdateProjectTool",
            domain = "projects",
            annotations = ToolAnnotations(idempotentHint = true),
        ),
        ToolEntry(
            name = "delete_project",
            toolClass = "DeleteProjectTool",
            domain = "projects",
            annotations = ToolAnnotations(destructiveHint = true),
        ),
        ToolEntry(
            name = "list_projects",
            toolClass = "ListProjectsTool",
            domain = "projects",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "get_project",
            toolClass = "GetProjectTool",
            domain = "projects",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),

        // ── Notes ─────────────────────────────────────────────────────────────
        ToolEntry(
            name = "create_note",
            toolClass = "CreateNoteTool",
            domain = "notes",
            annotations = ToolAnnotations(idempotentHint = true),
        ),
        ToolEntry(
            name = "update_note",
            toolClass = "UpdateNoteTool",
            domain = "notes",
            annotations = ToolAnnotations(idempotentHint = true),
        ),
        ToolEntry(
            name = "delete_note",
            toolClass = "DeleteNoteTool",
            domain = "notes",
            annotations = ToolAnnotations(destructiveHint = true),
        ),
        ToolEntry(
            name = "get_note",
            toolClass = "GetNoteTool",
            domain = "notes",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),

        // ── Tags ─────────────────────────────────────────────────────────────
        ToolEntry(
            name = "create_tag",
            toolClass = "CreateTagTool",
            domain = "tags",
            annotations = ToolAnnotations(idempotentHint = true),
        ),
        ToolEntry(
            name = "delete_tag",
            toolClass = "DeleteTagTool",
            domain = "tags",
            annotations = ToolAnnotations(destructiveHint = true),
        ),

        // ── ADR ──────────────────────────────────────────────────────────────
        ToolEntry(
            name = "write_adr",
            toolClass = "WriteAdrTool",
            domain = "adr",
            annotations = ToolAnnotations(openWorldHint = true),
        ),
        ToolEntry(
            name = "list_adrs",
            toolClass = "ListAdrsTool",
            domain = "adr",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "update_adr",
            toolClass = "UpdateAdrTool",
            domain = "adr",
            annotations = ToolAnnotations(openWorldHint = true),
        ),
        ToolEntry(
            name = "read_adr",
            toolClass = "ReadAdrTool",
            domain = "adr",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "list_open_deferred",
            toolClass = "ListOpenDeferredTool",
            domain = "adr",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "search_decisions",
            toolClass = "SearchDecisionsTool",
            domain = "adr",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),

        // ── Documents ───────────────────────────────────────────────────────
        ToolEntry(
            name = "search_docs",
            toolClass = "SearchDocsTool",
            domain = "docs",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),

        // ── AI Write Tools ──────────────────────────────────────────────────
        ToolEntry(
            name = "refine_task",
            toolClass = "RefineTaskTool",
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "smart_rewrite",
            toolClass = "SmartRewriteTool",
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "generate_description",
            toolClass = "GenerateDescriptionTool",
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "decompose_task",
            toolClass = "DecomposeTaskTool",
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "generate_checklist",
            toolClass = "GenerateChecklistTool",
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "pick_time",
            toolClass = "PickTimeTool",
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "cluster_tasks",
            toolClass = "ClusterTasksTool",
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "cluster_notes",
            toolClass = "ClusterNotesTool",
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "project_review",
            toolClass = "ProjectReviewTool",
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "weekly_plan",
            toolClass = "WeeklyPlanTool",
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "improve_note",
            toolClass = "ImproveNoteTool",
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "summarize_note",
            toolClass = "SummarizeNoteTool",
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "extract_actions",
            toolClass = "ExtractActionsTool",
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "rewrite_note",
            toolClass = "RewriteNoteTool",
            domain = "ai",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "suggest_tags",
            toolClass = "SuggestTagsTool",
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

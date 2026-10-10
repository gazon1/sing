package com.singularity.todo.mcp

/**
 * Single declarative registry of every MCP tool.
 *
 * Every tool that can be called by an MCP client is declared here with its
 * Koog [toolClass], the snake_case [name] the client uses, and the
 * [ToolAnnotations] that describe its behaviour to the LLM.
 *
 * This object is the **single source of truth** for:
 * - The DI registration list in [aiToolsModule] (derived from [catalog])
 * - The MCP server's tool descriptors (derived from [catalog])
 *
 * Adding a tool: add it to [catalog] here, and it is automatically included
 * in DI and the MCP registry.  Removing a tool: remove from [catalog] and all
 * three places update together.
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
     * @param description one-line description of what the tool does.
     * @param annotations behaviour hints for the LLM.
     */
    data class ToolEntry(
        val name: String,
        val toolClass: String,
        val domain: String,
        val description: String,
        val annotations: ToolAnnotations = ToolAnnotations(),
    )

    /**
     * Every MCP tool registered by this application.
     *
     * DI registration in [aiToolsModule] is derived from this list.
     * To add a tool: declare it here and it appears in DI and the MCP registry.
     */
    val catalog: List<ToolEntry> = listOf(
        // ── Tasks ──────────────────────────────────────────────────────────────
        ToolEntry(
            name = "create_task",
            toolClass = "CreateTaskTool",
            domain = "tasks",
            description = "Creates a new task with the given properties",
            annotations = ToolAnnotations(idempotentHint = true),
        ),
        ToolEntry(
            name = "update_task",
            toolClass = "UpdateTaskTool",
            domain = "tasks",
            description = "Updates an existing task's properties",
            annotations = ToolAnnotations(idempotentHint = true),
        ),
        ToolEntry(
            name = "delete_task",
            toolClass = "DeleteTaskTool",
            domain = "tasks",
            description = "Permanently deletes a task",
            annotations = ToolAnnotations(destructiveHint = true),
        ),
        ToolEntry(
            name = "list_tasks",
            toolClass = "ListTasksTool",
            domain = "tasks",
            description = "Lists tasks matching optional filters",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "get_task",
            toolClass = "GetTaskTool",
            domain = "tasks",
            description = "Gets a single task by ID",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "search_tasks",
            toolClass = "SearchTasksTool",
            domain = "tasks",
            description = "Full-text search over tasks",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "decompose_and_create",
            toolClass = "DecomposeAndCreateTool",
            domain = "tasks",
            description = "Decomposes a task into sub-tasks and creates them all",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "list_linked_tasks",
            toolClass = "ListLinkedTasksTool",
            domain = "tasks",
            description = "Lists tasks linked to a given task or note",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),

        // ── Projects ─────────────────────────────────────────────────────────
        ToolEntry(
            name = "create_project",
            toolClass = "CreateProjectTool",
            domain = "projects",
            description = "Creates a new project",
            annotations = ToolAnnotations(idempotentHint = true),
        ),
        ToolEntry(
            name = "update_project",
            toolClass = "UpdateProjectTool",
            domain = "projects",
            description = "Updates an existing project",
            annotations = ToolAnnotations(idempotentHint = true),
        ),
        ToolEntry(
            name = "delete_project",
            toolClass = "DeleteProjectTool",
            domain = "projects",
            description = "Permanently deletes a project",
            annotations = ToolAnnotations(destructiveHint = true),
        ),
        ToolEntry(
            name = "list_projects",
            toolClass = "ListProjectsTool",
            domain = "projects",
            description = "Lists all projects",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "get_project",
            toolClass = "GetProjectTool",
            domain = "projects",
            description = "Gets a single project by ID",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),

        // ── Notes ─────────────────────────────────────────────────────────────
        ToolEntry(
            name = "create_note",
            toolClass = "CreateNoteTool",
            domain = "notes",
            description = "Creates a new note",
            annotations = ToolAnnotations(idempotentHint = true),
        ),
        ToolEntry(
            name = "update_note",
            toolClass = "UpdateNoteTool",
            domain = "notes",
            description = "Updates an existing note",
            annotations = ToolAnnotations(idempotentHint = true),
        ),
        ToolEntry(
            name = "delete_note",
            toolClass = "DeleteNoteTool",
            domain = "notes",
            description = "Permanently deletes a note",
            annotations = ToolAnnotations(destructiveHint = true),
        ),
        ToolEntry(
            name = "get_note",
            toolClass = "GetNoteTool",
            domain = "notes",
            description = "Gets a single note by ID",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),

        // ── Tags ─────────────────────────────────────────────────────────────
        ToolEntry(
            name = "create_tag",
            toolClass = "CreateTagTool",
            domain = "tags",
            description = "Creates a new tag",
            annotations = ToolAnnotations(idempotentHint = true),
        ),
        ToolEntry(
            name = "delete_tag",
            toolClass = "DeleteTagTool",
            domain = "tags",
            description = "Permanently deletes a tag",
            annotations = ToolAnnotations(destructiveHint = true),
        ),

        // ── ADR ──────────────────────────────────────────────────────────────
        ToolEntry(
            name = "write_adr",
            toolClass = "WriteAdrTool",
            domain = "adr",
            description = "Writes or updates an Architecture Decision Record",
            annotations = ToolAnnotations(openWorldHint = true),
        ),
        ToolEntry(
            name = "list_adrs",
            toolClass = "ListAdrsTool",
            domain = "adr",
            description = "Lists all ADR filenames",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),
        ToolEntry(
            name = "read_adr",
            toolClass = "ReadAdrTool",
            domain = "adr",
            description = "Reads the content of an ADR by filename",
            annotations = ToolAnnotations(readOnlyHint = true),
        ),

        // ── AI Write Tools ──────────────────────────────────────────────────
        ToolEntry(
            name = "refine_task",
            toolClass = "RefineTaskTool",
            domain = "ai",
            description = "AI-powered task refinement: improves title, description, and metadata",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "smart_rewrite",
            toolClass = "SmartRewriteTool",
            domain = "ai",
            description = "AI-powered smart rewrite of task or note content",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "generate_description",
            toolClass = "GenerateDescriptionTool",
            domain = "ai",
            description = "Generates a description for a task from its title",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "decompose_task",
            toolClass = "DecomposeTaskTool",
            domain = "ai",
            description = "Decomposes a task into sub-tasks using AI",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "generate_checklist",
            toolClass = "GenerateChecklistTool",
            domain = "ai",
            description = "Generates a checklist of sub-tasks from a task description",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "pick_time",
            toolClass = "PickTimeTool",
            domain = "ai",
            description = "AI suggests optimal scheduling for a task",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "cluster_tasks",
            toolClass = "ClusterTasksTool",
            domain = "ai",
            description = "Groups tasks into thematic clusters",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "cluster_notes",
            toolClass = "ClusterNotesTool",
            domain = "ai",
            description = "Groups notes into thematic clusters",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "project_review",
            toolClass = "ProjectReviewTool",
            domain = "ai",
            description = "AI review of a project's tasks and structure",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "weekly_plan",
            toolClass = "WeeklyPlanTool",
            domain = "ai",
            description = "AI generates a weekly plan from available tasks",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "improve_note",
            toolClass = "ImproveNoteTool",
            domain = "ai",
            description = "AI improves a note's clarity and structure",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "summarize_note",
            toolClass = "SummarizeNoteTool",
            domain = "ai",
            description = "Generates a summary of a note",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "extract_actions",
            toolClass = "ExtractActionsTool",
            domain = "ai",
            description = "Extracts actionable tasks from a note",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "rewrite_note",
            toolClass = "RewriteNoteTool",
            domain = "ai",
            description = "AI rewrites a note's content",
            annotations = ToolAnnotations(),
        ),
        ToolEntry(
            name = "suggest_tags",
            toolClass = "SuggestTagsTool",
            domain = "ai",
            description = "AI suggests relevant tags for a task or note",
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

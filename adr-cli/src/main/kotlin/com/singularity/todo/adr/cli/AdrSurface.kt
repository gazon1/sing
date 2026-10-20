package com.singularity.todo.adr.cli

// A subcommand: the token, the full invocation, and one line about what it does.
data class AdrCommandSpec(val name: String, val usage: String, val summary: String)

// A flag: as typed, and what it changes.
data class AdrFlagSpec(val flag: String, val summary: String)

// A CLI command and the MCP tool it is the command line face of.
data class AdrMcpToolSpec(val command: String, val tool: String)

/**
 * The ADR tool surface, declared once.
 *
 * `--help` and the "ADR tools" section of the agent skills used to be written out
 * separately, which is how a skill came to promise `write_adr` would "create or
 * update" a decision two releases after the update half stopped existing (#511, #526).
 * A document nobody diffs against the code is true until it is edited, and a skill is
 * edited rarely while the code is edited daily — so the document is always the stale
 * one, and it always reads like documentation rather than like a bug.
 *
 * This list is what both are rendered from. `AdrCli.usage()` prints it;
 * `scripts/render-adr-tools-section.py` reads it to write the generated block in
 * the SKILL.md of each ADR skill; `scripts/check-skill-drift.py` re-renders and fails on
 * any difference. Adding a subcommand here therefore puts it into `--help`, into the
 * skill, and into the gate's expectations in one edit.
 *
 * ## Why the Python side parses this file instead of calling it
 *
 * The renderer is Python and the drift gate runs in `scripts/ci/static-gates.sh`,
 * which is specified to finish in about a minute **without a JVM**. Shelling out to a
 * built `singularity-adr` would make the cheapest gate in the repository the slowest,
 * and a gate that is usually skipped is a gate that is usually lying.
 *
 * So the trade is explicit: this file is parsed, not executed. That is only safe while
 * the declarations below stay in the one line-oriented shape the parser expects — which
 * is why every entry is a single call, and why the renderer fails loudly on a line it
 * cannot read instead of skipping it. A parser that shrugs is how a "single source of
 * truth" quietly becomes two sources again.
 *
 * The MCP tools are the mirror image: their descriptions live on the tool classes
 * themselves, because the server builds its schema from those constants — so the
 * renderer reads them from there and a tool's description reaches the skill without
 * anyone restating it. The catalogue names them; the classes describe them.
 */
object AdrSurface {

    /**
     * Subcommands, in the order `help` lists them.
     *
     * **One declaration per line.** `scripts/render-adr-tools-section.py` parses this
     * block, and it refuses a declaration it cannot read instead of skipping it — a
     * silently skipped entry would drop a command from the skill while the section
     * still looked complete, which is the exact failure this mechanism exists to
     * prevent. Wrapping these across lines is not a style preference; it breaks the
     * gate that keeps the skill honest.
     */
    val commands: List<AdrCommandSpec> = listOf(
        AdrCommandSpec("new", "new <slug> <title> [tag ...]", "write a new decision"),
        AdrCommandSpec("list", "list", "every decision in the corpus"),
        AdrCommandSpec("list-open", "list-open", "deferred backlog entries still open"),
        AdrCommandSpec("read", "read <slug>", "one decision, with its body"),
        AdrCommandSpec("search", "search <query>", "find decisions by title, tags or body"),
        AdrCommandSpec("update", "update <slug> <status> [by]", "change status, never the body (#509)"),
        AdrCommandSpec("stale", "stale [--days N] [--status S]", "what the gate will reject next (#521)"),
        AdrCommandSpec("prune", "prune [--keep slug]", "uncited decisions, never deleted (#525)"),
        AdrCommandSpec("validate", "validate", "run the repository's ADR status gate"),
        AdrCommandSpec("version", "version [--check]", "which build this is, and does it match the checkout"),
        AdrCommandSpec("upgrade", "upgrade [--check|--force]", "rebuild this binary when it no longer matches"),
    )

    /** Flags that apply to every subcommand. One per line, for the same reason. */
    val globalFlags: List<AdrFlagSpec> = listOf(
        AdrFlagSpec("--json", "machine-readable output on every command (#524)"),
        AdrFlagSpec("--review-by", "YYYY-MM-DD on update: when this is due to be revisited (#527)"),
    )

    /** Commands whose result is safe to consume from a script. */
    val jsonCommands: List<String> = commands.map { it.name }

    /**
     * Which MCP tool each ADR command is the command line face of.
     *
     * The CLI and the MCP server are two interfaces to the same corpus, and the
     * mapping between them used to live nowhere: a tool could lose its terminal
     * face, or a command could lose its tool face, and both sides kept working.
     * Declared here, it is checked by `scripts/check-skill-drift.py` against the
     * `adr` domain of `McpToolCatalog.kt`.
     *
     * **One declaration per line**, for the same reason the commands are one per
     * line: the renderer refuses a declaration it cannot read rather than skipping it.
     */
    val mcpTools: List<AdrMcpToolSpec> = listOf(
        AdrMcpToolSpec("new", "write_adr"),
        AdrMcpToolSpec("list", "list_adrs"),
        AdrMcpToolSpec("list-open", "list_open_deferred"),
        AdrMcpToolSpec("read", "read_adr"),
        AdrMcpToolSpec("update", "update_adr"),
        AdrMcpToolSpec("search", "search_decisions"),
    )

    /** One `help` line per command, padded to a common column. */
    fun usageLines(): List<String> {
        val width = commands.maxOf { it.usage.length } + 2
        return commands.map { "  ${it.usage.padEnd(width)}${it.summary}" }
    }
}
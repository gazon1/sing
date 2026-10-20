package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ─── Tools ─────────────────────────────────────────────────────────────────────

@Serializable
data class ListAdrsInput(val limit: Int = 50)

class ListAdrsTool(private val storage: AdrStorage) :
    SimpleTool<ListAdrsInput>(
        TypeToken.of(ListAdrsInput::class.java),
        NAME,
        DESCRIPTION,
    ) {
    override suspend fun execute(args: ListAdrsInput): String {
        val adrs = storage.listAdrs().take(args.limit)
        return Json.encodeToString(
            AdrStorage.ListAdrsOutput.serializer(),
            AdrStorage.ListAdrsOutput(adrs),
        )
    }

    companion object {
        const val NAME = "list_adrs"
        const val DESCRIPTION = "List all Architecture Decision Records sorted by date (newest first)."
    }
}

@Serializable
data class ReadAdrInput(val slug: String)

class ReadAdrTool(private val storage: AdrStorage) :
    SimpleTool<ReadAdrInput>(
        TypeToken.of(ReadAdrInput::class.java),
        NAME,
        DESCRIPTION,
    ) {
    override suspend fun execute(args: ReadAdrInput): String {
        val adr =
            storage.readAdr(args.slug)
                ?: return Json.encodeToString(
                    AdrStorage.ReadAdrOutput.serializer(),
                    AdrStorage.ReadAdrOutput(args.slug, "(not found)", "", emptyList(), ""),
                )
        return Json.encodeToString(
            AdrStorage.ReadAdrOutput.serializer(),
            AdrStorage.ReadAdrOutput(adr.slug, adr.title, adr.date, adr.tags, adr.body),
        )
    }

    companion object {
        const val NAME = "read_adr"
        const val DESCRIPTION = "Read a specific ADR by its slug (e.g. 2026-09-05-koin-suspend-bridge)."
    }
}

@Serializable
data class WriteAdrInput(val slug: String, val title: String, val tags: List<String> = emptyList(), val body: String)

class WriteAdrTool(private val storage: AdrStorage) :
    SimpleTool<WriteAdrInput>(
        TypeToken.of(WriteAdrInput::class.java),
        NAME,
        DESCRIPTION,
    ) {
    override suspend fun execute(args: WriteAdrInput): String {
        val path = storage.writeAdr(args.slug, args.title, args.tags, args.body)
        return Json.encodeToString(
            AdrStorage.WriteAdrOutput.serializer(),
            AdrStorage.WriteAdrOutput(args.slug, path, args.title),
        )
    }

    companion object {
        const val NAME = "write_adr"

        /**
         * Says exactly what it does, which is not what it used to say (#511).
         *
         * This string reaches the tool *schema*, and an agent reads the schema before
         * it acts. "Create or update" produced a reliable failure: the agent called
         * `write_adr` on an existing slug, got `ADR already exists`, and worked
         * around it by inventing `slug-v2` — leaving an orphaned predecessor with no
         * `supersedes` edge, which is precisely the state the update path exists to
         * prevent.
         *
         * The second paragraph is the part that changes behaviour: it names
         * [UpdateAdrTool.NAME] and says what superseding means.
         */
        const val DESCRIPTION =
            "Create a NEW Architecture Decision Record (ADR) as a Markdown file. Fails if " +
                "the slug already exists — a decision is never overwritten. " +
                "To close or revise an existing decision use update_adr, which changes its " +
                "status and records the supersedes/superseded-by link. To say something new " +
                "that replaces an old decision, write a new ADR naming the old one in its " +
                "`supersedes:` frontmatter field, then call update_adr on the old one with " +
                "status=superseded — do NOT invent a '-v2' slug, it leaves the predecessor " +
                "unlinked."
    }
}

@Serializable
data class UpdateAdrInput(val slug: String, val status: String, val supersededBy: String? = null)

/** The `update_adr` MCP tool — the write-side half of [AdrStorage.updateAdr] (#511). */
class UpdateAdrTool(private val storage: AdrStorage) :
    SimpleTool<UpdateAdrInput>(
        TypeToken.of(UpdateAdrInput::class.java),
        NAME,
        DESCRIPTION,
    ) {
    override suspend fun execute(args: UpdateAdrInput): String {
        val path = storage.updateAdr(args.slug, args.status, args.supersededBy)
        return Json.encodeToString(
            AdrStorage.UpdateAdrOutput.serializer(),
            AdrStorage.UpdateAdrOutput(args.slug, args.status, args.supersededBy, path),
        )
    }

    companion object {
        const val NAME = "update_adr"

        /**
         * The contract, stated where an agent will read it before choosing.
         *
         * The body is never touched — that is the property that makes this safe to
         * call on a decision somebody else wrote.
         */
        const val DESCRIPTION =
            "Change an existing ADR's status frontmatter; the body is never modified. " +
                "status must be one of: accepted, deferred, superseded, open. " +
                "status=superseded additionally requires supersededBy, naming an existing " +
                "slug (a markdown link is accepted). Use this to close a decision once " +
                "it is settled, and to mark one as replaced after writing its successor — " +
                "the replacement is a separate write_adr call, not a new '-v2' slug of " +
                "this one."
    }
}

class ListOpenDeferredTool(private val storage: AdrStorage) :
    SimpleTool<Unit>(
        TypeToken.of(Unit::class.java),
        NAME,
        DESCRIPTION,
    ) {
    override suspend fun execute(args: Unit): String {
        val entries = storage.listOpenDeferred()
        return Json.encodeToString(
            AdrStorage.ListOpenDeferredOutput.serializer(),
            AdrStorage.ListOpenDeferredOutput(entries),
        )
    }

    companion object {
        const val NAME = "list_open_deferred"
        const val DESCRIPTION = "List all deferred backlog entries with OPEN or PARTIAL status."
    }
}

@Serializable
data class SearchDecisionsInput(
    val query: String,
    val limit: Int = 20,
    /** "decision", "deferred" or "all". */
    val kind: String = "all",
)

/**
 * The `search_decisions` MCP tool — the read-side answer to "what did we decide?".
 *
 * The corpus was previously reachable only through a shell: `list_adrs` names the
 * decisions, and reading one needs its exact slug, so an agent asking "what did we
 * decide about the sync conflict?" had to grep the repository instead. This tool is
 * that grep, as an API the agent already has.
 *
 * The answer carries the status of every hit, so a decision that is still open is
 * visible without opening the document.
 */
class SearchDecisionsTool(private val storage: AdrStorage) :
    SimpleTool<SearchDecisionsInput>(
        TypeToken.of(SearchDecisionsInput::class.java),
        NAME,
        DESCRIPTION,
    ) {
    override suspend fun execute(args: SearchDecisionsInput): String {
        val envelope = storage.searchAdrs(args.query, args.limit, SearchKind.from(args.kind))
        return Json.encodeToString(SearchEnvelope.serializer(), envelope)
    }

    companion object {
        const val NAME = "search_decisions"

        /**
         * Says what it searches and what it cannot do.
         *
         * The `available` field is the part an agent needs to read: an empty list means
         * "nothing matched", and `available: false` means "nothing was searched" — the
         * corpus is not there. Those are different answers, and an agent that cannot
         * tell them apart will report "no decision mentions this" when the truth is
         * that there is no corpus to search.
         */
        const val DESCRIPTION =
            "Search the decision records by title, tags or body text, newest first. " +
                "Returns each match with its status, tags, date and a snippet, so an " +
                "open or superseded decision is visible without reading the file. " +
                "Use read_adr for the full text of a hit. An empty result means nothing " +
                "matched; check the 'available' field — false means there was no corpus " +
                "to search, not that nothing matched."
    }
}

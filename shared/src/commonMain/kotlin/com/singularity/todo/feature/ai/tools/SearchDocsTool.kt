package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SearchDocsInput(
    val query: String,
    val limit: Int = 20,
    /** "decisions", "architecture", "skills", "specs" or "all". */
    val scope: String = "all",
)

/**
 * The `search_docs` MCP tool — "where is the rule about X?".
 *
 * The knowledge of this project is not only in the decisions. The architecture
 * documents, the skills and the capability specifications are where a procedure or a
 * contract usually lives, and all of them were reachable only through a shell. An agent
 * that cannot search them re-derives answers that are already written down.
 *
 * The decisions are included in the `all` scope, so "search everything" is one call;
 * [SearchDecisionsTool] remains the *structured* question, with the status of each
 * decision attached.
 */
class SearchDocsTool(private val storage: AdrStorage) :
    SimpleTool<SearchDocsInput>(
        TypeToken.of(SearchDocsInput::class.java),
        NAME,
        DESCRIPTION,
    ) {
    override suspend fun execute(args: SearchDocsInput): String {
        val envelope = storage.search.searchDocuments(args.query, SearchScope.from(args.scope), args.limit)
        return Json.encodeToString(SearchEnvelope.serializer(), envelope)
    }

    companion object {
        const val NAME = "search_docs"

        /**
         * Names the places it looks and the shape of the answer.
         *
         * The scopes are named so the agent can narrow the search rather than reading
         * every hit: a question about a procedure belongs to the skills, a question
         * about a contract to the specifications.
         */
        const val DESCRIPTION =
            "Search the project's written knowledge — the architecture documents, the " +
                "skills, the capability specifications and the decision records — and " +
                "return matching files with a path and a snippet. Narrow the search " +
                "with the scope argument: decisions, architecture, skills, specs or " +
                "all. An empty result means nothing matched; check the 'available' " +
                "field — false means the corpus was not searched at all."
    }
}

package com.singularity.todo

import ai.koog.agents.core.tools.Tool
import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.feature.ai.McpToolCatalog
import com.singularity.todo.test.helpers.testPlatformModule
import org.junit.jupiter.api.Tag
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The toolbox that is *wired* must be the toolbox that is *declared*.
 *
 * ## The defect this closes
 *
 * The MCP catalogue and the Koin registration list are two hand-maintained lists of the
 * same 39 tools. Nothing compared them: a tool added to the catalogue and forgotten in
 * the list is a tool the client asks for and never gets, and it fails at call time
 * rather than at build time. A tool added to the list and forgotten in the catalogue is
 * a tool nobody documented.
 *
 * The static gate (`scripts/check-skill-drift.py`) compares the catalogue with the tool
 * *classes*. It cannot see the DI list, because a list of `get<XTool>()` calls is not a
 * declaration of anything the parser can read. This test closes that gap the only way
 * it can be closed: by **resolving the graph** and looking at what it actually holds.
 *
 * The check exists on the JVM and on Android because the two lists are separate files.
 * A tool wired on one platform and forgotten on the other is invisible to any check
 * that reads one of them.
 *
 * ## What it does not assert
 *
 * Nothing here calls a tool. Resolving the list runs the factories, which is where a
 * missing dependency would fail, but the tools' behaviour is not exercised — that is
 * what the tool tests are for.
 */
@Tag("fast")
class ToolSurfaceWiringTest {

    @Test
    fun `the wired toolbox is the declared toolbox`() {
        val app = koinApplication {
            // KOIN-W003 is the same accepted tradeoff `DomainGraphResolutionTest`
            // takes: the module set is composed at runtime, so the compile-time graph
            // check cannot see it. This test is the runtime validation that replaces it.
            @Suppress("KoinCompilationForInternal")
            modules(
                listOf(coreLoggingModule()) +
                    domainModule() +
                    listOf(testPlatformModule()),
            )
        }
        try {
            val tools: List<Tool<*, *>> = app.koin.get()
            val declared = McpToolCatalog.catalog

            val wiredNames = tools.map { it.descriptor.name }
            val declaredNames = declared.map { it.name }

            assertEquals(
                declaredNames.sorted(),
                wiredNames.sorted(),
                "the wired tools and the catalogue disagree; a client can call a tool " +
                    "that is not wired, or a wired tool that nothing documents",
            )
            assertEquals(
                wiredNames.size,
                wiredNames.distinct().size,
                "two wired tools answer to the same name; the second one wins silently",
            )

            val classByName = tools.associate { it.descriptor.name to it::class.simpleName }
            for (entry in declared) {
                assertEquals(
                    entry.toolClass.simpleName,
                    classByName[entry.name],
                    "the catalogue points ${entry.name} at ${entry.toolClass.simpleName}, but " +
                        "that is not the class that is wired",
                )
            }

            for (tool in tools) {
                assertTrue(
                    tool.descriptor.description.isNotBlank(),
                    "${tool.descriptor.name} has no description; the agent would be told nothing",
                )
            }
        } finally {
            app.close()
        }
    }
}

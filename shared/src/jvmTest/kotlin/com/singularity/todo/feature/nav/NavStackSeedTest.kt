package com.singularity.todo.feature.nav

import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A nested graph's stack is seeded with the route the outer graph asked for.
 *
 * ## What went wrong
 *
 * The desktop entry seeded the tasks stack with `Create(null)` and pushed the requested
 * route on top when it was a detail route. That put a screen the person never asked for
 * underneath the one they did: back from a task's detail screen opened an empty editor
 * instead of leaving the graph, and the date and the section prefill the route carried
 * were dropped — the editor read the placeholder, not the route.
 *
 * ## Why this test reads the files
 *
 * The rule it replaces lived in `ArchitectureTest`, whose Konsist scope is `commonMain`
 * only. The file it wanted to check — `JvmNavEntries.kt` — lives in `jvmMain`, so the
 * rule found nothing and returned early: a gate that reads no files passes. This one
 * reads the source roots the build hands it (`commonMain.root`, `jvmMain.root`,
 * `androidMain.root`), so it sees the platform files that actually carry the entries.
 *
 * ## What the rules are
 *
 * 1. Wherever the nav layer creates a stack, the seed is the very expression the graph
 *    is started with. Comparing the *expressions* rather than the route types is the
 *    point: a rule that compares types is undone by the next route that grows a field
 *    (`Create(null)` against `Create(date)` is the same type with different parameters,
 *    and it is exactly the case that broke).
 * 2. Both platforms ask for the same tasks route — the two entries cannot drift.
 * 3. Neither platform pushes the requested route onto the stack a second time.
 * 4. The route the outer graph asks for carries its parameters.
 *
 * ADR `2026-10-03-nav3-backstack-top-vs-start-dispatch` records the decision;
 * OpenSpec change `the-desktop-task-stack-starts-elsewhere` records the behaviour.
 */
@Tag("fast")
class NavStackSeedTest {

    private fun sourceRoot(property: String): File = File(
        System.getProperty(property)
            ?: error(
                "$property system property is not set — " +
                    "see the jvmTest task config in shared/build.gradle.kts",
            ),
    )

    private val commonMainRoot: File = sourceRoot("commonMain.root")
    private val jvmMainRoot: File = sourceRoot("jvmMain.root")
    private val androidMainRoot: File = sourceRoot("androidMain.root")

    private fun file(root: File, path: String): File = File(root, path)

    /**
     * The source with comments removed — a KDoc example is documentation, not a call
     * site, and `start = route.start` inside a comment must not satisfy the rule.
     */
    private fun code(source: String): String = source
        .lineSequence()
        .filterNot { line ->
            val trimmed = line.trim()
            trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")
        }
        .joinToString("\n")

    /** The argument every stack seam is called with. */
    private fun seeds(source: String): List<String> = Regex(
        """remember(?:App|InMemory)NavBackStack\(\s*([^()]*?)\s*\)""",
    ).findAll(source).map { it.groupValues[1].trim() }.toList()

    /** The argument every graph is started with. */
    private fun starts(source: String): List<String> = Regex(
        """\bstart\s*=\s*([^,\n]+)""",
    ).findAll(source).map { it.groupValues[1].trim() }.toList()

    /** The body of `entry<AppDestination.X> { … }`, braces matched. */
    private fun entryBlock(source: String, name: String): String {
        val marker = "entry<AppDestination.$name>"
        val start = source.indexOf(marker)
        require(start >= 0) { "no entry for $name in the source" }
        var depth = 0
        var index = source.indexOf('{', start)
        val from = index
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++

                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(from, index + 1)
                }
            }
            index++
        }
        error("the entry for $name is not closed")
    }

    private fun navFiles(): List<File> = listOf(commonMainRoot, jvmMainRoot, androidMainRoot)
        .flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.path.replace('\\', '/').contains("/nav/") }
                .toList()
        }

    @Test
    fun `every stack is seeded with the route its graph is started with`() {
        val offenders = mutableListOf<String>()

        navFiles().forEach { file ->
            val source = code(file.readText())
            val seedExpressions = seeds(source)
            val startExpressions = starts(source)
            if (seedExpressions.isEmpty() || startExpressions.isEmpty()) return@forEach

            seedExpressions.forEach { seed ->
                if (seed !in startExpressions) {
                    offenders += "${file.path.replace('\\', '/')}: the stack is seeded with " +
                        "`$seed`, but the graph is started with " +
                        startExpressions.joinToString(", ") { "`$it`" } +
                        ". NavDisplay renders stack.top, so the seed IS the screen."
                }
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "A stack seeded with a route the outer graph did not ask for:\n" +
                offenders.joinToString("\n") + "\n" +
                "See ADR 2026-10-03-nav3-backstack-top-vs-start-dispatch.",
        )
    }

    @Test
    fun `both platforms ask for the same tasks route`() {
        val android = entryBlock(
            file(androidMainRoot, "com/singularity/todo/feature/nav/AndroidNavEntries.kt").readText(),
            "TasksGraph",
        )
        val jvm = entryBlock(
            file(jvmMainRoot, "com/singularity/todo/feature/nav/JvmNavEntries.kt").readText(),
            "TasksGraph",
        )

        assertEquals(
            starts(code(android)),
            starts(code(jvm)),
            "The two platforms ask the tasks graph for different routes, so one of them " +
                "renders a screen the other does not.",
        )
    }

    @Test
    fun `neither platform pushes the requested route a second time`() {
        val android = entryBlock(
            file(androidMainRoot, "com/singularity/todo/feature/nav/AndroidNavEntries.kt").readText(),
            "TasksGraph",
        )
        val jvm = entryBlock(
            file(jvmMainRoot, "com/singularity/todo/feature/nav/JvmNavEntries.kt").readText(),
            "TasksGraph",
        )

        listOf("Android" to android, "JVM" to jvm).forEach { (platform, block) ->
            assertTrue(
                !Regex("""\.add\(""").containsMatchIn(block),
                "The $platform tasks entry pushes the requested route onto the stack again. " +
                    "The stack is already seeded with it, so the push makes it two entries " +
                    "deep and back uncovers the seed instead of leaving the graph.",
            )
        }
    }

    @Test
    fun `the tasks route carries the parameters the outer graph asked for`() {
        val today = LocalDate(2026, 9, 16)

        assertEquals(
            TasksRoute.Create(today, null),
            AppDestination.TasksStartRoute.Create().toTasksRoute(today),
            "The date a tab was already showing must reach the new-task screen.",
        )
        assertEquals(
            TasksRoute.Create(null, "work"),
            AppDestination.TasksStartRoute.Create(draftKey = "work").toTasksRoute(null),
            "The section a saved view was showing must reach the new-task screen.",
        )
        assertEquals(
            TasksRoute.Detail(TaskId("t1")),
            AppDestination.TasksStartRoute.Detail(TaskId("t1")).toTasksRoute(today),
            "A detail route is a detail route, whatever date the shell carried.",
        )
    }
}

package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.agenda.domain.model.heldBuckets
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Holds the vocabulary registry to its one rule: one name, one question.
 *
 * ## Why this file exists
 *
 * The bug the registry was written for was never the word "Inbox". It was that
 * `TaskFilter`, `TaskSelection`, the agenda's presets and the titles the user reads
 * answer the same questions under different names, and nothing checked that they stayed
 * in step — so one word could come to mean two things and the two halves of the app
 * would quietly disagree.
 *
 * So the check has three halves. The first is completeness: every name the code offers
 * has to be in the registry, and the registry may not name anything the code does not
 * offer — otherwise a new filter could arrive with a name nobody said what it meant.
 * The second is the rule itself: a name that appears in two vocabularies has to answer
 * the same question in both of them, unless one of them is declared as the different
 * one, with a reason. The third is the answers themselves: the date buckets a name
 * holds are read out of the code, and the registry has to hold to them.
 *
 * The vocabularies are read from the code rather than from a list, so the check cannot
 * be satisfied by editing the check: the filter shapes come from the sealed
 * interface's own nested classes, the selections from the enum, the agenda's presets
 * and its section headers from the presets' own selectors.
 */
@Tag("fast")
class TaskVocabularyTest {
    @Test
    fun `every filter shape is registered, and the registry names no other filter`() {
        val shapes =
            TaskFilter::class.java.declaredClasses
                .mapNotNull { it.simpleName }
                .toSet()
        val registered = registered(TaskVocabulary.Source.FILTER)

        assertEquals(shapes, registered.keys, "every filter shape has a name in the registry, and no other filter does")
    }

    @Test
    fun `every selection the wire offers is registered, and the registry names no other selection`() {
        val wire = TaskSelection.entries.map { it.wireName }.toSet()
        val registered = registered(TaskVocabulary.Source.SELECTION)

        wire.forEach {
            assertTrue(it in registered, "the wire offers '$it' and the registry does not say what it answers")
        }
        registered.keys.forEach {
            assertTrue(it in wire, "the registry names '$it', which the wire does not offer")
        }
    }

    @Test
    fun `every agenda preset is registered, and the registry names no other preset`() {
        val presets = agendaPresetNames()
        val registered = registered(TaskVocabulary.Source.PRESET)

        assertEquals(
            presets,
            registered.keys,
            "every agenda preset has a name in the registry, and no other preset does",
        )
    }

    @Test
    fun `every date-based preset holds the buckets the registry declares`() {
        assertEquals(
            datePresetNames,
            TaskVocabulary.presetBuckets.keys,
            "the registry declares buckets for presets that are not about dates, or misses one",
        )

        TaskVocabulary.presetBuckets.forEach { (name, buckets) ->
            val definition = datePresets.getValue(name)

            assertEquals(
                buckets,
                definition.heldBuckets(),
                "the '$name' preset holds different buckets than the registry says it holds",
            )
        }
    }

    /**
     * A section whose title is a bucket's name holds that bucket, and only that one.
     *
     * The rule is about the *name*, not about the buckets: a section called "This Week"
     * has to be the tasks of this week and nothing else. A section that holds a bucket
     * without being called after it is a section that answers a narrower question — the
     * work-day blocks, which are today's tasks inside a time window and are called
     * "Work" and "Lunch" — and those are named for what they narrow, not for the bucket
     * they narrow. The registry says what each of those titles answers.
     */
    @Test
    fun `a section named after a bucket holds that bucket, and only it`() {
        val bucketByTitle = TaskVocabulary.bucketTitles.entries.associate { (bucket, title) -> title to bucket }

        presetDefinitions().forEach { (name, definition) ->
            definition.sections.forEach { section ->
                val bucket = bucketByTitle[section.name] ?: return@forEach

                assertEquals(
                    setOf(bucket),
                    section.selector.heldBuckets(),
                    "the '$name' preset's section '${section.name}' is named after the $bucket bucket, " +
                        "so that is the bucket it has to hold",
                )
            }
        }
    }

    @Test
    fun `every name the user reads is a section's title, and every section's title is registered`() {
        val titles =
            presetDefinitions()
                .values
                .flatMap { definition -> definition.sections.map { it.name } }
                .toSet()

        assertEquals(
            titles,
            registered(TaskVocabulary.Source.SECTION).keys,
            "the names the user reads and the names the registry holds for them have to be the same set",
        )
    }

    @Test
    fun `one name answers one question`() {
        val byName = TaskVocabulary.entries.groupBy { it.name.lowercase() }

        byName.forEach { (name, group) ->
            val declared = TaskVocabulary.divergences.filter { it.name.equals(name, ignoreCase = true) }
            val declaredSources = declared.map { it.source }.toSet()
            val undeclared = group.filterNot { it.source in declaredSources }

            if (group.map { it.question }.distinct().size == 1) {
                assertTrue(
                    declared.isEmpty(),
                    "the divergence declared for '$name' is stale: every vocabulary answers it the same way",
                )
                return@forEach
            }

            assertTrue(
                undeclared.isNotEmpty(),
                "'$name' answers two questions and no vocabulary is declared as the one that differs",
            )
            assertEquals(
                1,
                undeclared.map { it.question }.distinct().size,
                "the vocabularies that are not declared must agree on what '$name' answers",
            )

            declared.forEach { divergence ->
                val entry =
                    assertNotNull(
                        group.firstOrNull { it.source == divergence.source },
                        "the divergence declared for '$name' names a vocabulary that has no '$name'",
                    )
                assertNotEquals(
                    entry.question,
                    undeclared.first().question,
                    "the divergence declared for '$name' is stale: that vocabulary answers it the same way",
                )
            }
        }
    }

    /** The registry's entries for one vocabulary, keyed by name. */
    private fun registered(source: TaskVocabulary.Source): Map<String, String> {
        val entries = TaskVocabulary.entries.filter { it.source == source }

        assertEquals(
            entries.size,
            entries.map { it.name }.distinct().size,
            "a vocabulary cannot hold the same name twice",
        )
        return entries.associate { it.name to it.question }
    }

    /** The date buckets a definition holds, read out of its own sections. */
    private fun AgendaDefinition.heldBuckets(): Set<RelativeBucket> =
        sections.flatMapTo(mutableSetOf()) { it.selector.heldBuckets() }

    /** The presets that are about dates, by the name the registry knows them. */
    private val datePresets: Map<String, AgendaDefinition> =
        mapOf(
            "All" to AgendaPresets.All,
            "Inbox" to AgendaPresets.Inbox,
            "Today" to AgendaPresets.Today,
            "Upcoming" to AgendaPresets.Upcoming,
        )

    private val datePresetNames: Set<String> = datePresets.keys

    /**
     * Every preset, by the name the registry knows it, with the arguments it needs.
     *
     * The presets that take an argument are built here with a throwaway argument: the
     * sections they hold are the same whatever the argument is, and the section titles
     * are what the registry has to answer for.
     */
    private fun presetDefinitions(): Map<String, AgendaDefinition> {
        val workDay = AgendaPresets.workDayPreset(540, 1020, 720, 780)

        return mapOf(
            "All" to AgendaPresets.All,
            "Inbox" to AgendaPresets.Inbox,
            "Today" to AgendaPresets.Today,
            "Upcoming" to AgendaPresets.Upcoming,
            "byProject" to AgendaPresets.byProject(ProjectId("project")),
            "byTag" to AgendaPresets.byTag(TagId("tag")),
            "byTags" to AgendaPresets.byTags(setOf(TagId("tag"))),
            "byDateRange" to AgendaPresets.byDateRange(LocalDate(2026, 1, 1), LocalDate(2026, 1, 31)),
            "byBlocked" to AgendaPresets.byBlocked(),
            "byInProgress" to AgendaPresets.byInProgress(),
            "byFollowUp" to AgendaPresets.byFollowUp(),
            "byStagnant" to AgendaPresets.byStagnant(),
            "byOverdue" to AgendaPresets.byOverdue(),
            "forToday" to
                AgendaPresets.forToday(
                    DayOfWeek.MONDAY,
                    setOf(DayOfWeek.MONDAY),
                    workDay,
                ),
            "workDayPreset" to workDay,
        )
    }

    /**
     * The names the agenda's presets answer to.
     *
     * The named presets are properties and answer to their title; the rest are
     * functions and answer to their own name. The default-argument bridges Kotlin
     * generates are synthetic and are not presets of their own.
     *
     * A function that takes a value class is mangled on the JVM — `byProject(id:
     * ProjectId)` arrives as `byProject-9X1fth8` — so the mangling suffix is cut off
     * and the name the reader knows is what the registry holds.
     */
    private fun agendaPresetNames(): Set<String> =
        AgendaPresets::class.java.declaredMethods
            .filter { it.returnType == AgendaDefinition::class.java && !it.isSynthetic }
            .mapTo(mutableSetOf()) { method ->
                val name = method.name.substringBefore('-')
                if (method.parameterCount == 0 && name.startsWith("get")) name.removePrefix("get") else name
            }
}

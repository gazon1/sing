package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaDefinition
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
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
 * `TaskFilter`, `TaskSelection` and the agenda's presets answer the same questions
 * under different names, and nothing checked that they stayed in step — so one word
 * could come to mean two things and the two halves of the app would quietly disagree.
 *
 * So the check has two halves. The first is completeness: every name the code offers
 * has to be in the registry, and the registry may not name anything the code does not
 * offer — otherwise a new filter could arrive with a name nobody said what it meant.
 * The second is the rule itself: a name that appears in two vocabularies has to answer
 * the same question in both of them, unless one of them is declared as the different
 * one, with a reason.
 *
 * The vocabularies are read from the code rather than from a list, so the check cannot
 * be satisfied by editing the check: the filter shapes come from the sealed
 * interface's own nested classes, the selections from the enum, the agenda's presets
 * from the object's own members.
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
        val aliases = TaskVocabulary.aliases.map { it.name }.toSet()
        val registered = registered(TaskVocabulary.Source.SELECTION)

        wire.forEach {
            assertTrue(it in registered, "the wire offers '$it' and the registry does not say what it answers")
        }
        registered.keys.forEach {
            assertTrue(it in wire || it in aliases, "the registry names '$it', which the wire does not offer")
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
    fun `an alias answers the question of the selection it names`() {
        val registered = registered(TaskVocabulary.Source.SELECTION)

        TaskVocabulary.aliases.forEach { alias ->
            assertEquals(
                registered[alias.selection.wireName],
                registered[alias.name],
                "the alias '${alias.name}' must answer what '${alias.selection.wireName}' answers",
            )
        }
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

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.genui.schema

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Tag("fast")
class DataModelTest {

    @Test
    fun getReturnsRootObjectForRootPath() {
        val model = DataModel()
        assertEquals(JsonObject(emptyMap()), model.get(UiPath.Root))
    }

    @Test
    fun setAndGetRoundtrip() {
        val model = DataModel()
        model.set(UiPath.of("name"), JsonPrimitive("Alice"))
        assertEquals(JsonPrimitive("Alice"), model.get(UiPath.of("name")))
    }

    @Test
    fun setOverwritesExistingValue() {
        val model = DataModel()
        model.set(UiPath.of("name"), JsonPrimitive("Alice"))
        model.set(UiPath.of("name"), JsonPrimitive("Bob"))
        assertEquals(JsonPrimitive("Bob"), model.get(UiPath.of("name")))
    }

    @Test
    fun nestedPathCreatesIntermediateObjects() {
        val model = DataModel()
        model.set(UiPath.of("user", "name"), JsonPrimitive("Alice"))
        val root = model.snapshot()
        assertEquals(
            JsonPrimitive("Alice"),
            root["user"]?.jsonObject?.get("name"),
        )
    }

    @Test
    fun flowEmitsOnSet(): Unit = runTest {
        val model = DataModel()
        model.set(UiPath.of("name"), JsonPrimitive("Alice"))
        val flow = model.flow(UiPath.of("name"))
        assertEquals(JsonPrimitive("Alice"), flow.first())
    }

    @Test
    fun snapshotReturnsCurrentState() {
        val model = DataModel()
        model.set(UiPath.of("key"), JsonPrimitive("value"))
        val snap = model.snapshot()
        assertEquals(JsonPrimitive("value"), snap["key"])
    }

    @Test
    fun getReturnsNullForNonExistentNonRootPath() {
        val model = DataModel()
        assertNull(model.get(UiPath.of("nonexistent")))
    }

    /**
     * A numeric segment makes an array, and a value written through one reads back.
     *
     * This is the shape every task list uses: `/tasks/0/title`. Writing it into an object with a
     * "0" key succeeds and renders, and only reading it back reveals the problem — a reader treats
     * `0` as an index. Nothing fails loudly anywhere: the surface draws with empty titles and the
     * message that carried the data was accepted.
     */
    @Test
    fun aValueWrittenThroughAnIndexComesBack() {
        val model = DataModel()
        model.set(UiPath.of("t", "0", "title"), JsonPrimitive("Buy groceries"))

        assertEquals(JsonPrimitive("Buy groceries"), model.get(UiPath.of("t", "0", "title")))
        assertEquals(JsonArray::class, model.snapshot()["t"]!!::class)
    }

    @Test
    fun writingOneIndexDoesNotDisturbItsNeighbours() {
        val model = DataModel()
        model.set(UiPath.of("t", "0", "title"), JsonPrimitive("first"))
        model.set(UiPath.of("t", "1", "title"), JsonPrimitive("second"))

        assertEquals(JsonPrimitive("first"), model.get(UiPath.of("t", "0", "title")))
        assertEquals(JsonPrimitive("second"), model.get(UiPath.of("t", "1", "title")))
    }

    @Test
    fun aLaterIndexFillsTheGapWithoutInventingValues() {
        val model = DataModel()
        model.set(UiPath.of("t", "2", "title"), JsonPrimitive("third"))

        val list = model.get(UiPath.of("t")) as JsonArray
        assertEquals(3, list.size, "The array reaches the index that was written")
        assertEquals(JsonNull, list[0], "and the gap before it is empty, not invented")
        assertEquals(JsonPrimitive("third"), (list[2] as JsonObject)["title"])
        assertEquals(JsonPrimitive("third"), model.get(UiPath.of("t", "2", "title")))
    }

    /** A key that merely looks numeric — a project called "2026" — stays an object key. */
    @Test
    fun anIndexUnderANamedKeyStillReadsBack() {
        val model = DataModel()
        model.set(UiPath.of("projects", "0", "name"), JsonPrimitive("Home"))

        assertEquals(JsonPrimitive("Home"), model.get(UiPath.of("projects", "0", "name")))
    }

    @Test
    fun anOverwrittenValueKeepsItsPlaceInTheArray() {
        val model = DataModel()
        model.set(UiPath.of("t", "0", "title"), JsonPrimitive("before"))
        model.set(UiPath.of("t", "0", "title"), JsonPrimitive("after"))

        assertEquals(JsonPrimitive("after"), model.get(UiPath.of("t", "0", "title")))
        assertEquals(1, (model.get(UiPath.of("t")) as JsonArray).size)
    }
}

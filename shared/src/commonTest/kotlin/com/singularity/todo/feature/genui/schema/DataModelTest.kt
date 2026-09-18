package com.singularity.todo.feature.genui.schema

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}

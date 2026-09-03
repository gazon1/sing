package com.singularity.todo.feature.genui.schema

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataModelTest {

    @Test
    fun `get returns root object for Root path`() {
        val model = DataModel()
        assertEquals(JsonObject(emptyMap()), model.get(UiPath.Root))
    }

    @Test
    fun `set and get roundtrip`() {
        val model = DataModel()
        model.set(UiPath.of("name"), JsonPrimitive("Alice"))
        assertEquals(JsonPrimitive("Alice"), model.get(UiPath.of("name")))
    }

    @Test
    fun `set overwrites existing value`() {
        val model = DataModel()
        model.set(UiPath.of("name"), JsonPrimitive("Alice"))
        model.set(UiPath.of("name"), JsonPrimitive("Bob"))
        assertEquals(JsonPrimitive("Bob"), model.get(UiPath.of("name")))
    }

    @Test
    fun `nested path creates intermediate objects`() {
        val model = DataModel()
        model.set(UiPath.of("user", "name"), JsonPrimitive("Alice"))
        val root = model.snapshot()
        assertEquals(
            JsonPrimitive("Alice"),
            root["user"]?.jsonObject?.get("name")
        )
    }

    @Test
    fun `flow emits on set`(): Unit = runBlocking {
        val model = DataModel()
        model.set(UiPath.of("name"), JsonPrimitive("Alice"))
        val flow = model.flow(UiPath.of("name"))
        assertEquals(JsonPrimitive("Alice"), flow.first())
    }

    @Test
    fun `snapshot returns current state`() {
        val model = DataModel()
        model.set(UiPath.of("key"), JsonPrimitive("value"))
        val snap = model.snapshot()
        assertEquals(JsonPrimitive("value"), snap["key"])
    }

    @Test
    fun `get returns null for non-existent non-root path`() {
        val model = DataModel()
        assertNull(model.get(UiPath.of("nonexistent")))
    }
}

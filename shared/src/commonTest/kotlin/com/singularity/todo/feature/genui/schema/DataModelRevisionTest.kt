@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.genui.schema

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The count of the server's rewrites belongs to the value it counts.
 *
 * The count says "this value was replaced from outside", and it is the count of *this
 * path's* rewrites. It used to be a counter beside the document, one per surface: a
 * write to one path told every other path that it had been rewritten, and a surface
 * that was deleted and recreated came back with the count of a surface that no longer
 * existed.
 */
@Tag("fast")
class DataModelRevisionTest {

    @Test
    fun theFieldOwnWriteDoesNotMoveTheCount() {
        val model = DataModel()
        model.set(UiPath.of("name"), JsonPrimitive("Alice"))

        assertEquals(0L, model.revision(UiPath.of("name")))
    }

    @Test
    fun aServerWriteMovesTheCountOfThatPath() {
        val model = DataModel()
        model.setFromServer(UiPath.of("name"), JsonPrimitive("Alice"))

        assertEquals(1L, model.revision(UiPath.of("name")))
    }

    @Test
    fun everyServerWriteMovesTheCountAgain() {
        val model = DataModel()
        model.setFromServer(UiPath.of("name"), JsonPrimitive("Alice"))
        model.setFromServer(UiPath.of("name"), JsonPrimitive("Bob"))

        assertEquals(2L, model.revision(UiPath.of("name")))
    }

    @Test
    fun aServerWriteToOnePathDoesNotMoveAnotherPathsCount() {
        val model = DataModel()
        model.setFromServer(UiPath.of("first"), JsonPrimitive("Alice"))

        assertEquals(0L, model.revision(UiPath.of("second")))
    }

    @Test
    fun theValueAndTheCountAreReadTogether() {
        val model = DataModel()
        model.setFromServer(UiPath.of("name"), JsonPrimitive("Alice"))

        val read = model.valueAt(UiPath.of("name"))

        assertEquals(JsonPrimitive("Alice"), read.value)
        assertEquals(1L, read.revision)
    }

    @Test
    fun theCountOfAPathThatWasNeverWrittenIsZero() {
        val model = DataModel()

        assertEquals(0L, model.revision(UiPath.of("nothing")))
    }

    @Test
    fun theFlowEmitsTheValueAndTheCountTogether() = runTest {
        val model = DataModel()
        model.setFromServer(UiPath.of("name"), JsonPrimitive("Alice"))

        val emitted = model.flowAt(UiPath.of("name")).first()

        assertEquals(JsonPrimitive("Alice"), emitted.value)
        assertEquals(1L, emitted.revision)
    }

    @Test
    fun theFlowOfOnePathDoesNotSeeAnotherPathsWrite() = runTest {
        val model = DataModel()
        model.setFromServer(UiPath.of("first"), JsonPrimitive("Alice"))

        val emitted = model.flowAt(UiPath.of("second")).first()

        assertEquals(null, emitted.value)
        assertEquals(0L, emitted.revision)
    }
}

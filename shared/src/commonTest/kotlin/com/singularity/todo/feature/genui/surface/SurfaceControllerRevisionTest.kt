@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.genui.surface

import com.singularity.todo.feature.genui.catalog.NodeRef
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.parser.UiEvent
import com.singularity.todo.feature.genui.schema.UiPath
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The count of the server's rewrites follows the surface's data model.
 *
 * It used to be a map beside the surfaces, so it outlived them: a surface that was
 * deleted and then created again under the same id came back carrying the count of the
 * surface that had been there before, and a reset left the counts behind as well.
 */
@Tag("fast")
class SurfaceControllerRevisionTest {

    private fun controllerWithSurface(id: String = "s1"): SurfaceController {
        val ctrl = SurfaceController()
        ctrl.apply(
            UiEvent.CreateSurface(
                surfaceId = SurfaceId(id),
                rootId = NodeRef("r1"),
                components = mapOf("r1" to UiNode.Text("Hello")),
            ),
        )
        return ctrl
    }

    @Test
    fun aServerWriteMovesTheCountOfThePathItWrote() {
        val ctrl = controllerWithSurface()
        ctrl.apply(
            UiEvent.UpdateData(SurfaceId("s1"), UiPath.of("name"), JsonPrimitive("Alice")),
        )

        assertEquals(1L, ctrl.valueAt(SurfaceId("s1"), UiPath.of("name")).revision)
    }

    @Test
    fun aServerWriteToOnePathLeavesTheOtherPathsCountAlone() {
        val ctrl = controllerWithSurface()
        ctrl.apply(
            UiEvent.UpdateData(SurfaceId("s1"), UiPath.of("first"), JsonPrimitive("Alice")),
        )

        assertEquals(0L, ctrl.valueAt(SurfaceId("s1"), UiPath.of("second")).revision)
    }

    @Test
    fun theFieldOwnWriteDoesNotMoveTheCount() {
        val ctrl = controllerWithSurface()
        ctrl.write(SurfaceId("s1"), UiPath.of("name"), JsonPrimitive("Alice"))

        assertEquals(0L, ctrl.valueAt(SurfaceId("s1"), UiPath.of("name")).revision)
    }

    @Test
    fun aSurfaceThatComesBackDoesNotInheritTheCountOfTheOneBeforeIt() {
        val ctrl = controllerWithSurface()
        ctrl.apply(
            UiEvent.UpdateData(SurfaceId("s1"), UiPath.of("name"), JsonPrimitive("Alice")),
        )
        ctrl.apply(UiEvent.DeleteSurface(SurfaceId("s1")))
        ctrl.apply(
            UiEvent.CreateSurface(
                surfaceId = SurfaceId("s1"),
                rootId = NodeRef("r1"),
                components = mapOf("r1" to UiNode.Text("Hello")),
            ),
        )

        assertEquals(0L, ctrl.valueAt(SurfaceId("s1"), UiPath.of("name")).revision)
    }

    @Test
    fun aResetLeavesNoCountBehind() {
        val ctrl = controllerWithSurface()
        ctrl.apply(
            UiEvent.UpdateData(SurfaceId("s1"), UiPath.of("name"), JsonPrimitive("Alice")),
        )
        ctrl.reset()
        ctrl.apply(
            UiEvent.CreateSurface(
                surfaceId = SurfaceId("s1"),
                rootId = NodeRef("r1"),
                components = mapOf("r1" to UiNode.Text("Hello")),
            ),
        )

        assertEquals(0L, ctrl.valueAt(SurfaceId("s1"), UiPath.of("name")).revision)
    }
}

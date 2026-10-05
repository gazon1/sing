package com.singularity.todo.feature.genui.core

import com.singularity.todo.feature.genui.catalog.NodeRef
import com.singularity.todo.feature.genui.catalog.SingularityCatalog
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.parser.UiEvent
import com.singularity.todo.feature.genui.schema.UiPath
import com.singularity.todo.feature.genui.surface.SurfaceId
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * These are the relationships a per-component decoder cannot see, and every one of them used to
 * reach the renderer — where the only response was to draw nothing and write a log line, after the
 * model had been told the surface was fine.
 */
@Tag("fast")
class A2uiValidatorTest {

    private val validator = A2uiValidator(SingularityCatalog)
    private val surface = SurfaceId("s1")

    @Test
    fun aResolvedReferencePasses() {
        val result = validator.validate(create(column = listOf("t1"), text = mapOf("t1" to UiNode.Text("Hi"))))
        assertTrue(result.errors.isEmpty(), result.errors.toString())
    }

    @Test
    fun aReferenceToSomethingNotYetDefinedIsReportedButKept() {
        // Streaming means an undefined reference is usually just early, not wrong — so the
        // component survives, and the model is told in case it really is missing.
        val result = validator.validate(create(column = listOf("t1", "ghost"), text = mapOf("t1" to UiNode.Text("Hi"))))
        val error = result.errors.single()
        assertEquals(A2uiErrorCode.UNKNOWN_NODE_REF, error.code)
        assertTrue("ghost" in error.message, error.message)
        assertEquals(2, result.event.componentsOf().size, "A late reference does not cost the component")
    }

    @Test
    fun aReferenceToAnEarlierMessageIsResolved() {
        val known = mapOf("t1" to UiNode.Text("from the previous message"))
        val result = validator.validate(create(column = listOf("t1"), text = emptyMap()), known)
        assertTrue(result.errors.isEmpty(), result.errors.toString())
    }

    @Test
    fun aModalInsideAListIsRejectedWithTheLegalParents() {
        val result = validator.validate(
            create(
                column = emptyList(),
                text = mapOf(
                    "l1" to UiNode.ListView(listOf(NodeRef("m1"))),
                    "m1" to UiNode.Modal(NodeRef("t1"), UiPath.parse("/open")),
                    "t1" to UiNode.Text("inside"),
                ),
            ),
        )
        val error = result.errors.single { it.code == A2uiErrorCode.INVALID_CHILD }
        assertEquals(A2uiErrorCode.INVALID_CHILD, error.code)
        assertTrue("card" in error.allowed, "The model needs where a modal may go: ${error.allowed}")
        assertTrue("m1" !in result.event.componentsOf(), "The offending component is dropped")
        assertTrue("l1" in result.event.componentsOf(), "Its parent survives so the surface still renders")
    }

    @Test
    fun aModalDirectlyInAColumnIsFine() {
        val result = validator.validate(
            create(
                column = listOf("m1"),
                text = mapOf(
                    "m1" to UiNode.Modal(NodeRef("t1"), UiPath.parse("/open")),
                    "t1" to UiNode.Text("inside"),
                ),
            ),
        )
        assertTrue(result.errors.isEmpty(), result.errors.toString())
    }

    @Test
    fun aCycleIsDetectedAndTheComponentClosingItIsDropped() {
        val result = validator.validate(
            create(
                column = emptyList(),
                text = mapOf(
                    "a" to UiNode.Column(listOf(NodeRef("b"))),
                    "b" to UiNode.Column(listOf(NodeRef("a"))),
                    "t1" to UiNode.Text("fine"),
                ),
            ),
        )
        assertTrue(result.errors.any { it.code == A2uiErrorCode.CYCLIC_REFERENCE }, result.errors.toString())
        val remaining = result.event.componentsOf()
        assertTrue("t1" in remaining, "An unrelated component is not collateral damage")
        // Either end of a two-node cycle may be the one dropped; what matters is that exactly one
        // goes, so the surface still terminates and still renders the rest.
        assertTrue("a" in remaining || "b" in remaining, "The cycle is broken, not left in place: $remaining")
        assertTrue(!("a" in remaining && "b" in remaining), "One end of the cycle is dropped: $remaining")
    }

    @Test
    fun aDeepChainIsNotMistakenForACycle() {
        val nodes = (0 until 40).associate { index: Int ->
            "c$index" to UiNode.Column(listOf(NodeRef("c${index + 1}")))
        }
        val result = validator.validate(
            create(
                column = emptyList(),
                text = nodes + mapOf("c40" to UiNode.Text("leaf")),
            ),
        )
        assertTrue(result.errors.isEmpty(), "A long chain is not a cycle: ${result.errors.map { it.message }}")
    }

    @Test
    fun aSelfReferenceIsACycle() {
        val result = validator.validate(
            create(column = emptyList(), text = mapOf("a" to UiNode.Column(listOf(NodeRef("a"))))),
        )
        assertTrue(result.errors.any { it.code == A2uiErrorCode.CYCLIC_REFERENCE })
    }

    @Test
    fun aTabPanelReferenceIsCheckedToo() {
        val result = validator.validate(
            create(
                column = listOf("tabs"),
                text = mapOf(
                    "tabs" to UiNode.Tabs(listOf(UiNode.Tab("One", NodeRef("ghost")))),
                ),
            ),
        )
        assertTrue(result.errors.any { it.code == A2uiErrorCode.UNKNOWN_NODE_REF }, result.errors.toString())
    }

    @Test
    fun aCardWrappingOneChildIsResolved() {
        val result = validator.validate(
            create(
                column = listOf("card"),
                text = mapOf("card" to UiNode.Card(NodeRef("t1")), "t1" to UiNode.Text("Hi")),
            ),
        )
        assertTrue(result.errors.isEmpty(), result.errors.toString())
    }

    @Test
    fun messagesThatCarryNoComponentsAreLeftAlone() {
        val result = validator.validate(
            UiEvent.UpdateData(surface, UiPath.parse("/name"), kotlinx.serialization.json.JsonPrimitive("x")),
        )
        assertTrue(result.errors.isEmpty())
    }

    private fun create(
        column: List<String>,
        text: Map<String, UiNode>,
    ): UiEvent.CreateSurface {
        val components: MutableMap<String, UiNode> = mutableMapOf()
        if (text.isNotEmpty() || column.isNotEmpty()) {
            components["root"] = UiNode.Column(column.map { NodeRef(it) })
        }
        components += text
        val rootId: String = if (components.containsKey("root")) "root" else components.keys.first()
        return UiEvent.CreateSurface(surface, NodeRef(rootId), components)
    }

    private fun UiEvent.componentsOf(): Map<String, UiNode> = when (this) {
        is UiEvent.CreateSurface -> components
        is UiEvent.UpdateComponents -> components
        else -> emptyMap()
    }
}

package com.singularity.todo.feature.genui.catalog

import com.singularity.todo.feature.genui.core.A2uiParseOutcome
import com.singularity.todo.feature.genui.parser.A2uiParser
import com.singularity.todo.feature.genui.parser.UiEvent
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.material3.Material3Catalog
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The catalog is the contract, and these are the checks that keep it from becoming a second
 * source of truth that quietly disagrees with the first.
 *
 * The three failures guarded here are all silent, and each was possible in the arrangement this
 * replaced: a kind in the hierarchy with no schema, a schema with no renderer, and a name list
 * whose contents depended on the build rather than on a declaration.
 */
@Tag("fast")
class SingularityCatalogTest {

    private val catalog: A2uiCatalog = SingularityCatalog

    private val registry: ComponentRegistry = ComponentRegistry().also { Material3Catalog.installAll(it) }

    @Test
    fun catalogDeclaresEverySerializableNodeType() {
        val declared: Set<String> = serializableNodeNames()
        val missing: Set<String> = declared - catalog.components.keys
        assertTrue(missing.isEmpty(), "Node types with no catalog schema: $missing")
    }

    @Test
    fun everyCatalogComponentHasARenderer() {
        val unrenderable: Set<String> = catalog.components.keys.filterNot { kind: String -> registry.has(kind) }.toSet()
        assertTrue(unrenderable.isEmpty(), "Catalog components with no renderer: $unrenderable")
    }

    @Test
    fun everyRendererHasACatalogEntry() {
        val declared: Set<String> = catalog.components.keys
        val undeclared: List<String> = NodeKinds.ALL.filterNot { it in declared }
        assertTrue(undeclared.isEmpty(), "Rendered components missing from the catalog: $undeclared")
    }

    @Test
    fun domainComponentsAreDeclaredAndRendered() {
        for (name in listOf("task_card", "due_date", "project_chip")) {
            assertNotNull(catalog.component(name), "$name is declared in the node hierarchy, so it needs a schema")
            assertTrue(registry.has(name), "$name has no renderer")
        }
    }

    @Test
    fun aDueDateWithNeitherPathNorValueIsRejected() {
        // The one rule of this component that is not expressible as a required property: either
        // source is acceptable, and a component with neither has nothing to draw.
        val outcome = A2uiParser(catalog).parseLine(
            """{"createSurface":{"surfaceId":"s","rootId":"d","components":[
                {"id":"d","kind":"due_date"}]}}""",
        )
        val parsed = outcome as A2uiParseOutcome.Parsed
        assertEquals(0, (parsed.event as UiEvent.CreateSurface).components.size, "There is nothing to draw")
        assertEquals(1, parsed.errors.size)
    }

    @Test
    fun componentNamesAreUnique() {
        // A duplicated name would silently shadow a schema in the map, so the count has to match
        // the declaration count rather than the set count.
        val declared: Int = componentSchemas().size
        assertEquals(declared, catalog.components.size)
    }

    @Test
    fun tabIsNotOfferedToTheModelAsAComponent() {
        // Tab is a nested structure inside the tabs array, not something the model can place by id.
        // The name-list implementation dropped it because it carried no serialization name, which
        // was correct by accident; here it is asserted directly.
        assertFalse("tab" in catalog.components)
        assertNotNull(catalog.component("tabs"))
    }

    @Test
    fun modalIsRestrictedToTopLevelContainers() {
        val modal: A2uiComponentSchema = assertNotNull(catalog.component("modal"))
        assertEquals(setOf("column", "row", "card"), modal.allowedParents)
        assertTrue(catalog.allowsChild("column", "modal"))
        assertFalse(catalog.allowsChild("list", "modal"))
        assertFalse(catalog.allowsChild("modal", "modal"))
    }

    @Test
    fun undeclaredChildIsNeverAllowed() {
        assertFalse(catalog.allowsChild("column", "chart"))
    }

    @Test
    fun requiredPropertiesAreDeclaredForEveryComponentWithThem() {
        val inconsistent: List<String> = catalog.components.keys.sorted().filter { name: String ->
            val schema: A2uiComponentSchema = catalog.component(name) ?: return@filter false
            schema.properties.any { it.required && it.default != null }
        }
        assertTrue(inconsistent.isEmpty(), "Required properties that also carry a default: $inconsistent")
    }

    private fun serializableNodeNames(): Set<String> = UiNode::class.sealedSubclasses
        .mapNotNull { subclass ->
            subclass.annotations.filterIsInstance<SerialName>().firstOrNull()?.value
        }
        .toSet()
}

/** Every kind the renderer registry can draw, kept explicit so the check is not self-fulfilling. */
private object NodeKinds {
    val ALL: List<String> = listOf(
        "text", "heading", "badge", "icon", "divider",
        "button", "text_field", "checkbox",
        "column", "row", "list", "card", "tabs", "modal",
        "task_card", "due_date", "project_chip",
    )
}

/** Generation of the instructions the model reads. */
@Tag("fast")
class CatalogPromptTest {

    private val catalog: A2uiCatalog = SingularityCatalog

    @Test
    fun promptIsDeterministic() {
        assertEquals(CatalogPrompt.render(catalog), CatalogPrompt.render(catalog))
    }

    @Test
    fun promptNamesEveryComponentAndFunction() {
        val prompt: String = CatalogPrompt.render(catalog)
        val missingComponents: List<String> = catalog.components.keys.filterNot { it in prompt }
        val missingFunctions: List<String> = catalog.functions.keys.filterNot { it in prompt }
        assertTrue(missingComponents.isEmpty(), "Components absent from the prompt: $missingComponents")
        assertTrue(missingFunctions.isEmpty(), "Functions absent from the prompt: $missingFunctions")
    }

    @Test
    fun promptStatesRequirednessAndTypes() {
        val prompt: String = CatalogPrompt.render(catalog)
        // A name on its own is what the model got before; requiredness is what it cannot guess.
        assertTrue("label: string, required" in prompt, "Required marker missing:\n$prompt")
        assertTrue("level: integer, optional (default 2)" in prompt, "Default missing:\n$prompt")
    }

    @Test
    fun promptStatesTheModalRestriction() {
        assertTrue("only inside: card, column, row" in CatalogPrompt.render(catalog))
    }

    @Test
    fun promptStatesTheWireFormat() {
        val prompt: String = CatalogPrompt.render(catalog)
        for (operation in listOf("createSurface", "updateComponents", "updateData", "deleteSurface")) {
            assertTrue(operation in prompt, "Operation $operation missing from the prompt")
        }
    }
}

/** The exported schema an agent can validate its own output against. */
@Tag("fast")
class CatalogJsonTest {

    private val catalog: A2uiCatalog = SingularityCatalog

    @Test
    fun exportIsDeterministic() {
        assertEquals(catalog.jsonSchema().toString(), catalog.jsonSchema().toString())
    }

    @Test
    fun exportCarriesCatalogIdentity() {
        val schema = catalog.jsonSchema().jsonObject
        assertEquals(catalog.id, schema["catalogId"]?.jsonPrimitive?.content)
        assertEquals(catalog.protocolVersion, schema["protocolVersion"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun exportMarksRequiredProperties() {
        val definitions = catalog.jsonSchema().jsonObject["\$defs"]?.jsonObject
        val button = assertNotNull(definitions?.get("button")?.jsonObject)
        val required: List<String> = button["required"].toString()
            .split("\"")
            .filter { it == "label" || it == "kind" || it == "id" }
        assertTrue("label" in required, "Required property missing from the export: $required")
    }

    @Test
    fun exportListsEveryComponentAsAnAlternative() {
        val any = catalog.jsonSchema().jsonObject["anyComponent"]?.jsonObject
        val alternatives: String = assertNotNull(any?.get("oneOf")).toString()
        val missing: List<String> = catalog.components.keys.filterNot { it in alternatives }
        assertTrue(missing.isEmpty(), "Components absent from the export: $missing")
    }

    @Test
    fun exportIsParseableByTheClientJson() {
        val text: String = catalog.jsonSchema().toString()
        val parsed = Json.parseToJsonElement(text)
        assertNotNull(parsed.jsonObject["\$defs"])
    }
}

package com.singularity.todo.feature.genui.render.material3

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.genui.catalog.NodeRef
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.DataContext
import com.singularity.todo.feature.genui.render.genuiTag
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults

/**
 * Installs all Material3 renderers into [registry].
 *
 * Usage:
 * ```
 * val registry = ComponentRegistry().also { Material3Catalog.install(it) }
 * ```
 */
object Material3Catalog {
    fun install(registry: ComponentRegistry) {
        registry.apply {
            registerText()
            registerHeading()
            registerButton()
            registerColumn()
            registerRow()
            registerCard()
            registerList()
            registerDivider()
            registerBadge()
            registerTextField()
            registerCheckbox()
            registerTabs()
            registerIcon()
            registerModal()
        }
    }

    private fun ComponentRegistry.registerText() = register("text") { node, _ ->
        val t = node as UiNode.Text
        Text(
            text = t.value,
            color = when (t.tone) {
                UiNode.Tone.Error -> Color.Red
                UiNode.Tone.Warning -> Color(0xFFCC7700)
                UiNode.Tone.Positive -> Color(0xFF2E7D32)
                else -> Color.Unspecified
            },
            modifier = Modifier.genuiTag("text_${t.value.take(16).replace(" ", "_")}", "Text: ${t.value.take(32)}"),
        )
    }

    private fun ComponentRegistry.registerHeading() = register("heading") { node, _ ->
        val h = node as UiNode.Heading
        Text(
            text = h.text,
            style = when (h.level) {
                1 -> MaterialTheme.typography.headlineLarge
                2 -> MaterialTheme.typography.headlineMedium
                3 -> MaterialTheme.typography.headlineSmall
                else -> MaterialTheme.typography.titleLarge
            },
            modifier = Modifier.genuiTag("heading_${h.text.take(16).replace(" ", "_")}", "Heading: ${h.text}"),
        )
    }

    private fun ComponentRegistry.registerButton() = register("button") { node, ctx ->
        val b = node as UiNode.Button
        androidx.compose.material3.Button(
            onClick = { ctx.onAction(ctx.surfaceId, b.action ?: "", b.data) },
            modifier = Modifier.genuiTag("btn_${b.label.replace(" ", "_")}", "Button: ${b.label}"),
        ) {
            Text(b.label)
        }
    }

    private fun ComponentRegistry.registerColumn() = register("column") { node, ctx ->
        val c = node as UiNode.Column
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.genuiTag("column", "Column"),
        ) {
            c.children.forEach { ref -> RenderSingle(ref, ctx) }
        }
    }

    private fun ComponentRegistry.registerRow() = register("row") { node, ctx ->
        val r = node as UiNode.Row
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.genuiTag("row", "Row"),
        ) {
            r.children.forEach { ref -> RenderSingle(ref, ctx) }
        }
    }

    private fun ComponentRegistry.registerCard() = register("card") { node, ctx ->
        val c = node as UiNode.Card
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .genuiTag("card", "Card")
                .padding(4.dp),
        ) {
            RenderSingle(c.child, ctx)
        }
    }

    private fun ComponentRegistry.registerList() = register("list") { node, ctx ->
        val l = node as UiNode.ListView
        if (l.direction == UiNode.Direction.Vertical) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.genuiTag("list_v", "List").heightIn(max = 400.dp),
            ) {
                items(l.children) { ref -> RenderSingle(ref, ctx) }
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.genuiTag("list_h", "List"),
            ) {
                items(l.children) { ref -> RenderSingle(ref, ctx) }
            }
        }
    }

    private fun ComponentRegistry.registerDivider() = register("divider") { _, _ ->
        HorizontalDivider(modifier = Modifier.genuiTag("divider", "Divider").fillMaxWidth())
    }

    private fun ComponentRegistry.registerBadge() = register("badge") { node, _ ->
        val b = node as UiNode.Badge
        val color = when (b.tone) {
            UiNode.Tone.Positive -> Color(0xFF2E7D32)
            UiNode.Tone.Warning -> Color(0xFFCC7700)
            UiNode.Tone.Error -> Color.Red
            else -> MaterialTheme.colorScheme.primary
        }
        SuggestionChip(
            onClick = { },
            label = { Text(b.text) },
            modifier = Modifier.genuiTag("badge_${b.text}", "Badge: ${b.text}"),
            colors = SuggestionChipDefaults.suggestionChipColors(
                containerColor = color.copy(alpha = 0.15f),
                labelColor = color,
            ),
        )
    }

    private fun ComponentRegistry.registerTextField() = register("text_field") { node, ctx ->
        val f = node as UiNode.TextField
        OutlinedTextField(
            value = f.initial,
            onValueChange = { newValue: String ->
                ctx.onDataChange(
                    ctx.surfaceId,
                    f.path,
                    kotlinx.serialization.json.JsonPrimitive(newValue),
                )
            },
            label = { Text(f.label) },
            modifier = Modifier.genuiTag("textfield_${f.label.replace(" ", "_")}", "TextField: ${f.label}"),
        )
    }

    private fun ComponentRegistry.registerCheckbox() = register("checkbox") { node, ctx ->
        val c = node as UiNode.Checkbox
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .genuiTag("checkbox_${c.label.replace(" ", "_")}", "Checkbox: ${c.label}")
                .selectable(selected = c.initial, onClick = {
                    ctx.onDataChange(
                        ctx.surfaceId,
                        c.path,
                        kotlinx.serialization.json.JsonPrimitive(!c.initial),
                    )
                }),
        ) {
            Checkbox(checked = c.initial, onCheckedChange = null)
            Spacer(Modifier.width(8.dp))
            Text(c.label)
        }
    }

    private fun ComponentRegistry.registerTabs() = register("tabs") { node, ctx ->
        val t = node as UiNode.Tabs
        if (t.tabs.isEmpty()) return@register
        var selected by remember { mutableIntStateOf(0) }
        Column(modifier = Modifier.genuiTag("tabs", "Tabs")) {
            TabRow(selectedTabIndex = selected) {
                t.tabs.forEachIndexed { idx: Int, tab: UiNode.Tab ->
                    Tab(
                        selected = selected == idx,
                        onClick = { selected = idx },
                        text = { Text(tab.title) },
                    )
                }
            }
            val selectedTab = t.tabs.getOrNull(selected) ?: return@Column
            RenderSingle(selectedTab.child, ctx)
        }
    }

    private fun ComponentRegistry.registerIcon() = register("icon") { node, _ ->
        val i = node as UiNode.Icon
        val icon = resolveIcon(i.name)
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = i.name,
                modifier = Modifier.genuiTag("icon_${i.name}", "Icon: ${i.name}"),
            )
        } else {
            Text("[icon: ${i.name}]", color = Color.Gray)
        }
    }

    private fun ComponentRegistry.registerModal() = register("modal") { node, ctx ->
        val m = node as UiNode.Modal
        RenderSingle(m.child, ctx)
    }
}

// ─── Package-private render helpers ──────────────────────────────────────────

@Composable
private fun RenderSingle(ref: NodeRef, ctx: DataContext) {
    val surfaces by ctx.surfaces.collectAsState()
    val surface = surfaces[ctx.surfaceId] ?: return
    val node = surface.components[ref.id] ?: return
    ctx.registry.render(node, ctx)
}

private fun resolveIcon(name: String) = when (name) {
    "star"     -> Icons.Filled.Star
    "check"    -> Icons.Filled.Check
    "close"    -> Icons.Filled.Close
    "add"      -> Icons.Filled.Add
    "delete"   -> Icons.Filled.Delete
    "edit"     -> Icons.Filled.Edit
    "settings" -> Icons.Filled.Settings
    "search"   -> Icons.Filled.Search
    "info"     -> Icons.Filled.Info
    "warning"  -> Icons.Filled.Warning
    else       -> null
}

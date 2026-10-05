package com.singularity.todo.feature.genui.render.domain

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.platform.todayInSystemZone
import com.singularity.todo.core.ui.components.formatDueChip
import com.singularity.todo.core.ui.components.formatRussianDueDate
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.DataContext
import com.singularity.todo.feature.genui.render.genuiTag
import com.singularity.todo.feature.genui.render.resolveText
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The three domain components, registered into the same registry as the Material3 set.
 *
 * They read the surface's own data model and never a repository, which is what keeps this layer
 * free of task and project dependencies: the agent sends the data, the surface renders it, and
 * re-reading the same messages produces the same screen. A component that could fetch would make a
 * surface depend on when it was drawn.
 *
 * They exist because the generic components can only say "here is a column with text in it". An
 * agent asked to show three tasks builds exactly that unless the vocabulary offers the shape the
 * app already uses, and the difference is visible to the user.
 */
internal fun ComponentRegistry.registerDomainComponents() {
    register("task_card") { node, ctx, modifier -> TaskCardView(node as UiNode.TaskCard, ctx, modifier) }
    register("due_date") { node, ctx, modifier -> DueDateView(node as UiNode.DueDate, ctx, modifier) }
    register("project_chip") { node, ctx, modifier -> ProjectChipView(node as UiNode.ProjectChip, ctx, modifier) }
}

@Composable
private fun TaskCardView(card: UiNode.TaskCard, ctx: DataContext, modifier: Modifier) {
    val title: String = ctx.resolveText(card.title)
    val today = remember { todayInSystemZone() }
    val dueText: String? = if (card.duePath != null) {
        ctx.value(card.duePath).collectAsStateWithLifecycle(initialValue = null).value.text()
    } else {
        null
    }
    val projectName: String? = if (card.projectPath != null) {
        ctx.value(card.projectPath).collectAsStateWithLifecycle(initialValue = null).value.text()
    } else {
        null
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .genuiTag("task_card_${title.take(16).replace(" ", "_")}", "Task: $title")
            .tapWhen(card.action != null) { ctx.onAction(ctx.surfaceId, card.action.orEmpty(), null) },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textDecoration = if (card.done) TextDecoration.LineThrough else null,
            )
            if (dueText != null || projectName != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (dueText != null) {
                        Chip(label = dueDateText(dueText, today, UiNode.DueStyle.Relative))
                    }
                    if (projectName != null) {
                        Chip(label = projectName)
                    }
                }
            }
        }
    }
}

@Composable
private fun DueDateView(due: UiNode.DueDate, ctx: DataContext, modifier: Modifier) {
    val today = remember { todayInSystemZone() }
    val bound: JsonElement? = if (due.path != null) {
        ctx.value(due.path).collectAsStateWithLifecycle(initialValue = null).value
    } else {
        null
    }
    val raw: String? = bound.text() ?: due.value
    if (raw == null) return
    Chip(
        label = dueDateText(raw, today, due.style),
        modifier = modifier.genuiTag("due_date", "Due date"),
    )
}

@Composable
private fun ProjectChipView(chip: UiNode.ProjectChip, ctx: DataContext, modifier: Modifier) {
    val bound: JsonElement? = if (chip.path != null) {
        ctx.value(chip.path).collectAsStateWithLifecycle(initialValue = null).value
    } else {
        null
    }
    val name: String? = bound.text() ?: chip.name
    if (name == null) return
    Chip(
        label = name,
        modifier = modifier.genuiTag("project_chip_$name", "Project: $name"),
    )
}

/**
 * A disabled chip.
 *
 * Disabled because these are labels a surface displays, not controls: a project chip that looked
 * pressable and did nothing is worse than one that reads as data. Making it actionable is a
 * question about what tapping a project should do, and that belongs to a caller that has an answer.
 */
@Composable
private fun Chip(label: String, modifier: Modifier = Modifier) {
    // The empty onClick is deliberate: the chip is disabled, so the lambda is never reached, and
    // Material3's parameter is not nullable. Mirrors the same decision in DetailMetaChip.
    @Suppress("NoEmptyOnClickLambda")
    FilterChip(
        selected = false,
        onClick = {},
        enabled = false,
        label = { Text(label) },
        modifier = modifier,
    )
}

/**
 * Formats a due date through the formatters the rest of the app already uses.
 *
 * "Today, 09:00" is written once, in `core/ui/components/Formatters.kt`; a second implementation
 * here would drift from it the first time either changed.
 */
private fun dueDateText(raw: String, today: LocalDate, style: UiNode.DueStyle): String {
    val date: LocalDate = runCatching { LocalDate.parse(raw.take(10)) }.getOrNull() ?: return raw
    return when (style) {
        UiNode.DueStyle.Relative -> formatDueChip(date, null, today)?.text ?: date.toString()

        UiNode.DueStyle.Absolute -> formatRussianDueDate(date) ?: date.toString()

        UiNode.DueStyle.Both -> {
            val relative: String = formatDueChip(date, null, today)?.text ?: date.toString()
            val absolute: String = formatRussianDueDate(date) ?: date.toString()
            "$relative, $absolute"
        }
    }
}

/** The text of a JSON value, or null when it is not a string. */
private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull

/** Adds a tap only when there is an action, so a non-interactive card does not look interactive. */
private fun Modifier.tapWhen(enabled: Boolean, onClick: () -> Unit): Modifier =
    if (enabled) this.clickable(onClick = onClick) else this

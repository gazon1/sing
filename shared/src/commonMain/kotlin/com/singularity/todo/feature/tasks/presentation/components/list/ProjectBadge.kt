package com.singularity.todo.feature.tasks.presentation.components.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSizes
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing
import com.singularity.todo.feature.tasks.presentation.theme.mutedTextColor

/**
 * Проект как бейдж: цветная точка проекта и его имя.
 *
 * Цвет — проекта ([color], ARGB); текст — темы. Это то, что делает проекты
 * различимыми с одного взгляда, а не только по имени. Имя сокращается
 * многоточием и не вытесняет дату из строки (FR-031).
 *
 * @param name the project's name — the text of the badge.
 * @param color the project's ARGB colour, or null when the project has none.
 */
@Composable
fun ProjectBadge(
    name: String,
    color: Int?,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        if (color != null) {
            Box(
                modifier =
                    Modifier
                        .size(TaskListSizes.MetaIcon)
                        .clip(CircleShape)
                        .background(Color(color)),
            )
            Spacer(Modifier.width(TaskListSpacing.Xxs))
        }
        Text(
            text = name,
            color = mutedTextColor(),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

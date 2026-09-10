package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.feature.tasks.presentation.theme.TaskColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing

/**
 * Нижняя панель с primary action.
 *
 * В исходном макете не было явной кнопки сохранения — пользователь должен
 * был бы полагаться на системный back или неявный жест, что плохо с точки
 * зрения UX: primary action экрана должен быть очевиден и физически
 * доступен большим пальцем (bottom-anchored, а не в топ-баре).
 */
@Composable
fun TaskSaveBar(
    isEnabled: Boolean,
    onSaveClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = TaskColors.Background,
        modifier = modifier.fillMaxWidth()
    ) {
        Button(
            onClick = onSaveClick,
            enabled = isEnabled,
            shape = RoundedCornerShape(TaskSpacing.cardCornerRadius),
            colors = ButtonDefaults.buttonColors(
                containerColor = TaskColors.AccentBlue,
                disabledContainerColor = TaskColors.Surface
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TaskSpacing.screenPadding, vertical = TaskSpacing.md)
                .height(52.dp)
        ) {
            Text(
                text = "Сохранить",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

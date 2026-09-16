package com.singularity.todo.feature.calendar.presentation.components.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import com.singularity.todo.feature.calendar.presentation.theme.CalendarPalette
import com.singularity.todo.feature.calendar.presentation.theme.LocalCalendarPalette

/** Dropdown selector for CalendarViewMode (Day / 4 days / Week / Month). */
@Composable
fun ViewModeDropdown(selected: CalendarViewMode, onSelect: (CalendarViewMode) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val palette: CalendarPalette = LocalCalendarPalette.current

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .background(palette.surface, RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selected.label,
                color = palette.textPrimary,
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                Icons.Default.UnfoldMore,
                contentDescription = null,
                tint = palette.textMuted,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(palette.surface),
        ) {
            CalendarViewMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = mode.label,
                            color = palette.textPrimary,
                            fontWeight = if (mode == selected) FontWeight.Medium else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

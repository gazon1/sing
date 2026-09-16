package com.singularity.todo.feature.calendar.presentation.components.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.feature.calendar.domain.logic.displayName
import com.singularity.todo.feature.calendar.domain.logic.monthGridDates
import com.singularity.todo.feature.calendar.presentation.theme.LocalCalendarPalette
import kotlinx.datetime.LocalDate

/**
 * Right-side mini calendar panel (slide-in overlay).
 * Shows a month grid for navigation plus filter rows.
 */
@Composable
fun MiniCalendarPanel(
    monthAnchor: LocalDate,
    selectedDate: LocalDate,
    today: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalCalendarPalette.current
    val weekdayLabels = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    val gridDates = remember(monthAnchor) { monthGridDates(monthAnchor) }

    Column(
        modifier = modifier
            .width(340.dp)
            .fillMaxHeight()
            .background(palette.background)
            .padding(20.dp),
    ) {
        // Header with month name and close button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${monthAnchor.month.displayName()} ${monthAnchor.year}",
                color = palette.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                Icons.Default.Close,
                contentDescription = "Close",
                tint = palette.textSecondary,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onClose() },
            )
        }

        Spacer(Modifier.height(16.dp))

        // Weekday labels
        Row(modifier = Modifier.fillMaxWidth()) {
            weekdayLabels.forEach { label ->
                Text(
                    text = label,
                    color = palette.textMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Date grid
        gridDates.chunked(7).forEach { week ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                week.forEach { date ->
                    MiniDateCell(
                        date = date,
                        isCurrentMonth = date.month == monthAnchor.month,
                        isToday = date == today,
                        isSelected = date == selectedDate,
                        onClick = { onDateSelected(date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Filter rows (placeholder — connects to CalendarFilterPanel state later)
        FilterRow(icon = Icons.Default.DonutLarge, label = "Project", onClick = {})
        FilterRow(icon = Icons.Default.LocalOffer, label = "Tags", onClick = {})
        FilterRow(icon = Icons.Default.PriorityHigh, label = "Priority", onClick = {})
        FilterRow(icon = Icons.Default.Checklist, label = "Tasks / Notes", onClick = {})
        FilterRow(icon = Icons.Default.CheckBoxOutlineBlank, label = "Status", onClick = {})
    }
}

@Composable
private fun MiniDateCell(
    date: LocalDate,
    isCurrentMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalCalendarPalette.current

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .background(
                when {
                    isSelected -> palette.accent
                    isToday -> palette.accent.copy(alpha = 0.25f)
                    else -> Color.Transparent
                },
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${date.dayOfMonth}",
                color = when {
                    isSelected -> Color.White
                    !isCurrentMonth -> palette.textMuted
                    else -> palette.textPrimary
                },
                fontSize = 14.sp,
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
            )
            // Show month abbreviation on the 1st of each month
            if (date.dayOfMonth == 1) {
                Text(
                    text = date.month.displayName().take(3),
                    color = palette.textMuted,
                    fontSize = 9.sp,
                )
            }
        }
    }
}

@Composable
private fun FilterRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val palette = LocalCalendarPalette.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = palette.textSecondary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                color = palette.textPrimary,
                fontSize = 15.sp,
            )
        }
        Icon(
            Icons.Default.Close, // placeholder — will be ChevronRight
            contentDescription = null,
            tint = palette.textMuted,
            modifier = Modifier.size(18.dp),
        )
    }
    Spacer(Modifier.height(10.dp))
}

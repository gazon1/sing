package com.singularity.todo.feature.tasks.presentation.components.upcoming

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.feature.tasks.presentation.theme.TaskListColors
import kotlinx.datetime.LocalDate

/**
 * Top bar for the Upcoming screen.
 *
 * Shows a back button and the currently selected date formatted as
 * "Thursday, 17 September 2026".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpcomingTopBar(
    selectedDate: LocalDate,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        title = {
            Text(
                text = formatFullDate(selectedDate),
                color = TaskListColors.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TaskListColors.TextPrimary,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = TaskListColors.Background,
        ),
    )
}

private fun formatFullDate(date: LocalDate): String {
    val dow = when (date.dayOfWeek) {
        kotlinx.datetime.DayOfWeek.MONDAY -> "Monday"
        kotlinx.datetime.DayOfWeek.TUESDAY -> "Tuesday"
        kotlinx.datetime.DayOfWeek.WEDNESDAY -> "Wednesday"
        kotlinx.datetime.DayOfWeek.THURSDAY -> "Thursday"
        kotlinx.datetime.DayOfWeek.FRIDAY -> "Friday"
        kotlinx.datetime.DayOfWeek.SATURDAY -> "Saturday"
        kotlinx.datetime.DayOfWeek.SUNDAY -> "Sunday"
    }
    val month = when (date.month) {
        kotlinx.datetime.Month.JANUARY -> "January"
        kotlinx.datetime.Month.FEBRUARY -> "February"
        kotlinx.datetime.Month.MARCH -> "March"
        kotlinx.datetime.Month.APRIL -> "April"
        kotlinx.datetime.Month.MAY -> "May"
        kotlinx.datetime.Month.JUNE -> "June"
        kotlinx.datetime.Month.JULY -> "July"
        kotlinx.datetime.Month.AUGUST -> "August"
        kotlinx.datetime.Month.SEPTEMBER -> "September"
        kotlinx.datetime.Month.OCTOBER -> "October"
        kotlinx.datetime.Month.NOVEMBER -> "November"
        kotlinx.datetime.Month.DECEMBER -> "December"
    }
    return "$dow, ${date.dayOfMonth} $month ${date.year}"
}

package com.singularity.todo.feature.calendar.presentation.components.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import com.singularity.todo.core.ui.components.LocalScreenActions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.ShellOverride
import com.singularity.todo.feature.calendar.presentation.state.CalendarIntent
import com.singularity.todo.feature.calendar.presentation.state.CalendarUiState
import com.singularity.todo.feature.calendar.presentation.theme.LocalCalendarPalette
import kotlinx.datetime.LocalDate

/**
 * Top bar of the Calendar screen: date range label, navigation arrows, "Today",
 * view-mode dropdown, and mini-calendar toggle.
 *
 * @param headerLabelOverride Optional override for the displayed label. When
 *   non-null, replaces [CalendarUiState.Loaded]'s `headerLabel` (used by
 *   [CalendarContent] to render the live pager header without VM round-trips).
 */
@Suppress("LongMethod")
@Composable
fun CalendarTopBar(
    state: CalendarUiState.Loaded,
    today: LocalDate,
    onIntent: (CalendarIntent) -> Unit,
    modifier: Modifier = Modifier,
    headerLabelOverride: String? = null,
    onBack: (() -> Unit)? = null,
) {
    val palette = LocalCalendarPalette.current
    val displayedLabel = headerLabelOverride ?: state.headerLabel

    val holder = LocalScreenActions.current
    if (holder != null) {
        ShellOverride(
            title = {
                Text(
                    text = displayedLabel,
                    color = palette.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier = Modifier.testTag(TestTags.CALENDAR_MONTH_TITLE),
                )
            },
            navigationIcon = if (onBack != null) {
                {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(TestTags.TOP_BAR_BACK_BUTTON)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            } else {
                null
            },

        )
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.CalendarMonth,
            contentDescription = null,
            tint = palette.textSecondary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = displayedLabel,
            color = palette.textPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            // The title's wording is the app's; the node that holds it is the
            // app's structure. A flow that asserts the string pins the app to a
            // sentence, a flow that asserts the tag asks whether the calendar
            // named the period at all.
            modifier = Modifier.testTag(TestTags.CALENDAR_MONTH_TITLE),
        )
        // The "More" affordance that used to sit here was removed: it opened nothing
        // and there is no overflow menu behind it. See ADR
        // 2026-09-30-dead-affordances-removed.

        Spacer(Modifier.weight(1f))

        ViewModeDropdown(
            selected = state.viewMode,
            onSelect = { onIntent(CalendarIntent.ViewModeChanged(it)) },
        )

        Spacer(Modifier.width(12.dp))

        Icon(
            Icons.Default.CalendarToday,
            contentDescription = "Open mini calendar",
            tint = palette.textSecondary,
            modifier = Modifier
                .size(20.dp)
                .clickable { onIntent(CalendarIntent.ToggleMiniCalendar) },
        )

        Spacer(Modifier.width(12.dp))

        Icon(
            Icons.Default.ChevronLeft,
            contentDescription = "Previous",
            tint = palette.textSecondary,
            modifier = Modifier
                .size(20.dp)
                .clickable { onIntent(CalendarIntent.GoPrevious) },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Today",
            color = palette.textSecondary,
            fontSize = 14.sp,
            modifier = Modifier.clickable { onIntent(CalendarIntent.GoToday) },
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = "Next",
            tint = palette.textSecondary,
            modifier = Modifier
                .size(20.dp)
                .clickable { onIntent(CalendarIntent.GoNext) },
        )

        // The "Filter" icon + label that used to close the bar were removed for the
        // same reason: no filter state, no filter sheet, no filtering in the query.
    }
}

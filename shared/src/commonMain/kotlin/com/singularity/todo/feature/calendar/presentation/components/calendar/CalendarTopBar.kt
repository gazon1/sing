package com.singularity.todo.feature.calendar.presentation.components.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.LocalScreenActions
import com.singularity.todo.core.ui.components.ShellOverride
import com.singularity.todo.core.ui.touchTarget
import com.singularity.todo.feature.calendar.domain.model.CalendarViewMode
import com.singularity.todo.feature.calendar.domain.state.CalendarIntent
import com.singularity.todo.feature.calendar.domain.state.CalendarUiState
import com.singularity.todo.feature.calendar.presentation.theme.CalendarPalette
import com.singularity.todo.feature.calendar.presentation.theme.LocalCalendarPalette
import kotlinx.datetime.LocalDate

/**
 * The width below which the bar stops trying to hold the period name and the
 * controls on one line.
 *
 * The two do not fit side by side on a phone. The controls are five 48 dp tap
 * targets (four buttons and the view-mode trigger) and need about 300 dp of the
 * bar's content width; a period name worth reading needs about 120 dp more; the
 * `16.dp` padding on each side costs 32 dp. A 360 dp phone leaves 328 dp of
 * content, so something has to give — and both ways of making it give are
 * wrong. A squeezed control is an unreachable control (that is the bug this bar
 * was fixed for: "Today" wrapped one letter per line at 360 dp), and a control
 * parked behind a horizontal drag on a 36 dp strip is reachable only in a test.
 * So below this width the bar takes a second line: the period name on one, the
 * controls on the other, each with the whole width to itself.
 *
 * The shell branch does not need this: the shell owns the title there, and the
 * controls alone fit a 360 dp phone's 328 dp of content width.
 */
private val SingleLineMinWidth = 480.dp

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
        // The shell owns the only bar on Android, so the month belongs to the
        // shell bar: the shell says which month is on screen instead of saying
        // the word "Calendar".
        ShellOverride(
            title = { CalendarBarTitle(text = displayedLabel, palette = palette) },
            navigationIcon =
                if (onBack != null) {
                    {
                        IconButton(onClick = onBack, modifier = Modifier.testTag(TestTags.TOP_BAR_BACK_BUTTON)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                } else {
                    null
                },
        )
        // The controls stay in the screen. The shell's bar has room for a title
        // and a back button, and not for a view-mode dropdown and a month
        // pager: a calendar whose month cannot be changed is not a calendar.
        // They need about 300 dp and a 360 dp phone leaves 328 dp, so they are
        // neither squeezed, nor scrolled, nor hidden behind an overflow.
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .background(palette.background)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CalendarControls(palette = palette, viewMode = state.viewMode, onIntent = onIntent)
        }
        return
    }

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .background(palette.background)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (maxWidth < SingleLineMinWidth) {
            // Two lines. Stacking is what keeps both readable: the period name is
            // not cut down to the few characters a squeezed share of one line
            // would leave, and the controls are not handed the name's leavings.
            Column(modifier = Modifier.fillMaxWidth()) {
                CalendarBarTitle(
                    text = displayedLabel,
                    palette = palette,
                    // The full width of its own line is this title's maximum, so
                    // the ellipsis below is what a too-long name meets — not a
                    // zero-width slot, and not a wrap onto a second line.
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CalendarControls(palette = palette, viewMode = state.viewMode, onIntent = onIntent)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.CalendarMonth,
                    contentDescription = null,
                    tint = palette.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                CalendarBarTitle(
                    text = displayedLabel,
                    palette = palette,
                    // The title is the only child that can give in this row: the
                    // icon, the spacers and the controls are measured at their own
                    // size first, and the title takes what is left. A long period
                    // name therefore costs the title its own text and never costs
                    // the bar its pager.
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                CalendarControls(palette = palette, viewMode = state.viewMode, onIntent = onIntent)
            }
        }
        // The "More" affordance that used to sit in this bar was removed: it
        // opened nothing and there is no overflow menu behind it. See ADR
        // 2026-09-30-dead-affordances-removed.
    }
}

/**
 * The period name, in the one shape every branch of the bar draws it in.
 *
 * Three call sites (the shell's title slot, the wide row, the stacked row) and
 * one definition, because a bar whose title truncates in one place and wraps in
 * another is the bug this file exists to not have.
 *
 * The text is bounded by whatever the caller gives it — a weight in the wide
 * row, the whole line in the stacked one, the shell's title slot in the third —
 * so `maxLines = 1` with `Ellipsis` is the live mechanism and not decoration:
 * a name wider than its slot would otherwise spend the room it does not have
 * stacking one letter per line ("September 28 – October 4, 2026" at 20 sp is
 * wider than a phone).
 */
@Composable
private fun CalendarBarTitle(
    text: String,
    palette: CalendarPalette,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = palette.textPrimary,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        // The title's wording is the app's; the node that holds it is the
        // app's structure. A flow that asserts the string pins the app to a
        // sentence, a flow that asserts the tag asks whether the calendar
        // named the period at all.
        modifier = modifier.testTag(TestTags.CALENDAR_MONTH_TITLE),
    )
}

/**
 * The controls of the calendar bar: the view-mode dropdown, the mini-calendar
 * toggle, and the month pager.
 *
 * They are a composable of their own because they are drawn in two places: in
 * the bar the desktop draws, and in the bar the Android screen keeps while the
 * shell draws the title. Two copies of these controls would drift — the month
 * pager is the control a user notices first when it stops working.
 *
 * The strip is laid out at the size of its controls, and it does not scroll. A
 * scroll would not fix a phone-width bar, it would hide the problem: the strip
 * starts at scroll offset 0, so the month pager would sit off-screen behind a
 * drag nobody can see on a 36 dp bar. What brings the strip inside 328 dp
 * instead is its spacing — each control is a 48 dp tap target and the targets
 * abut, because `touchTarget()` already keeps their glyphs ~28 dp apart. The
 * bar that contains the strip is then responsible for giving it that width: the
 * desktop bar stacks below [SingleLineMinWidth], and the shell bar holds
 * nothing else.
 */
@Composable
private fun CalendarControls(
    palette: CalendarPalette,
    viewMode: CalendarViewMode,
    onIntent: (CalendarIntent) -> Unit,
) {
    Row(
        modifier = Modifier.testTag(TestTags.CALENDAR_CONTROLS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewModeDropdown(
            selected = viewMode,
            onSelect = { onIntent(CalendarIntent.ViewModeChanged(it)) },
        )

        // The trigger is a padded text, not a bare 48 dp target, so it is the one
        // place the strip needs a spacer; the four targets below abut.
        Spacer(Modifier.width(8.dp))

        Icon(
            Icons.Default.CalendarToday,
            contentDescription = "Open mini calendar",
            tint = palette.textSecondary,
            modifier =
                Modifier
                    .touchTarget()
                    .size(20.dp)
                    .clickable { onIntent(CalendarIntent.ToggleMiniCalendar) },
        )

        Icon(
            Icons.Default.ChevronLeft,
            contentDescription = "Previous",
            tint = palette.textSecondary,
            modifier =
                Modifier
                    .touchTarget()
                    .size(20.dp)
                    .clickable { onIntent(CalendarIntent.GoPrevious) },
        )
        Text(
            text = "Today",
            color = palette.textSecondary,
            fontSize = 14.sp,
            // The strip is measured against the room the bar has, so this label
            // has a finite maximum; without these two lines a bar narrower than
            // the strip would answer by stacking the word one letter per line —
            // the exact failure this bar was fixed for at 360 dp. The label is
            // also the last thing in the strip that may give: every control
            // around it keeps its own size.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .testTag(TestTags.CALENDAR_TODAY)
                    .touchTarget()
                    .clickable { onIntent(CalendarIntent.GoToday) },
        )
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = "Next",
            tint = palette.textSecondary,
            modifier =
                Modifier
                    .touchTarget()
                    .size(20.dp)
                    .clickable { onIntent(CalendarIntent.GoNext) },
        )

        // The "Filter" icon + label that used to close the bar were removed for the
        // same reason: no filter state, no filter sheet, no filtering in the query.
    }
}

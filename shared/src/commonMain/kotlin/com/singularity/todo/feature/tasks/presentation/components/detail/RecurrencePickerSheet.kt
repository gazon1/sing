package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Monthly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Weekly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Yearly
import com.singularity.todo.feature.tasks.presentation.components.RecurrenceFormatters
import com.singularity.todo.feature.tasks.presentation.components.RecurrenceFormatters.baseLabel
import com.singularity.todo.feature.tasks.presentation.components.RecurrenceFormatters.label
import com.singularity.todo.feature.tasks.presentation.components.TaskEditorSheetHost
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

private val WEEKDAY_NAMES = listOf(
    "Mon" to 1,
    "Tue" to 2,
    "Wed" to 3,
    "Thu" to 4,
    "Fri" to 5,
    "Sat" to 6,
    "Sun" to 7,
)

private val MONTH_NAMES = listOf(
    1 to "Jan", 2 to "Feb", 3 to "Mar", 4 to "Apr",
    5 to "May", 6 to "Jun", 7 to "Jul", 8 to "Aug",
    9 to "Sep", 10 to "Oct", 11 to "Nov", 12 to "Dec",
)

/**
 * Recurrence rule picker bottom sheet.
 *
 * Supports all [RecurrenceSpec] types: Interval (daily/weekly/monthly/yearly),
 * Weekly (specific weekdays), Monthly (day of month), Yearly (month + day).
 *
 * @param currentSpec The currently selected recurrence spec, or null for no recurrence.
 * @param anchorDate The date to use as anchor for preview (typically task's due date or today).
 * @param onApply Called with the selected [RecurrenceSpec], or null to clear recurrence.
 * @param onDismiss Called when the user dismisses the sheet.
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RecurrencePickerSheet(
    currentSpec: RecurrenceSpec?,
    anchorDate: LocalDate,
    onApply: (RecurrenceSpec?) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedBase by remember {
        mutableStateOf(
            currentSpec?.base
                ?: RecurrenceBase.FROM_COMPLETION,
        )
    }
    var selectedType by remember {
        mutableStateOf(
            currentSpec?.specType()
                ?: SpecType.INTERVAL_DAY,
        )
    }
    var intervalAmount by remember {
        mutableIntStateOf(
            currentSpec?.intervalAmount()
                ?: 1,
        )
    }
    var selectedUnit by remember {
        mutableStateOf(
            currentSpec?.intervalUnit()
                ?: DateTimeUnit.WEEK,
        )
    }
    var selectedWeekdays by remember {
        mutableStateOf(
            (currentSpec as? Weekly)?.weekdays
                ?: setOf(anchorDate.dayOfWeek.ordinal + 1),
        )
    }
    var monthlyDay by remember {
        mutableIntStateOf(
            (currentSpec as? Monthly)?.dayOfMonth
                ?: anchorDate.day,
        )
    }
    var yearlyMonth by remember {
        mutableStateOf(
            (currentSpec as? Yearly)?.month
                ?: anchorDate.month.number,
        )
    }
    var yearlyDay by remember {
        mutableIntStateOf(
            (currentSpec as? Yearly)?.day
                ?: anchorDate.day,
        )
    }

    fun buildSpec(): RecurrenceSpec = when (selectedType) {
        SpecType.INTERVAL_DAY -> Interval(selectedBase, intervalAmount, DateTimeUnit.DAY)
        SpecType.INTERVAL_WEEK -> Interval(selectedBase, intervalAmount, DateTimeUnit.WEEK)
        SpecType.INTERVAL_MONTH -> Interval(selectedBase, intervalAmount, DateTimeUnit.MONTH)
        SpecType.INTERVAL_YEAR -> Interval(selectedBase, intervalAmount, DateTimeUnit.YEAR)
        SpecType.WEEKLY -> Weekly(selectedBase, selectedWeekdays)
        SpecType.MONTHLY -> Monthly(selectedBase, monthlyDay)
        SpecType.YEARLY -> Yearly(selectedBase, yearlyMonth, yearlyDay)
    }

    val spec = remember(
        selectedBase,
        selectedType,
        intervalAmount,
        selectedUnit,
        selectedWeekdays,
        monthlyDay,
        yearlyMonth,
        yearlyDay,
    ) { buildSpec() }

    TaskEditorSheetHost(
        title = "Repeat",
        onClose = onDismiss,
        onConfirm = { onApply(spec) },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
        ) {
            // ── Clear recurrence ─────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth()
                    .selectable(
                        selected = currentSpec == null,
                        onClick = { onApply(null) },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 8.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = currentSpec == null, onClick = null)
                Text(
                    text = "Do not repeat",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Base mode ───────────────────────────────────────────────────
            Text(
                text = "Mode",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                RecurrenceBase.entries.forEachIndexed { index, base ->
                    SegmentedButton(
                        selected = selectedBase == base,
                        onClick = { selectedBase = base },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = RecurrenceBase.entries.size),
                        label = { Text(baseLabel(base), style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Type selector ───────────────────────────────────────────────
            Text(
                text = "Frequency",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Column(modifier = Modifier.selectableGroup()) {
                SpecType.entries.forEach { type ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .selectable(
                                selected = selectedType == type,
                                onClick = { selectedType = type },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 6.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selectedType == type, onClick = null)
                        Text(
                            text = type.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Interval amount + unit ──────────────────────────────────────
            when (selectedType) {
                SpecType.INTERVAL_DAY, SpecType.INTERVAL_WEEK,
                SpecType.INTERVAL_MONTH, SpecType.INTERVAL_YEAR,
                -> {
                    IntervalEditor(
                        amount = intervalAmount,
                        unit = selectedUnit,
                        type = selectedType,
                        onAmountChange = { intervalAmount = it },
                        onUnitChange = { selectedUnit = it },
                    )
                }

                SpecType.WEEKLY -> {
                    WeekdayEditor(
                        selected = selectedWeekdays,
                        onChange = { selectedWeekdays = it },
                    )
                }

                SpecType.MONTHLY -> {
                    DayOfMonthEditor(
                        day = monthlyDay,
                        onChange = { monthlyDay = it },
                    )
                }

                SpecType.YEARLY -> {
                    YearlyEditor(
                        month = yearlyMonth,
                        day = yearlyDay,
                        onMonthChange = { yearlyMonth = it },
                        onDayChange = { yearlyDay = it },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Preview ────────────────────────────────────────────────────
            Text(
                text = "Preview",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text(
                    text = label(spec),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = RecurrenceFormatters.nextOccurrencePreview(anchorDate, spec),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntervalEditor(
    amount: Int,
    unit: DateTimeUnit.DateBased,
    type: SpecType,
    onAmountChange: (Int) -> Unit,
    onUnitChange: (DateTimeUnit.DateBased) -> Unit,
) {
    val unitsForType = when (type) {
        SpecType.INTERVAL_DAY -> listOf(DateTimeUnit.DAY to "day(s)")
        SpecType.INTERVAL_WEEK -> listOf(DateTimeUnit.WEEK to "week(s)")
        SpecType.INTERVAL_MONTH -> listOf(DateTimeUnit.MONTH to "month(s)")
        SpecType.INTERVAL_YEAR -> listOf(DateTimeUnit.YEAR to "year(s)")
        else -> emptyList()
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = amount.toString(),
            onValueChange = {
                it.toIntOrNull()
                    ?.let { v -> if (v > 0) onAmountChange(v) }
            },
            label = { Text("Every") },
            modifier = Modifier.width(80.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            unitsForType.forEach { (u, label) ->
                Row(
                    modifier = Modifier.selectable(
                        selected = unit == u,
                        onClick = { onUnitChange(u) },
                        role = Role.RadioButton,
                    )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = unit == u, onClick = null)
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayEditor(selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WEEKDAY_NAMES.forEach { (name, iso) ->
            FilterChip(
                selected = iso in selected,
                onClick = {
                    onChange(
                        if (iso in selected) selected - iso else selected + iso,
                    )
                },
                label = { Text(name) },
            )
        }
    }
}

@Composable
private fun DayOfMonthEditor(day: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = day.toString(),
            onValueChange = {
                it.toIntOrNull()
                    ?.let { d -> if (d in 1..31) onChange(d) }
            },
            label = { Text("Day of month") },
            modifier = Modifier.width(140.dp),
        )
        Text(
            text = "(1–31)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun YearlyEditor(month: Int, day: Int, onMonthChange: (Int) -> Unit, onDayChange: (Int) -> Unit) {
    Column {
        Text(
            text = "Month",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MONTH_NAMES.forEach { (m, name) ->
                FilterChip(
                    selected = month == m,
                    onClick = { onMonthChange(m) },
                    label = { Text(name) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        DayOfMonthEditor(day = day, onChange = onDayChange)
    }
}

// ─── Spec type enum ───────────────────────────────────────────────────────────

private enum class SpecType(val label: String) {
    INTERVAL_DAY("Daily"),
    INTERVAL_WEEK("Weekly"),
    INTERVAL_MONTH("Monthly"),
    INTERVAL_YEAR("Yearly"),
    WEEKLY("Specific weekdays"),
    MONTHLY("Day of month"),
    YEARLY("Yearly on date"),
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun RecurrenceSpec.specType(): SpecType = when (this) {
    is Interval -> when (unit) {
        DateTimeUnit.DAY -> SpecType.INTERVAL_DAY
        DateTimeUnit.WEEK -> SpecType.INTERVAL_WEEK
        DateTimeUnit.MONTH -> SpecType.INTERVAL_MONTH
        DateTimeUnit.YEAR -> SpecType.INTERVAL_YEAR
        else -> SpecType.INTERVAL_DAY
    }

    is Weekly -> SpecType.WEEKLY

    is Monthly -> SpecType.MONTHLY

    is Yearly -> SpecType.YEARLY
}

private fun RecurrenceSpec.intervalAmount(): Int = (this as? Interval)?.amount
    ?: 1

private fun RecurrenceSpec.intervalUnit(): DateTimeUnit.DateBased = (this as? Interval)?.unit
    ?: DateTimeUnit.WEEK

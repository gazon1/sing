package com.singularity.todo.core.schedule

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.isoDayNumber

/**
 * The days the user works on.
 *
 * The settings screen has always had a "Working Days" section, and for a long
 * time the section held two switches: Saturday and Sunday. Two switches named
 * after the weekend can only say something about the weekend, so the section
 * promised a working week and delivered a weekend — and the flags were read by
 * nobody, which is why the dead-settings gate had to waive them instead of
 * reporting them.
 *
 * The model is now the set of working days, which is what the section always
 * claimed to be. The screen renders seven switches, the agenda asks the same
 * question ("is today one of the days the user works on?") and the persisted
 * form is the set, not a pair of booleans about two days.
 *
 * The old pair is not lost. A user who set "Saturday is a working day" before
 * this change keeps it: [fromLegacyFlags] turns the two booleans into the
 * equivalent set, and the repository reads the old values only while no set
 * has been stored yet.
 */
object WorkingDays {

    /**
     * Monday to Friday — the set a user has before they touch the section.
     */
    val DEFAULT: Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
    )

    /**
     * The set as it is stored: the ISO day numbers (Monday = 1 … Sunday = 7),
     * ascending, comma separated.
     *
     * ISO numbers rather than names, because the storage format outlives the
     * code that writes it: a name can be renamed, a number cannot.
     */
    fun toStorageString(days: Set<DayOfWeek>): String =
        days.sortedBy { it.isoDayNumber }.joinToString(",") { it.isoDayNumber.toString() }

    /**
     * The set as it is read back.
     *
     * Unknown numbers are dropped rather than fatal: a stored value from a
     * future version must not make the settings screen unopenable. A stored
     * value that names no day at all is read as "no working days" — the
     * empty set is a choice, and the difference between "never set" and
     * "set to nothing" is carried by the preference being absent, not by
     * the text.
     */
    fun fromStorageString(raw: String): Set<DayOfWeek> {
        val byNumber = DayOfWeek.entries.associateBy { it.isoDayNumber }
        return raw.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .mapNotNull { byNumber[it] }
            .toSet()
    }

    /**
     * The set the two legacy booleans meant.
     *
     * The booleans are named `weekendSat` / `weekendSun`, which reads the other
     * way round from the switch under the "Working Days" heading: `true` means
     * the day *is* a working day. The screen was the authority on that, and
     * this is the same reading — [saturdayIsWorkingDay] is true when the user
     * turned the Saturday switch on.
     */
    fun fromLegacyFlags(
        saturdayIsWorkingDay: Boolean,
        sundayIsWorkingDay: Boolean,
    ): Set<DayOfWeek> = buildSet {
        addAll(DEFAULT)
        if (saturdayIsWorkingDay) add(DayOfWeek.SATURDAY)
        if (sundayIsWorkingDay) add(DayOfWeek.SUNDAY)
    }

    /**
     * The two legacy booleans the set implies, for export formats that still
     * carry them. A day that is not Saturday or Sunday has no legacy flag, so
     * it is lost in this projection — which is exactly why the new field
     * travels alongside the old ones.
     */
    fun toLegacyFlags(days: Set<DayOfWeek>): Pair<Boolean, Boolean> =
        (DayOfWeek.SATURDAY in days) to (DayOfWeek.SUNDAY in days)
}

/**
 * The day as people write it: "Monday" … "Sunday".
 *
 * `DayOfWeek.name` is the enum constant (`MONDAY`), which is a code, not a
 * label. The screen shows the label.
 */
fun DayOfWeek.displayName(): String = when (this) {
    DayOfWeek.MONDAY -> "Monday"
    DayOfWeek.TUESDAY -> "Tuesday"
    DayOfWeek.WEDNESDAY -> "Wednesday"
    DayOfWeek.THURSDAY -> "Thursday"
    DayOfWeek.FRIDAY -> "Friday"
    DayOfWeek.SATURDAY -> "Saturday"
    DayOfWeek.SUNDAY -> "Sunday"
}

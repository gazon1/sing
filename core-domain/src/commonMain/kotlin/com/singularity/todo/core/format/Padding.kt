package com.singularity.todo.core.format

/**
 * An integer in the app's own digits: `7` → `"07"`, `2026` → `"2026"`.
 *
 * `"%02d".format(x)` is the same trap as `"%.1f"`: it follows the device
 * locale, and on an Arabic phone it writes "١٤" where the app means "14".
 * The digits are the app's own, so they are padded by hand.
 *
 * Its own leaf package because two layers need it and neither may depend on
 * the other: the data layer writes times into the database in this format and
 * the UI shows them. A data package must not import a UI package, so the
 * helper sits below both.
 */
fun formatPadded(
    value: Int,
    digits: Int = 2,
): String = value.toString().padStart(digits, '0')

/** [formatPadded] for a long — an elapsed clock counts in seconds, and they are long. */
fun formatPadded(
    value: Long,
    digits: Int = 2,
): String = value.toString().padStart(digits, '0')

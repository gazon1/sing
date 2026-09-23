package com.singularity.todo.feature.calendar_sync.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Helper to check and request calendar READ + WRITE permissions on Android.
 *
 * The actual permission request is triggered from an Activity via
 * [requestPermissions][android.app.Activity.requestPermissions].
 * This class only provides [hasPermissions] to check the current state.
 *
 * Usage in a Composable:
 * ```kotlin
 * val launcher = rememberLauncherForActivityResult(
 *     ActivityResultContracts.RequestMultiplePermissions()
 * ) { result ->
 *     val granted = result.values.all { it }
 *     viewModel.processIntent(CalendarSyncIntent.SetPermission(granted))
 * }
 * // on button click:
 * launcher.launch(arrayOf(READ_CALENDAR, WRITE_CALENDAR))
 * ```
 */
object CalendarPermissionRequester {

    const val READ_CALENDAR = Manifest.permission.READ_CALENDAR
    const val WRITE_CALENDAR = Manifest.permission.WRITE_CALENDAR

    /**
     * True if both READ_CALENDAR and WRITE_CALENDAR are granted.
     */
    fun hasPermissions(context: Context): Boolean {
        val read = ContextCompat.checkSelfPermission(context, READ_CALENDAR)
        val write = ContextCompat.checkSelfPermission(context, WRITE_CALENDAR)
        return read == PackageManager.PERMISSION_GRANTED &&
            write == PackageManager.PERMISSION_GRANTED
    }
}

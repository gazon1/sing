package com.singularity.todo.feature.calendar_sync.permission

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/**
 * Contracts for calendar permission strings used across the permission flow.
 */
object CalendarPermissionContracts {
    const val READ_CALENDAR = Manifest.permission.READ_CALENDAR
    const val WRITE_CALENDAR = Manifest.permission.WRITE_CALENDAR
    val PERMISSIONS = arrayOf(READ_CALENDAR, WRITE_CALENDAR)
}

@Composable
actual fun rememberCalendarPermissionRequester(): CalendarPermissionRequester {
    val context = androidx.compose.ui.platform.LocalContext.current

    var hasPermissions by remember {
        mutableStateOf(
            CalendarPermissionContracts.PERMISSIONS.all { perm ->
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            },
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasPermissions = result.values.all { it }
    }

    return object : CalendarPermissionRequester {
        override val hasPermissions: Boolean get() = hasPermissions
        override fun requestPermissions() {
            launcher.launch(CalendarPermissionContracts.PERMISSIONS)
        }
    }
}

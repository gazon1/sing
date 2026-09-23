package com.singularity.todo.core.log

import android.os.Build

actual fun osDescription(): String {
    return "Android API ${Build.VERSION.SDK_INT} (SDK ${Build.VERSION.RELEASE})"
}

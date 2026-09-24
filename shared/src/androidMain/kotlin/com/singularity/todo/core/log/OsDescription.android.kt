package com.singularity.todo.core.log

import android.os.Build

actual fun osDescription(): String = "Android API ${Build.VERSION.SDK_INT} (SDK ${Build.VERSION.RELEASE})"

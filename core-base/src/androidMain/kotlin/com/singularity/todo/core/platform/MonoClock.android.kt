package com.singularity.todo.core.platform

import android.os.SystemClock

actual val monoClockMillis: Long
    get() = SystemClock.elapsedRealtime()

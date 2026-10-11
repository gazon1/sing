package com.singularity.todo.core.platform

actual val monoClockMillis: Long
    get() = System.nanoTime() / 1_000_000

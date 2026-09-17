package com.singularity.todo.core.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

actual fun createBackgroundScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)

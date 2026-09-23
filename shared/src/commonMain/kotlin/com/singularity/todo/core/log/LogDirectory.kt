package com.singularity.todo.core.log

import okio.Path

/**
 * Returns the platform-specific directory where log files are stored.
 * Called by [FileLogWriter] at initialization time.
 */
expect fun logDirectory(): Path

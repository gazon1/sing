package com.singularity.todo.core.log

import co.touchlab.kermit.Logger

/**
 * Holder for ad-hoc logger usage where DI injection is not suitable —
 * root composables, top-level functions, or one-off helpers.
 *
 * For feature classes (ViewModels, repositories, use cases) prefer constructor
 * injection of `Logger` via Koin's `kermitLoggerModule()` and `getWith("ClassName")`.
 */
class LoggerHolder(val log: Logger)

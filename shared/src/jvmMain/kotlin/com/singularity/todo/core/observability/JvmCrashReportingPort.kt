package com.singularity.todo.core.observability

/**
 * JVM [CrashReportingPort] — deliberately inert.
 *
 * AppTracer ships Android-only, and the desktop app keeps its existing Kermit
 * file log as its diagnostic sink; adding a second one is out of scope. The
 * binding still has to exist, for the same reason `Haptic`'s does: a definition
 * that is missing surfaces as `NoDefinitionFoundException` thrown *inside*
 * composition, which Compose retries every frame — an endless redraw loop that
 * looks like a hang rather than an error.
 *
 * A distinct type rather than a direct `NoOpCrashReportingPort()` binding so
 * this file is not dead code, and so the reason for the no-op is recorded next
 * to the definition instead of in a comment on a shared class.
 */
class JvmCrashReportingPort : CrashReportingPort by NoOpCrashReportingPort()

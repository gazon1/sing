package com.singularity.todo.core.log

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity

/**
 * A [LogWriter] decorator that redacts credential-shaped substrings from log messages
 * and throwable stack traces before delegating to the wrapped writer.
 *
 * Redacted patterns:
 * - JWT tokens (`eyJ…`)
 * - Supabase `anonKey` and `supabaseUrl` values
 * - `sk-` API keys
 * - Bearer authorization tokens
 * - Email addresses
 *
 * Replacement value: `[redacted]`
 *
 * This writer must be instantiated **per sink**, not shared — one per console writer
 * and one per file writer.
 */
class RedactingLogWriter(private val delegate: LogWriter) : LogWriter() {

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        delegate.log(severity, redact(message), tag, throwable?.let(::redactThrowable))
    }

    /**
     * A redacted **copy** of [original]: redacted message, redacted cause chain, and the
     * original's stack trace.
     *
     * ## Why the stack trace is copied rather than rebuilt
     *
     * The previous implementation wrapped the throwable in an anonymous
     * `object : Throwable(...)` constructed *here*. Kotlin captures the stack at
     * construction, so every throwable reaching the log file recorded
     * `RedactingLogWriter.log` as its origin — the redaction frame, never the frame that
     * actually failed. A shared log bundle was therefore useless for stack traces, which is
     * the main reason a user sends one. `Throwable.setStackTrace` moves the real frames
     * across, so the copy is faithful where it matters.
     *
     * The throwable's concrete *class* is still not reproduced — reconstructing an
     * arbitrary subclass instance is not possible without a factory. Its name survives via
     * [RedactedThrowable.toString], which is what `stackTraceToString()` prints, and the
     * frames below it are now the genuine ones.
     *
     * ## Why the cause chain is walked
     *
     * The original expression passed `original.cause` through untouched, so a credential
     * nested in a cause's message reached both Logcat and the log file in the clear. The
     * whole chain is now redacted, with a seen-set to make a self-referential cause
     * (`cause === this`) terminate instead of recursing forever.
     */
    private fun redactThrowable(original: Throwable): Throwable {
        val seen = mutableSetOf<Throwable>()
        fun rebuild(source: Throwable): Throwable {
            // A Throwable does not override equals/hashCode, so this set is identity-based.
            if (!seen.add(source)) return RedactedThrowable(source.javaClass.name, null, null)
            val redactedCause = source.cause
                ?.takeIf { it !== source }
                ?.let(::rebuild)
            return RedactedThrowable(
                originalName = source.javaClass.name,
                redactedMessage = source.message?.let(::redact),
                cause = redactedCause,
            ).apply { stackTrace = source.stackTrace }
        }
        return rebuild(original)
    }

    private fun redact(text: String): String {
        var result = text
        for ((pattern, replacement) in REDACTION_PATTERNS) {
            result = pattern.replace(result, replacement)
        }
        return result
    }

    /**
     * Carries the original's class name and redacted text while staying a plain
     * [Throwable], so nothing downstream can accidentally introspect the pre-redaction
     * instance.
     */
    private class RedactedThrowable(
        private val originalName: String,
        private val redactedMessage: String?,
        cause: Throwable?,
    ) : Throwable(redactedMessage, cause) {
        override fun toString(): String =
            if (redactedMessage == null) originalName else "$originalName: $redactedMessage"
    }

    private companion object {
        /**
         * Order is significant: contextual patterns run before the generic [JWT] one, because a
         * JWT-shaped token also matches the generic pattern. Redacting the token first would
         * leave the surrounding context (`Bearer [JWT]`, `anonKey=[JWT]`) with the credential
         * *label* intact and the specific placeholder never applied.
         */
        private val REDACTION_PATTERNS = listOf(
            // Supabase URL with credentials embedded (no other pattern can match it first)
            Regex("""https://[^@\s]+:[^@\s]+@[^\s"',;]+""") to "[supabase url=redacted]",

            // Bearer token in Authorization header value (JWTs contain periods)
            Regex("""(?i)bearer\s+[A-Za-z0-9_\-.]{10,}""") to "[bearer token]",

            // Supabase anonKey / service_role key value (JWT format, contains periods)
            Regex("""(?i)(anonKey|service_role)\s*[=:]\s*"?[A-Za-z0-9_\-.]{10,}""") to "[$1=redacted]",

            // Generic JWT tokens: eyJ...base64url... — must stay after every contextual pattern
            Regex("""eyJ[A-Za-z0-9_-]{3,}\.[A-Za-z0-9_-]{3,}\.[A-Za-z0-9_-]{3,}""") to "[JWT]",

            // sk- API keys (OpenAI, Anthropic, etc.)
            Regex("""(?i)sk-[A-Za-z0-9]{16,}""") to "[sk-redacted]",

            // Email addresses — must stay last, it would otherwise eat `user:pass@host`
            Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""") to "[email]",
        )
    }
}

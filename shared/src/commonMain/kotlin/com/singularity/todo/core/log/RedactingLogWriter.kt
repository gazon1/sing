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
        val redactedMessage = redact(message)
        val redactedThrowable = throwable?.let { original ->
            object : Throwable(redact(original.message ?: ""), original.cause) {
                override fun toString(): String = "${original.javaClass.name}: ${redact(original.message ?: "")}"
            }
        }
        delegate.log(severity, redactedMessage, tag, redactedThrowable)
    }

    private fun redact(text: String): String {
        var result = text
        for ((pattern, replacement) in REDACTION_PATTERNS) {
            result = pattern.replace(result, replacement)
        }
        return result
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

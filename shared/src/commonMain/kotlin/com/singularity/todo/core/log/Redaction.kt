package com.singularity.todo.core.log

/**
 * Redaction helpers for use in log messages.
 *
 * These functions are safe to call on any string value before including it in a
 * log call — they replace the meaningful content with a fixed placeholder so the
 * log remains readable while no user content is exposed.
 */
object Redaction {
    /**
     * Redacts an email address as `us***@dom***`.
     * The domain is preserved for grouping/filtering purposes in logs.
     */
    fun redactEmail(email: String): String {
        val at = email.indexOf('@')
        if (at < 1) return "[email]"
        val local = email.substring(0, at)
        val domain = email.substring(at + 1)
        val domDot = domain.indexOf('.')
        val domPrefix = if (domDot > 0) domain.substring(0, domDot) else domain
        return "${local.take(2)}***@${domPrefix.take(3)}***"
    }
}

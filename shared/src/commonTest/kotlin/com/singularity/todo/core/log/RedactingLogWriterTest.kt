package com.singularity.todo.core.log

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeLogWriter : LogWriter() {
    val messages = mutableListOf<String>()
    val throwables = mutableListOf<Throwable?>()
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        messages.add(message)
        throwables.add(throwable)
    }
}

/** A structurally valid JWT: three base64url segments, the first starting with `eyJ`. */
private const val JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4ifQ" +
    ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"

private const val SK_KEY = "sk-1234567890abcdefghijklmnopqrstuvwxyz"

@Tag("fast")
class RedactingLogWriterTest {

    /** Writes [message] through the real decorator and returns what the delegate actually saw. */
    private fun redact(message: String, throwable: Throwable? = null): String {
        val fake = FakeLogWriter()
        RedactingLogWriter(fake).log(Severity.Info, message, "TestTag", throwable)
        return fake.messages.single()
    }

    private fun delegatedThrowable(throwable: Throwable): Throwable {
        val fake = FakeLogWriter()
        RedactingLogWriter(fake).log(Severity.Error, "msg", "TestTag", throwable)
        return fake.throwables.single()!!
    }

    companion object {
        @JvmStatic
        fun messageCases(): List<Arguments> = listOf(
            Arguments.of("User authenticated with $JWT", listOf("eyJ"), listOf("[JWT]")),
            Arguments.of("Sending to user@example.com", listOf("user@example.com"), listOf("[email]")),
            Arguments.of("API key $SK_KEY", listOf("sk-12345"), listOf("[sk-redacted]")),
            Arguments.of("Authorization: Bearer $JWT", listOf("Bearer"), listOf("[bearer token]")),
            Arguments.of("Supabase init: anonKey=$JWT", listOf(JWT), listOf("[anonKey=redacted]")),
            Arguments.of("Bootstrapping with service_role=$JWT", listOf(JWT), listOf("[service_role=redacted]")),
            Arguments.of(
                "Connecting to https://user:s3cr3t@abc123.supabase.co/rest/v1/tasks",
                listOf("s3cr3t"),
                listOf("[supabase url=redacted]"),
            ),
            // Safe content is left untouched.
            Arguments.of(
                "Task created with title 'Buy milk' assigned to user-123",
                emptyList<String>(),
                listOf("Buy milk", "user-123", "Task created"),
            ),
            // Several credentials in one message are all redacted.
            Arguments.of(
                "Login for john@example.com using $SK_KEY and JWT $JWT",
                listOf("john@example.com", "sk-12345", "eyJ"),
                listOf("[email]", "[sk-redacted]", "[JWT]"),
            ),
        )

        @JvmStatic
        fun throwableCases(): List<Arguments> = listOf(
            Arguments.of(RuntimeException("Failed for user@secret.com"), listOf("user@secret.com"), listOf("[email]")),
            Arguments.of(IllegalStateException("key $SK_KEY rejected"), listOf("sk-12345"), listOf("[sk-redacted]")),
            // A credential nested in the cause chain.
            Arguments.of(
                IllegalStateException("upload failed", IllegalArgumentException("rejected for $EMAIL")),
                listOf(EMAIL),
                listOf("[email]"),
            ),
            // A self-referential cause must not make the redaction recurse forever.
            Arguments.of(SelfCausedException("cycle for $EMAIL"), listOf(EMAIL), listOf("[email]")),
        )
    }

    @ParameterizedTest
    @MethodSource("messageCases")
    fun `redacts credentials in the message and preserves safe content`(
        message: String,
        forbidden: List<String>,
        markers: List<String>,
    ) {
        val result = redact(message)
        forbidden.forEach { assertFalse(result.contains(it), "'$it' should be redacted in: $result") }
        markers.forEach { assertContains(result, it) }
    }

    @ParameterizedTest
    @MethodSource("throwableCases")
    fun `redacts a credential in the throwable and its cause chain`(
        throwable: Throwable,
        forbidden: List<String>,
        markers: List<String>,
    ) {
        val delegated = delegatedThrowable(throwable)
        val rendered = generateSequence(delegated as Throwable?) { it.cause }
            .joinToString(" | ") { it.toString() }
        forbidden.forEach { assertFalse(rendered.contains(it), "'$it' leaked in: $rendered") }
        markers.forEach { assertContains(rendered, it) }
    }

    @Test
    fun `preserves the originating stack trace`() {
        val thrown = originatingIllegalStateException()
        val frames = delegatedThrowable(thrown).stackTrace.map { "${it.className}.${it.methodName}" }
        assertTrue(
            frames.any { it.endsWith("originatingIllegalStateException") },
            "Delegated throwable must carry the frames of the throwing site, " +
                "not the redaction site. Got: $frames",
        )
    }

    @Test
    fun `preserves the original class name in the rendered throwable`() {
        assertContains(delegatedThrowable(SelfCausedException("plain")).toString(), "SelfCausedException")
    }
}

/** Thrown from a named function so the test can assert that frame survives redaction. */
private fun originatingIllegalStateException() = IllegalStateException("thrown here")

private const val EMAIL = "leaked@secret.com"

/** A throwable whose cause is itself — the terminating case for cause-chain redaction. */
private class SelfCausedException(message: String) : RuntimeException(message) {
    override val cause: Throwable
        get() = this
}

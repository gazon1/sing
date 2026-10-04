package com.singularity.todo.core.log

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import org.junit.jupiter.api.Tag
import kotlin.test.Test
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

    @Test
    fun `redacts JWT tokens from message`() {
        val result = redact("User authenticated with $JWT")
        assertFalse(result.contains("eyJ"), "JWT should be redacted")
        assertContains(result, "[JWT]")
    }

    @Test
    fun `redacts email addresses from message`() {
        val result = redact("Sending to user@example.com")
        assertFalse(result.contains("user@example.com"), "Email should be redacted")
        assertContains(result, "[email]")
    }

    @Test
    fun `redacts sk-API keys from message`() {
        val result = redact("API key $SK_KEY")
        assertFalse(result.contains("sk-12345"), "API key should be redacted")
        assertContains(result, "[sk-redacted]")
    }

    @Test
    fun `redacts bearer tokens from message`() {
        val result = redact("Authorization: Bearer $JWT")
        assertFalse(result.contains("Bearer"), "Bearer token should be redacted")
        assertContains(result, "[bearer token]")
    }

    @Test
    fun `redacts Supabase anonKey from message`() {
        val result = redact("Supabase init: anonKey=$JWT")
        assertFalse(result.contains(JWT), "anonKey value should be redacted")
        assertContains(result, "[anonKey=redacted]")
    }

    @Test
    fun `redacts Supabase service_role from message`() {
        val result = redact("Bootstrapping with service_role=$JWT")
        assertFalse(result.contains(JWT), "service_role value should be redacted")
        assertContains(result, "[service_role=redacted]")
    }

    @Test
    fun `redacts credentials embedded in a Supabase URL`() {
        val result = redact("Connecting to https://user:s3cr3t@abc123.supabase.co/rest/v1/tasks")
        assertFalse(result.contains("s3cr3t"), "Embedded password should be redacted")
        assertContains(result, "[supabase url=redacted]")
    }

    @Test
    fun `preserves safe content`() {
        val result = redact("Task created with title 'Buy milk' assigned to user-123")
        assertContains(result, "Buy milk")
        assertContains(result, "user-123")
        assertContains(result, "Task created")
    }

    @Test
    fun `redacts multiple credentials in same message`() {
        val result = redact("Login for john@example.com using $SK_KEY and JWT $JWT")
        assertFalse(result.contains("john@example.com"), "Email redacted")
        assertFalse(result.contains("sk-12345"), "API key redacted")
        assertFalse(result.contains("eyJ"), "JWT redacted")
        assertContains(result, "[email]")
        assertContains(result, "[sk-redacted]")
        assertContains(result, "[JWT]")
    }

    @Test
    fun `redacts throwable message`() {
        val fake = FakeLogWriter()
        val cause = RuntimeException("Failed for user@secret.com")
        RedactingLogWriter(fake).log(Severity.Error, "Request failed", "TestTag", cause)
        val delegated = fake.throwables.single()
        assertTrue(delegated != null, "Throwable should be delegated")
        assertFalse(delegated.toString().contains("user@secret.com"), "Email redacted in throwable toString")
    }

    @Test
    fun `redacts throwable message even when log message is clean`() {
        val fake = FakeLogWriter()
        val cause = IllegalStateException("key $SK_KEY rejected")
        RedactingLogWriter(fake).log(Severity.Error, "auth rejected", "TestTag", cause)
        assertContains(fake.throwables.single().toString(), "[sk-redacted]")
    }

    @Test
    fun `preserves the originating stack trace`() {
        val fake = FakeLogWriter()
        val thrown = originatingIllegalStateException()
        RedactingLogWriter(fake).log(Severity.Error, "boom", "TestTag", thrown)

        val frames = fake.throwables.single()!!.stackTrace.map { "${it.className}.${it.methodName}" }
        assertTrue(
            frames.any { it.endsWith("originatingIllegalStateException") },
            "Delegated throwable must carry the frames of the throwing site, " +
                "not the redaction site. Got: $frames",
        )
    }

    @Test
    fun `redacts a credential nested in the cause chain`() {
        val fake = FakeLogWriter()
        val root = IllegalArgumentException("rejected for $EMAIL")
        val wrapper = IllegalStateException("upload failed", root)
        RedactingLogWriter(fake).log(Severity.Error, "upload failed", "TestTag", wrapper)

        val delegated = fake.throwables.single()!!
        val rendered = generateSequence(delegated as Throwable?) { it.cause }
            .joinToString(" | ") { it.toString() }
        assertFalse(rendered.contains(EMAIL), "Cause-chain email leaked: $rendered")
        assertContains(rendered, "[email]")
    }

    @Test
    fun `redacts a self-referential cause without recursing forever`() {
        val fake = FakeLogWriter()
        val looping = SelfCausedException("cycle for $EMAIL")
        RedactingLogWriter(fake).log(Severity.Error, "loop", "TestTag", looping)

        val rendered = generateSequence(fake.throwables.single() as Throwable?) { it.cause }
            .joinToString(" | ") { it.toString() }
        assertFalse(rendered.contains(EMAIL), "Cause-chain email leaked: $rendered")
    }

    @Test
    fun `preserves the original class name in the rendered throwable`() {
        val fake = FakeLogWriter()
        RedactingLogWriter(fake).log(Severity.Error, "boom", "TestTag", SelfCausedException("plain"))
        val rendered = fake.throwables.single()!!.toString()
        assertContains(rendered, "SelfCausedException")
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

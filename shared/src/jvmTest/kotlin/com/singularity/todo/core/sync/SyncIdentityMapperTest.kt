package com.singularity.todo.core.sync

import com.singularity.todo.core.error.AppError
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The boundary between a stored composite identity and a sync scope.
 *
 * ## What the refusal is protecting
 *
 * The tempting fallback for an unparseable value is `"default"` for the profile,
 * because that is what a fresh install calls its first profile. Accepting it would
 * attribute a row to an account and profile the user has never had, and read it
 * back to a place where no such row exists — a row that is invisible in one
 * direction and orphaned in the other, with nothing to point at the moment it
 * happened. A refusal is loud, happens once, and names the value that was wrong.
 */
@Tag("fast")
class SyncIdentityMapperTest {

    private val mapper = SyncIdentityMapper()
    private val scope = SyncScope(ownerId = "owner-1", profileId = "work")

    @Test
    fun `an identity round-trips through a scope`() {
        val encoded = mapper.encode(scope)

        assertEquals("owner-1/work", encoded.encoded)
        assertEquals(scope, mapper.toScope(encoded.encoded))
    }

    @Test
    fun `a uuid owner and a ulid profile both survive`() {
        // The shapes that actually occur: Supabase issues uuid owners, ProfileId is
        // a ULID or the literal "default". A parser that assumed otherwise would
        // work in a test and fail on a real account.
        val real = SyncScope(ownerId = "9f8e7d6c-5b4a-3210-9e8f-7d6c5b4a3210", profileId = "01HZX9K3M7Q")

        assertEquals(real, mapper.toScope(mapper.encode(real).encoded))
    }

    @Test
    fun `an unparseable value is an error, not a default`() {
        val error = assertFailsWith<AppError.Validation> { mapper.toScope("owner-1") }

        assertEquals("sync.malformed_identity", error.code)
        assertTrue("owner-1" in error.message.orEmpty(), "the message should name the value")
    }

    @Test
    fun `an empty half is refused`() {
        // "owner-1/" parses into two parts under a naive split and yields a blank
        // profile, which is the shape that silently routes rows to a scope with no
        // profile in it.
        assertFailsWith<AppError.Validation> { mapper.toScope("owner-1/") }
        assertFailsWith<AppError.Validation> { mapper.toScope("/work") }
    }

    @Test
    fun `a blank value is refused`() {
        assertFailsWith<AppError.Validation> { mapper.toScope("") }
    }

    @Test
    fun `more than two halves is refused`() {
        assertFailsWith<AppError.Validation> { mapper.toScope("owner-1/work/extra") }
    }

    @Test
    fun `a slash inside a value is refused rather than silently split`() {
        // The separator is a fixed character and neither half may contain one. A
        // parser that split on the last separator instead would read this as owner
        // "owner-1/work" and profile "extra" — two values, neither of which exists.
        assertFailsWith<AppError.Validation> { mapper.toScope("owner-1/work/extra") }
    }
}

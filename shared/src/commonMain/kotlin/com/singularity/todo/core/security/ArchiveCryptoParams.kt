package com.singularity.todo.core.security

/**
 * The numbers the archive's cryptography is made of — in one place.
 *
 * ## Why they live here and not in the two implementations
 *
 * The archive has two implementations — `JvmArchiveCrypto` and
 * `AndroidArchiveCrypto` — because `javax.crypto` is not available in
 * `commonMain`. They were written as copies of each other, and a copy is a
 * number waiting to drift: one of them gets its iteration count changed, and
 * every archive written by one platform stops opening on the other.
 *
 * The archive is the one file that holds everything, so a drift between the two
 * halves is not a defect in one archive — it is a feature that works on the
 * desktop and silently fails on the phone, with the message "the password does
 * not open it", which sends the person looking for the wrong thing.
 *
 * So the parameters are declared once, here, and both implementations read
 * them. The golden test that goes with them checks that the two platforms
 * still produce the same bytes for the same input — a drift now fails a test
 * instead of a person's backup.
 *
 * ## Why these numbers
 *
 * - **PBKDF2-HMAC-SHA256, 210 000 iterations** — a password is short and
 *   guessable, and the cost of guessing it is the point. The count is a
 *   compromise between a phone that has to do this in a second and a password
 *   that a stolen archive survives;
 * - **256-bit key** — AES-256, the size GCM is designed around;
 * - **128-bit tag** — the GCM authentication tag's full length;
 * - **16-byte salt** — what PBKDF2 expects, and enough that two archives under
 *   one password do not share a key;
 * - **12-byte nonce** — what GCM expects, and fresh for every archive.
 *
 * These are the numbers the decision record names; changing one of them is a
 * decision, and the decision record is where it belongs.
 */
object ArchiveCryptoParams {

    /** The cipher: AES in Galois/Counter Mode, without padding. */
    const val TRANSFORM = "AES/GCM/NoPadding"

    /** The key derivation: PBKDF2 with HMAC-SHA256. */
    const val KDF = "PBKDF2WithHmacSHA256"

    /** How many times the derivation is repeated. */
    const val ITERATIONS = 210_000

    /** The derived key's length, in bits. */
    const val KEY_BITS = 256

    /** The GCM authentication tag's length, in bits. */
    const val TAG_BITS = 128

    /** The salt's length, in bytes: what PBKDF2 expects. */
    const val SALT_BYTES = 16

    /** The GCM nonce's length, in bytes: what GCM expects. */
    const val NONCE_BYTES = 12
}

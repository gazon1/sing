package com.singularity.todo.core.security

import org.junit.jupiter.api.Tag

/**
 * The desktop's cryptography, against the bytes the phone has to agree with.
 *
 * The four cases are [ArchiveCryptoGoldenContract]'s, because they are the same
 * four cases the phone answers: the archive is written on one platform and read
 * on the other, and only a question asked of both can say the two agree. All
 * this class adds is the desktop's `ArchiveCrypto` and the tag the JVM lane
 * selects on.
 *
 * Between this class and `ArchiveCryptoGoldenAndroidTest` the answer to the
 * archive's whole design rests on is kept: does an archive written here open
 * there? Without them the only evidence is a person's backup failing to
 * restore, with a message that points at the password.
 */
@Tag("fast")
class ArchiveCryptoGoldenJvmTest : ArchiveCryptoGoldenContract() {
    override val crypto: ArchiveCrypto = JvmArchiveCrypto()
}

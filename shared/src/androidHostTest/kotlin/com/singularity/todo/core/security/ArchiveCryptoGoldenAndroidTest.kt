package com.singularity.todo.core.security

/**
 * The phone's cryptography, against the bytes the desktop produced.
 *
 * The four cases are [ArchiveCryptoGoldenContract]'s — they used to be a copy of
 * the desktop's test, and a copy of a test is a copy of a rule. All this class
 * adds is the phone's `ArchiveCrypto`.
 *
 * ## What this is the only local test for
 *
 * `AndroidArchiveCrypto` had no test at all. It is a copy of
 * `JvmArchiveCrypto`, and a copy is a number waiting to drift: an archive
 * written by the desktop would stop opening on the phone, and the message the
 * app would give is "the password does not open it" — which sends the person
 * looking for a wrong password instead of a changed constant.
 *
 * Nothing else in the project could have caught that. The container is proved
 * in `commonTest` with a faked cryptography, and the desktop's real cipher is
 * proved in `jvmTest`; the phone's half was the one with no witness. This
 * class is that witness: it opens the archive the desktop's JDK produced, and
 * it seals the same bytes itself.
 *
 * ## Why this class carries no `@Tag`
 *
 * `testAndroidHostTest` is exempt from the tag filter, because the classes that
 * forced the exemption are JUnit4 — Robolectric is a JUnit4 runner, discovered
 * through the Vintage engine, and JUnit4 has no `@Tag` at all. The exemption
 * covers this class too, so it runs when the whole `androidHostTest` source set
 * runs, through the narrow include list in `shared/build.gradle.kts`. The
 * contract it extends is in `commonTest`, which this source set compiles
 * against; the `fast` tag lives on the JVM subclass for the same reason.
 *
 * ## Why no Robolectric runner
 *
 * Nothing here touches the Android framework: the cryptography is
 * `javax.crypto`, which the JVM provides. The class is in `androidHostTest`
 * only because `AndroidArchiveCrypto` lives in `androidMain`, and that is
 * where the Android half of the archive is.
 *
 * ## Why this class is named in the build
 *
 * `shared/build.gradle.kts` narrows `testAndroidHostTest` to a list of class
 * names, because `androidHostTest` inherits all of `commonTest` and would
 * otherwise re-run the whole JVM suite. This class is one of the entries; a
 * rename here without the matching entry leaves the phone's half unwitnessed
 * while the task stays green.
 */
class ArchiveCryptoGoldenAndroidTest : ArchiveCryptoGoldenContract() {
    override val crypto: ArchiveCrypto = AndroidArchiveCrypto()
}

package com.singularity.todo.arch.fixtures

/**
 * The JVM secure-store flag as it was before the java-keyring migration, kept on disk
 * because it is the positive control of the architecture gate that forbids it.
 *
 * ## Why a file that is wrong on purpose is committed
 *
 * The guard reads the JVM security package and fails when a source there asks the host's
 * operating system what it can do. A scan like that reports success when it has stopped
 * matching anything, and a green check over a rule that no longer fires is
 * indistinguishable from a clean package. The only way to prove the rule still fires is to
 * keep one instance of the forbidden shape on disk and require the same scan to name it.
 *
 * ## Two constraints on this file
 *
 * **It must never compile.** It sits in the jvmTest fixtures tree, which is not a Kotlin
 * source path, so the compiler, detekt and the coverage gates never see it. If it ever
 * starts compiling, a tree that exists to be broken has been wired into the build.
 *
 * **Its class name must not end in the test suffix.** Other gates parse this directory and
 * treat a class named that way as a real test class, which would make them answer a
 * question about a fixture.
 *
 * Nothing in this file is live code. It is the shape of a decision, recorded.
 */
@Suppress("unused")
internal class HardwareBackedFlagFixture {
    /**
     * The removed gate: the flag asked the platform as well as the credential store.
     *
     * On the Linux host the suite runs on this answered `true` exactly like the current
     * implementation, which is why a stubbed unit test could never catch it coming back.
     */
    fun isHardwareBacked(): Boolean = IS_LINUX && keyringAvailable()

    private fun keyringAvailable(): Boolean = true

    private val IS_LINUX =
        System.getProperty("os.name", "").lowercase().contains("linux")
}

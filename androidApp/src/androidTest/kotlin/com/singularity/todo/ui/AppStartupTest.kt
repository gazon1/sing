package com.singularity.todo.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SmallTest
import androidx.test.uiautomator.By
import com.singularity.todo.BuildConfig
import com.singularity.todo.MainActivity
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.testing.InstrumentedTestBase
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * The preconditions every UI assertion rests on.
 *
 * ## Why this class exists
 *
 * A UI test that waits for the shell and does not find it has one failure message
 * for two different causes:
 *
 * - the app is asking for a sign-in, and the shell is behind the sign-in screen;
 * - the app is fine and the shell was simply late.
 *
 * The first is a build problem — the APK under test was assembled without the
 * auth bypass — and it was measured on 2026-10-16 and written up in ADR
 * `2026-10-16-android-instrumentation-runs-through-the-emulator-module.md`. The
 * second is a runner problem. A red run that cannot say which of the two it was
 * is a run that has to be re-read by hand, and the reading is what this class
 * makes unnecessary: it asserts the preconditions by name, so a failure says
 * "the bypass did not take effect" instead of "the shell was not visible".
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AppStartupTest : InstrumentedTestBase() {

    /**
     * The APK under test must be assembled with the auth bypass.
     *
     * Without it the app installs the real auth repository, opens on the sign-in
     * screen, and every assertion about the shell is made against a screen in
     * front of the one it describes. The workflow passes
     * `-PTEST_AUTH_BYPASS=true` for exactly this reason; this test is what says
     * whether it arrived.
     */
    @Test
    @SmallTest
    fun the_build_under_test_carries_the_auth_bypass() {
        assertTrue(
            "The APK under test was assembled without -PTEST_AUTH_BYPASS=true, so the app " +
                "opens on the sign-in screen and no shell assertion can reach the shell. " +
                "Check that the workflow passes GRADLE_ARGS=-PTEST_AUTH_BYPASS=true to both " +
                "the build and the test task.",
            BuildConfig.TEST_AUTH_BYPASS,
        )
    }

    /**
     * The app must not be asking for a sign-in.
     *
     * The session is read from the app's own Koin graph — the instrumentation runs
     * in the app's process, so this is the same state the UI renders from. A
     * signed-out session is the sign-in screen, and it is a different failure
     * from a slow shell.
     */
    @Test
    fun the_app_is_not_asking_for_a_sign_in() {
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                MainActivity::class.java,
            )
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        try {
            device.awaitShell()
            val session = currentSession()
            assertNotEquals(
                "The app is on the sign-in screen, so the shell was never reachable. " +
                    "The auth bypass did not take effect in this build.",
                Session.SignedOut,
                session,
            )
        } finally {
            scenario.close()
        }
    }

    /**
     * The shell's own affordances must be addressable by the ids the tests use.
     *
     * `testTagsAsResourceId` is what turns a Compose test tag into an
     * accessibility resource id, and it is set on the version gate's modifier.
     * If that seam breaks, the shell is on screen and still invisible to
     * UiAutomator — a failure that reads exactly like "the shell was not there".
     */
    @Test
    fun the_shell_is_addressable_by_test_tag() {
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                MainActivity::class.java,
            )
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        try {
            device.awaitShell()
            assertNotEquals(
                "The shell is on screen but no node carries a resource id, so the test tags " +
                    "are not reaching the accessibility tree. " +
                    "Check testTagsAsResourceId on the app's root modifier.",
                null,
                device.findObject(By.res(TODAY_TAB)),
            )
        } finally {
            scenario.close()
        }
    }

    /** The session the app is in, read from the app's own Koin graph. */
    private fun currentSession(): Session =
        runCatching {
            GlobalContext.getOrNull()?.get<AuthRepository>()?.currentSession?.value
        }.getOrNull() ?: Session.Loading
}

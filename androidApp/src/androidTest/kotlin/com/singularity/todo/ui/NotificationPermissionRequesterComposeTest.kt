package com.singularity.todo.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import com.singularity.todo.core.notifications.rememberNotificationPermissionRequester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The composable wiring of the notification permission requester, on a device.
 *
 * `NotificationPermissionRequesterTest` (androidHostTest) drives the observer through
 * a real [LifecycleRegistry], but it registers the observer itself: it cannot notice a
 * `DisposableEffect` deleted from `rememberNotificationPermissionRequester`. This test
 * mounts the production composable and asserts the three things only the composition
 * can show:
 *
 * - a resume of the owner the composable sees re-reads the grant and reaches the
 *   rendered state;
 * - disposing the composable removes the observer, so the owner it left behind cannot
 *   write into a screen that is gone;
 * - swapping the [LocalLifecycleOwner] moves the observer from the old owner to the new
 *   one, which is what `DisposableEffect(lifecycleOwner, context)` is for.
 *
 * The platform grant is not touched. `POST_NOTIFICATIONS` is read through a
 * [ContextWrapper] whose `checkPermission` answers from a variable, because a real
 * revocation kills the process (see the ADR), which would take the test with it. So the
 * subject here is the wiring, not the OS: the observer and the platform read it drives
 * are what `NotificationPermissionRequesterTest` covers.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class NotificationPermissionRequesterComposeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * The host activity's context, not the application's: `rememberLauncherForActivityResult`
     * finds its registry owner by walking the context chain, so the wrapper has to sit in
     * front of the activity rather than replace it.
     */
    private val context: Context get() = compose.activity

    @Test
    fun a_resume_rereads_the_grant_through_the_registered_observer() {
        val permissions = FakePermissionContext(context)
        val owner = resumedOwner()

        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides permissions,
                LocalLifecycleOwner provides owner,
            ) {
                PermissionProbe()
            }
        }

        compose.onNodeWithTag(PROBE).assertTextEquals("denied")
        val readsAtMount = permissions.reads

        compose.runOnIdle {
            permissions.granted = true
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }

        compose.onNodeWithTag(PROBE).assertTextEquals("granted")
        assertTrue(
            "the resume did not re-read the permission through the observer",
            permissions.reads > readsAtMount,
        )
    }

    @Test
    fun leaving_the_composition_removes_the_observer() {
        val permissions = FakePermissionContext(context)
        val owner = resumedOwner()
        var mounted by mutableStateOf(true)

        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides permissions,
                LocalLifecycleOwner provides owner,
            ) {
                if (mounted) {
                    PermissionProbe()
                } else {
                    BasicText("unwired")
                }
            }
        }

        assertEquals("the mounted composable did not register its observer", 1, observerCount(owner))

        compose.runOnIdle { mounted = false }
        compose.waitForIdle()
        assertEquals("the disposed composable kept its observer", 0, observerCount(owner))

        val readsBeforeResume = permissions.reads
        compose.runOnIdle {
            permissions.granted = true
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()

        assertEquals(
            "a disposed composable was still re-reading the permission",
            readsBeforeResume,
            permissions.reads,
        )
    }

    @Test
    fun swapping_the_lifecycle_owner_moves_the_observer() {
        val permissions = FakePermissionContext(context)
        val first = resumedOwner()
        val second = resumedOwner()
        var owner by mutableStateOf<TestOwner>(first)

        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides permissions,
                LocalLifecycleOwner provides owner,
            ) {
                PermissionProbe()
            }
        }

        compose.onNodeWithTag(PROBE).assertTextEquals("denied")
        assertEquals("the first owner did not receive the observer", 1, observerCount(first))
        assertEquals("the second owner was registered before it was used", 0, observerCount(second))

        compose.runOnIdle { owner = second }
        compose.waitForIdle()
        assertEquals("the observer stayed on the replaced owner", 0, observerCount(first))
        assertEquals("the observer did not move to the new owner", 1, observerCount(second))

        val readsAfterSwap = permissions.reads
        compose.runOnIdle {
            permissions.granted = true
            first.registry.currentState = Lifecycle.State.CREATED
            first.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()

        assertEquals(
            "the replaced owner still drove the state",
            readsAfterSwap,
            permissions.reads,
        )
        compose.onNodeWithTag(PROBE).assertTextEquals("denied")

        compose.runOnIdle {
            second.registry.currentState = Lifecycle.State.CREATED
            second.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.onNodeWithTag(PROBE).assertTextEquals("granted")
    }

    @Composable
    private fun PermissionProbe() {
        val requester = rememberNotificationPermissionRequester()
        BasicText(
            text = if (requester.hasPermissions) "granted" else "denied",
            modifier = Modifier.testTag(PROBE),
        )
    }

    private fun observerCount(owner: TestOwner): Int {
        var count = -1
        compose.runOnIdle { count = owner.registry.observerCount }
        return count
    }

    /**
     * An owner already at `RESUMED`, as the screen is when the user leaves for Settings.
     *
     * `createUnsafe` because the tests move the registry from the instrumentation thread
     * as well as the main thread, and the safe factory refuses state changes off the main
     * thread.
     */
    private fun resumedOwner(): TestOwner = TestOwner().apply { registry.currentState = Lifecycle.State.RESUMED }

    private class TestOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)

        override val lifecycle: Lifecycle get() = registry
    }

    /**
     * The live grant, answered from a variable.
     *
     * Only `POST_NOTIFICATIONS` is intercepted: anything else the framework asks the
     * context during composition keeps its real answer.
     */
    private class FakePermissionContext(base: Context) : ContextWrapper(base) {
        @Volatile var granted: Boolean = false

        /** How many times the permission was read; written on the composition thread. */
        @Volatile var reads: Int = 0

        override fun checkPermission(
            permission: String,
            pid: Int,
            uid: Int,
        ): Int {
            if (permission != Manifest.permission.POST_NOTIFICATIONS) {
                return super.checkPermission(permission, pid, uid)
            }
            reads++
            return if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
    }

    private companion object {
        const val PROBE = "notification_permission_probe"
    }
}

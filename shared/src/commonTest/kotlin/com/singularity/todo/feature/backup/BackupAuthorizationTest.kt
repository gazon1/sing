package com.singularity.todo.feature.backup

import com.singularity.todo.core.backup.ScheduledAuthorization
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

/**
 * What the screen says after the device was authorized.
 *
 * The three cases are three different situations, and the person is told which
 * one they are in. A screen that said only "this device can now back up on its
 * own" would leave the case where the archive in the cloud still needs the old
 * password looking like the case where it does not.
 */
@Tag("fast")
class BackupAuthorizationTest {

    @Test
    fun `the re-sealed archive says it opens with the new password`() {
        val message = authorizationMessage(ScheduledAuthorization.Repushed("u-1/a.zip"))

        assertTrue(
            message.contains("new password"),
            "the message must say what changed for the archive: $message",
        )
    }

    @Test
    fun `a device with nothing to seal says only that it is authorized`() {
        val message = authorizationMessage(ScheduledAuthorization.Authorized())

        assertTrue(
            message.contains("on its own"),
            "the message must say the device is authorized: $message",
        )
    }

    @Test
    fun `the archive that was not re-sealed says so`() {
        val message = authorizationMessage(ScheduledAuthorization.AuthorizedButNotRepushed("the bucket refused"))

        assertTrue(
            message.contains("old password") || message.contains("password it was made with"),
            "the message must say the old copy still needs its own password: $message",
        )
    }
}

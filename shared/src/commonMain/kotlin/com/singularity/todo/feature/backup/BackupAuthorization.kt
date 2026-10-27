package com.singularity.todo.feature.backup

import com.singularity.todo.core.backup.ScheduledAuthorization

/**
 * What the screen says after the device was authorized.
 *
 * The three cases are three different situations, and the person is told which
 * one they are in: the archive in the cloud opens with the new password, there
 * was no archive to seal again, or the archive in the cloud still asks for the
 * password it was made with. One sentence for all three would leave the last two
 * looking like the first.
 */
fun authorizationMessage(authorization: ScheduledAuthorization): String =
    when (authorization) {
        is ScheduledAuthorization.Repushed ->
            "This device can now back up on its own, and the archive in the cloud opens with your new password"

        is ScheduledAuthorization.Authorized ->
            "This device can now back up on its own"

        is ScheduledAuthorization.AuthorizedButNotRepushed ->
            "This device can now back up on its own, but the archive in the cloud was not updated — " +
                "it still opens with the password it was made with"
    }

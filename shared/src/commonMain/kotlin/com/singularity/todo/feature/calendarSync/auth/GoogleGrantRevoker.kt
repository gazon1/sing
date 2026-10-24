package com.singularity.todo.feature.calendarSync.auth

/**
 * Makes the Google credential this profile holds stop working.
 *
 * ## Why this is a port, and not a method on the authorizer
 *
 * "Disconnect" has to do two things: forget the credential on this device, and
 * make the credential itself stop working. The second one is not local, and where
 * it happens depends on the platform:
 *
 * - on Android the refresh token is on the server, so the grant has to be revoked
 *   there and the row deleted;
 * - on the desktop the refresh token is in the device's own keystore, so it has to
 *   be revoked at Google directly.
 *
 * The platform picks the implementation, the same way it picks the redeemer — the
 * two halves of a credential's life travel together.
 *
 * ## Why forgetting is not enough
 *
 * A device that forgets a token the server still holds leaves a credential that
 * works: the server keeps renewing it, and the person who pressed "disconnect"
 * has a live grant they cannot see and cannot cancel. The revoke is what makes
 * the promise true.
 */
interface GoogleGrantRevoker {

    /**
     * Revokes the credential [userId] holds.
     *
     * @return success when the credential does not work any more — including the
     *   case where there was nothing to revoke, because an account that was never
     *   connected is disconnected.
     */
    suspend fun revoke(userId: String): Result<Unit>
}

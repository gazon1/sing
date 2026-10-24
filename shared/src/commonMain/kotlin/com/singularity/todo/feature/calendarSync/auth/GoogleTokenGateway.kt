package com.singularity.todo.feature.calendarSync.auth

/**
 * The project's own server, as the calendar sign-in sees it.
 *
 * ## Why the server holds the Google credential
 *
 * On Android the offline flow gives the app a *server auth code*, and redeeming it
 * needs the Google client secret. A secret inside an APK is a secret that has leaked
 * — the APK is a zip file anyone can unzip. So the app sends the code to the server,
 * the server redeems it, and the server keeps the refresh token.
 *
 * The refresh token is the part that matters: an access token lasts about an hour,
 * and a calendar sync has to run unattended. A refresh token on the device would have
 * to be renewed with the same secret, so it stays on the server, and the device asks
 * for an access token when it needs one.
 *
 * ## Why a port
 *
 * The wire format is the thing that can go wrong silently: a renamed field reads as
 * a null token, and a null token reads as "not connected". The port keeps the format
 * in one place that a test can drive with a canned body.
 */
interface GoogleTokenGateway {

    /**
     * Redeems a server auth code for an access token.
     *
     * The refresh token behind the code stays on the server: the caller receives an
     * access token and nothing that could be used to mint another one.
     */
    suspend fun exchange(serverAuthCode: String): Result<GoogleCredentials>

    /**
     * A fresh access token for the account that is connected.
     *
     * The server renews its own credential when the stored one has expired, so the
     * caller does not have to know whether it is asking for the first token or the
     * hundredth.
     */
    suspend fun accessToken(): Result<GoogleCredentials>
}

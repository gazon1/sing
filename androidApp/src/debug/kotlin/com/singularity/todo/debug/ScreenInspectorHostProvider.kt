package com.singularity.todo.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/**
 * Debug-only host for the headless screen-inspector contract (#3074).
 *
 * ## Why a ContentProvider and not a service or a socket
 *
 * The contract has to be reachable from a client outside the app process — an agent
 * running `adb`. A Provider is the only Android mechanism that gives a synchronous
 * request/response over the binder, so one `content call` is one inspect or one action and
 * the JSON comes back in the same command. A service needs a second process-lifetime
 * decision and an AIDL or a Messenger; a socket would be a network surface the ADRs
 * already refuse for the desktop host. No background thread, no service, no port.
 *
 * It is also the seam the release boundary can be *checked*: the class lives in
 * `androidApp/src/debug`, so it is not compiled into a release APK at all — not disabled
 * by a flag, absent. `ScreenInspectorHostIsDebugOnlyTest` asserts exactly that.
 *
 * ## Caller check
 *
 * `exported="true"` only because `adb shell` is a different UID and could not reach a
 * private provider; the manifest declares it without a permission so the shell client
 * needs no `pm grant` on a test device. What keeps it local is the guard in [call]: the
 * caller must be the shell (`com.android.shell`), the app itself, or root, matched by UID
 * rather than by a name an app could claim. Every other UID is refused before a request is
 * read.
 *
 * It is not reachable from another app on the device, and it is not reachable over a
 * network at all — nothing here opens a socket.
 */
class ScreenInspectorHostProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle {
        if (!isAllowedCaller(Binder.getCallingUid())) {
            return refusal("The screen-inspector host is not available to this caller.")
        }
        val response = ScreenInspectorDeviceHost.handle(method, arg)
        return Bundle().apply { putString(EXTRA_RESULT, response) }
    }

    /**
     * The app itself, the adb shell, or root.
     *
     * `adb shell content call` runs as the shell UID, and a shell user without root cannot
     * become another app's UID — so this is the same-device development path the Issue
     * allows, and nothing wider.
     */
    private fun isAllowedCaller(uid: Int): Boolean = uid == Process.myUid() || uid == SHELL_UID || uid == ROOT_UID

    private fun refusal(message: String): Bundle =
        Bundle().apply {
            putString(
                EXTRA_RESULT,
                """{"ok":false,"error":{"code":"unsupported","message":"$message"}}""",
            )
        }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        /** The adb shell UID; `content call` arrives as this. */
        const val SHELL_UID = 2000

        /** Root, for a rooted test device or a debug shell as root. */
        const val ROOT_UID = 0

        const val EXTRA_RESULT = "result"
    }
}

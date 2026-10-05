package com.singularity.todo.core.datastore

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import okio.IOException

/**
 * Emits [emptyPreferences] when a preferences read fails with an I/O error, and
 * re-throws everything else.
 *
 * ## What it is for
 *
 * A DataStore file that is corrupt or unreadable must not take a screen down. Every
 * caller in this codebase wants the same response — read as empty, tell the log if the
 * caller cares, carry on — so it is written once here instead of being copied per
 * feature. Four identical private copies existed: in the whats-new prefs, the settings
 * preference wrappers, the calendar-sync settings and the auth session store. A fifth
 * used `java.io.IOException` fully qualified, inline, in a `catch` — and being fully
 * qualified, no import-based check could see it.
 *
 * ## Why [okio.IOException] and not `java.io.IOException`
 *
 * `okio.IOException` is `actual typealias java.io.IOException` on JVM and Android, and
 * an ordinary open class everywhere else. Naming it here therefore changes nothing
 * about which exceptions are caught on the targets this project builds today, while
 * the direct `java.io.IOException` name stops meaning anything outside them. The
 * alternative — catching `Throwable` — would swallow cancellation and turn the
 * corrupt-file case into a silently wrong one.
 *
 * Anything that is not an I/O failure still propagates: a cancellation must cancel,
 * and a programming error must be reported rather than rendered as "no settings".
 *
 * @param onError Invoked with the I/O failure before the empty value is emitted. The
 *   draft store passes a log line here; the rest have nothing to add. A silently
 *   swallowed I/O error is indistinguishable from a user who has no settings yet.
 */
fun Flow<Preferences>.catchDataStoreIoError(
    onError: (Throwable) -> Unit = {},
): Flow<Preferences> = catch { e ->
    if (e is IOException) {
        onError(e)
        emit(emptyPreferences())
    } else {
        throw e
    }
}

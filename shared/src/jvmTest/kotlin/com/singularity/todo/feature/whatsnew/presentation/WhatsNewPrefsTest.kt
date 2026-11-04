package com.singularity.todo.feature.whatsnew.presentation

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The dismissal record is what the sheet's whole behaviour rests on.
 *
 * The sheet shows a payload once and never again, and the flows reach it through the debug
 * seed link — which is why the record has to be forgettable as well as recordable. A flow
 * that cannot forget it passes once and fails on every later run, and the failure looks like
 * a broken sheet rather than a stale record.
 */
@Tag("fast")
class WhatsNewPrefsTest {
    private fun store(dir: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { File(dir, "whatsnew.preferences_pb") }

    @Test
    fun `a payload nobody dismissed is shown`(
        @TempDir dir: File,
    ) = runTest {
        val prefs = WhatsNewPrefs.create(store(dir))

        assertTrue(prefs.shouldShow("Release notes demo"), "an unseen payload is shown")
    }

    @Test
    fun `a dismissed payload is not shown again`(
        @TempDir dir: File,
    ) = runTest {
        val prefs = WhatsNewPrefs.create(store(dir))

        prefs.markShown("Release notes demo")

        assertFalse(prefs.shouldShow("Release notes demo"), "the dismissed payload stays hidden")
    }

    @Test
    fun `another payload is still shown`(
        @TempDir dir: File,
    ) = runTest {
        val prefs = WhatsNewPrefs.create(store(dir))

        prefs.markShown("Release notes demo")

        assertTrue(prefs.shouldShow("Release notes next"), "the next release reaches the user")
    }

    @Test
    fun `a forgotten dismissal is shown again`(
        @TempDir dir: File,
    ) = runTest {
        val prefs = WhatsNewPrefs.create(store(dir))

        prefs.markShown("Release notes demo")
        prefs.forget()

        assertTrue(prefs.shouldShow("Release notes demo"), "forgetting the record re-arms the sheet")
    }

    @Test
    fun `a blank payload is never shown`(
        @TempDir dir: File,
    ) = runTest {
        val prefs = WhatsNewPrefs.create(store(dir))

        assertFalse(prefs.shouldShow(null), "no payload, no sheet")
        assertFalse(prefs.shouldShow(""), "a blank payload is no payload")
    }
}

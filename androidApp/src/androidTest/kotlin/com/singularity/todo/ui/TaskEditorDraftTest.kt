package com.singularity.todo.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import com.singularity.todo.MainActivity
import com.singularity.todo.core.draft.DraftStore
import com.singularity.todo.core.settings.SettingsRepository
import com.singularity.todo.core.text.FieldOwnedText
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.domain.model.TaskDraftKeys
import com.singularity.todo.feature.tasks.domain.state.TaskDraft
import com.singularity.todo.testing.InstrumentedTestBase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * The draft the device carries, and the harness's way of getting rid of it.
 *
 * ## What this proves, and what only the device can prove
 *
 * The task editor restores the draft it was last given, and the draft is
 * persisted: it outlives the process, and it outlives the run. On a device
 * that still carries the state of an earlier run, the editor opens with that
 * text already in the title field. That is the app behaving correctly — a
 * form that restores its draft is the point of the draft — and it is the
 * reason a test that wants an empty editor has to make that true first.
 *
 * The desktop tier owns the FAB flow — `docs/architecture/test-tier-ownership.md`
 * says so — and what is left here is what only a device can observe: a draft
 * that outlived the process, persisted on the device, being restored by the
 * editor when it opens.
 *
 * ## The two tests
 *
 * [the_editor_restores_the_draft_the_device_persisted] seeds a draft and opens
 * the editor **without** clearing it, so the assertion is on the restoration
 * path itself rather than on the harness helper.
 *
 * [the_clearing_touches_the_editors_drafts_and_nothing_else] is the other half:
 * the helper empties the editor's drafts, and the `DataStore` it shares with
 * the app's settings is left alone.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class TaskEditorDraftTest : InstrumentedTestBase() {
    /**
     * The editor restores the draft the device persisted.
     *
     * The draft is written into the app's own store before the editor opens,
     * so the assertion is the same on every device: the editor either restores
     * the seeded text or it does not.
     */
    @Test
    fun the_editor_restores_the_draft_the_device_persisted() {
        val scenario = launchApp()
        try {
            seedDraft(TaskDraftKeys.TASK_CREATE, LEFTOVER_TITLE)

            openTaskEditor()

            val restored =
                device.awaitObject(
                    By.res(TestTags.TASK_EDITOR_TITLE_INPUT).text(LEFTOVER_TITLE),
                    "the task editor's title input carrying the persisted draft",
                    TAB_TIMEOUT_MS,
                )
            assertEquals(
                "the editor must restore the draft that outlived the process",
                LEFTOVER_TITLE,
                restored.text,
            )
        } finally {
            scenario.close()
            clearTaskEditorDrafts()
        }
    }

    /**
     * The drafts that are cleared are the editor's, and only the editor's.
     *
     * The store the editor restores from shares its `DataStore` with the app's
     * settings, so a clear that emptied the store's namespace would take the
     * device's settings with it. The probe is a real app setting: it has to
     * survive the clear, because "the editor's drafts are gone" and "the store
     * was emptied" are different things, and only the second one is a bug.
     */
    @Test
    fun the_clearing_touches_the_editors_drafts_and_nothing_else() {
        val scenario = launchApp()
        val previousStaleness = stalenessThreshold()
        try {
            seedDraft(TaskDraftKeys.TASK_CREATE, LEFTOVER_TITLE)
            setStalenessThreshold(PROBE_STALENESS_DAYS)

            val titleInput = openTaskEditorWithAnEmptyDraft()

            assertTrue(
                "the editor must open empty once the drafts are cleared, got: ${titleInput.text}",
                titleInput.text.isEmpty(),
            )
            val editorDraft = loadDraft(TaskDraftKeys.TASK_CREATE)
            assertTrue(
                "the editor's own draft must be gone, got: ${editorDraft?.title?.text}",
                editorDraft?.title?.text != LEFTOVER_TITLE,
            )
            assertEquals(
                "an app setting is not a draft and must survive the clear",
                PROBE_STALENESS_DAYS,
                stalenessThreshold(),
            )
        } finally {
            scenario.close()
            clearTaskEditorDrafts()
            setStalenessThreshold(previousStaleness)
        }
    }

    /**
     * Opens the app and waits for its shell.
     *
     * The app has to be running before the store can be reached: the store is
     * read from the app's own dependency graph, and there is no graph before
     * the app has started.
     *
     * @return the scenario, so the test can close the app it opened.
     */
    private fun launchApp(): ActivityScenario<MainActivity> {
        val intent =
            Intent(
                ApplicationProvider.getApplicationContext(),
                MainActivity::class.java,
            )
        val scenario = ActivityScenario.launch<MainActivity>(intent)
        device.awaitShell()
        return scenario
    }

    /**
     * Writes a draft into the app's own store.
     *
     * The store is the one the app resolves — the instrumentation runs in the
     * app's process — so the draft is written where the editor will look for
     * it.
     */
    @Suppress("NoRunBlocking") // Instrumented test: the draft store is a suspend API.
    private fun seedDraft(
        key: String,
        title: String,
    ) {
        runBlocking {
            draftStore().save(
                key,
                TaskDraft(title = FieldOwnedText.empty().seeded(title)),
                TaskDraft.serializer(),
            )
        }
    }

    /**
     * The draft the store holds under [key], if it holds one.
     */
    @Suppress("NoRunBlocking") // Instrumented test: the draft store is a suspend API.
    private fun loadDraft(key: String): TaskDraft? =
        runBlocking { draftStore().load(key, TaskDraft.serializer()) }

    private fun draftStore(): DraftStore = GlobalContext.get().get()

    /**
     * The current value of a real app setting, used as the boundary probe.
     *
     * It shares the `DataStore` with the draft store, so a clear that reached
     * the whole namespace would reset it.
     */
    @Suppress("NoRunBlocking") // Instrumented test: the settings API is suspend.
    private fun stalenessThreshold(): Int = runBlocking { settings().stalenessThresholdDays.first() }

    @Suppress("NoRunBlocking") // Instrumented test: the settings API is suspend.
    private fun setStalenessThreshold(value: Int) {
        runBlocking { settings().setStalenessThresholdDays(value) }
    }

    private fun settings(): SettingsRepository = GlobalContext.get().get()

    companion object {
        /** The title of the draft that must be restored, and later cleared. */
        private const val LEFTOVER_TITLE = "Leftover draft"

        /** A setting value that is not the default, so its survival is visible. */
        private const val PROBE_STALENESS_DAYS = 37
    }
}

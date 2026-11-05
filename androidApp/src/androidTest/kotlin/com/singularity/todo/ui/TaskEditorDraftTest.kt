package com.singularity.todo.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.singularity.todo.MainActivity
import com.singularity.todo.core.draft.DraftStore
import com.singularity.todo.core.text.FieldOwnedText
import com.singularity.todo.feature.tasks.domain.model.TaskDraftKeys
import com.singularity.todo.feature.tasks.domain.state.TaskDraft
import com.singularity.todo.testing.InstrumentedTestBase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * The task editor opens on an empty draft.
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
 * ## Why the draft is seeded here
 *
 * The test writes a draft into the app's own store before opening the editor.
 * Without that, the assertion would only be meaningful on a device that
 * happens to carry a leftover draft — on a clean device it would pass for the
 * wrong reason. With it, the assertion is the same on every device: either the
 * drafts are cleared and the editor opens empty, or they are not and the
 * editor opens with the seeded text.
 *
 * @see InstrumentedTestBase.openTaskEditorWithAnEmptyDraft
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class TaskEditorDraftTest : InstrumentedTestBase() {
    /**
     * The editor opens empty once the drafts are cleared.
     */
    @Test
    fun the_editor_opens_with_an_empty_draft() {
        val scenario = launchApp()
        try {
            seedDraft(TaskDraftKeys.TASK_CREATE, LEFTOVER_TITLE)
            assertEquals(
                "the draft must be in the store before the editor is opened, or the " +
                    "assertion below would hold for the wrong reason",
                LEFTOVER_TITLE,
                loadDraft(TaskDraftKeys.TASK_CREATE)?.title?.text,
            )

            val titleInput = openTaskEditorWithAnEmptyDraft()

            assertTrue(
                "the editor must open empty once the drafts are cleared, got: ${titleInput.text}",
                titleInput.text.isEmpty(),
            )
        } finally {
            scenario.close()
        }
    }

    /**
     * The drafts that are cleared are the editor's, and only the editor's.
     *
     * The store the editor restores from shares its storage with the app's
     * settings, so a clear that emptied the store's namespace would take the
     * device's other state with it. The probe below is a draft under a key
     * that is not the editor's: it has to survive the clear, because "the
     * editor's drafts are gone" and "the store was emptied" are different
     * things, and only the second one is a bug.
     */
    @Test
    fun the_clearing_touches_the_editors_drafts_and_nothing_else() {
        val scenario = launchApp()
        try {
            seedDraft(TaskDraftKeys.TASK_CREATE, LEFTOVER_TITLE)
            seedDraft(PROBE_KEY, PROBE_TITLE)

            openTaskEditorWithAnEmptyDraft()

            val editorDraft = loadDraft(TaskDraftKeys.TASK_CREATE)
            assertTrue(
                "the editor's own draft must be gone, got: ${editorDraft?.title?.text}",
                editorDraft?.title?.text != LEFTOVER_TITLE,
            )
            assertEquals(
                "a draft that is not the editor's must survive the clear",
                PROBE_TITLE,
                loadDraft(PROBE_KEY)?.title?.text,
            )
        } finally {
            clearDraft(PROBE_KEY)
            scenario.close()
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
            GlobalContext.get().get<DraftStore>().save(
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
    private fun loadDraft(key: String): TaskDraft? {
        return runBlocking {
            GlobalContext.get().get<DraftStore>().load(key, TaskDraft.serializer())
        }
    }

    /**
     * Takes the probe out of the store again.
     *
     * The probe is written into the app's own storage, so it is taken out of
     * it — a test that leaves a key behind leaves the next test a device that
     * is not the device it expects.
     */
    @Suppress("NoRunBlocking") // Instrumented test: the draft store is a suspend API.
    private fun clearDraft(key: String) {
        runBlocking {
            GlobalContext.get().get<DraftStore>().clear(key)
        }
    }

    companion object {
        /** The title of the draft that must not survive the clear. */
        private const val LEFTOVER_TITLE = "Leftover draft"

        /** The title of the draft that must survive the clear. */
        private const val PROBE_TITLE = "Another feature's draft"

        /**
         * A draft key that is not the editor's.
         *
         * A probe, and nothing more: it names a namespace the editor does not
         * read, so it can only be touched by a clear that reaches beyond the
         * editor's drafts.
         */
        private const val PROBE_KEY = "another_feature_draft"
    }
}

package com.singularity.todo.feature.flows.auth

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.test.helpers.DesktopShell
import com.singularity.todo.test.helpers.awaitTag
import com.singularity.todo.test.helpers.clickContentDescription
import com.singularity.todo.test.helpers.clickTag
import com.singularity.todo.test.helpers.runDesktopAppTest
import com.singularity.todo.test.helpers.tapTab
import com.singularity.todo.test.helpers.typeIntoTag
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Desktop automation for scenario `AUTH-FIRSTRUN-01` — see
 * `infra/kiwi/scenarios/auth/firstrun/AUTH-FIRSTRUN-01.yaml`.
 *
 * The `@DisplayName` above is the linkage: the id is the first token of the
 * string, and `just trace-validate` rejects it anywhere else.
 *
 * ## The absence is the assertion, and it needs no separate check
 *
 * `AuthGuard` renders `LoginScreen()` when the session is `SignedOut` and the
 * app content when it is `Anonymous` or `SignedIn`. There is no `testTag` on the
 * auth screens, and none was added here: the first line of the test *is* the
 * assertion. If the guard ever put a sign-in wall in front of an account-less
 * session, `clickContentDescription(FAB_ADD_TASK)` would find nothing to click
 * and the test would fail at the click — naming the cause, rather than asserting
 * that some string is absent.
 *
 * That also means this test fails for a *different* reason than it was written
 * for if the FAB is ever renamed or removed, which is the trade: one assertion
 * carries both facts, and a failure names whichever broke.
 *
 * ## The ambient state is the point
 *
 * Measured, not assumed: the desktop harness binds an `Anonymous` session
 * (probed by resolving `AuthRepository` from the harness Koin graph — a
 * throwaway probe, since every existing desktop test already sees app content
 * and that is only consistent with `Anonymous` or `SignedIn`). So "usable with
 * no account" is not a setup step this test arranges; it is the condition the
 * whole desktop suite already runs under, and this test is the first to say so
 * out loud.
 */
@OptIn(ExperimentalTestApi::class)
@Tag("slow")
class AuthFirstRunScenarioTest {

    @Test
    @DisplayName("AUTH-FIRSTRUN-01 a task can be created with no account configured")
    fun a_task_can_be_created_without_an_account() = runDesktopAppTest(checkA11y = true) {
        val title = "No account needed"

        clickContentDescription(DesktopShell.FAB_ADD_TASK)
        typeIntoTag(TestTags.TASK_EDITOR_TITLE_INPUT, title)
        clickTag(TestTags.TASK_EDITOR_SAVE)

        // The row surviving a navigation away and back is the storage half of
        // the claim: the scenario says the data is kept locally, and a task that
        // only ever existed in the editor would satisfy nothing.
        //
        // **Inbox, not Today** — worth stating because it cost an iteration. A
        // task saved from the composer has `dueDate = null`, and Today lists only
        // what is due today; undated work lives in Inbox. The row is written
        // either way — the failure bundle's DB snapshot showed it, with
        // `userId=test-user` — so asserting on Today would test the agenda's
        // filtering rule rather than this scenario.
        tapTab("Inbox")
        awaitTag(TestTags.taskItem(title)).assertIsDisplayed()
    }
}

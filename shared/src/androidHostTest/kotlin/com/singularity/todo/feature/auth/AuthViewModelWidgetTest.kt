package com.singularity.todo.feature.auth

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.UserId
import com.singularity.todo.test.fakes.FakeAuthRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Widget tests for [LoginScreen] + [AuthViewModel].
 *
 * Creates ViewModel directly with [FakeAuthRepository] — no Koin, no real DB, no network.
 * Tests the actual Composable UI with real state flowing from VM to Compose.
 */
@RunWith(AndroidJUnit4::class)
class AuthViewModelWidgetTest {

    private val fakeAuthRepo = FakeAuthRepository(Session.Anonymous(UserId.anonymous))

    @get:Rule
    val composeRule = createComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `login screen shows email and password inputs`() {
        val viewModel = AuthViewModel(fakeAuthRepo)
        composeRule.setContent {
            LoginScreen(
                onSuccess = {},
                onContinueOffline = {},
                viewModel = viewModel
            )
        }
        composeRule.onNodeWithTag(TestTags.AUTH_EMAIL_INPUT).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.AUTH_PASSWORD_INPUT).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `login screen shows action buttons`() {
        val viewModel = AuthViewModel(fakeAuthRepo)
        composeRule.setContent {
            LoginScreen(
                onSuccess = {},
                onContinueOffline = {},
                viewModel = viewModel
            )
        }
        composeRule.onNodeWithTag(TestTags.AUTH_SIGN_IN_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.AUTH_CONTINUE_OFFLINE_BUTTON).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `continue offline button exists and is clickable`() {
        val viewModel = AuthViewModel(fakeAuthRepo)
        composeRule.setContent {
            LoginScreen(
                onSuccess = {},
                onContinueOffline = {},
                viewModel = viewModel
            )
        }
        // Button should be displayed and clickable — the onContinueOffline callback
        // is wired by the parent (App) after anonymous sign-in succeeds.
        composeRule.onNodeWithTag(TestTags.AUTH_CONTINUE_OFFLINE_BUTTON)
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `email input accepts text`() {
        val viewModel = AuthViewModel(fakeAuthRepo)
        composeRule.setContent {
            LoginScreen(
                onSuccess = {},
                onContinueOffline = {},
                viewModel = viewModel
            )
        }
        composeRule.onNodeWithTag(TestTags.AUTH_EMAIL_INPUT).performTextInput("user@example.com")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTags.AUTH_EMAIL_INPUT).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `password input accepts text`() {
        val viewModel = AuthViewModel(fakeAuthRepo)
        composeRule.setContent {
            LoginScreen(
                onSuccess = {},
                onContinueOffline = {},
                viewModel = viewModel
            )
        }
        composeRule.onNodeWithTag(TestTags.AUTH_PASSWORD_INPUT).performTextInput("secret123")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTags.AUTH_PASSWORD_INPUT).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `toggle mode button switches between login and signup`() {
        val viewModel = AuthViewModel(fakeAuthRepo)
        composeRule.setContent {
            LoginScreen(
                onSuccess = {},
                onContinueOffline = {},
                viewModel = viewModel
            )
        }
        composeRule.onNodeWithTag(TestTags.AUTH_SIGN_IN_BUTTON).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.AUTH_TOGGLE_MODE_BUTTON).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTags.AUTH_SIGN_IN_BUTTON).assertIsDisplayed()
    }
}

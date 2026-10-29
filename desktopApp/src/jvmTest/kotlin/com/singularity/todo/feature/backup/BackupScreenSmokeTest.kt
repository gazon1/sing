@file:OptIn(ExperimentalTestApi::class)

package com.singularity.todo.feature.backup

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import com.singularity.todo.test.helpers.assertTextDisplayed
import com.singularity.todo.test.helpers.clickText
import com.singularity.todo.test.helpers.runIsolatedComposeTest
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Tag
import kotlin.test.Test

@Tag("slow")
@Tag("slow:compose")
class BackupScreenSmokeTest {
    @Test
    fun `authorize scheduled backup dialog opens and dismisses`() = runIsolatedComposeTest {
        setContent {
            BackupScreen(
                state = BackupUiState(),
                events = emptyFlow(),
                onBack = {},
                onCreateBackup = {},
                onSelectRestoreFile = {},
                onRestore = {},
                onDelete = {},
                onPush = { _, _ -> },
                onSaveToFile = {},
                onExportSettings = {},
                onSelectSettingsFile = {},
                onShareSettingsJson = {},
                onImportSettings = {},
                onLoadRemoteBackups = {},
                onRestoreFromCloud = { _, _ -> },
                onAuthorizeScheduledBackup = {},
            )
        }

        clickText("Let this device back up on its own")
        assertTextDisplayed("Back up on its own")
        clickText("Cancel")
        onAllNodesWithText("Back up on its own").assertCountEquals(0)
    }
}
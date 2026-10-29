package com.singularity.todo.core.backup

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsUserChosenDestinationTest {
    @Test
    fun bareFileNameIsNotUserChosen() {
        assertFalse(isUserChosenDestination("backup.zip"))
    }

    @Test
    fun contentUriIsUserChosen() {
        val uri = "content://com.android.externalstorage.documents/document/primary:backup.zip"
        assertTrue(isUserChosenDestination(uri))
    }

    @Test
    fun absolutePathIsUserChosen() {
        assertTrue(isUserChosenDestination("/data/backup.zip"))
        assertTrue(isUserChosenDestination("C:\\backups\\backup.zip"))
    }

    @Test
    fun httpUriIsUserChosen() {
        assertTrue(isUserChosenDestination("https://example.com/backup.zip"))
    }
}

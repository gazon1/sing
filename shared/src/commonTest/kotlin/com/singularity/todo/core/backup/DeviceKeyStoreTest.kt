package com.singularity.todo.core.backup

import com.singularity.todo.core.security.FakeArchiveCrypto
import com.singularity.todo.test.fakes.FakeSecureStorage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

import org.junit.jupiter.api.Tag

@Tag("fast")
class DeviceKeyStoreTest {
    @Test
    fun deviceKeyGeneratedAndPersisted() = runTest {
        val storage = FakeSecureStorage()
        val crypto = FakeArchiveCrypto()
        val archive = EncryptedArchive(crypto)
        val store = SecureStorageDeviceKeyStore(storage, crypto, archive)
        val key1 = store.deviceKey()
        val key2 = store.deviceKey()
        assertContentEquals(key1, key2)
    }

    @Test
    fun clearRemovesKeyAndBlock() = runTest {
        val storage = FakeSecureStorage()
        val crypto = FakeArchiveCrypto()
        val archive = EncryptedArchive(crypto)
        val store = SecureStorageDeviceKeyStore(storage, crypto, archive)
        store.prepare("pw".toCharArray())
        assertNotNull(store.keyBlock())
        store.clear()
        assertNull(store.keyBlock())
    }
}
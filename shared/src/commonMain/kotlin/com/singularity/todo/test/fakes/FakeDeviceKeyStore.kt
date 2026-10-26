package com.singularity.todo.test.fakes

import com.singularity.todo.core.backup.ArchiveKeyBlock
import com.singularity.todo.core.backup.DeviceKeyStore
import com.singularity.todo.core.backup.EncryptedArchive

/**
 * The device key, kept in memory.
 *
 * ## What it stands for
 *
 * The real store keeps the key in the platform keystore. A test does not need a
 * keystore — it needs the **decisions** the store makes, and those decisions
 * are observable: the key is generated once, the block is wrapped once, and
 * forgetting the key forgets the block with it.
 *
 * The wrapping is real: the block is made by the archive's own
 * [EncryptedArchive.wrapDeviceKey], so a test that opens an archive with this
 * store's block under a password opens it for real. A fake that made up the
 * bytes would test nothing about the thing it stands for.
 */
class FakeDeviceKeyStore(
    private val archive: EncryptedArchive,
) : DeviceKeyStore {

    /** The key, as it was generated. */
    private var key: ByteArray? = null

    /** The block, as it was wrapped. */
    private var block: ArchiveKeyBlock? = null

    /** The passwords the store was asked to wrap under, in order. */
    val wrappedWith = mutableListOf<String>()

    /**
     * A copy of the key: the caller wipes what it gets, and the store keeps
     * its own — a key that the caller can erase is a key that is gone.
     */
    override suspend fun deviceKey(): ByteArray =
        (key ?: ByteArray(32) { it.toByte() }.also { key = it }).copyOf()

    override suspend fun keyBlock(): ArchiveKeyBlock? = block

    override suspend fun prepare(password: CharArray): ArchiveKeyBlock {
        wrappedWith += password.concatToString()
        val deviceKey = deviceKey()
        val wrapped = archive.wrapDeviceKey(deviceKey, password)
        block = wrapped
        return wrapped
    }

    override suspend fun clear() {
        key = null
        block = null
    }
}

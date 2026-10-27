package com.singularity.todo.core.backup

import com.singularity.todo.core.security.ArchiveCrypto
import com.singularity.todo.core.security.SecureStoragePort

/**
 * The key a device seals its archives with when no person is there.
 *
 * ## What this is for
 *
 * A scheduled export has no person, and therefore no password — but the archive
 * still has to be openable by the person who owns the account. So the archive is
 * sealed under a **device key**: a random 256-bit key generated on first use and
 * kept in the platform keystore, never derived from the password and never sent
 * anywhere.
 *
 * The key travels with the archive in a form only the password can open: the
 * [ArchiveKeyBlock]. The password is therefore asked **once per device**, and the
 * scheduled runs use the device key.
 *
 * ## What this is not
 *
 * It is not a stored password. The password is the one secret that cannot be kept
 * — storing it removes the property the encryption exists for — and the device key
 * does not weaken that: the block that carries it is sealed under the password,
 * so a bucket read by someone who is not the person still yields nothing.
 */
interface DeviceKeyStore {

    /**
     * The device key, generated on first use.
     *
     * The returned array is the caller's to wipe; the store keeps its own copy.
     */
    suspend fun deviceKey(): ByteArray

    /** The wrapped device key, or `null` when the password has not been asked yet. */
    suspend fun keyBlock(): ArchiveKeyBlock?

    /**
     * The wrapped device key, wrapping it under [password] the first time.
     *
     * The wrapping happens **once**: the block has a salt of its own, so it does
     * not have to be re-wrapped when the archive's salt changes.
     */
    suspend fun prepare(password: CharArray): ArchiveKeyBlock

    /**
     * Forgets the device key and its block.
     *
     * The archives sealed under the key cannot be opened by anyone afterwards —
     * including the person who owns the account. That is the price of the key
     * being the only copy.
     */
    suspend fun clear()
}

/**
 * The device key, kept in the platform keystore.
 *
 * ## Why the keystore and not a file
 *
 * The key is a secret that lives on the device, and the platform already has a
 * place for secrets: [SecureStoragePort]. On Android that is the
 * hardware-backed keystore, on the desktop a keyring — and on a platform where
 * neither exists, a file that is encrypted with a key derived from the machine.
 * A key of the archive's own in an ordinary file would be a copy of the key next
 * to the archives it opens.
 *
 * ## What is stored
 *
 * Two values, both as text, because a keystore that only holds text is the one
 * every platform has:
 *
 * - the device key, as hex;
 * - the wrapped key block, as hex.
 *
 * Neither is the password, and neither is derived from it.
 */
class SecureStorageDeviceKeyStore(
    private val storage: SecureStoragePort,
    private val crypto: ArchiveCrypto,
    private val archive: EncryptedArchive,
) : DeviceKeyStore {

    override suspend fun deviceKey(): ByteArray {
        val stored = storage.read(DEVICE_KEY)
        if (stored != null) return decodeHex(stored)
        val key = crypto.randomBytes(ArchiveFormat.DEVICE_KEY_BYTES)
        storage.write(DEVICE_KEY, key.toHexString())
        // A block that was wrapped under an older key would not open under this
        // one, so it goes with the key it belonged to.
        storage.delete(KEY_BLOCK)
        return key
    }

    override suspend fun keyBlock(): ArchiveKeyBlock? {
        val stored = storage.read(KEY_BLOCK) ?: return null
        // A block without the key it wraps opens nothing: the archive would be
        // sealed under a key that is not there, and the person restoring it
        // would be asked for a password that cannot open anything.
        if (storage.read(DEVICE_KEY) == null) return null
        return ArchiveKeyBlock.fromBytes(decodeHex(stored))
    }

    override suspend fun prepare(password: CharArray): ArchiveKeyBlock {
        // The device key stays the same; only the wrapping changes. So the
        // archives this device already wrote keep opening — they carry their own
        // block — and the ones written from now on open under the password that
        // was asked for last.
        val key = deviceKey()
        val block = archive.wrapDeviceKey(key, password)
        storage.write(KEY_BLOCK, block.toBytes().toHexString())
        return block
    }

    override suspend fun clear() {
        storage.delete(KEY_BLOCK)
        storage.delete(DEVICE_KEY)
    }

    private companion object {
        /** Where the device key lives. */
        const val DEVICE_KEY = "backup.device_key"

        /** Where the wrapped device key lives. */
        const val KEY_BLOCK = "backup.device_key_block"
    }
}

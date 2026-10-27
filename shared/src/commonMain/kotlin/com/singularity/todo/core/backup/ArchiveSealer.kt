package com.singularity.todo.core.backup

/**
 * The key a push seals the archive under.
 *
 * ## Why the key is a value and not a flag
 *
 * There are two ways to seal a chain, and they differ in one thing only: the key.
 * A person pushing an archive from the app has a password to hand; a scheduled run
 * has a device key and no person. Everything else — what is sealed, in what order,
 * into which object, under which name — is the same work, and it was being done
 * twice: once by [RemoteBackupService.upload] and once by the repository, which
 * sealed the container itself and then handed the finished bytes to a second call.
 *
 * Two copies of the same work is where the difference crept in: the scheduled path
 * sealed the **snapshot alone**, so the chain that the export had built never
 * reached the bucket, and the daily backup was the size of a full archive while
 * the manual push was the size of a change.
 *
 * So the key is what varies, and it is passed as a value: one call, one sealing
 * path, and the difference between the two pushes is visible in one line.
 *
 * ## The secret is consumed
 *
 * A sealer holds a secret, and the call that takes it wipes it before it returns:
 * a password that stays in memory after the upload is a password that can be read
 * out of a heap dump. The device key is wiped the same way, and the store keeps
 * its own copy — the key a scheduled run seals with is a copy handed out for one
 * use.
 */
sealed interface ArchiveSealer {

    /**
     * The chain, sealed under the person's password.
     *
     * The password is the key the archive is encrypted under, and the server never
     * sees it: the bytes that reach the bucket are ciphertext.
     */
    class UnderPassword(val password: CharArray) : ArchiveSealer

    /**
     * The chain, sealed under the device key.
     *
     * The key is the one the device holds, and it travels with the container
     * wrapped under the person's password — so the person who restores the archive
     * still needs their password, and the scheduled run needs nobody.
     */
    class UnderDeviceKey(val deviceKey: ByteArray, val keyBlock: ArchiveKeyBlock) : ArchiveSealer
}

package com.singularity.todo.core.security

import com.singularity.todo.core.process.Subprocess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * JVM implementation of [SecureStoragePort].
 *
 * Strategy:
 *  1. On Linux: try `secret-tool` (libsecret/GNOME Keyring) first.
 *  2. On all JVM platforms: fall back to an AES-GCM encrypted file
 *     at `~/.config/singularity/secure.bin`.
 *
 * The AES-GCM fallback derives a key from `user.home` via PBKDF2 — no external deps.
 */
open class JvmSecureStorage : SecureStoragePort {

    /** Open so test subclasses can direct the fallback file to a test-specific path. */
    protected open val fallbackFile: Path by lazy {
        val home = System.getProperty("user.home")
        val dir = Paths.get(home, ".config", "singularity")
        Files.createDirectories(dir)
        dir.toFile().setReadable(false, false)
        dir.toFile().setWritable(false, false)
        dir.toFile().setExecutable(false, false)
        dir.toFile().setReadable(true, true)
        dir.toFile().setWritable(true, true)
        dir.toFile().setExecutable(true, true)
        dir.resolve("secure.bin")
    }

    // ─── SecureStoragePort ────────────────────────────────────────────────────

    override suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        secretToolRead(key) ?: fallbackReadWithRecovery(key)
    }

    override suspend fun write(key: String, value: String) = withContext(Dispatchers.IO) {
        if (!secretToolWrite(key, value)) fallbackWriteWithRecovery(key, value)
    }

    override suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        secretToolDelete(key)
        fallbackDelete(key)
    }

    override fun isHardwareBacked(): Boolean = IS_LINUX && secretToolAvailable()

    // ─── libsecret via secret-tool CLI ───────────────────────────────────────
    //
    // Every call goes through `Subprocess`, which drains the child's output before
    // waiting. Two of these methods used to `waitFor()` with no drain at all, which
    // deadlocks as soon as the child writes more than a pipe buffer — a hang that would
    // only appear on a machine where `secret-tool` was unusually chatty, and so would be
    // very hard to reproduce.

    /** Open so test subclasses can force the fallback path. */
    protected open fun secretToolAvailable(): Boolean =
        IS_LINUX && Subprocess.runQuietly(listOf("which", "secret-tool")) == 0

    private fun secretToolRead(key: String): String? {
        if (!secretToolAvailable()) return null
        return Subprocess.runCapturing(listOf("secret-tool", "lookup", "key=$key"))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun secretToolWrite(key: String, value: String): Boolean {
        if (!IS_LINUX || !secretToolAvailable()) return false
        // The secret goes on stdin, never in the command line: an argument is visible in
        // `ps` output to every process on the machine for as long as it runs.
        return Subprocess.runCapturing(
            argv = listOf(
                "secret-tool",
                "store",
                "--label=singularity:$key",
                "key",
                key,
            ),
            stdin = value,
        ) != null
    }

    private fun secretToolDelete(key: String) {
        if (!IS_LINUX || !secretToolAvailable()) return
        Subprocess.runQuietly(listOf("secret-tool", "delete", "key=$key"))
    }

    // ─── AES-GCM file fallback ───────────────────────────────────────────────

    private val rng = SecureRandom()

    private fun deriveKey(): SecretKeySpec {
        // shortcut: key derived from public user.home with a fixed salt; real
        // protection needs an OS keyring or a user-supplied secret, upgrade when
        // the fallback stops being a last resort.
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(
            System.getProperty("user.home")!!.toCharArray(),
            SALT,
            ITERATIONS,
            KEY_BITS,
        )
        val key = factory.generateSecret(spec)
        return SecretKeySpec(key.encoded, "AES")
    }

    /**
     * Reads [key] from [fallbackFile], recovering from a corrupted file by erasing it once.
     *
     * The `secure.bin` file stores all keys. If the file is corrupted — wrong MAC, truncated
     * header, unrecognised format — the whole file is unreadable and the correct response
     * is to treat every key as absent and let the next write start fresh. This is the same
     * policy as Android's [SecureStoreRecovery]: one erase-and-retry, then propagate.
     *
     * The erase destroys all keys in the file, not just the one that failed to read. This
     * is the right trade-off for the fallback: it is used when libsecret is unavailable, and
     * a JVM desktop install that cannot use the system keyring has no other durable credential
     * store worth recovering.
     *
     * A second failure is not a stale-ciphertext problem and is not retried.
     */
    private fun fallbackReadWithRecovery(key: String): String? =
        SecureStoreRecovery.openOrErase(
            open = { fallbackRead(key) },
            erase = { Files.deleteIfExists(fallbackFile) },
        )

    private fun fallbackRead(key: String): String? {
        if (!Files.exists(fallbackFile)) return null
        return DataInputStream(Files.newInputStream(fallbackFile)).use { dis ->
            val count = dis.readInt()
            require(count in 0..MAX_ENTRIES) { "entry count out of range: $count" }
            repeat(count) {
                val k = dis.readUTF()
                val ivLen = dis.readInt()
                require(ivLen in 0..MAX_IV_BYTES) { "iv length out of range: $ivLen" }
                val iv = ByteArray(ivLen)
                dis.readFully(iv)
                val ctLen = dis.readInt()
                require(ctLen in 0..MAX_CT_BYTES) { "ciphertext length out of range: $ctLen" }
                val ct = ByteArray(ctLen)
                dis.readFully(ct)
                if (k == key) {
                    val cipher = Cipher.getInstance(AES_TRANSFORM)
                    cipher.init(Cipher.DECRYPT_MODE, deriveKey(), GCMParameterSpec(TAG_BITS, iv))
                    return String(cipher.doFinal(ct), Charsets.UTF_8)
                }
            }
            null
        }
    }

    /**
     * Writes [key]=[value] to [fallbackFile], recovering from a corrupted file by erasing it once.
     *
     * A write that cannot complete leaves the file in an indeterminate state — partially written
     * entries, wrong count, corrupted MAC — so a write failure is treated the same as a read
     * failure: erase and retry once. The retry appends the new key to an empty store, which
     * means any other keys that were in the file are lost. This is unavoidable for a file that
     * cannot be read: recovery requires starting over.
     *
     * A second failure propagates with the first attached as suppressed.
     */
    private fun fallbackWriteWithRecovery(key: String, value: String) =
        SecureStoreRecovery.openOrErase(
            open = { fallbackWrite(key, value) },
            erase = { Files.deleteIfExists(fallbackFile) },
        )

    private fun fallbackWrite(key: String, value: String) {
        val entries = mutableMapOf<String, Pair<ByteArray, ByteArray>>() // iv to ct
        if (Files.exists(fallbackFile)) {
            DataInputStream(Files.newInputStream(fallbackFile)).use { dis ->
                val count = dis.readInt()
                require(count in 0..MAX_ENTRIES) { "entry count out of range: $count" }
                repeat(count) {
                    val k = dis.readUTF()
                    val ivLen = dis.readInt()
                    require(ivLen in 0..MAX_IV_BYTES) { "iv length out of range: $ivLen" }
                    val iv = ByteArray(ivLen)
                    dis.readFully(iv)
                    val ctLen = dis.readInt()
                    require(ctLen in 0..MAX_CT_BYTES) { "ciphertext length out of range: $ctLen" }
                    val ct = ByteArray(ctLen)
                    dis.readFully(ct)
                    if (k != key) entries[k] = iv to ct
                }
            }
        }
        val iv = ByteArray(12)
        rng.nextBytes(iv)
        val cipher = Cipher.getInstance(AES_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(), GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        // The read side bounds a ciphertext length; a value that exceeds it would write
        // successfully and then make every key unreadable, so refuse it here instead.
        require(ct.size <= MAX_CT_BYTES) { "value too large for the fallback store: ${ct.size} bytes" }
        entries[key] = iv to ct

        DataOutputStream(Files.newOutputStream(fallbackFile)).use { dos ->
            dos.writeInt(entries.size)
            for ((k, pair) in entries) {
                val (iv2, ct2) = pair
                dos.writeUTF(k)
                dos.writeInt(iv2.size)
                dos.write(iv2)
                dos.writeInt(ct2.size)
                dos.write(ct2)
            }
        }
        fallbackFile.toFile().setReadable(false, false)
        fallbackFile.toFile().setWritable(false, false)
        fallbackFile.toFile().setReadable(true, true)
        fallbackFile.toFile().setWritable(true, true)
    }

    private fun fallbackDelete(key: String) {
        if (!Files.exists(fallbackFile)) return
        val entries = mutableMapOf<String, Pair<ByteArray, ByteArray>>()
        val parsed = runCatching {
            DataInputStream(Files.newInputStream(fallbackFile)).use { dis ->
                val count = dis.readInt()
                require(count in 0..MAX_ENTRIES) { "entry count out of range: $count" }
                repeat(count) {
                    val k = dis.readUTF()
                    val ivLen = dis.readInt()
                    require(ivLen in 0..MAX_IV_BYTES) { "iv length out of range: $ivLen" }
                    val iv = ByteArray(ivLen)
                    dis.readFully(iv)
                    val ctLen = dis.readInt()
                    require(ctLen in 0..MAX_CT_BYTES) { "ciphertext length out of range: $ctLen" }
                    val ct = ByteArray(ctLen)
                    dis.readFully(ct)
                    if (k != key) entries[k] = iv to ct
                }
            }
        }.isSuccess
        // A file that cannot be parsed is left exactly as it is. Rewriting it from a partial
        // parse would launder a truncated file into a valid shorter one, silently dropping
        // every entry after the break point.
        if (!parsed) return
        DataOutputStream(Files.newOutputStream(fallbackFile)).use { dos ->
            dos.writeInt(entries.size)
            for ((k, pair) in entries) {
                val (iv2, ct2) = pair
                dos.writeUTF(k)
                dos.writeInt(iv2.size)
                dos.write(iv2)
                dos.writeInt(ct2.size)
                dos.write(ct2)
            }
        }
        fallbackFile.toFile().setReadable(false, false)
        fallbackFile.toFile().setWritable(false, false)
        fallbackFile.toFile().setReadable(true, true)
        fallbackFile.toFile().setWritable(true, true)
    }

    private companion object {
        private val IS_LINUX = System.getProperty("os.name", "")
            .lowercase().contains("linux")
        private const val AES_TRANSFORM = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128

        // Fixed at 100_000 on purpose: the key derives from public `user.home`, so a
        // higher cost buys no attacker resistance but does re-derive a different key —
        // which would make every existing secure.bin entry undecryptable and take the
        // backup device key with it. Change only together with a stored KDF version.
        private const val ITERATIONS = 100_000
        private const val KEY_BITS = 256
        private val SALT = "singularity-v1".toByteArray()

        // Bounds for the fallback file's untrusted lengths. A corrupted or hostile file must
        // not be able to drive an allocation; exceeding these is treated as corruption, which
        // read/write turn into a single erase-and-retry and delete leaves alone.
        private const val MAX_ENTRIES = 1024
        private const val MAX_IV_BYTES = 16
        private const val MAX_CT_BYTES = 64 * 1024
    }
}

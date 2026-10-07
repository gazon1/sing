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
class JvmSecureStorage : SecureStoragePort {

    private val fallbackFile: Path by lazy {
        val home = System.getProperty("user.home")
        val dir = Paths.get(home, ".config", "singularity")
        Files.createDirectories(dir)
        dir.resolve("secure.bin")
    }

    // ─── SecureStoragePort ────────────────────────────────────────────────────

    override suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        secretToolRead(key) ?: fallbackRead(key)
    }

    override suspend fun write(key: String, value: String) = withContext(Dispatchers.IO) {
        if (!secretToolWrite(key, value)) fallbackWrite(key, value)
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

    private fun secretToolAvailable(): Boolean =
        Subprocess.runQuietly(listOf("which", "secret-tool")) == 0

    private fun secretToolRead(key: String): String? {
        if (!IS_LINUX || !secretToolAvailable()) return null
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

    private fun fallbackRead(key: String): String? {
        if (!Files.exists(fallbackFile)) return null
        return runCatching {
            DataInputStream(Files.newInputStream(fallbackFile)).use { dis ->
                val count = dis.readInt()
                repeat(count) {
                    val k = dis.readUTF()
                    val ivLen = dis.readInt()
                    val iv = ByteArray(ivLen)
                    dis.readFully(iv)
                    val ctLen = dis.readInt()
                    val ct = ByteArray(ctLen)
                    dis.readFully(ct)
                    if (k == key) {
                        val cipher = Cipher.getInstance(AES_TRANSFORM)
                        cipher.init(Cipher.DECRYPT_MODE, deriveKey(), GCMParameterSpec(TAG_BITS, iv))
                        return@runCatching String(cipher.doFinal(ct), Charsets.UTF_8)
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun fallbackWrite(key: String, value: String) {
        val entries = mutableMapOf<String, Pair<ByteArray, ByteArray>>() // iv to ct
        if (Files.exists(fallbackFile)) {
            runCatching {
                DataInputStream(Files.newInputStream(fallbackFile)).use { dis ->
                    val count = dis.readInt()
                    repeat(count) {
                        val k = dis.readUTF()
                        val ivLen = dis.readInt()
                        val iv = ByteArray(ivLen)
                        dis.readFully(iv)
                        val ctLen = dis.readInt()
                        val ct = ByteArray(ctLen)
                        dis.readFully(ct)
                        if (k != key) entries[k] = iv to ct
                    }
                }
            }
        }
        val iv = ByteArray(12)
        rng.nextBytes(iv)
        val cipher = Cipher.getInstance(AES_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(), GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
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
    }

    private fun fallbackDelete(key: String) {
        val entries = mutableMapOf<String, Pair<ByteArray, ByteArray>>()
        if (Files.exists(fallbackFile)) {
            runCatching {
                DataInputStream(Files.newInputStream(fallbackFile)).use { dis ->
                    val count = dis.readInt()
                    repeat(count) {
                        val k = dis.readUTF()
                        val ivLen = dis.readInt()
                        val iv = ByteArray(ivLen)
                        dis.readFully(iv)
                        val ctLen = dis.readInt()
                        val ct = ByteArray(ctLen)
                        dis.readFully(ct)
                        if (k != key) entries[k] = iv to ct
                    }
                }
            }
        }
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
    }

    private companion object {
        private val IS_LINUX = System.getProperty("os.name", "")
            .lowercase().contains("linux")
        private const val AES_TRANSFORM = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val ITERATIONS = 100_000
        private const val KEY_BITS = 256
        private val SALT = "singularity-v1".toByteArray()
    }
}

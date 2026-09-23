package com.singularity.todo.core.security

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

    private fun secretToolAvailable(): Boolean = runCatching {
        val p = ProcessBuilder("which", "secret-tool")
            .redirectErrorStream(true)
            .start()
        p.waitFor()
        p.exitValue() == 0
    }.getOrDefault(false)

    private fun secretToolRead(key: String): String? {
        if (!IS_LINUX || !secretToolAvailable()) return null
        return runCatching {
            val p = ProcessBuilder("secret-tool", "lookup", "key=$key")
                .redirectErrorStream(true)
                .start()
            val result = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            if (p.exitValue() == 0) result else null
        }.getOrNull()
    }

    private fun secretToolWrite(key: String, value: String): Boolean {
        if (!IS_LINUX || !secretToolAvailable()) return false
        return runCatching {
            val p = ProcessBuilder(
                "secret-tool",
                "store",
                "--label=singularity:$key",
                "key",
                key,
            ).also { it.redirectErrorStream(true) }.start()
            p.outputStream.writer().use { it.write(value) }
            p.outputStream.close()
            p.waitFor()
            p.exitValue() == 0
        }.getOrDefault(false)
    }

    private fun secretToolDelete(key: String) {
        if (!IS_LINUX || !secretToolAvailable()) return
        runCatching {
            val p = ProcessBuilder("secret-tool", "delete", "key=$key")
                .redirectErrorStream(true)
                .start()
            p.waitFor()
        }
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

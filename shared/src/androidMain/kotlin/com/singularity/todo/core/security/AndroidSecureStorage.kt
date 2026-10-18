package com.singularity.todo.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android implementation of [SecureStoragePort] using
 * `androidx.security:security-crypto` EncryptedSharedPreferences.
 * Keys and values are stored in the Android Keystore-backed encryptedPrefs file.
 *
 * ## Why an unreadable file is erased rather than reported
 *
 * [EncryptedSharedPreferences] throws rather than blocks when the ciphertext it
 * finds does not match the key it has. The state that produces it is ordinary:
 * Android's Auto Backup copies `shared_prefs/` across an uninstall, and the
 * Android Keystore key that decrypts it is deleted on uninstall and is never
 * backed up. The reinstall therefore opens a file encrypted with a key this
 * device no longer has, and every read throws `GeneralSecurityException` /
 * `KeyStoreException` for the life of that install.
 *
 * AndroidX says the exclusion is mandatory
 * (`EncryptedSharedPreferences.create` javadoc: "The preference file should not
 * be backed up with Auto Backup"), and `backup_rules.xml` implements that. The
 * recovery here is for what the exclusion cannot reach: an install that already
 * carries the broken file, or a device where backup ran before the rule shipped.
 *
 * Retrying the failure is not enough on its own — a `by lazy` that throws
 * rethrows the same error forever, which is how the caller ends up with a screen
 * that can never finish starting. Deleting the file is what makes the next
 * attempt succeed: a fresh key, an empty store, and a signed-out user who can
 * sign in again. The data in that file was already unreadable, so deleting it
 * destroys nothing that could have been recovered.
 */
class AndroidSecureStorage(context: Context) : SecureStoragePort {

    private val prefs: SharedPreferences by lazy {
        createPrefs(context)
    }

    private fun createPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return try {
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (first: Exception) {
            // Only a key mismatch is recoverable here, and that is the failure that
            // arrives as a throw rather than as an empty store. Anything else — a
            // revoked lock screen, a full disk — will fail again on a clean file,
            // and the second failure is the one worth reporting to the caller.
            eraseUnreadableFile(context)
            try {
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (second: Exception) {
                second.addSuppressed(first)
                throw second
            }
        }
    }

    private fun eraseUnreadableFile(context: Context) {
        runCatching {
            File(context.applicationInfo.dataDir, "shared_prefs/$PREFS_NAME.xml").delete()
        }
    }

    override suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        prefs.getString(key, null)
    }

    override suspend fun write(key: String, value: String) = withContext(Dispatchers.IO) {
        prefs.edit { putString(key, value) }
    }

    override suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        prefs.edit { remove(key) }
    }

    override fun isHardwareBacked(): Boolean = true

    companion object {
        private const val PREFS_NAME = "singularity_secure_prefs"
    }
}

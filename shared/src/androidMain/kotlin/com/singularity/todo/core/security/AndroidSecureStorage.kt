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
 * The decision lives in [SecureStoreRecovery] rather than inline, because this
 * class needs a `Context` and a `security-crypto` artifact and so is unreachable
 * from a JVM test — which would leave "can the app recover from a broken
 * install" as the one behaviour nothing covers.
 */
class AndroidSecureStorage(context: Context) : SecureStoragePort {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        SecureStoreRecovery.openOrErase(
            open = { createPrefs(context, masterKey) },
            erase = { eraseUnreadableFile(context) },
        )
    }

    private fun createPrefs(context: Context, masterKey: MasterKey): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

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

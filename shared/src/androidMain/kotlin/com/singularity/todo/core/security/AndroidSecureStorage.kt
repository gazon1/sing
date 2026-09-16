package com.singularity.todo.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of [SecureStoragePort] using
 * `androidx.security:security-crypto` EncryptedSharedPreferences.
 * Keys and values are stored in the Android Keystore-backed encryptedPrefs file.
 */
class AndroidSecureStorage(context: Context) : SecureStoragePort {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
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

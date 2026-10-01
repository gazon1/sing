package com.singularity.todo.core.security

import com.singularity.todo.feature.profile.domain.port.ProfileRepository

/**
 * A [SecureStoragePort] that namespaces all keys under `profiles/{profileId}/`
 * using the currently active profile from [ProfileRepository].
 *
 * This provides per-profile isolation of sensitive data (e.g. AI API keys).
 * The base [SecureStoragePort] remains global; this wrapper is used when
 * profile-scoped storage is needed.
 *
 * @param prefixKey if `true`, keys are prefixed with `profiles/{profileId}/`.
 *                   if `false`, the underlying store is used as-is (useful for testing).
 */
class ProfileAwareSecureStorage(
    private val delegate: SecureStoragePort,
    private val profileRepository: ProfileRepository,
    private val prefixKey: Boolean = true,
) : SecureStoragePort {

    private fun prefixed(key: String): String = if (!prefixKey) {
        key
    } else {
        "profiles/${profileRepository.activeProfileId.value.value}/$key"
    }

    override suspend fun read(key: String): String? = delegate.read(prefixed(key))

    override suspend fun write(key: String, value: String) {
        delegate.write(prefixed(key), value)
    }

    override suspend fun delete(key: String) {
        delegate.delete(prefixed(key))
    }

    override fun isHardwareBacked(): Boolean = delegate.isHardwareBacked()
}

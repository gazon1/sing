@file:Suppress("UNUSED") // Part of the preview surface; methods are called by preview renderers.

package com.singularity.todo.core.ui.preview

import com.singularity.todo.feature.profile.Profile
import com.singularity.todo.feature.profile.ProfileId
import com.singularity.todo.feature.profile.domain.port.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Minimal preview-only implementation of [ProfileRepository] that returns hardcoded data.
 * Used in @Preview composables to avoid pulling in
 * [com.singularity.todo.test.fakes.FakeProfileRepository] (2200 lines of fake state management)
 * into production APKs.
 */
internal object PreviewProfileRepository : ProfileRepository {

    private val allProfiles = listOf(PreviewSamples.previewProfile)
    private val _activeProfileId = MutableStateFlow(PreviewSamples.previewProfile.id)

    override fun observeAll(): Flow<List<Profile>> = flowOf(allProfiles)

    override fun observe(id: ProfileId): Flow<Profile?> = flowOf(allProfiles.find { it.id == id })

    override suspend fun get(id: ProfileId): Profile? = allProfiles.find { it.id == id }

    override suspend fun create(item: Profile): Result<Profile> = Result.success(item)

    override suspend fun update(item: Profile): Result<Profile> = Result.success(item)

    override suspend fun delete(id: ProfileId): Result<Unit> = Result.success(Unit)

    override fun activeProfile(): Flow<Profile> = flowOf(PreviewSamples.previewProfile)

    override val activeProfileId: StateFlow<ProfileId> = _activeProfileId

    override suspend fun switchTo(id: ProfileId): Result<Unit> {
        _activeProfileId.value = id
        return Result.success(Unit)
    }

    override suspend fun ensureDefaults(extraProfiles: List<Triple<String, String, Int>>) {
        // no-op for previews
    }
}

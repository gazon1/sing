package com.singularity.todo.feature.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.coroutines.createBackgroundScope
import com.singularity.todo.core.database.ProfileDao
import com.singularity.todo.core.database.ProfileEntity
import com.singularity.todo.core.platform.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Instant

/**
 * Room + DataStore implementation of [ProfileRepository].
 *
 * Profile records are in Room (durable, syncable). The active-profile key lives
 * in DataStore so it survives DB wipes on Android.
 *
 * @param scope CoroutineScope for hosting the active-profile StateFlow's collector.
 *   Mandatory — caller is responsible for providing the scope. In production
 *   this comes from Koin's `single { ... createBackgroundScope() }`. In tests,
 *   inject a `TestScope` or `backgroundScope`.
 */
class ProfileRepositoryImpl(
    private val profileDao: ProfileDao,
    private val dataStore: DataStore<Preferences>,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : ProfileRepository {

    companion object {
        private val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
    }

    // Collect DataStore into a StateFlow for activeProfileId
    private val _activeProfileId: StateFlow<ProfileId> = dataStore.data
        .map { prefs ->
            prefs[ACTIVE_PROFILE_ID]?.let { ProfileId.fromString(it) } ?: ProfileId.default
        }
        .stateIn(scope, SharingStarted.Eagerly, ProfileId.default)

    override fun all(): Flow<List<Profile>> = profileDao.all().map { entities -> entities.map { it.toDomain() } }

    override fun activeProfile(): Flow<Profile> = _activeProfileId.map { id ->
        profileDao.getById(id.value)?.toDomain()
            ?: profileDao.getDefault()?.toDomain()
            ?: Profile.createDefault(clock)
    }

    override val activeProfileId: StateFlow<ProfileId> = _activeProfileId

    override suspend fun create(name: String, emoji: String, colorIdx: Int): ProfileId {
        val now = clock.now()
        val id = ProfileId.generate()
        val entity = ProfileEntity(
            id = id.value,
            name = name,
            emoji = emoji,
            colorIdx = colorIdx,
            isDefault = false,
            createdAt = instantToEpochMillis(now),
            updatedAt = instantToEpochMillis(now),
        )
        profileDao.upsert(entity)
        return id
    }

    override suspend fun update(id: ProfileId, name: String, emoji: String, colorIdx: Int) {
        val existing = profileDao.getById(id.value)
            ?: throw IllegalArgumentException("Profile not found: ${id.value}")
        val updated = existing.copy(
            name = name,
            emoji = emoji,
            colorIdx = colorIdx,
            updatedAt = instantToEpochMillis(clock.now()),
        )
        profileDao.upsert(updated)
    }

    override suspend fun delete(id: ProfileId): Result<Unit> {
        if (profileDao.count() <= 1) {
            return Result.failure(IllegalStateException("Cannot delete the last remaining profile"))
        }
        profileDao.deleteById(id.value)
        // If we deleted the active profile, switch to default
        if (_activeProfileId.value == id) {
            val default = profileDao.getDefault()
            if (default != null) {
                dataStore.edit { it[ACTIVE_PROFILE_ID] = default.id }
            }
        }
        return Result.success(Unit)
    }

    override suspend fun switchTo(id: ProfileId) {
        profileDao.getById(id.value)
            ?: throw IllegalArgumentException("Profile not found: ${id.value}")
        dataStore.edit { it[ACTIVE_PROFILE_ID] = id.value }
    }

    override suspend fun getById(id: ProfileId): Profile? = profileDao.getById(id.value)?.toDomain()

    override suspend fun ensureDefaults(extraProfiles: List<Triple<String, String, Int>>) {
        // Idempotent: if a default profile already exists, leave it.
        if (profileDao.getDefault() == null) {
            val now = instantToEpochMillis(clock.now())
            profileDao.upsert(
                ProfileEntity(
                    id = ProfileId.default.value,
                    name = "Personal",
                    emoji = "🏠",
                    colorIdx = 0,
                    isDefault = true,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        // Avoid duplicates for extra profiles — match names that already exist.
        val wantedNames = extraProfiles.map { it.first }.toSet()
        if (wantedNames.isEmpty()) return
        val existingNames = profileDao.allNames().filter { it in wantedNames }.toSet()
        for ((name, emoji, colorIdx) in extraProfiles) {
            if (name in existingNames) continue
            val now = instantToEpochMillis(clock.now())
            profileDao.upsert(
                ProfileEntity(
                    id = ProfileId.generate().value,
                    name = name,
                    emoji = emoji,
                    colorIdx = colorIdx,
                    isDefault = false,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }
}

// ─── Mapping ───────────────────────────────────────────────────────────────────

private fun ProfileEntity.toDomain(): Profile = Profile(
    id = ProfileId.fromString(id),
    name = name,
    emoji = emoji,
    colorIdx = colorIdx,
    isDefault = isDefault,
    createdAt = Instant.fromEpochMilliseconds(createdAt),
    updatedAt = Instant.fromEpochMilliseconds(updatedAt),
)

private fun instantToEpochMillis(instant: Instant): Long = instant.toEpochMilliseconds()

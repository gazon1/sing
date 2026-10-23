package com.singularity.todo.feature.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.database.ProfileDao
import com.singularity.todo.core.database.ProfileEntity
import com.singularity.todo.feature.profile.domain.port.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
import com.singularity.todo.core.error.runCatchingCancellable

/**
 * Room + DataStore implementation of [ProfileRepository].
 *
 * Profile records are in Room (durable, syncable). The active-profile key lives in a
 * DataStore file of its own, so it survives DB wipes on Android — and so that a second
 * process on the same machine can open the application's settings without touching
 * the profile the user is working in.
 *
 * @param scope CoroutineScope for hosting the active-profile StateFlow's collector.
 *   Mandatory — caller is responsible for providing the scope. In production
 *   this comes from Koin's `single { ... createBackgroundScope() }`. In tests,
 *   inject a `TestScope` or `backgroundScope`.
 * @param profileStateStore where the active profile is kept. **Null means "this process
 *   does not own the active profile"** — see [fixedProfileId].
 * @param fixedProfileId the profile this process works as, when it was told one.
 * @param legacyProfileStateStore the file the active profile used to live in, read once
 *   to move the key out of it. Null when there is nothing to move.
 */
class ProfileRepositoryImpl(
    private val profileDao: ProfileDao,
    private val profileStateStore: DataStore<Preferences>?,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val fixedProfileId: ProfileId? = null,
    legacyProfileStateStore: DataStore<Preferences>? = null,
) : ProfileRepository {

    /**
     * The stored active profile, or null when this process does not have one.
     *
     * A process that was told which profile to work as has no store at all: the store
     * takes a file lock, and the application is holding it. Opening it here would turn
     * "a second process on the same machine" into "a second process fighting the
     * application for a lock" — and the only way to avoid that is to not ask for the
     * store.
     */
    private val activeProfileState: ActiveProfileState? =
        if (fixedProfileId == null) {
            profileStateStore?.let { ActiveProfileState(it, legacyProfileStateStore) }
        } else {
            null
        }

    // Collect DataStore into a StateFlow for activeProfileId
    private val _activeProfileId = MutableStateFlow(fixedProfileId ?: ProfileId.default)

    init {
        // The stored active profile is read only when there is one to read.
        val state = activeProfileState
        if (state != null) {
            scope.launch {
                state.ids.collect { id -> _activeProfileId.value = id }
            }
        }
    }

    // ── GenericUserScopedRepository ────────────────────────────────────────────

    override fun observeAll(): Flow<List<Profile>> = profileDao.all().map { entities -> entities.map { it.toDomain() } }

    override fun observe(id: ProfileId): Flow<Profile?> =
        profileDao.all().map { entities -> entities.find { it.id == id.value }?.toDomain() }

    override suspend fun get(id: ProfileId): Profile? = profileDao.getById(id.value)?.toDomain()

    override suspend fun create(item: Profile): Result<Profile> = runCatchingCancellable {
        val entity = item.toEntity()
        profileDao.upsert(entity)
        item
    }

    override suspend fun update(item: Profile): Result<Profile> = runCatchingCancellable {
        val existing = profileDao.getById(item.id.value)
            ?: throw IllegalArgumentException("Profile not found: ${item.id.value}")
        val updated = existing.copy(
            name = item.name,
            emoji = item.emoji,
            colorIdx = item.colorIdx,
            updatedAt = instantToEpochMillis(clock.now()),
        )
        profileDao.upsert(updated)
        updated.toDomain()
    }

    override suspend fun delete(id: ProfileId): Result<Unit> {
        if (fixedProfileId != null && id == fixedProfileId) {
            return Result.failure(
                IllegalStateException("Cannot delete the profile this process works as"),
            )
        }
        if (profileDao.count() <= 1) {
            return Result.failure(IllegalStateException("Cannot delete the last remaining profile"))
        }
        profileDao.deleteById(id.value)
        // If we deleted the active profile, switch to default. A process with a fixed
        // profile has no active profile of its own to switch, and no store to switch it in.
        if (_activeProfileId.value == id && activeProfileState != null) {
            val default = profileDao.getDefault()
            if (default != null) {
                activeProfileState.write(ProfileId.fromString(default.id))
            }
        }
        return Result.success(Unit)
    }

    // ── Profile-specific observers ─────────────────────────────────────────────

    override fun activeProfile(): Flow<Profile> = _activeProfileId.map { id ->
        profileDao.getById(id.value)?.toDomain()
            ?: profileDao.getDefault()?.toDomain()
            ?: Profile.createDefault(clock)
    }

    override val activeProfileId: StateFlow<ProfileId> = _activeProfileId

    // ── Domain methods ─────────────────────────────────────────────────────────

    override suspend fun switchTo(id: ProfileId): Result<Unit> {
        if (fixedProfileId != null) {
            // A process that was told which profile to work as does not get to change
            // its mind: the profile belongs to whoever started the process.
            return Result.failure(
                IllegalStateException("This process works as a fixed profile and cannot switch"),
            )
        }
        return runCatchingCancellable {
            profileDao.getById(id.value)
                ?: throw IllegalArgumentException("Profile not found: ${id.value}")
            activeProfileState?.write(id)
        }
    }

    override suspend fun findByName(name: String): Profile? =
        profileDao.findByName(name)?.toDomain()

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

private fun Profile.toEntity(): ProfileEntity = ProfileEntity(
    id = id.value,
    name = name,
    emoji = emoji,
    colorIdx = colorIdx,
    isDefault = isDefault,
    createdAt = instantToEpochMillis(createdAt),
    updatedAt = instantToEpochMillis(updatedAt),
)

private fun instantToEpochMillis(instant: Instant): Long = instant.toEpochMilliseconds()

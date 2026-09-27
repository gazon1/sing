---
name: singularity-todo-attachments
description: Attachment feature pattern for the Singularity Todo KMP app. Use when adding file attachment support for tasks OR notes. Covers AttachmentEntity (owner_id + owner_kind generalization), AttachmentOwner sealed interface (Task/Note), AttachmentDao, AttachmentRepository interface, AttachmentStorage port, AttachmentUploadService port, AttachmentType enum, AttachmentSyncStatus, AttachmentsViewModel with owner runtime param, and Migration4To5 for schema v4→v5. The storage layer is platform-specific (Android: internal / external storage, JVM: FileSystem port).
---

# Attachments Feature Pattern

## Current Schema (as of 2026-09-07)

The attachment system was initially tied to `TaskId`. **Generalization to support both `TaskId` and `NoteId` is planned for Phase 4** via `owner_id` + `owner_kind` columns.

**Current state of `AttachmentEntity`:**
```kotlin
// ⚠️ LEGACY — still uses task_id in this codebase.
// Will be migrated to owner_id + owner_kind in Phase 4.
@Entity(
    tableName = "attachments",
    indices = [Index("task_id"), Index("user_id"), Index("sync_status")]
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("task_id") val taskId: String,   // ← will be replaced
    @ColumnInfo("user_id") val userId: String,
    val type: String, // AttachmentType as String
    @ColumnInfo("url") val url: String? = null,
    val title: String = "",
    @ColumnInfo("local_path") val localPath: String? = null,
    @ColumnInfo("remote_url") val remoteUrl: String? = null,
    @ColumnInfo("file_size_bytes") val fileSizeBytes: Long = 0L,
    @ColumnInfo("mime_type") val mimeType: String? = null,
    @ColumnInfo("checksum") val checksum: String? = null,
    @ColumnInfo("sync_status") val syncStatus: String = "Pending",
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
    @ColumnInfo("deleted_at") val deletedAt: Long? = null,
    @ColumnInfo("server_version") val serverVersion: Long = 0L,
    @ColumnInfo("hlc") val hlc: String? = null
)
```

**Target schema (Phase 4 — v4→v5):**
```kotlin
// Target — replaces task_id with owner_id + owner_kind
@Entity(
    tableName = "attachments",
    indices = [Index("owner_id"), Index("user_id"), Index("sync_status")]
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("owner_id") val ownerId: String,              // replaces task_id
    @ColumnInfo("owner_kind") val ownerKind: String = "task", // "task" | "note"
    @ColumnInfo("user_id") val userId: String,
    val type: String, // AttachmentType as String
    @ColumnInfo("url") val url: String? = null,
    val title: String = "",
    @ColumnInfo("local_path") val localPath: String? = null,
    @ColumnInfo("remote_url") val remoteUrl: String? = null,
    @ColumnInfo("file_size_bytes") val fileSizeBytes: Long = 0L,
    @ColumnInfo("mime_type") val mimeType: String? = null,
    @ColumnInfo("checksum") val checksum: String? = null,
    @ColumnInfo("sync_status") val syncStatus: String = "Pending",
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
    @ColumnInfo("deleted_at") val deletedAt: Long? = null,
    @ColumnInfo("server_version") val serverVersion: Long = 0L,
    @ColumnInfo("hlc") val hlc: String? = null
)
```

## Architecture

```
AttachmentStorage (port — commonMain)
    ├── AndroidStorageAdapter (androidMain) — real file storage
    └── FakeAttachmentStorage (commonMain) — in-memory for tests

AttachmentUploadService (port — commonMain)
    ├── StubAttachmentUploadService (commonMain) — no-op for now
    └── RealUploadService (future — HTTP multipart)

AttachmentRepository (commonMain)
    └── Room DAO + AttachmentEntity

AttachmentsViewModel (commonMain) — UI state + user intents
AttachmentsViewModel.kt + AttachmentButton/Sheet/Tile/Thumbnail.kt
```

## AttachmentOwner sealed interface (Phase 4 target)

```kotlin
// Represents the owner of an attachment — either a Task or a Note.
// Used instead of hard-coded TaskId throughout the attachment system.
sealed interface AttachmentOwner {
    data class Task(val id: TaskId) : AttachmentOwner
    data class Note(val id: NoteId) : AttachmentOwner

    val key: String  // "task:<id>" or "note:<id>" — for Fake repos

    companion object {
        fun fromTaskId(id: TaskId) = Task(id)
        fun fromNoteId(id: NoteId) = Note(id)
    }
}

// Extension for converting from legacy taskId String (Phase 3 → 4 transition)
fun AttachmentOwner.Companion.fromLegacyTaskId(taskId: String): AttachmentOwner =
    AttachmentOwner.Task(TaskId.fromString(taskId))
```

## AttachmentEntity (Phase 4 target)

```kotlin
@Entity(
    tableName = "attachments",
    indices = [Index("owner_id"), Index("type")]
)
data class AttachmentEntity(
    @PrimaryKey val id: String,           // ULID
    @ColumnInfo("owner_id") val ownerId: String,  // NoteId or TaskId (as String)
    @ColumnInfo("owner_kind") val ownerKind: String, // "task" | "note"
    @ColumnInfo("user_id") val userId: String,
    val type: String, // AttachmentType as String (TypeConverter handles enum↔String)
    val name: String,
    val mimeType: String,
    @ColumnInfo("size_bytes") val sizeBytes: Long,
    @ColumnInfo("local_path") val localPath: String?,   // null = not downloaded
    @ColumnInfo("remote_url") val remoteUrl: String?,  // null = not uploaded
    @ColumnInfo("sync_status") val syncStatus: String, // AttachmentSyncStatus
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long,
    @ColumnInfo("device_id") val deviceId: String?,
    @ColumnInfo("hlc") val hlc: String?,
)

enum class AttachmentType {
    IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, OTHER
}

enum class AttachmentSyncStatus {
    LOCAL_ONLY, UPLOADING, SYNCED, FAILED
}
```

## AttachmentDao (Phase 4 target)

```kotlin
interface AttachmentDao {
    // Phase 4: owner_id + owner_kind replaces task_id
    @Query("SELECT * FROM attachments WHERE owner_id = :ownerId AND owner_kind = :ownerKind")
    fun watchByOwner(ownerId: String, ownerKind: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE owner_id = :ownerId AND owner_kind = :ownerKind")
    suspend fun findByOwner(ownerId: String, ownerKind: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun findById(id: String): AttachmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AttachmentEntity)

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE attachments SET sync_status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    // Legacy (remove after Phase 4):
    // @Query("SELECT * FROM attachments WHERE task_id = :taskId")
    // fun watchByTask(taskId: String): Flow<List<AttachmentEntity>>
}
```

## AttachmentStorage port

```kotlin
interface AttachmentStorage {
    /** Save bytes to local storage, return local file path. */
    suspend fun save(ownerId: String, name: String, mimeType: String, bytes: ByteArray): String

    /** Read bytes from local path. */
    suspend fun read(localPath: String): ByteArray

    /** Delete local file. */
    suspend fun delete(localPath: String)

    /** Returns total size of all attachments for owner. */
    suspend fun totalSize(ownerId: String): Long
}
```

**Android implementation**: stores in app-specific external storage (`context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)`).

**JVM implementation**: stores in `~/.local/share/singularity/attachments/`.

**Fake for tests**: `FakeAttachmentStorage` backed by `MutableMap<String, ByteArray>`.

## AttachmentUploadService port

```kotlin
interface AttachmentUploadService {
    /**
     * Upload a local file to the remote server.
     * Returns the remote URL on success.
     * Throws on failure (the caller marks sync_status = FAILED).
     */
    suspend fun upload(localPath: String, mimeType: String, progress: (Int) -> Unit = {}): String

    /**
     * Download a remote URL to local storage.
     * Returns local path.
     */
    suspend fun download(remoteUrl: String, targetPath: String)
}
```

Currently only `StubAttachmentUploadService` exists (returns `localPath` as-is). A real implementation would use Supabase Storage.

## AttachmentRepository (Phase 4 target)

```kotlin
interface AttachmentRepository {
    // Phase 4: owner-aware signatures
    fun watchForOwner(owner: AttachmentOwner): Flow<List<Attachment>>
    fun watchForUser(userId: UserId): Flow<List<Attachment>>
    suspend fun findById(id: AttachmentId): Attachment?
    suspend fun addAttachment(owner: AttachmentOwner, fileName: String, mimeType: String, bytes: ByteArray): Result<AttachmentId>
    suspend fun removeAttachment(id: AttachmentId): Result<Unit>
    suspend fun retryUpload(id: AttachmentId): Result<Unit>
    suspend fun totalSizeForOwner(owner: AttachmentOwner): Long

    // Legacy (remove after Phase 4):
    // fun watchForTask(taskId: TaskId): Flow<List<Attachment>>
    // suspend fun attachToTask(taskId: TaskId, ...): Result<AttachmentId>
}
```

## AttachmentsViewModel (Phase 4 — with owner runtime param)

```kotlin
sealed interface AttachmentsUiState {
    data object Idle : AttachmentsUiState
    data class Viewing(val attachments: List<Attachment>) : AttachmentsUiState
    data class Uploading(val progress: Int) : AttachmentsUiState
    data class Error(val message: String) : AttachmentsUiState
}

sealed interface AttachmentsIntent {
    data class Add(val owner: AttachmentOwner, val fileName: String, val mimeType: String, val bytes: ByteArray) : AttachmentsIntent
    data class Remove(val id: AttachmentId) : AttachmentsIntent
    data class Retry(val id: AttachmentId) : AttachmentsIntent
}

class AttachmentsViewModel(
    private val repository: AttachmentRepository,
    private val storage: AttachmentStorage,
    private val uploader: AttachmentUploadService,
    private val owner: AttachmentOwner,    // ← Phase 4: runtime param (NOT constructor-parametrized DI)
) : ViewModel() {

    private val _state = MutableStateFlow<AttachmentsUiState>(AttachmentsUiState.Idle)
    val state: StateFlow<AttachmentsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.watchForOwner(owner).collect { attachments ->
                _state.value = AttachmentsUiState.Viewing(attachments)
            }
        }
    }

    fun onIntent(intent: AttachmentsIntent) = viewModelScope.launch {
        when (intent) {
            is AttachmentsIntent.Add -> addAttachment(intent)
            is AttachmentsIntent.Remove -> repository.removeAttachment(intent.id).getOrThrow()
            is AttachmentsIntent.Retry -> repository.retryUpload(intent.id).getOrThrow()
        }
    }

    private suspend fun addAttachment(intent: AttachmentsIntent.Add) {
        _state.value = AttachmentsUiState.Uploading(0)
        val result = repository.addAttachment(
            owner = intent.owner,
            fileName = intent.fileName,
            mimeType = intent.mimeType,
            bytes = intent.bytes,
        )
        result.fold(
            onSuccess = { _state.value = AttachmentsUiState.Idle },
            onFailure = { _state.value = AttachmentsUiState.Error(it.message ?: "Upload failed") }
        )
    }
}
```

**DI registration (Phase 4):**
```kotlin
// Modules.kt — use viewModel { } with runtime parameter, NOT viewModelOf
viewModel { (owner: AttachmentOwner) ->
    AttachmentsViewModel(
        repository = get(),
        storage = get(),
        uploader = get(),
        owner = owner,
    )
}

// Usage in Composable — pass owner via parametersOf:
@Composable
fun AttachmentSheet(
    owner: AttachmentOwner,
    onDismiss: () -> Unit,
) {
    val vm: AttachmentsViewModel = koinViewModel { parametersOf(owner) }
    // ...
}
```

**⚠️ Never use `factory { AttachmentsViewModel(...) }` for ViewModel** — it causes memory leaks (see the DI table in `AGENTS.md`).

## UI components

| Component | File | Purpose |
|---|---|---|
| `AttachmentButton` | `feature/attachments/AttachmentButton.kt` | Shows count badge, opens sheet. Accepts `owner: AttachmentOwner` param. |
| `AttachmentSheet` | `feature/attachments/AttachmentSheet.kt` | Bottom sheet with attachment list + file picker. Accepts `owner: AttachmentOwner`. |
| `AttachmentTile` | `feature/attachments/AttachmentTile.kt` | List item: icon, name, size, status |
| `AttachmentThumbnail` | `feature/attachments/AttachmentThumbnail.kt` | Image thumbnail with Coil |

## FileKit integration

File picker uses `io.github.vinceglb:filekit-core` + `filekit-dialogs-compose`:

```kotlin
val result = rememberFilePicker(
    allowedExtensions = listOf("jpg", "png", "pdf", "docx"),
    onResult = { uri ->
        val bytes = fileKit.readBytes(uri)
        vm.onIntent(AttachmentsIntent.Add(owner, name, mime, bytes))
    }
)
```

## Testing pattern

```kotlin
class AttachmentsViewModelTest {
    @Test fun `add attachment starts upload`() = runTest {
        val owner = AttachmentOwner.Task(TaskId.generate())
        val vm = AttachmentsViewModel(
            repository = FakeAttachmentRepository(),
            storage = FakeAttachmentStorage(),
            uploader = FakeAttachmentUploadService(),
            owner = owner,
        )
        vm.onIntent(AttachmentsIntent.Add(owner, "photo.jpg", "image/jpeg", byteArrayOf(1, 2, 3)))
        assertTrue(vm.state.value is AttachmentsUiState.Uploading)
    }
}
```

## Migration: Phase 4 (v4 → v5)

**Dev strategy (current — uses `fallbackToDestructive`):**
- No `addMigrations(...)` needed.
- `AppDatabaseFactory.kt` keeps `.fallbackToDestructiveMigration(dropAllTables = true)`.
- On schema bump, local DB is wiped and recreated from scratch on each dev device.
- Developer cleans DB manually: `adb shell pm clear com.singularity.todo`.

**AutoMigrationSpec (for schema JSON generation):**
```kotlin
// Migrations.kt
object Migration4To5 : AutoMigrationSpec {
    // Room auto-detects column changes from schema diff.
    // This object only exists to satisfy autoMigrations = [...] in @Database.
    // Actual migration is handled by fallbackToDestructiveMigration in dev.
}
```

**AppDatabase bump:**
```kotlin
@Database(
    entities = [..., AttachmentEntity::class],
    version = 5,   // ← bump from 4
    autoMigrations = [AutoMigration(from = 4, to = 5, spec = Migration4To5::class)],
    exportSchema = true
)
```

**Prod migration (future — separate PR):**
- Replace `fallbackToDestructiveMigration` with `addMigrations(MIGRATION_4_5)`.
- Write manual migration for `ALTER TABLE attachments RENAME COLUMN task_id TO owner_id`.
- Add `owner_kind TEXT NOT NULL DEFAULT 'task'` column.
- Update all call sites that still use `taskId: String`.

## Key Files (Phase 4 target)

| File | Role |
|---|---|
| `core/database/Entities.kt` | `AttachmentEntity` (target: `owner_id` + `owner_kind`) |
| `core/database/Daos.kt` | `AttachmentDao` (target: `watchByOwner`) |
| `core/attachments/AttachmentRepository.kt` | Interface (target: `watchForOwner`, `addAttachment(owner, ...)`) |
| `core/attachments/AttachmentStorage.kt` | Storage port |
| `core/attachments/AttachmentUploadService.kt` | Upload port |
| `feature/attachments/AttachmentsViewModel.kt` | UI logic + runtime owner param |
| `feature/attachments/AttachmentSheet.kt` | Sheet with owner param |
| `feature/attachments/AttachmentButton.kt` | Badge button with owner param |
| `test/fakes/FakeRepositories.kt` | `FakeAttachmentRepository` (Phase 4: needs owner-aware `watchForOwner`) |

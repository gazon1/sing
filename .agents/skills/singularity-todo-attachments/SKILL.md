---
name: singularity-todo-attachments
description: Attachment feature pattern for the Singularity Todo KMP app. Use when adding file attachment support, upload, download, or storage for notes and tasks. Covers AttachmentEntity, AttachmentDao, AttachmentRepository interface, AttachmentStorage port, AttachmentUploadService port, AttachmentType enum, AttachmentSyncStatus, and the AttachmentsViewModel UI layer. The storage layer is platform-specific (Android: internal / external storage, JVM: FileSystem port).
---

# Attachments Feature Pattern

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

## AttachmentEntity (Room)

```kotlin
@Entity(
    tableName = "attachments",
    primaryKeys = ["id"],
    indices = [Index("owner_id"), Index("type")]
)
data class AttachmentEntity(
    @PrimaryKey val id: String,           // ULID
    @ColumnInfo("owner_id") val ownerId: String,  // NoteId or TaskId
    @ColumnInfo("type") val type: AttachmentType,
    val name: String,
    val mimeType: String,
    @ColumnInfo("size_bytes") val sizeBytes: Long,
    @ColumnInfo("local_path") val localPath: String?,   // null = not downloaded
    @ColumnInfo("remote_url") val remoteUrl: String?,  // null = not uploaded
    @ColumnInfo("sync_status") val syncStatus: AttachmentSyncStatus,
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

## AttachmentDao

```kotlin
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE owner_id = :ownerId")
    fun watchByOwner(ownerId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun findById(id: String): AttachmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AttachmentEntity)

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE attachments SET sync_status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: AttachmentSyncStatus)
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

## AttachmentRepository

```kotlin
interface AttachmentRepository {
    fun watchAttachments(ownerId: String): Flow<List<Attachment>>
    suspend fun addAttachment(ownerId: String, fileName: String, mimeType: String, bytes: ByteArray): Result<AttachmentId>
    suspend fun removeAttachment(id: AttachmentId): Result<Unit>
    suspend fun retryUpload(id: AttachmentId): Result<Unit>
}
```

## AttachmentsViewModel

```kotlin
sealed interface AttachmentsUiState {
    data object Idle : AttachmentsUiState
    data class Viewing(val attachments: List<Attachment>) : AttachmentsUiState
    data class Uploading(val progress: Int) : AttachmentsUiState
    data class Error(val message: String) : AttachmentsUiState
}

sealed interface AttachmentsIntent {
    data class Add(val ownerId: String, val fileName: String, val mimeType: String, val bytes: ByteArray) : AttachmentsIntent
    data class Remove(val id: AttachmentId) : AttachmentsIntent
    data class Retry(val id: AttachmentId) : AttachmentsIntent
}

class AttachmentsViewModel(
    private val repository: AttachmentRepository,
    private val storage: AttachmentStorage,
    private val uploader: AttachmentUploadService,
    private val currentUserId: UserId,
) : ViewModel() {

    private val _state = MutableStateFlow<AttachmentsUiState>(AttachmentsUiState.Idle)
    val state: StateFlow<AttachmentsUiState> = _state.asStateFlow()

    fun processIntent(intent: AttachmentsIntent) = viewModelScope.launch {
        when (intent) {
            is AttachmentsIntent.Add -> addAttachment(intent)
            is AttachmentsIntent.Remove -> repository.removeAttachment(intent.id).getOrThrow()
            is AttachmentsIntent.Retry -> repository.retryUpload(intent.id).getOrThrow()
        }
    }

    private suspend fun addAttachment(intent: AttachmentsIntent.Add) {
        _state.value = AttachmentsUiState.Uploading(0)
        val result = repository.addAttachment(
            ownerId = intent.ownerId,
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

## UI components

| Component | File | Purpose |
|---|---|---|
| `AttachmentButton` | `AttachmentsViewModel.kt` (companion?) | Shows count badge, opens sheet |
| `AttachmentSheet` | `AttachmentsViewModel.kt` | Bottom sheet with attachment list |
| `AttachmentTile` | `AttachmentsViewModel.kt` | List item: icon, name, size, status |
| `AttachmentThumbnail` | `AttachmentsViewModel.kt` | Image thumbnail with Coil |

The components are currently in the `feature/attachments/` directory.

## FileKit integration

File picker uses `io.github.vinceglb:filekit-core` + `filekit-dialogs-compose`:

```kotlin
val result = rememberFilePicker(
    allowedExtensions = listOf("jpg", "png", "pdf", "docx"),
    onResult = { uri ->
        val bytes = fileKit.readBytes(uri)
        viewModel.processIntent(AttachmentsIntent.Add(ownerId, name, mime, bytes))
    }
)
```

## Testing pattern

```kotlin
class AttachmentsViewModelTest {
    @Test fun `add attachment starts upload`() = runTest {
        val vm = AttachmentsViewModel(
            repository = FakeAttachmentRepository(),
            storage = FakeAttachmentStorage(),
            uploader = FakeAttachmentUploadService(),
            currentUserId = UserId("user-1"),
        )
        vm.processIntent(AttachmentsIntent.Add("note-1", "photo.jpg", "image/jpeg", byteArrayOf(1, 2, 3)))
        assertTrue(vm.state.value is AttachmentsUiState.Uploading)
    }
}
```

## Key Files

| File | Purpose |
|---|---|
| `core/database/Entities.kt` | `AttachmentEntity` |
| `core/database/Daos.kt` | `AttachmentDao` |
| `core/attachments/AttachmentRepository.kt` | Interface |
| `core/attachments/AttachmentStorage.kt` | Storage port |
| `core/attachments/AttachmentUploadService.kt` | Upload port |
| `core/attachments/AttachmentRepositoryImpl.kt` | Room implementation |
| `feature/attachments/AttachmentsViewModel.kt` | UI logic + UI components |
| `test/fakes/FakeRepositories.kt` | `FakeAttachmentRepository` |

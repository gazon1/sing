---
name: singularity-todo-multi-profile
description: Namespace-based multi-profile pattern for Singularity Todo KMP. Use when adding profile support (separate namespaces for AI-agent dogfooding vs personal tasks). Covers Profile domain object, ProfileRepository, ProfileAwareCurrentUser, per-profile SecureStorage keys, AI config isolation, --profile CLI argument parsing, ProfileSwitcherScreen UI, and ZCode dual-server setup.
---

# Multi-Profile — Namespace-Based Isolation

## Why This Skill Exists

You need to separate AI-agent work from personal tasks. Both should be in the same app, but isolated: different tasks, different notes, different API keys, different token budgets. Profiles give you that isolation without building two separate apps.

**Profile = namespace, not user.** A single human uses multiple profiles for different contexts. Not multi-user (different humans) — that requires full auth which is a separate concern.

## When to Use This Skill

- Adding `Profile` domain object and `ProfileRepository`.
- Implementing `ProfileAwareCurrentUser` to map `profileId` → `userId`.
- Isolating AI config (apiKey, model) per profile.
- Building the `ProfileSwitcherScreen` UI.
- Parsing `--profile=NAME` CLI argument.
- Setting up ZCode with two MCP servers for different profiles.

Skip for: single-profile deployment, full multi-user auth (different humans).

## Profile Domain Object

```kotlin
// shared/src/commonMain/.../feature/profile/Profile.kt
package com.singularity.todo.core.profile

import kotlinx.datetime.Instant

@Serializable
data class Profile(
    val id: String,                  // UUID
    val name: String,                // "Personal", "AI Agent", "Work"
    val emoji: String = "",           // visual marker: "🤖", "🏠", "💼"
    val isDefault: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class ProfileSettings(
    val aiProvider: String = "OPENAI",
    val aiModel: String = "gpt-4o-mini",
    val aiBaseUrl: String? = null,
    // apiKey stored in SecureStorage with prefix "ai_key_openai:{profileId}"
)

val DEFAULT_PROFILE_ID = "default"

val Profile.defaultSettings get() = ProfileSettings()
```

## ProfileRepository

```kotlin
// shared/src/commonMain/.../feature/profile/domain/port/ProfileRepository.kt
package com.singularity.todo.core.profile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ProfileRepository {
    /** Currently active profile. Changes trigger reactive updates in all repositories. */
    val active: StateFlow<Profile>

    /** All profiles. */
    val all: StateFlow<List<Profile>>

    /** Create a new profile. */
    suspend fun create(name: String, emoji: String = ""): Result<Profile>

    /** Rename an existing profile. */
    suspend fun rename(id: String, name: String, emoji: String = ""): Result<Unit>

    /** Delete a profile. Fails if it's the last one or the active one. */
    suspend fun delete(id: String): Result<Unit>

    /** Switch to a different profile. */
    suspend fun switchTo(id: String): Result<Unit>

    /** Get profile settings (AI config). */
    fun settings(profileId: String): Flow<ProfileSettings>

    /** Update profile settings. */
    suspend fun updateSettings(profileId: String, settings: ProfileSettings): Result<Unit>
}
```

### Storage

Store profiles in the existing Room database — same file, different table:

```kotlin
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val emoji: String,
    val isDefault: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "profile_settings", indices = [Index("profile_id")])
data class ProfileSettingsEntity(
    @PrimaryKey val profileId: String,
    val aiProvider: String,
    val aiModel: String,
    val aiBaseUrl: String?,
)
```

**Design rationale:** profiles live in the same DB as tasks/notes, not a separate file. This keeps migration simple and avoids cross-file foreign keys. The `userId` column in `TaskEntity`/`NoteEntity` already exists — we repurpose it as `profileId`.

## ProfileAwareCurrentUser

The key insight: `profileId` maps to `userId` transparently. Existing repositories already filter by `userId`. We don't change the contract — we change what `userId` means.

```kotlin
// shared/src/commonMain/.../feature/profile/ProfileAwareCurrentUser.kt
package com.singularity.todo.core.profile

import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.feature.tasks.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * Wraps the existing CurrentUser interface.
 * Maps profile.id → userId so all existing repositories work unchanged.
 *
 * Profile "personal" (id="personal") → userId = "profile:personal"
 * Profile "ai-agent" (id="ai-agent") → userId = "profile:ai-agent"
 *
 * This preserves the existing repository contract while adding profile isolation.
 */
class ProfileAwareCurrentUser(
    private val profileRepo: ProfileRepository,
) : CurrentUser {
    override val userId: StateFlow<UserId> = profileRepo.active
        .map { UserId.fromString("profile:${it.id}") }
        .stateIn(scope, SharingStarted.Eagerly, UserId.anonymous)

    val profileId: String get() = profileRepo.active.value.id
    val profile: Profile get() = profileRepo.active.value
}
```

**Why `"profile:${it.id}"?** Keeps profile-scope and real-user-scope clearly separate. If Supabase auth is added later, `userId = "user:123"` would come from `Session.SignedIn`, and `"profile:${id}"` from profiles.

## Per-Profile AI Config

AI settings (provider, model, apiKey) must be isolated per profile:

```kotlin
// In OpenAiConfig.resolve():
fun resolve(
    secureStorage: SecureStoragePort,
    settings: SettingsRepository,
    profileId: String,
): OpenAiConfig {
    val profileSettings = settings.aiSettings(profileId).first()

    return OpenAiConfig(
        provider = LlmProvider.fromId(profileSettings.aiProvider),
        baseUrl = profileSettings.aiBaseUrl ?: LlmProvider.fromId(profileSettings.aiProvider).defaultBaseUrl,
        modelId = profileSettings.aiModel.ifBlank { OpenAiConfig.DEFAULT_MODEL },
        apiKey = ApiKey(
            secureStorage.read("ai_key_openai:$profileId")
                ?: secureStorage.read(OpenAiConfig.KEY_OPENAI)  // fallback to legacy key
                ?: "",
        ),
    )
}
```

**SecureStorage keys:**
- Legacy (no profile): `ai_key_openai`
- Profile-specific: `ai_key_openai:{profileId}`

This allows the AI-agent profile to have its own API key (maybe a different org) while personal uses the main key.

## --profile CLI Argument Parsing

```kotlin
// mcp-server/src/main/kotlin/com/singularity/todo/mcp/Args.kt
package com.singularity.todo.mcp

fun Array<String>.parseArgs(): ParsedArgs {
    var profileId: String? = null
    var shouldInit = false
    var shouldCreateProfile = false

    for (arg in this) {
        when {
            arg.startsWith("--profile=") -> profileId = arg.removePrefix("--profile=")
            arg == "--init" -> shouldInit = true
            arg == "--create" -> shouldCreateProfile = true
        }
    }

    return ParsedArgs(
        profileId = profileId ?: "default",
        shouldInit = shouldInit,
        shouldCreateProfile = shouldCreateProfile,
    )
}

data class ParsedArgs(
    val profileId: String,
    val shouldInit: Boolean,
    val shouldCreateProfile: Boolean,
)
```

## ProfileSwitcherScreen UI

New destination: `AppDestination.ProfileSwitcher`

```kotlin
// shared/src/commonMain/.../feature/profile/ProfileSwitcherScreen.kt
@Composable
fun ProfileSwitcherScreen(
    viewModel: ProfileSwitcherViewModel = koinViewModel(),
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profiles") },
                navigationIcon = { BackButton(onNavigateBack) },
                actions = {
                    IconButton(onClick = { viewModel.showCreateDialog() }) {
                        Icon(Icons.Default.Add, contentDescription = "New profile")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            // Active profile badge
            item {
                Text(
                    text = "Active: ${state.activeProfile?.emoji ?: ""} ${state.activeProfile?.name}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }

            // Profile list
            items(state.profiles) { profile ->
                ProfileListItem(
                    profile = profile,
                    isActive = profile.id == state.activeProfile?.id,
                    onSelect = { viewModel.switchTo(profile.id) },
                    onRename = { viewModel.showRenameDialog(profile) },
                    onDelete = { viewModel.confirmDelete(profile) },
                )
            }
        }
    }

    // Create dialog
    if (state.showCreateDialog) {
        CreateProfileDialog(
            onDismiss = { viewModel.dismissDialog() },
            onCreate = { name, emoji ->
                viewModel.create(name, emoji)
            },
        )
    }
}

@Composable
private fun ProfileListItem(
    profile: Profile,
    isActive: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = { Text("${profile.emoji} ${profile.name}") },
        trailingContent = {
            if (isActive) {
                Icon(Icons.Default.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary)
            }
        },
        modifier = Modifier.clickable(onClick = onSelect),
    )
}
```

**Integration point:** `AuthGuard` shows "Create your first profile" wizard if `profileRepo.all.first().isEmpty()` instead of showing `LoginScreen`.

## ZCode Dual-Server Setup

Two profiles, two MCP servers, both available simultaneously:

```toml
# ~/.zcode/mcp/servers.toml
[[servers]]
name = "singularity-todo-agent"
command = ["./gradlew", ":mcp-server:run", "--quiet", "--args=--profile=ai-agent"]
description = "MCP server for AI-agent dogfooding"

[[servers]]
name = "singularity-todo-personal"
command = ["./gradlew", ":mcp-server:run", "--quiet", "--args=--profile=personal"]
description = "MCP server for personal tasks"
```

When ZCode connects to both, the AI agent sees tools from **both** profiles, each namespaced to its profile's data. The agent can choose which profile to use based on context.

## What Is Isolated Per Profile

| Layer | Isolation | Mechanism |
|---|---|---|
| Tasks, Notes, Projects, Tags | ✅ | `profileId` → `userId` in all queries |
| AI Config (provider, model, baseUrl) | ✅ | `ProfileSettings` in Room |
| API Key | ✅ | `SecureStorage` key with `:{profileId}` suffix |
| LLM Usage Records | ✅ | `LlmUsageEntity.profileId` column |
| Reminders, Attachments | ✅ | FK to `TaskEntity` which has `profileId` |
| MCP Server process | ✅ | `--profile=NAME` argument |
| Chat history | ❌ | Per-profile ChatViewModel scope (in-memory, OK) |
| Settings (theme, notifications) | ❌ | Global (not profile-isolated) |

**Note:** Chat history is in-memory per `ChatViewModel` instance and not persisted. This is acceptable — chat is ephemeral context for the LLM agent loop, not a messaging app.

## Default Profiles on First Run

On first launch, create two default profiles:

```kotlin
// In ProfileRepositoryImpl initialization:
suspend fun ensureDefaultProfiles() {
    if (dao.countProfiles() == 0) {
        create("Personal", emoji = "🏠")
        create("AI Agent", emoji = "🤖")
    }
}
```

## Common Mistakes

```kotlin
// ❌ WRONG — using profile name as userId (spaces, special chars)
val userId = UserId.fromString(profile.name)  // "AI Agent" has space!

// ✅ CORRECT — use profile.id (UUID, always safe)
val userId = UserId.fromString("profile:${profile.id}")

// ❌ WRONG — storing apiKey without profile prefix
secureStorage.write("ai_key_openai", key)  // overwrites other profiles

// ✅ CORRECT — profile-prefixed key
secureStorage.write("ai_key_openai:${profile.id}", key)

// ❌ WRONG — global CurrentUser ignoring profile
class CurrentUser { val userId = UserId.anonymous }  // hardcoded!

// ✅ CORRECT — profile-aware
class ProfileAwareCurrentUser(private val profileRepo: ProfileRepository) {
    override val userId: StateFlow<UserId> = profileRepo.active
        .map { UserId.fromString("profile:${it.id}") }
}

// ❌ WRONG — all profiles share same DB path
val dbPath = "~/.singularity-todo/singularity-todo.db"  // same for all!

// ✅ CORRECT — same file, different userId filter (row-level isolation)
// No change to dbPath needed — isolation is at query level via userId
```

## Files Reference

| File | Purpose |
|---|---|
| `shared/src/commonMain/.../feature/profile/Profile.kt` | Domain object + ProfileSettings |
| `shared/src/commonMain/.../feature/profile/domain/port/ProfileRepository.kt` | Interface |
| `shared/src/commonMain/.../feature/profile/ProfileRepositoryImpl.kt` | Room + DataStore implementation |
| `shared/src/commonMain/.../feature/profile/ProfileAwareCurrentUser.kt` | userId mapper |
| `shared/src/commonMain/.../feature/profile/ProfileSwitcherViewModel.kt` | VM for UI |
| `shared/src/commonMain/.../feature/profile/ProfileSwitcherScreen.kt` | Compose screen |
| `mcp-server/src/main/kotlin/.../mcp/Args.kt` | `--profile` argument parsing |
| `shared/src/commonMain/.../core/settings/SettingsRepository.kt` | Per-profile AI settings |

## Related Skills

- `singularity-todo-mcp-server` — `--profile` argument is parsed here.
- `singularity-todo-mcp-init` — `McpInitCommand` uses `profileName` in manifest.
- `singularity-todo-llm-usage-tracking` — `profileId` in `ToolUsageEvent` for per-profile breakdown.
- `singularity-todo-cli-tool-surface` — authorization uses `currentUser.userId` (profile-aware).
- `singularity-todo-room-migration` — profiles table is additive, v7→v8.
- `singularity-todo-koin-dsl` — `ProfileRepository` registered as `single`.
- ADR `2026-09-07-multi-profile-and-usage-tracking` — rationale.

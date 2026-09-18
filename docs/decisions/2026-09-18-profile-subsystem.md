---
title: "Profile subsystem: isolation, ProfileRepository, ProfileAwareCurrentUser"
date: 2026-09-18
status: accepted
tags: [profile, architecture, multi-profile, core]
---

## Context

The app supports multiple isolated data namespaces called **profiles** — e.g. "Personal" and "AI Agent". Each profile owns its own tasks, notes, projects, tags, and LLM usage records.

The challenge is combining two concerns:
1. **Auth session** (`Session.SignedIn.userId`) — identifies the Supabase auth user
2. **Profile ID** (`ProfileId`) — isolates data within that auth user

## Decision

### ProfileRepository (domain layer)

`ProfileRepository` manages the profile lifecycle and active selection:

```kotlin
interface ProfileRepository {
    fun all(): Flow<List<Profile>>
    fun activeProfile(): Flow<Profile>
    val activeProfileId: StateFlow<ProfileId>
    suspend fun create(name: String, emoji: String, colorIdx: Int): ProfileId
    suspend fun update(id: ProfileId, name: String, emoji: String, colorIdx: Int)
    suspend fun delete(id: ProfileId): Result<Unit>
    suspend fun switchTo(id: ProfileId)
    suspend fun getById(id: ProfileId): Profile?
    suspend fun ensureDefaults(extraProfiles: List<Triple<String, String, Int>>)
}
```

Profile records live in **Room** (durable, syncable). The active-profile key lives in **DataStore** (survives DB resets and wipes).

### ProfileAwareCurrentUser (presentation layer)

`ProfileAwareCurrentUser` combines the auth user ID with the active profile:

```kotlin
class ProfileAwareCurrentUser(
    currentUser: CurrentUser,
    profileRepository: ProfileRepository,
    scope: CoroutineScope,
) {
    /** Profile-scoped: "{profileId}/{userId}" or just "userId" for default profile */
    val scopedUserId: StateFlow<UserId>

    /** Raw auth userId */
    val userId: StateFlow<UserId>

    /** Active profile ID */
    val profileId: StateFlow<ProfileId>
}
```

The `scopedUserId` is prepended with the profile ID when not the default profile:
- Default profile: `userId` → `"user_1"`
- Other profile "abc": `userId` → `"abc/user_1"`

This keeps existing data visible while allowing the MCP server to route requests to the correct profile.

### ProfileBootstrapper

On app startup, `ProfileBootstrapper` calls `profileRepository.ensureDefaults()` which:
1. Creates "Personal" (🏠) if no profiles exist
2. Creates "AI Agent" (🤖) on CLI/MCP hosts (for `--profile=ai-agent`)

### Data isolation

All repository queries filter by `userId` (which is `scopedUserId` in presentation VMs):
```kotlin
fun watchTasks(userId: UserId, filter: TaskFilter): Flow<List<Task>>
// Called as: watchTasks(currentUser.scopedUserId.value, filter)
```

## Consequences

- **Positive**: Clean separation of auth (user) vs data namespace (profile)
- **Positive**: MCP server can route to any profile via `--profile=<id>`
- **Negative**: Compound `scopedUserId` is a string manipulation — a proper `ScopedUserId` value class would be cleaner (future work)
- **Negative**: Profile deletion cascades to all that profile's data — no soft-delete for profiles

## Links

- `feature/profile/Profile.kt` — domain model
- `feature/profile/ProfileRepository.kt` — repository interface
- `feature/profile/ProfileAwareCurrentUser.kt` — current-user + profile combination
- `feature/profile/ProfileBootstrapper.kt` — startup initialization

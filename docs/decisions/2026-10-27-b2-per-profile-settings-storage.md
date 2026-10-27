---
status: accepted
title: "Per-profile storage for settings: scoped keys, not per-profile files"
date: 2026-10-27
tags: ["settings", "profile", "architecture"]
---


## Context

Feature 016 ("Plans Timeline and Projects Menu") added a dedicated port for the
menu's project-tree folding, `ProjectsTreeSettingsRepository`, and its first
implementation keys the folded set by the profile-scoped user id inside the
shared `user_settings.preferences_pb` file (`projects.collapsed.<scopedUserId>`).
That is the same per-profile isolation model already used for plans granularity
and the recurring-series repair marker. B2 asks whether that key-scoped model
should be replaced by a separate DataStore file per profile, mirroring the
`profile_state.preferences_pb` split that keeps the active profile out of the
settings file a second process opens.

## Idea

1. **Per-profile files for the tree port only.** Resolve a different
   `DataStore<Preferences>` per profile at runtime, selected on the scoped user
   id, and key the folding inside it without the scoped suffix. This needs a
   cached factory (DataStore instances are not interchangeable per file) and
   changes the repository's constructor.
2. **General migration.** Move every feature settings repository to its own
   per-profile file. Largest blast radius: all settings repositories, the
   settings migration, and tests.
3. **Keep the scoped key.** Retain `projects.collapsed.<scopedUserId>` in the
   shared settings file; document it as the per-profile storage contract for
   settings.

## Decision

We keep the scoped-key model (option 3) and do **not** introduce per-profile
files for settings, including the tree-folding port. The tree port stays keyed by
the profile-scoped user id inside `user_settings.preferences_pb`, exactly as
B1 shipped it. A per-profile file is deferred until a concrete need appears
(see Consequences).

## Rationale

- **Profile switching is runtime.** A per-profile file has to be resolved
  reactively (`flatMapLatest` over the profile/user) and cached by path on both
  platforms, because `PreferenceDataStoreFactory` rejects a second DataStore for
  the same file. That is a real, testable piece of machinery for a value that
  changes only when the user switches profiles — and the switch already has to
  re-derive every scoped repository read anyway.
- **Consistency.** Plans granularity, the recurring-series repair marker, and
  now the tree folding all use the same scoped-key convention. Splitting one of
  them onto a different storage shape would make "where does this setting live"
  answer differently per feature, with no isolation benefit.
- **Process isolation is not a need here.** The reason `profile_state` got its
  own file is that a second process (the MCP tool server) opens the settings
  file for the AI configuration and must not learn which profile the user is in.
  Tree folding is not consumed by that process, so opening the shared settings
  file for it costs nothing.
- **Migration risk.** A general move touches every settings repository and the
  one-shot migration; a narrow move splits the storage shape for one feature.
  Neither buys a property the scoped key does not already provide.

## Consequences

- **Always key a per-profile setting by the profile-scoped user id**
  (`scopedUserIdFor(profileId, userId)`), inside the shared settings file, and
  never by the raw user id alone — otherwise profiles leak into each other.
- **A new settings port may reuse the shared file with a scoped key by default.**
  Splitting it into its own file requires an ADR that names the need (process
  isolation, size, or a consumer that must not open the settings file).
- **If a per-profile file is ever introduced**, it must be resolved through a
  cached factory keyed by file path on both platforms, and the repository must
  switch stores reactively on profile/user change rather than caching one store
  for the lifetime of the ViewModel.
- **Do not migrate the existing settings repositories to per-profile files**
  without a separate ADR; the current contract is the scoped key.

## Links

- Feature 016 — `openspec/changes/archive/2026-10-27-plans-timeline-and-projects-menu/`
- B1 implementation — `shared/.../feature/projects/domain/port/ProjectsTreeSettingsRepository.kt`,
  `.../data/DataStoreProjectsTreeSettingsRepository.kt`
- Precedent for a split file — `shared/.../feature/profile/ActiveProfileState.kt`,
  `profile_state.preferences_pb`
- Related decisions — `2026-09-18-profile-subsystem.md`,
  `2026-10-04-sync-state-keyed-by-owner-and-profile.md`

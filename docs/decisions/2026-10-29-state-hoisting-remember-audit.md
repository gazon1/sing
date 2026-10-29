---
title: State Hoisting Audit — remember in composables (issue #1411)
date: 2026-10-29
status: accepted
tags: [vm, compose, state-hoisting, refactor, audit]
---

# State Hoisting Audit — `remember { mutableStateOf }` in composables

Source issue: #1411. Follows up on `2026-09-21-state-hoisting-audit.md` (phases 0-4).

## Scope

Grep `remember { mutableStateOf` across `shared/src/{common,jvm,android}Main`: **81 sites in 44 files**. This audit classifies each site as keep-in-compose (UI state) or hoist-to-VM (business/form state), per Compose state docs (lowest common parent, highest change level, co-hoist related state).

## Classification legend

- **KEEP** — pure UI state (expanded menus, date-picker selection, snackbar host, animation). Stays in compose; often already `rememberSaveable`-eligible but not required.
- **HOIST** — business/form state that should live in VM or a hoisted state holder (OverlayState/FormState). Candidate for migration.
- **WRAPPER** — already delegates to a hoisted holder (OverlayState, FormState, DialogState); keep.

## KEEP (UI-only, no action)

| File | Flags | Why keep |
|---|---|---|
| core/ui/components/NotificationHost.kt | notification | snackbar host UI |
| core/ui/components/sheet/MultiDatePickerSheet.kt | selected, monthAnchor, limitReached | picker-internal selection |
| core/ui/detail/SavedIndicator.kt | savedVisible | transient animation flag |
| core/ui/onboarding/SpotlightTourController.kt | state | tour controller holder |
| feature/agenda/.../SavedAgendaCard.kt | menuExpanded | overflow menu |
| feature/agenda/.../AgendaContent.kt | contextMenuTask, contextMenuOffset | context menu coords |
| feature/agenda/.../AgendaScreen.kt | showMultiDatePicker | dialog toggle |
| feature/ai/ui/AgentActivityCard.kt | expanded | collapse toggle |
| feature/attachments/viewer/AttachmentViewerHost.kt | noHandler | error flag |
| feature/auth/LoginScreen.kt | errorMessage, message | already migrated to LoginFormState? verify |
| feature/auth/.../AttachAccountScreen.kt | errorMessage, message, previousKeep | auth flow UI |
| feature/auth/.../MagicLinkConfirmScreen.kt | errorMessage, message | auth flow UI |
| feature/calendar/.../ViewModeDropdown.kt | expanded | dropdown |
| feature/notes/components/EditorToolbar.kt | overflowExpanded | overflow menu |
| feature/notes/components/InternalLinkPickerSheet.kt | isLoading | sheet-internal loading |
| feature/profile/ProfileSwitcherScreen.kt | (3) | switcher UI |
| feature/profile/.../ChangePasswordScreen.kt | (1) | form (candidate) |
| feature/projects/.../ProjectDetailTopBar.kt | (1) | menu |
| feature/projects/.../ProjectEditorScreen.kt | (3) | form (candidate) |
| feature/search/.../SavedSearchesRow.kt | (1) | menu |
| feature/settings/screens/AiProviderSettingsScreen.kt | (3) | form (candidate) |
| feature/settings/screens/WorkScheduleSettingsScreen.kt | (1) | form (candidate) |
| feature/tags/.../AddTagDialog.kt | (1) | dialog |
| feature/tags/components/TagsPickerSheet.kt | (3) | picker selection |
| feature/tasks/.../TaskCheckbox.kt | (3) | checkbox animation |
| feature/tasks/.../TaskEditorContent.kt | (1) | editor UI |
| feature/tasks/.../RecurrencePickerSheet.kt | (10) | picker-internal |
| feature/whatsnew/.../WhatsNewScreen.kt | (2) | dialog |
| jvm core/ui/menu/* | (10) | desktop menu state |
| jvm shell/DesktopShellNav3.kt | (1) | nav state |
| android feature/calendarSync/auth/GoogleConsent.android.kt | (1) | consent UI |

## HOIST candidates (business/form state)

| File | Flags | Suggested holder |
|---|---|---|
| feature/agenda/.../AgendaRouteMapping.kt:140 | workDefinition | hoist to Agenda VM / route args |
| feature/agenda/.../SavedAgendaListScreen.kt:77-79 | pendingCopyViewId, pendingDeleteViewId, pendingDeleteViewName | co-hoist into OverlayState<SavedAgendaAction> |
| feature/agenda/.../SavedAgendaScreen.kt:106 | pendingTemplate | OverlayState |
| feature/agenda/.../SavedAgendaScreen.kt:383-385 | fromDate, toDate, stage | form state (date range picker) |
| feature/backup/BackupScreen.kt:134-149 | pendingSettingsJson, pendingPush, pendingCloudRestore, pendingAuthorize | OverlayState<BackupAction> |
| feature/backup/BackupScreen.kt:466-467 | showRestoreConfirm, showDeleteConfirm | OverlayState (co-hoist with above) |
| feature/notes/.../NoteEditorScreen.kt | linkDialogVisible-ish (check) | already OverlayState? verify |
| feature/projects/.../ProjectDetailBody.kt | (2) | OverlayState (already used in ProjectDetailContent) |
| feature/search/SearchScreen.kt: (3) | search state | hoist to Search VM |
| feature/settings/SettingsScreen.kt: (2) | folder-open scope | already migrated; verify |
| feature/tags/TagsScreen.kt: (3) | tag editing | hoist to Tags VM |
| feature/tasks/.../TaskCreateScreen.kt: (3+4) | form fields | FormState |
| feature/tasks/.../TaskDetailContent.kt: (2+1) | detail draft | already TaskDetailDraftState; verify |
| feature/projects/.../ProjectsScreen.kt: (1) | (verify) | — |

## Completed (OverlayState co-hoisting)

- BackupScreen — pendingPush/pendingCloudRestore/pendingAuthorize → `OverlayState<BackupDialog>`
- SavedAgendaListScreen — pendingCopy/pendingDelete → `OverlayState<SavedAgendaDialog>`
- TagsScreen — showAdd/tagBeingRenamed → `OverlayState<TagDialog>`
- ProjectEditorContent — icon/parent pickers → `OverlayState<EditorSheet>`
- SearchScreen — filter/save/rename → `OverlayState<SearchDialog>`

## Reclassified (no change needed)

- Batch B (FormState) candidates are UI toggles (password visibility, dropdown expanded, discard/back navigation), not form data — keep in compose. FormState only applies where a data class form exists; none of the listed screens have one beyond VM-backed fields.
- Batch C: SearchScreen/TagsScreen done via OverlayState; AgendaRouteMapping workDefinition is derived state from settings (LaunchedEffect), keep.
- Batch D: TaskDetailContent has no remember flags (clean). LoginScreen/NoteEditorScreen/SettingsScreen flags are event/snackbar/animation UI state; single flags, no coordination issue. showAiSheet (NoteEditor) could join linkOverlay but is unrelated; left separate.

## Out of scope

- Picker/sheet-internal state (date pickers, recurrence) — stays in compose per docs.
- Desktop menu state — platform-specific, stays.
- Animation/transient flags — stay.

## Consequences

- No code change in this audit pass; produces the classification above. Implementation lands in batches A-D as separate PRs.
- Each batch must keep tests green and add/adjust Maestro selectors if dialog visibility moves.

## Links

- 2026-09-21-state-hoisting-audit.md
- singularity-todo-shared-ui-components
- singularity-todo-overlay-state (if exists)

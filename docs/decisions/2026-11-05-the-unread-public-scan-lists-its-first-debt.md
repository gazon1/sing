# The unread-public scan lists its first debt

**Date:** 2026-11-05

## Idea

The `unread-public` detector (#1916) reports a public declaration that no production
code reads. Its first run over this repository reported 117 of them: constants, computed properties and options that only tests, or
nothing at all, ever read.

## Decision

Every one of them is accepted debt, recorded as an exemption row in
`scripts/find-unwired-surfaces-baseline.txt` with a reason, and listed here. None of
them is deleted in the change that introduced the detector: the detector's job is to
name the debt, and removing a declaration is a change to the feature that owns it.

The advisory floor stays at the residual count — zero blocking findings — because a
symbol that is exempted does not fail the build and must not be counted as if it did.

## Rationale

The floor is a budget for *unargued* findings. Raising it to the number this detector
reports would be a way of saying "these are fine" without saying why, once per
symbol. The exemption file is the place where each of those decisions is written down,
and the backlog reference points at the list below, so a reader can see the whole set
and what would close it.

## Consequences

The gate is green while these declarations are still there. That is the point of the
list: the debt is enumerated, not budgeted. Removing a declaration drops its row and
the count with it.

## The accepted set

| Symbol | File | Finding |
|---|---|---|
| `tracerConfiguration` | `androidApp/src/pro/kotlin/com/singularity/todo/pro/ProSingularityApp.kt` | unread-public — computed property |
| `SECONDS_PER_DAY` | `core-base/src/commonMain/kotlin/com/singularity/todo/core/platform/TimeConstants.kt` | unread-public — constant |
| `droppedAfterClose` | `shared-ui/src/commonMain/kotlin/com/singularity/todo/core/ui/EventBus.kt` | unread-public — computed property |
| `AGENT_TOOL_CALL_CARD` | `shared-ui/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt` | unread-public — constant |
| `AGENT_TOOL_CALL_STATUS` | `shared-ui/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt` | unread-public — constant |
| `AGENT_TOOL_CALL_SUMMARY` | `shared-ui/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt` | unread-public — constant |
| `AGENT_TOOL_CALL_SUMMARY_TOGGLE` | `shared-ui/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt` | unread-public — constant |
| `CALENDAR_WEEKDAY_MON` | `shared-ui/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt` | unread-public — constant |
| `CALENDAR_WEEKDAY_SUN` | `shared-ui/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt` | unread-public — constant |
| `MENU_CALENDAR` | `shared-ui/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt` | unread-public — constant |
| `PIN` | `shared-ui/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt` | unread-public — constant |
| `REMINDER_DEFAULTS_SECTION` | `shared-ui/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt` | unread-public — constant |
| `TASKS_LIST` | `shared-ui/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt` | unread-public — constant |
| `UNPIN` | `shared-ui/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt` | unread-public — constant |
| `isOverflowOpen` | `shared-ui/src/commonMain/kotlin/com/singularity/todo/core/ui/components/OverlayState.kt` | unread-public — computed property |
| `hasPermission` | `shared-ui/src/commonMain/kotlin/com/singularity/todo/feature/calendarSync/presentation/CalendarSyncViewModel.kt` | write-only — option |
| `backlinkCount` | `shared-ui/src/commonMain/kotlin/com/singularity/todo/feature/notes/presentation/viewmodel/NotePreview.kt` | unread-public — computed property |
| `progressFraction` | `shared-ui/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/model/ProjectDetailUi.kt` | unread-public — computed property |
| `ADD_ACCOUNT` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `ADD_TASK` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `APP_BACKGROUNDED` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `APP_FUNCTION_CALL` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `APP_INTENT_CALL` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `APP_OPENED` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `COMPLETE_TASK` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `CONTACT_DEVELOPER` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `CONTENT_PROVIDER_API` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `CONTENT_PROVIDER_ASTRID2` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `CONTENT_PROVIDER_TASKS` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `CREATE_TAG` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `DELETE_LIST` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `DELETE_TAG` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `DOCUMENTATION` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `INITIAL_SYNC_COMPLETE` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `ISSUE_TRACKER` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `MCP_TOOL_USED` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `ONBOARDING_COMPLETE` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `PARAM_ACCESS` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `PARAM_COLLECTION` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `PARAM_FROM_BACKGROUND` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `PARAM_MESSAGE` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `PARAM_PACKAGE` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `PARAM_PERIOD` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `PARAM_PROVIDER` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `PARAM_SELECTION` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `PARAM_SOURCE` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `PARAM_TASK_COUNT` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `PARAM_TIER` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `PARAM_TYPE` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `PERIOD_ANNUAL` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `PERIOD_MONTHLY` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `PRICING_BILLING_TOGGLE` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `PRICING_SIGN_IN_CLICK` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `PRICING_SPONSOR_CLICK` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `PRICING_SUBSCRIBE_CLICK` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `PRIVACY_POLICY` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `RATE_TASKS` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `REDDIT` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `RESTORE_ERROR` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `RESTORE_NOT_SPONSOR` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `RESTORE_SELECTION` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `RESTORE_SPONSOR_CLICK` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `RESTORE_SUCCESS` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `SCREEN_ADD_ACCOUNT` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `SCREEN_PRICING` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `SCREEN_RESTORE_PURCHASES` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `SCREEN_WELCOME` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `SELECTION_GITHUB` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `SELECTION_GOOGLE_PLAY` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `SEND_LOGS` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `SIGNED_IN` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `SIGN_IN` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `SIGN_IN_ERROR` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `SIGN_IN_PROVIDER_SELECTED` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `SORT_CHANGE` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `SOURCE_CODE` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `SOURCE_SETTINGS` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `SYNC_ADD_ACCOUNT` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `SYNC_UNKNOWN_ACCESS` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `THIRD_PARTY_LICENSES` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `TIER_CLOUD` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `TIER_NYP` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `TOS` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `TRIGGERED` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `TWITTER` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `WELCOME` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `WHATS_NEW` | `shared/src/commonMain/kotlin/com/singularity/todo/core/analytics/AnalyticsEvents.kt` | unread-public — constant |
| `hasLocal` | `shared/src/commonMain/kotlin/com/singularity/todo/core/attachments/Attachment.kt` | unread-public — computed property |
| `hasRemote` | `shared/src/commonMain/kotlin/com/singularity/todo/core/attachments/Attachment.kt` | unread-public — computed property |
| `HEADER_BYTES` | `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/ArchiveFormat.kt` | unread-public — constant |
| `HEADER_BYTES_V2` | `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/ArchiveFormat.kt` | unread-public — constant |
| `HEADER_BYTES_V3` | `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/ArchiveFormat.kt` | unread-public — constant |
| `LATEST_VERSION` | `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/ArchiveFormat.kt` | unread-public — constant |
| `stateOrNull` | `shared/src/commonMain/kotlin/com/singularity/todo/core/billing/Entitlement.kt` | unread-public — computed property |
| `isMonthly` | `shared/src/commonMain/kotlin/com/singularity/todo/core/billing/SubscriptionProvider.kt` | unread-public — property |
| `purchaseToken` | `shared/src/commonMain/kotlin/com/singularity/todo/core/billing/SubscriptionProvider.kt` | unread-public — property |
| `TTL_MILLIS` | `shared/src/commonMain/kotlin/com/singularity/todo/core/config/RemoteConfigCacheRepositoryImpl.kt` | unread-public — constant |
| `isLeft` | `shared/src/commonMain/kotlin/com/singularity/todo/core/error/Either.kt` | unread-public — computed property |
| `isRight` | `shared/src/commonMain/kotlin/com/singularity/todo/core/error/Either.kt` | unread-public — computed property |
| `NOTE_PREFIX` | `shared/src/commonMain/kotlin/com/singularity/todo/core/links/InternalLinks.kt` | unread-public — constant |
| `EVENT_SESSION_START` | `shared/src/commonMain/kotlin/com/singularity/todo/core/observability/ToolCallLog.kt` | unread-public — constant |
| `DEFAULT_AI_MODEL` | `shared/src/commonMain/kotlin/com/singularity/todo/core/settings/SettingsRepository.kt` | unread-public — constant |
| `DEFAULT_SYSTEM_PROMPT` | `shared/src/commonMain/kotlin/com/singularity/todo/core/settings/SettingsRepository.kt` | unread-public — constant |
| `EVENT_SCHEMA_VERSION` | `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncContract.kt` | unread-public — constant |
| `PHASE_LONG_BREAK` | `shared/src/commonMain/kotlin/com/singularity/todo/feature/alarms/AlarmContract.kt` | unread-public — constant |
| `PHASE_SHORT_BREAK` | `shared/src/commonMain/kotlin/com/singularity/todo/feature/alarms/AlarmContract.kt` | unread-public — constant |
| `PHASE_WORK` | `shared/src/commonMain/kotlin/com/singularity/todo/feature/alarms/AlarmContract.kt` | unread-public — constant |
| `anchorStart` | `shared/src/commonMain/kotlin/com/singularity/todo/feature/attachments/annotation/AttachmentAnnotationViewModel.kt` | unread-public — computed property |
| `arrayProperty` | `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/catalog/A2uiCatalog.kt` | write-only — option |
| `errorOrNull` | `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/core/A2uiParseOutcome.kt` | unread-public — computed property |
| `eventOrNull` | `shared/src/commonMain/kotlin/com/singularity/todo/feature/genui/core/A2uiParseOutcome.kt` | unread-public — computed property |
| `isLeaf` | `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/Ids.kt` | unread-public — computed property |
| `closedTaskCount` | `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/domain/usecase/CloseProject.kt` | unread-public — property |
| `hasRejectionFeedback` | `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/model/ProposalItem.kt` | unread-public — computed property |
| `MIN_REASON_LENGTH` | `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/domain/usecase/ApplyProposalItemUseCase.kt` | unread-public — constant |
| `containsCycle` | `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/model/TaskDependency.kt` | write-only — option |
| `isOverEstimate` | `shared/src/commonMain/kotlin/com/singularity/todo/feature/timetracking/domain/logic/TimeBucketing.kt` | unread-public — computed property |

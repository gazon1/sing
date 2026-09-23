package com.singularity.todo.core.settings

import com.singularity.todo.core.llm.LlmProvider
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.core.settings.SettingsContributor
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import com.singularity.todo.feature.agenda.SavedAgendaViewId
import kotlinx.serialization.json.Json

/**
 * Imports a settings snapshot from JSON, validates it, and applies each
 * section to the corresponding [SettingsContributor].
 *
 * Schema version: if [SettingsSnapshot.schemaVersion] < [SettingsSnapshot.CURRENT_VERSION],
 * the import is rejected with a clear error. No automatic migration is performed
 * for settings (unlike BackupPayload which has [com.singularity.todo.core.backup.BackupMigrations]).
 *
 * Partial import: if any section fails to apply, the error is collected and
 * a [SettingsImportError.partialFailure] is returned listing the failed sections.
 */
open class SettingsImporter(private val contributors: Set<SettingsContributor<*, *>>) {
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface ImportResult {
        data object Success : ImportResult
        data class SchemaTooOld(val snapshotVersion: Int, val currentVersion: Int) : ImportResult
        data class ParseError(val message: String) : ImportResult
        data class PartialFailure(val failures: List<String>) : ImportResult
    }

    /**
     * Parses [jsonString] and applies each section to the matching contributor.
     *
     * 1. Parse JSON → [SettingsSnapshot].
     * 2. Validate schema version.
     * 3. For each section: dispatch the appropriate [SettingsIntent] to its contributor.
     * 4. Return [ImportResult].
     */
    open suspend fun importFromJson(jsonString: String): ImportResult {
        val snapshot: SettingsSnapshot = runCatching {
            json.decodeFromString(SettingsSnapshot.serializer(), jsonString)
        }.getOrElse { e ->
            return ImportResult.ParseError(e.message ?: "Failed to parse settings JSON")
        }

        if (snapshot.schemaVersion < SettingsSnapshot.CURRENT_VERSION) {
            return ImportResult.SchemaTooOld(
                snapshotVersion = snapshot.schemaVersion,
                currentVersion = SettingsSnapshot.CURRENT_VERSION,
            )
        }

        val failures = mutableListOf<String>()

        // Appearance
        runCatching {
            val contributor = contributors
                .filterIsInstance<SettingsContributor<SettingsSection.Appearance, SettingsIntent.Appearance>>()
                .firstOrNull()
            contributor?.process(SettingsIntent.Appearance.UpdateDarkTheme(snapshot.appearance.darkTheme))
            contributor?.process(SettingsIntent.Appearance.UpdateAccentColor(snapshot.appearance.accentColor))
            contributor?.process(SettingsIntent.Appearance.UpdateFontSizeScale(snapshot.appearance.fontSizeScale))
        }.onFailure { e ->
            failures.add("Appearance: ${e.message ?: e}")
        }

        // AI
        runCatching {
            val contributor = contributors
                .filterIsInstance<SettingsContributor<SettingsSection.Ai, SettingsIntent.Ai>>()
                .firstOrNull()
            contributor?.process(SettingsIntent.Ai.UpdateProvider(LlmProvider.fromId(snapshot.ai.providerId)))
            contributor?.process(SettingsIntent.Ai.UpdateBaseUrl(snapshot.ai.baseUrl))
            contributor?.process(SettingsIntent.Ai.UpdateModel(snapshot.ai.model))
            contributor?.process(SettingsIntent.Ai.UpdateSystemPrompt(snapshot.ai.systemPrompt))
        }.onFailure { e ->
            failures.add("AI: ${e.message ?: e}")
        }

        // Notifications
        runCatching {
            val contributor = contributors
                .filterIsInstance<SettingsContributor<SettingsSection.Notifications, SettingsIntent.Notifications>>()
                .firstOrNull()
            contributor?.process(SettingsIntent.Notifications.UpdateEnabled(snapshot.notifications.enabled))
            contributor?.process(SettingsIntent.Notifications.UpdateSound(snapshot.notifications.sound))
            contributor?.process(SettingsIntent.Notifications.UpdateVibration(snapshot.notifications.vibration))
            val offset = ReminderOffset.entries.find { it.minutes == snapshot.notifications.reminderDefaultMinutes }
                ?: ReminderOffset.AT_DUE
            contributor?.process(SettingsIntent.Notifications.UpdateReminderDefault(offset))
        }.onFailure { e ->
            failures.add("Notifications: ${e.message ?: e}")
        }

        // Work Schedule
        runCatching {
            val contributor = contributors
                .filterIsInstance<SettingsContributor<SettingsSection.WorkSchedule, SettingsIntent.WorkSchedule>>()
                .firstOrNull()
            contributor?.process(SettingsIntent.WorkSchedule.UpdateWorkDayStart(snapshot.workSchedule.dayStartMinutes))
            contributor?.process(SettingsIntent.WorkSchedule.UpdateWorkDayEnd(snapshot.workSchedule.dayEndMinutes))
            contributor?.process(
                SettingsIntent.WorkSchedule.UpdateWorkLunchStart(snapshot.workSchedule.lunchStartMinutes),
            )
            contributor?.process(SettingsIntent.WorkSchedule.UpdateWorkLunchEnd(snapshot.workSchedule.lunchEndMinutes))
            contributor?.process(SettingsIntent.WorkSchedule.UpdateWeekendSat(snapshot.workSchedule.weekendSat))
            contributor?.process(SettingsIntent.WorkSchedule.UpdateWeekendSun(snapshot.workSchedule.weekendSun))
        }.onFailure { e ->
            failures.add("Work Schedule: ${e.message ?: e}")
        }

        // Greeting
        runCatching {
            val contributor = contributors
                .filterIsInstance<SettingsContributor<SettingsSection.Greeting, SettingsIntent.Greeting>>()
                .firstOrNull()
            contributor?.process(SettingsIntent.Greeting.UpdateMorningEnd(snapshot.greeting.morningEndHour))
            contributor?.process(SettingsIntent.Greeting.UpdateAfternoonEnd(snapshot.greeting.afternoonEndHour))
        }.onFailure { e ->
            failures.add("Greeting: ${e.message ?: e}")
        }

        // Default Agenda View
        runCatching {
            val contributor = contributors
                .filterIsInstance<SettingsContributor<SettingsSection.DefaultAgendaView, SettingsIntent.DefaultAgendaView>>()
                .firstOrNull()
            val viewId = snapshot.defaultAgendaView.viewId?.let { SavedAgendaViewId.fromString(it) }
            contributor?.process(SettingsIntent.DefaultAgendaView.Update(viewId))
        }.onFailure { e ->
            failures.add("Default Agenda View: ${e.message ?: e}")
        }

        return if (failures.isEmpty()) {
            ImportResult.Success
        } else {
            ImportResult.PartialFailure(failures)
        }
    }
}

package com.singularity.todo.core.settings

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.isoDayNumber

/**
 * Exports the current settings as a JSON string.
 *
 * Reads all contributor sections and assembles them into a [SettingsSnapshot].
 * Ephemeral state (AI test results, model list) is NOT included —
 * it is runtime-only and must be re-established by the user after import.
 *
 * Used by [com.singularity.todo.feature.backup.BackupScreen] to produce a
 * settings-only snapshot (no tasks/notes/projects) for sharing.
 */
open class SettingsExporter(private val contributors: Set<SettingsContributor<*, *>>) {
    private val json = Json {
        encodeDefaults = true
        prettyPrint = true
    }

    /**
     * Reads current values from all contributor observe() flows and returns
     * a JSON string representation of the complete settings snapshot.
     *
     * Returns a JSON string on success, or an exception on failure.
     */
    open suspend fun exportAsJson(): String {
        val appearance = contributors.filterIsInstance<SettingsContributor<SettingsSection.Appearance, *>>()
            .firstOrNull()
            ?.observe()
            ?.first()
            ?: SettingsSection.Appearance()

        val ai = contributors.filterIsInstance<SettingsContributor<SettingsSection.Ai, *>>()
            .firstOrNull()
            ?.observe()
            ?.first()
            ?: SettingsSection.Ai()

        val notifications = contributors.filterIsInstance<SettingsContributor<SettingsSection.Notifications, *>>()
            .firstOrNull()
            ?.observe()
            ?.first()
            ?: SettingsSection.Notifications()

        val workSchedule = contributors.filterIsInstance<SettingsContributor<SettingsSection.WorkSchedule, *>>()
            .firstOrNull()
            ?.observe()
            ?.first()
            ?: SettingsSection.WorkSchedule()

        val greeting = contributors.filterIsInstance<SettingsContributor<SettingsSection.Greeting, *>>()
            .firstOrNull()
            ?.observe()
            ?.first()
            ?: SettingsSection.Greeting()

        val defaultAgendaView =
            contributors.filterIsInstance<SettingsContributor<SettingsSection.DefaultAgendaView, *>>()
                .firstOrNull()
                ?.observe()
                ?.first()
                ?: SettingsSection.DefaultAgendaView()

        val snapshot = SettingsSnapshot(
            schemaVersion = SettingsSnapshot.CURRENT_VERSION,
            appearance = AppearanceSnapshot(
                themeMode = appearance.themeMode.toStorageString(),
                accentColor = appearance.accentColor,
                fontSizeScale = appearance.fontSizeScale,
            ),
            ai = AiSnapshot(
                providerId = ai.provider.id,
                baseUrl = ai.baseUrl.text,
                model = ai.model.text,
                systemPrompt = ai.systemPrompt.text,
            ),
            notifications = NotificationsSnapshot(
                enabled = notifications.enabled,
                sound = notifications.sound,
                vibration = notifications.vibration,
                reminderDefaultMinutes = notifications.reminderDefault.minutes,
            ),
            workSchedule = WorkScheduleSnapshot(
                dayStartMinutes = workSchedule.dayStartMinutes,
                dayEndMinutes = workSchedule.dayEndMinutes,
                lunchStartMinutes = workSchedule.lunchStartMinutes,
                lunchEndMinutes = workSchedule.lunchEndMinutes,
                // The working days travel as the set they are. The two flags
                // travel next to them, derived from the set, so an older app
                // can still read the file and a newer one can still read an
                // older file.
                workingDays = workSchedule.workingDays
                    .sortedBy { it.isoDayNumber }
                    .map { it.isoDayNumber },
                weekendSat = DayOfWeek.SATURDAY in workSchedule.workingDays,
                weekendSun = DayOfWeek.SUNDAY in workSchedule.workingDays,
            ),
            greeting = GreetingSnapshot(
                morningEndHour = greeting.morningEndHour,
                afternoonEndHour = greeting.afternoonEndHour,
            ),
            defaultAgendaView = DefaultAgendaViewSnapshot(
                viewId = defaultAgendaView.viewId?.raw,
            ),
        )

        return json.encodeToString(SettingsSnapshot.serializer(), snapshot)
    }
}

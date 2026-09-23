package com.singularity.todo.core.config

import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.version.AppVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Remote configuration snapshot returned by [RemoteConfigPort].
 *
 * Produced by [RemoteConfigPort.snapshot] and [RemoteConfigPort.refresh].
 * Stored locally in Room (`remote_config_cache` table) so the app can
 * operate with the last-known-good values when offline.
 *
 * @see RemoteConfigPort
 */
@Serializable
data class RemoteConfigSnapshot(
    val schemaVersion: Int,
    val fetchedAtEpochMillis: Long,
    val minSupportedVersion: AppVersion?,
    val maintenanceBanner: BannerDto?,
    val whatsNewPayload: String? = null,
    /** Play In-App Update priority 0–10. Values ≥ 4 trigger immediate flexible update flow. */
    val updatePriority: Int? = null,
    /**
     * Which app store to use for in-app updates.
     * Defaults to [UpdateStoreType.GOOGLE_PLAY].
     *
     * When set to [UpdateStoreType.DIRECT_URL], [updateStoreUrl] is used instead.
     */
    val updateStoreType: UpdateStoreType = UpdateStoreType.GOOGLE_PLAY,
    /**
     * Override URL opened when [updateStoreType] is [UpdateStoreType.DIRECT_URL].
     * For Google Play use: `https://play.google.com/store/apps/details?id=...`.
     * For RuStore use: `https://rustore.ru/app/...`.
     * For Samsung Galaxy Store: `https://galaxystore.samsung.com/...`.
     */
    val updateStoreUrl: String? = null,
    val modelFlags: Map<String, Boolean> = emptyMap(),
    val mcpToolFlags: Map<String, Boolean> = emptyMap(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        /**
         * Validates [json] and returns a [RemoteConfigSnapshot] if valid.
         *
         * Returns null if the schema version is missing, too new, or decoding fails.
         */
        fun validate(json: JsonObject): RemoteConfigSnapshot? {
            val schemaVersion = json["schemaVersion"]?.jsonPrimitive?.intOrNull
            if (schemaVersion == null || schemaVersion > CURRENT_SCHEMA_VERSION) {
                return null
            }

            return try {
                StableJson.decodeFromString(
                    RemoteConfigSnapshot.serializer(),
                    json.toString(),
                )
            } catch (e: Throwable) {
                null
            }
        }

        /**
         * Returns a default snapshot used when no cached config exists
         * and the network is unavailable.
         */
        fun defaults(): RemoteConfigSnapshot = RemoteConfigSnapshot(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            fetchedAtEpochMillis = 0L,
            minSupportedVersion = null,
            maintenanceBanner = null,
            modelFlags = emptyMap(),
            mcpToolFlags = emptyMap(),
        )
    }

    /**
     * Returns the effective version gate: if [minSupportedVersion] is null,
     * no gate is active.
     */
    fun minVersion(): AppVersion? = minSupportedVersion
}

/**
 * Maintenance or informational banner rendered from remote config.
 *
 * [text] is the banner body. [severity] determines the visual tone.
 * [actionUrl] optionally links to a page (e.g. status website).
 * [actionLabel] is the label for the link (ignored if [actionUrl] is null).
 *
 * Rendered as a dismissible top banner in [SettingsScreen].
 * Dismissal is stored in DataStore per profile and does not survive a fresh install.
 */
@Serializable
data class BannerDto(
    val text: String,
    val severity: BannerSeverity = BannerSeverity.Info,
    val actionUrl: String? = null,
    val actionLabel: String? = null,
)

@Serializable
enum class BannerSeverity {
    Info,
    Warning,
    Critical,
}

/**
 * Supported app stores for in-app update flows.
 *
 * Used by [RemoteConfigSnapshot.updateStoreType] to select the active
 * [com.singularity.todo.update.UpdateStorePort] implementation.
 */
@Serializable
enum class UpdateStoreType {
    /** Google Play — flexible in-app update via Play Core library. */
    GOOGLE_PLAY,

    /** RuStore — in-app update via RuStore SDK. */
    RUSTORE,

    /** Samsung Galaxy Store — in-app update via Samsung Apps SDK. */
    SAMSUNG,

    /**
     * No in-app update SDK available.
     * Falls back to opening [RemoteConfigSnapshot.updateStoreUrl] in a browser
     * via [android.content.Intent.ACTION_VIEW].
     */
    DIRECT_URL,
}

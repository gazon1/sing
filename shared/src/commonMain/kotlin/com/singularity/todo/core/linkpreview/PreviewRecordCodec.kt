package com.singularity.todo.core.linkpreview

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * How one preview is written into the preferences store, and read back.
 *
 * ## Why this is its own type
 *
 * The cache's job is *policy* — what is fresh, what is evicted, what an image belongs
 * to. Turning an entry into a string and back is a separate job, and keeping it apart
 * means the policy is not read through a JSON reader on every other line.
 *
 * ## Why JSON
 *
 * The value is four nullable strings and a timestamp. A preferences store holds strings,
 * a hand-rolled delimiter format would have to escape whatever characters the strings
 * happen to contain — a page may put a newline or a `|` in a title — and JSON already
 * answers that. `ignoreUnknownKeys` means a field added by a later build does not make
 * an older one drop the whole entry.
 *
 * ## Why the key is not stored
 *
 * The address is the preferences key, so it is not repeated inside the value: one copy
 * is one thing to keep consistent.
 */
internal class PreviewRecordCodec {
    private val json = Json { ignoreUnknownKeys = true }

    /** The preferences key a preview is stored under. */
    fun entryKey(urlKey: String) = stringPreferencesKey("$ENTRY_PREFIX$urlKey")

    /** The preferences key a dismissal is stored under. */
    fun hiddenKey(urlKey: String) = stringPreferencesKey("$HIDDEN_PREFIX$urlKey")

    /** Whether [key] is one of this codec's entry keys. */
    fun isEntryKey(key: Preferences.Key<*>): Boolean = key.name.startsWith(ENTRY_PREFIX)

    /** Whether [key] is one of this codec's dismissal keys. */
    fun isHiddenKey(key: Preferences.Key<*>): Boolean = key.name.startsWith(HIDDEN_PREFIX)

    /** The address part of an entry key, for an entry that is about to be evicted. */
    fun urlKeyOf(key: Preferences.Key<*>): String = key.name.removePrefix(ENTRY_PREFIX)

    /** The stored form of [preview]. */
    fun encode(preview: LinkPreview): String =
        buildJsonObject {
            put("url", JsonPrimitive(preview.url))
            preview.siteName?.let { put("site", JsonPrimitive(it)) }
            put("title", JsonPrimitive(preview.title))
            preview.description?.let { put("description", JsonPrimitive(it)) }
            preview.imageUrl?.let { put("image", JsonPrimitive(it)) }
            put("fetchedAt", JsonPrimitive(preview.fetchedAt))
        }.toString()

    /** The preview [raw] holds, or null when it is not one this build understands. */
    fun decode(raw: String): LinkPreview? =
        runCatching {
            val obj: JsonObject = json.parseToJsonElement(raw).jsonObject
            LinkPreview(
                url = obj["url"]?.jsonPrimitive?.content ?: return@runCatching null,
                siteName = obj["site"]?.jsonPrimitive?.content,
                title = obj["title"]?.jsonPrimitive?.content.orEmpty(),
                description = obj["description"]?.jsonPrimitive?.content,
                imageUrl = obj["image"]?.jsonPrimitive?.content,
                fetchedAt = obj["fetchedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }.getOrNull()

    /**
     * A file-system-safe name for [urlKey].
     *
     * The address itself contains `/`, `?` and `:`; a hash of it is one opaque name that
     * cannot escape the image directory, and the entry holds the address the file
     * belongs to, so nothing is lost.
     */
    fun fileName(urlKey: String): String {
        var result = 0L
        for (char in urlKey) result = result * 31 + char.code
        return result.toULong().toString(16)
    }

    private companion object {
        const val ENTRY_PREFIX = "link_preview.v1."
        const val HIDDEN_PREFIX = "link_preview.hidden."
    }
}

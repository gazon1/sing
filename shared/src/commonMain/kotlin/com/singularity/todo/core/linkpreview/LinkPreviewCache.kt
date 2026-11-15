package com.singularity.todo.core.linkpreview

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.datastore.catchDataStoreIoError
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.files.FileSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The preview cache: one entry per address, and one image per entry that has one.
 *
 * ## Why the address is the key, not the task
 *
 * Two tasks can quote the same article, and one task's title is edited while it is
 * open. Keying by address means the second task costs nothing, and an edit that
 * keeps the address reuses the preview instead of fetching the page again — which
 * is the whole reason this is cached rather than fetched on every opening.
 *
 * ## What is stored, and what is not
 *
 * The four fields the card renders, plus when they were read. **No HTML**: the page
 * body is parsed and dropped, and what survives is what the page said about
 * itself. An image is the one thing kept as bytes, on disk, because it is the thing
 * that is not re-derivable from the rest of the entry.
 *
 * ## Expiry
 *
 * An entry older than [TTL_MILLIS] is stale but is still shown while it is refreshed,
 * so a page that is briefly unreachable does not blank a card that was working. The
 * refresh happens at most once per [REFRESH_AFTER_MILLIS] per address, which is what
 * keeps navigation from re-fetching: opening the same task twice in a minute reads
 * the cache twice and fetches once.
 *
 * ## Dismissal belongs to the address
 *
 * Hiding a preview hides *that address*, not that task. The card is a property of
 * "task with this link in its title", and the same link in another task is the same
 * thing the user already dismissed. It is also why dismissing writes nothing to the
 * task: the title keeps its address, and the address remains tappable — the
 * requirement that a hidden preview must not change the title is structural here,
 * because nothing here can reach a task row.
 *
 * @param dataStore the settings store. A preview is a few hundred bytes of text and
 *   belongs there rather than in a table of its own: a table would need a migration,
 *   a profile question and a sync decision for data that is derived and disposable.
 * @param fileSystem where an image is kept.
 * @param imageDir the directory images are written into, resolved by the graph.
 * @param clock the injected clock, so expiry is testable.
 */
class LinkPreviewCache(
    private val dataStore: DataStore<Preferences>,
    private val fileSystem: FileSystem,
    private val imageDir: String,
    private val clock: kotlin.time.Clock,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The cached entry for [key], or null when there is none. Never fetches. */
    fun observe(key: String): Flow<LinkPreview?> =
        dataStore.data
            .catchDataStoreIoError()
            .map { prefs -> prefs[entryKey(key)]?.let(::decode) }

    /** Whether [key]'s preview was dismissed. */
    fun observeHidden(key: String): Flow<Boolean> =
        dataStore.data
            .catchDataStoreIoError()
            .map { prefs -> prefs[hiddenKey(key)]?.let { it == HIDDEN } ?: false }

    /** Whether there is a stored entry at all, stale or fresh. */
    suspend fun cached(key: String): LinkPreview? = observe(key).first()

    /** Whether the entry for [key] was read recently enough to leave alone. */
    suspend fun isFresh(key: String): Boolean {
        val entry = cached(key) ?: return false
        return clock.now().toEpochMilliseconds() - entry.fetchedAt < REFRESH_AFTER_MILLIS
    }

    /**
     * Stores [preview] under its own key, replacing any previous entry.
     *
     * @return the addresses evicted to stay under [MAX_ENTRIES], so the caller can drop
     *   the images stored beside them. Empty in the ordinary case — the cache holds far
     *   fewer addresses than the limit.
     */
    suspend fun put(preview: LinkPreview): List<String> {
        var evicted = emptyList<String>()
        dataStore.edit { prefs ->
            prefs[entryKey(PreviewUrls.keyOf(preview.url))] = encode(preview)
            evicted = evictOldest(prefs)
        }
        return evicted
    }

    /**
     * Drops the least recently read entries once the cache is over [MAX_ENTRIES].
     *
     * A bound rather than an expiry alone: a preview is only re-read when a task names
     * the address, so an entry nothing points at would otherwise sit in the store
     * forever. The limit is generous — the value is a few hundred bytes — and is there
     * so the store cannot grow without one.
     *
     * @return the addresses whose entries were removed.
     */
    private fun evictOldest(prefs: androidx.datastore.preferences.core.MutablePreferences): List<String> {
        val entries =
            prefs
                .asMap()
                .filterKeys { it.name.startsWith(ENTRY_PREFIX) }
                .mapNotNull { (key, value) ->
                    val decoded = (value as? String)?.let(::decode) ?: return@mapNotNull null
                    decoded.url to decoded.fetchedAt
                }
        if (entries.size <= MAX_ENTRIES) return emptyList()
        val dropped = entries.sortedBy { it.second }.take(entries.size - MAX_ENTRIES)
        dropped.forEach { (url, _) -> prefs.remove(entryKey(PreviewUrls.keyOf(url))) }
        return dropped.map { it.first }
    }

    /**
     * Stores the image bytes for [key] and returns the local path.
     *
     * The path is derived from the key rather than stored: one image per address, and
     * a re-fetch overwrites it, so a stale file cannot outlive the entry it belongs
     * to and nothing has to track it separately.
     */
    suspend fun putImage(
        key: String,
        bytes: ByteArray,
    ): String =
        runCatchingCancellable {
            fileSystem.ensureDir(imageDir)
            val path = imagePath(key)
            fileSystem.writeBytes(path, bytes)
            path
        }.getOrThrow()

    /** The local image for [key], or null when none was stored. */
    suspend fun imagePathOrNull(key: String): String? =
        runCatchingCancellable {
            val path = imagePath(key)
            if (fileSystem.exists(path)) path else null
        }.getOrNull()

    /**
     * Where [key]'s image would be.
     *
     * Pure and stable, so a reader that only wants to *try* loading an image does not
     * pay for an existence check on every emission — the image loader reports a missing
     * file as a failed load, which is the same answer.
     */
    fun imagePath(key: String): String = "$imageDir/${hash(key)}.img"

    /** Records that the user dismissed [key]'s preview. */
    suspend fun hide(key: String) {
        dataStore.edit { prefs -> prefs[hiddenKey(key)] = HIDDEN }
    }

    /**
     * Drops the stored entry for [key], and its image, when [key] is no longer the
     * address the task points at.
     *
     * Called after a title edit so a link that was removed leaves nothing behind. It
     * removes exactly one address: another task may still show the same page, and its
     * entry is not this task's to drop.
     */
    suspend fun forget(key: String) {
        dataStore.edit { prefs -> prefs.remove(entryKey(key)) }
        runCatchingCancellable { fileSystem.delete(imagePath(key)) }
    }

    private fun entryKey(key: String) = stringPreferencesKey("$ENTRY_PREFIX$key")

    private fun hiddenKey(key: String) = stringPreferencesKey("$HIDDEN_PREFIX$key")

    private fun encode(preview: LinkPreview): String =
        buildJsonObject {
            put("url", JsonPrimitive(preview.url))
            preview.siteName?.let { put("site", JsonPrimitive(it)) }
            put("title", JsonPrimitive(preview.title))
            preview.description?.let { put("description", JsonPrimitive(it)) }
            preview.imageUrl?.let { put("image", JsonPrimitive(it)) }
            put("fetchedAt", JsonPrimitive(preview.fetchedAt))
        }.toString()

    private fun decode(raw: String): LinkPreview? =
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
     * A file-system-safe name for [key].
     *
     * The address itself contains `/`, `?` and `:`; a hash of it is one opaque name
     * that cannot escape the image directory, and the entry holds the address the
     * file belongs to, so nothing is lost.
     */
    private fun hash(key: String): String {
        var result = 0L
        for (char in key) result = result * 31 + char.code
        return result.toULong().toString(16)
    }

    companion object {
        /** How long an entry is trusted before it is refreshed in the background. */
        const val REFRESH_AFTER_MILLIS: Long = 6 * 60 * 60 * 1000L

        /** How many bytes of image a preview accepts. A card is a few hundred px wide. */
        const val MAX_IMAGE_BYTES: Int = 512 * 1024

        /** How many bytes of HTML are read before parsing metadata out of it. */
        const val MAX_PAGE_BYTES: Int = 512 * 1024

        /**
         * How many addresses the cache keeps.
         *
         * Far more than a person keeps in titles, and small enough that a store never
         * meant to hold a table cannot grow into one.
         */
        const val MAX_ENTRIES: Int = 500

        private const val ENTRY_PREFIX = "link_preview.v1."
        private const val HIDDEN_PREFIX = "link_preview.hidden."
        private const val HIDDEN = "1"
    }
}

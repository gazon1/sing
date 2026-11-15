package com.singularity.todo.core.linkpreview

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.singularity.todo.core.datastore.catchDataStoreIoError
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.files.FileSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The preview cache: one entry per address, and one image per entry that has one.
 *
 * ## Why the address is the key, not the task
 *
 * Two tasks can quote the same article, and one task's title is edited while it is
 * open. Keying by address means the second task costs nothing, and an edit that keeps
 * the address reuses the preview instead of fetching the page again — which is the
 * whole reason this is cached rather than fetched on every opening.
 *
 * ## What is stored, and what is not
 *
 * The four fields the card renders, plus when they were read. **No HTML**: the page body
 * is parsed and dropped, and what survives is what the page said about itself. An image
 * is the one thing kept as bytes, on disk, because it is the thing that is not
 * re-derivable from the rest of the entry. Every field is cut to what the card can hold
 * before it is written — a page is untrusted input, and its own ideas about how long a
 * description may be are not this store's problem. The stored form itself is
 * [PreviewRecordCodec]'s business, not this class's.
 *
 * ## Expiry
 *
 * An entry older than [REFRESH_AFTER_MILLIS] is stale but is still shown while it is
 * refreshed, so a page that is briefly unreachable does not blank a card that was
 * working. The refresh happens at most once per [REFRESH_AFTER_MILLIS] per address,
 * which is what keeps navigation from re-fetching: opening the same task twice in a
 * minute reads the cache twice and fetches once.
 *
 * ## The image belongs to the address the page named
 *
 * A refresh may name a different picture. The old file is deleted when that happens, so
 * a card cannot show yesterday's picture beside today's title.
 *
 * ## Dismissal belongs to the address
 *
 * Hiding a preview hides *that address*, not that task. The card is a property of "task
 * with this link in its title", and the same link in another task is the same thing the
 * user already dismissed. It is also why dismissing writes nothing to the task: the
 * title keeps its address, and the address remains tappable — the requirement that a
 * hidden preview must not change the title is structural here, because nothing here can
 * reach a task row.
 *
 * @param dataStore the settings store. A preview is a few hundred bytes of text and
 *   belongs there rather than in a table of its own: a table would need a migration, a
 *   profile question and a sync decision for data that is derived and disposable.
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
    /**
     * What storing a preview displaced.
     *
     * @property evicted the addresses whose entries were dropped to stay under the
     *   bound, so the caller can drop the images stored beside them. Empty in the
     *   ordinary case.
     */
    data class PutOutcome(val evicted: List<String>)

    private val codec = PreviewRecordCodec()

    /** The cached entry for [key], or null when there is none. Never fetches. */
    fun observe(key: String): Flow<LinkPreview?> =
        dataStore.data
            .catchDataStoreIoError()
            .map { prefs -> prefs[codec.entryKey(key)]?.let(codec::decode) }

    /** Whether [key]'s preview was dismissed. */
    fun observeHidden(key: String): Flow<Boolean> =
        dataStore.data
            .catchDataStoreIoError()
            .map { prefs -> prefs[codec.hiddenKey(key)] != null }

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
     * When the new entry names a different picture than the old one, the old file is
     * deleted here: the entry is the only thing that knows which picture it means, and a
     * file nothing points at is a file that keeps being shown.
     */
    suspend fun put(preview: LinkPreview): PutOutcome {
        var evicted = emptyList<String>()
        var staleImage = false
        val key = PreviewUrls.keyOf(preview.url)
        dataStore.edit { prefs ->
            val previous = prefs[codec.entryKey(key)]?.let(codec::decode)
            prefs[codec.entryKey(key)] = codec.encode(preview.clamped())
            staleImage = previous != null && previous.imageUrl != preview.imageUrl
            evicted = evictOldest(prefs)
        }
        if (staleImage) runCatchingCancellable { fileSystem.delete(imagePath(key)) }
        return PutOutcome(evicted)
    }

    /**
     * Drops the oldest records once there are more than [MAX_ENTRIES].
     *
     * A bound rather than an expiry alone: a preview is only re-read when a task names
     * the address, so an entry nothing points at would otherwise sit in the store
     * forever. The dismissed records are bounded the same way — a flag is small, but a
     * person who dismissed many previews over a year would still carry every one.
     *
     * @return the addresses whose entries were removed.
     */
    private fun evictOldest(prefs: MutablePreferences): List<String> {
        val entries =
            prefs
                .asMap()
                .filterKeys { codec.isEntryKey(it) }
                .mapNotNull { (key, value) ->
                    val decoded = (value as? String)?.let(codec::decode) ?: return@mapNotNull null
                    decoded.url to decoded.fetchedAt
                }
        val dropped = entries.sortedBy { it.second }.take((entries.size - MAX_ENTRIES).coerceAtLeast(0))
        dropped.forEach { (url, _) -> prefs.remove(codec.entryKey(PreviewUrls.keyOf(url))) }
        evictOldestDismissals(prefs)
        return dropped.map { it.first }
    }

    /** Drops dismissal records past the bound; see [evictOldest]. */
    private fun evictOldestDismissals(prefs: MutablePreferences) {
        val hidden = prefs.asMap().keys.filter { codec.isHiddenKey(it) }
        if (hidden.size <= MAX_ENTRIES) return
        // There is no timestamp on a dismissal; the key order is the only order there is,
        // and this bound is loose enough that it does not matter which end goes.
        hidden
            .sortedBy { it.name }
            .take(hidden.size - MAX_ENTRIES)
            .forEach { prefs.remove(it) }
    }

    /**
     * [preview] with each field cut to a size the card can hold.
     *
     * A page is untrusted input, and a title is not bounded by the standard: a page may
     * state a description of a megabyte, and storing it would put a megabyte in the
     * settings file for a card that shows two lines. The limits are generous relative to
     * what the card renders, so trimming is invisible in ordinary use.
     */
    private fun LinkPreview.clamped(): LinkPreview =
        copy(
            title = title.take(MAX_TITLE_CHARS),
            siteName = siteName?.take(MAX_SITE_CHARS),
            description = description?.take(MAX_DESCRIPTION_CHARS),
            imageUrl = imageUrl?.take(MAX_URL_CHARS),
        )

    /**
     * Stores the image bytes for [key] and returns the local path.
     *
     * The path is derived from the key rather than stored: one image per address, and a
     * re-fetch overwrites it, so a stale file cannot outlive the entry it belongs to and
     * nothing has to track it separately.
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
     * Pure and stable, so a reader that only wants to *try* loading an image does not pay
     * for an existence check on every emission — the image loader reports a missing file
     * as a failed load, which is the same answer.
     */
    fun imagePath(key: String): String = "$imageDir/${codec.fileName(key)}.img"

    /** Records that the user dismissed [key]'s preview. */
    suspend fun hide(key: String) {
        dataStore.edit { prefs -> prefs[codec.hiddenKey(key)] = HIDDEN }
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
        dataStore.edit { prefs -> prefs.remove(codec.entryKey(key)) }
        runCatchingCancellable { fileSystem.delete(imagePath(key)) }
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

        /** A title the card renders in two lines; anything more is not shown anyway. */
        const val MAX_TITLE_CHARS: Int = 300

        /** A site name is a name, not a sentence. */
        const val MAX_SITE_CHARS: Int = 120

        /** A description the card renders in two lines. */
        const val MAX_DESCRIPTION_CHARS: Int = 400

        /** An address longer than this is not one a page would usefully name. */
        const val MAX_URL_CHARS: Int = 2048

        private const val HIDDEN = "1"
    }
}

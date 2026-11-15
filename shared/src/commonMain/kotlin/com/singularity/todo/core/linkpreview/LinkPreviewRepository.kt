package com.singularity.todo.core.linkpreview

import com.singularity.todo.core.error.runCatchingCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * What the screen reads: the preview state of one address, and the way to dismiss it.
 *
 * A repository rather than a use case because there is nothing to decide and nothing
 * to write to the domain: the value is derived, cached and disposable, and no task,
 * project or note is touched by anything here.
 */
interface LinkPreviewRepository {
    /**
     * The preview of [url], as the cache holds it.
     *
     * Reads the cache and never fetches. Reading is what a recomposition and a
     * navigation do, and both must be free.
     */
    fun observe(url: String?): Flow<LinkPreviewState>

    /**
     * Produces the preview of [url], fetching the page when the cache has nothing
     * recent.
     *
     * Safe to call on every opening: a fresh entry is returned without a request, and
     * a failure keeps whatever the cache already held rather than blanking it.
     */
    suspend fun refresh(url: String): LinkPreviewState

    /** Dismisses the preview of [url] until the user asks for it again. */
    suspend fun hide(url: String)

    /** Forgets a cached address, for a title edit that no longer points at it. */
    suspend fun forget(url: String)
}

/**
 * The one implementation, over [PageSource] and [LinkPreviewCache].
 *
 * ## Where the fetching happens
 *
 * On [dispatcher], never on the caller's. A preview is started from a ViewModel, and
 * decoding a page on the main thread would be a frozen frame for a card the user did
 * not ask for.
 *
 * ## What a failure means
 *
 * A page that cannot be read is [LinkPreviewState.Unavailable] — not an error the
 * screen reports, and not a state that removes the address from the title. Whatever
 * the cache already held for that address is kept, so a site that is briefly down
 * degrades to the card from earlier rather than to nothing.
 *
 * ## The image is not a requirement
 *
 * The metadata is stored before the image is fetched, and an image failure is
 * swallowed: a card with a title and no picture is a working card. The image bytes
 * land beside the entry on disk, because they are the one part not re-derivable.
 */
class LinkPreviewRepositoryImpl(
    private val source: PageSource,
    private val cache: LinkPreviewCache,
    /** Whether the user has left link previews on; see `Tasks.linkPreviewsEnabled`. */
    private val linkPreviewsEnabled: Flow<Boolean>,
    private val clock: kotlin.time.Clock,
    private val dispatcher: CoroutineDispatcher,
) : LinkPreviewRepository {
    override fun observe(url: String?): Flow<LinkPreviewState> {
        if (url == null) return flowOf(LinkPreviewState.None)
        val key = PreviewUrls.keyOf(url)
        return combine(
            linkPreviewsEnabled,
            cache.observeHidden(key),
            cache.observe(key),
        ) { on, hidden, preview ->
            when {
                !on -> LinkPreviewState.None
                hidden -> LinkPreviewState.Hidden
                preview != null -> LinkPreviewState.Ready(preview)
                else -> LinkPreviewState.Loading
            }
        }.distinctUntilChanged()
    }

    override suspend fun refresh(url: String): LinkPreviewState {
        val key = PreviewUrls.keyOf(url)
        // Turned off, dismissed, or fresh: three reasons to answer without a request.
        // Read in this order because it is the order of cheapness.
        if (!linkPreviewsEnabled.first()) return LinkPreviewState.None
        if (cache.observeHidden(key).first()) return LinkPreviewState.Hidden
        if (cache.isFresh(key)) {
            return cache.cached(key)?.let(LinkPreviewState::Ready) ?: LinkPreviewState.Loading
        }
        return fetchOnce(url, key)
    }

    /**
     * Fetches [url] once, or waits for the fetch already running for it.
     *
     * One fetch per address at a time. Two surfaces showing the same task — two stacked
     * nav entries, a screen that reopens while the first is still loading — would
     * otherwise fetch the same page twice for the same answer.
     */
    private suspend fun fetchOnce(
        url: String,
        key: String,
    ): LinkPreviewState {
        val (flight, isOwner) =
            mutex.withLock {
                val existing = inFlight[key]
                if (existing != null) {
                    existing to false
                } else {
                    CompletableDeferred<LinkPreviewState>().also { inFlight[key] = it } to true
                }
            }
        if (!isOwner) {
            // The owner's coroutine holds the fetch, so a caller that gives up cancels
            // only its own `await` and leaves the answer coming for the others. If the
            // *owner* is cancelled, the deferred is cancelled with it — and this caller,
            // still alive, takes the work over rather than inheriting somebody else's
            // cancellation. `ensureActive` is what tells the two apart: this coroutine's
            // own cancellation is rethrown, the owner's is not.
            val waited = runCatchingCancellable { flight.await() }
            if (waited.isSuccess) return waited.getOrThrow()
            coroutineContext.ensureActive()
            return fetchOnce(url, key)
        }
        try {
            val state =
                withContext(dispatcher) {
                    runCatchingCancellable { fetchAndStore(url, key) }
                        .getOrElse {
                            cache.cached(key)?.let(LinkPreviewState::Ready) ?: LinkPreviewState.Unavailable
                        }
                }
            // The setting can be turned off while the page is in flight. The fetch itself
            // is left to finish — cancelling it would need a job handle per caller and
            // would buy nothing — but its answer must not put a card on the screen after
            // the user asked for none. Checked here, where the answer is *returned*,
            // rather than only before the request where it was *started*.
            val settled = if (linkPreviewsEnabled.first()) state else LinkPreviewState.None
            flight.complete(settled)
            return settled
        } finally {
            // Ends the flight for the waiters on **every** path, cancellation included.
            // A deferred completed normally is already done, so `cancel()` there is a
            // no-op; a deferred left open would hang every waiter for the life of its
            // scope, which is the leak this `finally` exists to prevent.
            flight.cancel()
            mutex.withLock { inFlight.remove(key, flight) }
        }
    }

    /** The fetch already running per address, so a second caller waits rather than repeats. */
    private val inFlight = mutableMapOf<String, CompletableDeferred<LinkPreviewState>>()

    /** Guards [inFlight], which more than one coroutine reads and writes. */
    private val mutex = Mutex()

    override suspend fun hide(url: String) {
        cache.hide(PreviewUrls.keyOf(url))
    }

    override suspend fun forget(url: String) {
        cache.forget(PreviewUrls.keyOf(url))
    }

    /**
     * Fetches [url], reads its metadata, stores it, and answers with it.
     *
     * The image is fetched after the entry is stored and its failure is dropped on
     * purpose: the picture is optional, the title is the card.
     */
    private suspend fun fetchAndStore(
        url: String,
        key: String,
    ): LinkPreviewState {
        val page = source.fetch(url, LinkPreviewCache.MAX_PAGE_BYTES, FetchKind.Html)
        val metadata = PageMetadataParser.parse(page.bytes.decodeToString(), page.finalUrl)
        val preview =
            LinkPreview(
                url = url,
                siteName = metadata.siteName,
                title = metadata.title.orEmpty(),
                description = metadata.description,
                imageUrl = metadata.imageUrl,
                fetchedAt = clock.now().toEpochMilliseconds(),
            )
        cache.put(preview)
        metadata.imageUrl?.let { image -> runCatchingCancellable { storeImage(key, image) } }
        return LinkPreviewState.Ready(preview)
    }

    /**
     * Downloads [imageUrl] through the same guarded path as the page itself.
     *
     * It is not handed to the image loader as a remote address: the address came from
     * the page, and letting the loader request it directly would skip the SSRF check,
     * the size limit and the media-type check that the page went through.
     */
    private suspend fun storeImage(
        key: String,
        imageUrl: String,
    ) {
        val image = source.fetch(imageUrl, LinkPreviewCache.MAX_IMAGE_BYTES, FetchKind.Image)
        cache.putImage(key, image.bytes)
    }
}

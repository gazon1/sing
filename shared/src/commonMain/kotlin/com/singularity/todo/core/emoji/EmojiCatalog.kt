package com.singularity.todo.core.emoji

import com.vanniktech.emoji.Emoji
import com.vanniktech.emoji.EmojiCategory
import com.vanniktech.emoji.EmojiManager
import com.vanniktech.emoji.EmojiProvider
import com.vanniktech.emoji.search.SearchEmojiManager

/**
 * The emoji the picker offers, read from the Vanniktech library.
 *
 * The library keeps its catalog in an installed [EmojiProvider], and its
 * `EmojiManager.categories` is `internal`, so the provider is installed here once
 * and kept — holding it is also the only route to the category data from common
 * code. [SearchEmojiManager] searches that same installation.
 *
 * The provider is platform-only (the library publishes each provider class in its
 * platform source sets), hence the [createEmojiProvider] seam.
 */
object EmojiCatalog {
    private val provider: EmojiProvider = createEmojiProvider().also(EmojiManager::install)

    /** The provider's categories, in its own deterministic order. */
    val categories: List<EmojiCategory> = provider.categories.asList()

    /**
     * The library's shortcode search.
     *
     * shortcut: the library ignores queries shorter than two characters, so a
     * one-character query returns no matches rather than a prefix search.
     */
    fun search(query: String): List<Emoji> = SearchEmojiManager().search(query).map { it.emoji }
}

/**
 * The provider whose data [EmojiCatalog] reads.
 *
 * Platform-only because the library publishes each provider class in its
 * platform source set, not in `commonMain`.
 */
internal expect fun createEmojiProvider(): EmojiProvider

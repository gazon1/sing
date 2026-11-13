@file:Suppress("unused", "UnusedPrivateMember", "UnusedPrivateProperty")

package com.singularity.todo.core.emoji

import com.vanniktech.emoji.Emoji
import com.vanniktech.emoji.EmojiCategory
import com.vanniktech.emoji.EmojiManager
import com.vanniktech.emoji.EmojiProvider
import com.vanniktech.emoji.emojiInformation
import com.vanniktech.emoji.search.SearchEmojiManager
import com.vanniktech.emoji.search.SearchEmojiResult

/**
 * #2918: the compile-time proof that Vanniktech Emoji's common API resolves from
 * `commonMain` for both Android and JVM Desktop. #2919 replaces this with the
 * catalog and search the picker reads.
 */
private val emojiLibraryApi =
    listOf(
        EmojiManager::install,
        EmojiManager::destroy,
        EmojiManager::release,
        SearchEmojiManager::search,
        EmojiProvider::categories,
        EmojiCategory::emojis,
        EmojiCategory::categoryNames,
        Emoji::unicode,
        Emoji::shortcodes,
        Emoji::variants,
        SearchEmojiResult::shortcode,
        { text: CharSequence -> text.emojiInformation().visualLength },
    )

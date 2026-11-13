package com.singularity.todo.core.emoji

import com.vanniktech.emoji.EmojiProvider
import com.vanniktech.emoji.google.GoogleEmojiProvider

internal actual fun createEmojiProvider(): EmojiProvider = GoogleEmojiProvider()

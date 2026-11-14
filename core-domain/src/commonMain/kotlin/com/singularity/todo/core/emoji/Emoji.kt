package com.singularity.todo.core.emoji

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A project-icon emoji: the exact Unicode string, as the user sees it.
 *
 * Serialized as its Unicode string. [value] is preserved exactly: ZWJ
 * sequences, flags, skin tones and variation selectors survive as they are.
 */
@Serializable(with = EmojiSerializer::class)
@JvmInline
value class Emoji(val value: String) {
    override fun toString(): String = value
}

internal object EmojiSerializer : KSerializer<Emoji> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.singularity.todo.core.emoji.Emoji", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Emoji,
    ) {
        encoder.encodeString(value.value)
    }

    override fun deserialize(decoder: Decoder): Emoji = Emoji(decoder.decodeString())
}

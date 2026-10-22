package com.singularity.todo.core.text

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializes a [FieldOwnedText] as the plain string it holds.
 *
 * A draft that is persisted has to survive a round trip through the disk, and the text is
 * all it carries: *which* write produced the value is the owner's business, not the store's.
 * Reading a stored draft is therefore a **seed** — the value came from outside the field, so
 * the field never produced it.
 *
 * The stored shape stays a plain string, so a draft written before the value became a
 * [FieldOwnedText] still reads back.
 */
object FieldOwnedTextAsString : KSerializer<FieldOwnedText> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        // The wire name stays the one the type had while it lived in
        // `core.ui.textfields`: it is part of the backup file format, and a backup written by an
        // older build has to restore. The package moved; the name on the wire did not.
        "com.singularity.todo.core.ui.textfields.FieldOwnedText",
        PrimitiveKind.STRING,
    )

    override fun serialize(encoder: Encoder, value: FieldOwnedText) {
        encoder.encodeString(value.text)
    }

    override fun deserialize(decoder: Decoder): FieldOwnedText =
        FieldOwnedText.empty().seeded(decoder.decodeString())
}

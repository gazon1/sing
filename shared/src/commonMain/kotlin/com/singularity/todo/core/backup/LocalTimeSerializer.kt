package com.singularity.todo.core.backup

import com.singularity.todo.core.database.LocalTimeFormats
import kotlinx.datetime.LocalTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * kotlinx.serialization [KSerializer] for [kotlinx.datetime.LocalTime] using ISO-8601 `HH:mm:ss`.
 *
 * Pairs with [com.singularity.todo.core.database.LocalTimeConverters] so DB and backup DTOs share
 * a single canonical wire format.
 */
object LocalTimeSerializer : KSerializer<LocalTime> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("kotlinx.datetime.LocalTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalTime) {
        encoder.encodeString(LocalTimeFormats.format(value))
    }

    override fun deserialize(decoder: Decoder): LocalTime = LocalTimeFormats.parse(decoder.decodeString())
}

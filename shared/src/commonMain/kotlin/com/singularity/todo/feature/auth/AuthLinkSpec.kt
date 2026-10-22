package com.singularity.todo.feature.auth

import okio.ByteString.Companion.encodeUtf8

/**
 * Шаблон и разбор magic-link'а — единственный источник правды о формате.
 *
 * Ссылка приходит из письма, отправленного Supabase (`SupabaseAuthGateway`),
 * и открывает экран подтверждения адреса.
 *
 * ## Граница ответственности
 *
 * Адрес, на который Supabase возвращает пользователя, настраивается в
 * панели Supabase — вне этого репозитория. Здесь живёт только формат: если
 * redirect в панели настроен на другой host, ссылка не распознается, и
 * приложение её просто не откроет.
 */
object AuthLinkSpec {

    /** Схема всех ссылок приложения. */
    const val SCHEME = "singularity"

    /** Authority magic-link'а. */
    const val HOST = "verify"

    /** URI-шаблон: адрес и токен — в query-параметрах. */
    const val URI = "$SCHEME://$HOST?email={email}&token={token}"

    /**
     * Magic-link с указанными адресом и токеном.
     *
     * Значения percent-кодируются: адрес содержит `@`, а токен — любой символ,
     * который байт-строка может содержать.
     *
     * @param email адрес, который подтверждается.
     * @param token токен подтверждения.
     */
    fun uri(email: String, token: String): String =
        "$SCHEME://$HOST?email=${encodeQueryValue(email)}&token=${encodeQueryValue(token)}"

    /**
     * Percent-кодирование значения query-параметра по RFC 3986.
     *
     * Кодируются UTF-8-байты, а не символы: символ вне BMP кодируется одним
     * `Char`, но несколькими байтами, и escape каждого байта — единственный
     * способ получить строку, которую обратный разбор вернёт целиком.
     */
    private fun encodeQueryValue(value: String): String {
        val bytes = value.encodeUtf8().toByteArray()
        return buildString(bytes.size) {
            for (byte in bytes) {
                val code = byte.toInt() and 0xFF
                val char = code.toChar()
                if (char in UNRESERVED) {
                    append(char)
                } else {
                    append('%')
                    append(HEX[code shr 4])
                    append(HEX[code and 0x0F])
                }
            }
        }
    }

    /** Символы RFC 3986, которые в значении параметра не кодируются. */
    private const val UNRESERVED =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"

    /** Таблица hex-цифр для percent-кодирования. */
    private const val HEX = "0123456789ABCDEF"
}

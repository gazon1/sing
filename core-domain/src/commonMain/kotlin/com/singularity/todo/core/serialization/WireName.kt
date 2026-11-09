package com.singularity.todo.core.serialization

import kotlin.enums.enumEntries

/**
 * An enum whose stored spelling is a contract, not a side effect of the constant's name.
 *
 * ## Why the name of the Kotlin constant is not a wire name
 *
 * An enum constant and a stored value are two different things. The constant is the
 * code's word for the thing and is free to change: `NextUp` reads better than
 * `Next` in one file and worse in another, and a refactor may well rename it. The
 * stored value is a promise to whoever reads it back — a backup taken in March, a
 * saved agenda view, a row in the database.
 *
 * When the two are the same string, every rename of the constant silently rewrites
 * the format, and the data written before the rename stops being readable by the code
 * written after it. That is the failure this interface exists to prevent: the value
 * that goes into a payload is declared once, next to the enum, and the reader accepts
 * it whatever the constant is called now.
 *
 * ## What "accepts the old spelling" means
 *
 * [parseWireName] matches the declared wire name *and* the constant's current name,
 * case-insensitively. So a payload written before the constant was renamed still
 * reads back, and a payload written after it reads back under the name it was given.
 * Both directions matter: a reader that only knew the new name would lose an old
 * backup, and a reader that only knew the constant's name would lose every backup
 * the day the constant changed.
 *
 * ## The wire name is not the serial name
 *
 * `@SerialName` changes what kotlinx.serialization writes, and for the enums that
 * travel inside a synced task that would change the protocol the server speaks.
 * This interface does not: it names the value *this app* stores, and the sync
 * document keeps its own spelling.
 */
interface WireNamed {
    /** The spelling this value is stored under. */
    val wireName: String
}

/**
 * Reads the value [value] names, or `null` when it names none.
 *
 * The match is against the declared [WireNamed.wireName] and against the constant's
 * own name, so a payload written under either spelling is read back.
 */
inline fun <reified E> parseWireNameOrNull(value: String): E? where E : Enum<E>, E : WireNamed =
    enumEntries<E>().firstOrNull { it.wireName == value || it.name.equals(value, ignoreCase = true) }

/**
 * Reads the value [value] names.
 *
 * @throws IllegalArgumentException when the value names no constant — a payload that
 * carries a spelling this build does not know is a payload that cannot be restored
 * faithfully, and saying so is better than reading it as something else.
 */
inline fun <reified E> parseWireName(value: String): E where E : Enum<E>, E : WireNamed =
    parseWireNameOrNull<E>(value)
        ?: throw IllegalArgumentException("unknown wire value: $value")

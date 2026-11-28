package com.singularity.todo.core.ui

/**
 * Typed dispatch wrapper for MVI intents.
 *
 * Replaces per-feature `@JvmInline value class XxxActions` with a single generic type.
 * The inline class prevents runtime overhead while preserving type safety.
 *
 * ## Before (per-feature)
 * ```kotlin
 * @JvmInline value class TagsActions(private val dispatch: (TagsIntent) -> Unit) {
 *     operator fun invoke(intent: TagsIntent) = dispatch(intent)
 * }
 * ```
 *
 * ## After (generic)
 * ```kotlin
 * val actions = IntentActions(dispatch)
 * actions(TagsIntent.Delete(id))
 * ```
 *
 * @param I The intent type, must implement [MviIntent].
 * @see MviIntent
 */
@JvmInline
value class IntentActions<I : MviIntent>(private val dispatch: (I) -> Unit) {
    /** Dispatches [intent] to the ViewModel. */
    operator fun invoke(intent: I) = dispatch(intent)
}

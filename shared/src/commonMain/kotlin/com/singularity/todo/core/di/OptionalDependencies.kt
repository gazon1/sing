package com.singularity.todo.core.di

import org.koin.core.error.InstanceCreationException
import org.koin.core.error.NoDefinitionFoundException
import org.koin.core.scope.Scope

/**
 * Resolves [T] when it is available, and returns `null` when it is not.
 *
 * `getOrNull` is not enough for an *optional* dependency. It answers one
 * question — "is there a definition for this type?" — and a definition that
 * exists but *fails to be created* is not a missing definition: it throws, and
 * the caller cannot tell the two apart. An optional dependency whose own wiring
 * is broken therefore took the whole screen down at composition time instead of
 * degrading to "this feature is not configured".
 *
 * That is not hypothetical. `RewriteNoteUseCase` takes the `TypedTool`
 * *interface*, `factoryOf` erased the generic argument, and nothing was bound
 * for the raw type. The definition was registered — so the "is it there?" check
 * was happy — and every *creation* of it threw `NoDefinitionFoundException`. The
 * note editor could not be composed on any platform, and the error surfaced as
 * a crash in composition rather than as a missing feature.
 *
 * A missing definition is not a failure: that is the normal case for an
 * optional dependency, and it is not reported. A definition that exists and
 * cannot be created *is* a defect, and [onFailure] is where it is reported.
 *
 * @param onFailure called with the root cause when a definition exists but
 *   cannot be created.
 */
internal inline fun <reified T : Any> Scope.getOptionalOrNull(onFailure: (Throwable) -> Unit = {}): T? =
    try {
        get(T::class)
    } catch (missing: NoDefinitionFoundException) {
        null
    } catch (broken: InstanceCreationException) {
        onFailure(broken.rootCause())
        null
    }

/**
 * The innermost cause of a failure.
 *
 * Koin wraps whatever a definition threw in an `InstanceCreationException`
 * whose own message is "Could not create instance for '[Factory: …]'" — the
 * reason is in the cause, and the reason is what a reader needs.
 */
internal fun Throwable.rootCause(): Throwable {
    var current: Throwable = this
    while (true) {
        val cause = current.cause ?: return current
        if (cause === current) return current
        current = cause
    }
}

package com.singularity.todo.core.settings

import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts the one-shot [SettingsDataStoreMigration] at first use instead of at module
 * construction.
 *
 * A Koin module's body runs when the module is *built*, and every test that compares
 * registrations builds it. Launching the migration from that body made merely building
 * a graph migrate the files in the developer's real home — and, because `user.home` is
 * process-global, made two tests that redirect it race. The settings bindings ask this
 * gate to start when they are first resolved, which is the first moment the settings
 * are actually used; building the module is then free of side effects.
 *
 * [isStarted] is read by the tests that hold both halves of that property.
 *
 * Public, because the platform module binds it and the desktop test module has to
 * bind the same type: the production/test parity check compares the registrations of
 * the two graphs, and a type the production graph binds and the test graph does not is
 * exactly what that check exists to catch. A class that only the module that binds it
 * can name makes that mirror impossible to write.
 */
class SettingsMigrationGate(private val migration: SettingsDataStoreMigration, private val scope: CoroutineScope) {
    private val started = AtomicBoolean(false)

    val isStarted: Boolean get() = started.get()

    fun ensureStarted() {
        if (started.compareAndSet(false, true)) migration.runDeferred(scope)
    }
}

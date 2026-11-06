package com.singularity.todo.test.helpers

import java.util.Locale

/**
 * Pins the JVM default locale to English for the duration of a Compose mount.
 *
 * The desktop test JVM renders the production app, and the app's strings come
 * from `composeResources`. On the JVM, Compose Multiplatform resolves those from
 * `Locale.getDefault()`, so on a developer machine whose default locale is `ru`
 * the same composable renders "Задача заблокирована зависимостями" and an
 * assertion written against the base `values/` string — "Task is blocked by
 * dependencies" — fails. That failure reports the host's locale, not the app,
 * which is the same class of bug the fixed-clock parameter of
 * [runDesktopAppTest] exists to prevent: a suite whose result depends on how the
 * machine is configured.
 *
 * The pin is made per mount, before the composition exists, because a string is
 * resolved whenever the composition first needs it — restoring it around an
 * individual assertion would be too early and too narrow. It is deliberately not
 * restored: the desktop UI suite asserts the base locale, so English is a
 * precondition of the suite rather than a fixture of one test. Nothing in this
 * module wants another locale, and the one test that changes the default on
 * purpose — `LocaleIndependenceTest` — lives in `:shared:jvmTest`, a different
 * JVM, and puts back what it changes.
 *
 * The suite runs with `same_thread` on both axes, so this global write does not
 * race another test.
 */
fun useEnglishTestLocale() {
    Locale.setDefault(Locale.ENGLISH)
}

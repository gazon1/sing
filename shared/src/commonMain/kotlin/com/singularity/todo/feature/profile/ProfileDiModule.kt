package com.singularity.todo.feature.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.singularity.todo.core.auth.CurrentUser
import com.singularity.todo.core.coroutines.createBackgroundScope
import com.singularity.todo.core.database.ProfileDao
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.observability.crashReportingFailureHandler
import com.singularity.todo.feature.profile.domain.port.ProfileRepository
import com.singularity.todo.feature.profile.presentation.AccountSettingsViewModel
import com.singularity.todo.feature.profile.presentation.ChangePasswordViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * The profile bindings.
 *
 * ## Why the profile is a parameter of the module
 *
 * A process may be told which profile to work as — the tool server is, when it is started
 * for an external agent — and a process that was told must not open the store that holds
 * the application's active profile. The store takes a file lock, and the application is
 * holding it, so opening it from a second process turns a side-by-side arrangement into a
 * fight over a lock.
 *
 * Making the profile a parameter of the module is what allows that: the ordinary
 * application passes nothing and gets the stored profile, and a process that was told a
 * profile passes it and never asks for the store.
 *
 * ## Why it is ADDED to the module list and never `includes()`d
 *
 * An included module creates a child scope, and the bindings in a child scope are not
 * visible to the sibling modules at the parent level. The first attempt at this
 * arrangement included it, and the profile bindings disappeared for every module that
 * needed them. A module added to the list lands at the root scope, which is where
 * everything else is.
 *
 * @param fixedProfileId the profile this process works as, or null to use the stored one.
 */
fun profileModule(fixedProfileId: ProfileId? = null): Module = module {
    single<ProfileRepository> {
        val dao = get<ProfileDao>()
        val clock = get<Clock>()
        val scope = createBackgroundScope(crashReportingFailureHandler(get<CrashReportingPort>()))
        if (fixedProfileId != null) {
            // Told which profile to work as: the stored active profile is none of this
            // process's business, and asking for the store would take the file lock the
            // application is holding. The store is resolved only on the other branch, so
            // a fixed-profile process never constructs it at all.
            ProfileRepositoryImpl(
                profileDao = dao,
                dataStore = null,
                clock = clock,
                scope = scope,
                fixedProfileId = fixedProfileId,
            )
        } else {
            ProfileRepositoryImpl(
                profileDao = dao,
                dataStore = get<DataStore<Preferences>>(),
                clock = clock,
                scope = scope,
            )
        }
    }
    single<ProfileAwareCurrentUser> {
        ProfileAwareCurrentUser(
            get<CurrentUser>(),
            get<ProfileRepository>(),
            createBackgroundScope(crashReportingFailureHandler(get<CrashReportingPort>())),
        )
    }
    factory { ProfileBootstrapper(get()) }
    // Explicit lambda (not viewModelOf(::AccountSettingsViewModel)): Koin's reflective
    // constructor resolution does NOT apply Kotlin default arguments. The constructor
    // has defaults for crashReporter and scope, and viewModelOf fails to resolve them
    // because reportingScope(get()) cannot be called reflectively. The explicit lambda
    // applies Kotlin defaults at the call site. See #662.
    //
    // Only `crashReporter` is passed, and it is passed by name. `scope` is left to
    // its own default, `reportingScope(crashReporter)` — passing a scope resolved
    // independently is what `NoDivergentScopeAndReporter` is about, and the two
    // custom rules together ask for exactly this shape: a named reporter, no
    // separately-chosen scope.
    viewModel {
        AccountSettingsViewModel(
            profileRepository = get(),
            crashReporter = get(),
        )
    }
    // Same issue as above: ChangePasswordViewModel has defaults for crashReporter and scope.
    viewModel {
        ChangePasswordViewModel(
            authRepository = get(),
            crashReporter = get(),
        )
    }
}

package com.singularity.todo.core.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.singularity.todo.core.security.FakeSecureStorage
import com.singularity.todo.core.security.SecureStoragePort
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.module
import org.koin.test.check.checkModules
import org.robolectric.annotation.Config

/**
 * Android-side DI graph verification via Robolectric.
 *
 * `checkModules()` walks the full graph on the actual Android runtime
 * (with a Robolectric Context), catching missing bindings before the app
 * reaches a device — including Android-only stubs like the TextGenPort
 * FakeTextGen registration that the JVM test cannot exercise.
 *
 * EncryptedSharedPreferences (used by [AndroidSecureStorage]) is brittle
 * under Robolectric, so the test registers [FakeSecureStorage] instead.
 *
 * Run with: ./gradlew :shared:testAndroidHostTest
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])  // Robolectric 4.16 maxSdkVersion=36; app targetSdkVersion=37
class AndroidDiGraphTest {

    @Suppress("DEPRECATION")
    @Test
    fun `android graph verifies on Robolectric`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        checkModules {
            modules(
                module {
                    single<Context> { context }
                    // EncryptedSharedPreferences fails under Robolectric
                    single<SecureStoragePort> { FakeSecureStorage() }
                },
                coreDomainModule(),
                platformModule(),
                aiToolsModule(),
            )
        }
    }
}

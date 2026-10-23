package com.singularity.todo.mcp
import ai.koog.agents.core.tools.Tool
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.singularity.todo.core.di.coreLoggingModule
import com.singularity.todo.core.di.domainModule
import com.singularity.todo.core.di.platformModule
import com.singularity.todo.core.security.SecureStoragePort
import com.singularity.todo.feature.ai.McpToolCatalog
import com.singularity.todo.feature.ai.WriteBehavior
import com.singularity.todo.feature.gate.gateModule
import com.singularity.todo.test.fakes.FakeSecureStorage
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner

/**
 * The Android toolbox that is *wired* must be the toolbox that is *declared*.
 *
 * The JVM half of this check is `ToolSurfaceWiringTest` in `desktopApp`, and the two
 * halves are separate for a reason: the catalogue and the Koin list are two files, one
 * per platform, and a tool wired on one and forgotten on the other is invisible to any
 * check that reads only one of them.
 *
 * The graph is the same one the app loads, minus the app-update module — a Play Store
 * client nothing here needs.
 *
 * ## The one binding that is replaced
 *
 * [SecureStoragePort] is bound to `EncryptedSharedPreferences`, which needs the
 * `AndroidKeyStore` provider — a provider that does not exist off a device. The tool
 * list is a single Koin definition, so *one* tool that needs secure storage makes the
 * whole list unresolvable, and the test would report "the toolbox cannot be created"
 * instead of what it is for.
 *
 * So the storage is replaced by a fake and nothing else is. The list of tools is still
 * the production list, built by the production module — that is the claim under test.
 * The same exclusion is on record in `AndroidGraphResolutionTest`, which documents the
 * three bindings that cannot be created off a device.
 */
@Tag("slow")
@Tag("slow:platform")
@RunWith(RobolectricTestRunner::class)
class ToolSurfaceWiringTest {

    @Test
    fun `the wired toolbox is the declared toolbox`() {
        val app = koinApplication {
            androidContext(ApplicationProvider.getApplicationContext<Context>())
            allowOverride(true)
            @Suppress("KoinCompilationForInternal")
            modules(
                listOf(platformModule(), coreLoggingModule()) +
                    domainModule() +
                    listOf(
                        gateModule("https://github.com/singularity-todo/singularity/releases"),
                        module { single<SecureStoragePort> { FakeSecureStorage() } },
                    ),
            )
        }
        try {
            val tools: List<Tool<*, *>> = app.koin.get()
            val declared = McpToolCatalog.catalog

            val wiredNames = tools.map { it.descriptor.name }
            val declaredNames = declared.map { it.name }

            assertEquals(
                declaredNames.sorted(),
                wiredNames.sorted(),
                "the wired tools and the catalogue disagree; a client can call a tool " +
                    "that is not wired, or a wired tool that nothing documents",
            )
            assertEquals(
                wiredNames.size,
                wiredNames.distinct().size,
                "two wired tools answer to the same name; the second one wins silently",
            )

            val classByName = tools.associate { it.descriptor.name to it::class.simpleName }
            for (entry in declared) {
                assertEquals(
                    entry.toolClass.simpleName,
                    classByName[entry.name],
                    "the catalogue points ${entry.name} at ${entry.toolClass.simpleName}, but " +
                        "that is not the class that is wired",
                )
            }

            for (tool in tools) {
                assertTrue(
                    tool.descriptor.description.isNotBlank(),
                    "${tool.descriptor.name} has no description; the agent would be told nothing",
                )
            }

            // ─── How each tool writes ───────────────────────────────────────────
            //
            // The same checks the JVM copy makes. The two lists are separate files, and a
            // rule that is checked on one platform only is a rule that is checked on one
            // platform only.
            for (entry in declared) {
                when (entry.writeBehavior) {
                    WriteBehavior.DIRECT_WRITE_EXCEPTION -> assertTrue(
                        !entry.exceptionReason.isNullOrBlank(),
                        "${entry.name} writes directly and says nothing about why it sits " +
                            "outside the proposal pipeline",
                    )

                    else -> assertTrue(
                        entry.exceptionReason == null,
                        "${entry.name} is ${entry.writeBehavior} and still carries an " +
                            "exception reason",
                    )
                }
            }
        } finally {
            app.close()
        }
    }
}

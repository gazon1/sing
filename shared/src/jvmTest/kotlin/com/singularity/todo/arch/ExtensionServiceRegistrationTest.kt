package com.singularity.todo.arch

import org.junit.jupiter.api.extension.Extension
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Tag
import java.util.concurrent.TimeUnit
import kotlin.test.Test

/**
 * Verifies that every JUnit Jupiter [Extension] registered in
 * `META-INF/services/org.junit.jupiter.api.extension.Extension` resolves to a real,
 * loadable class — preventing a repeat of the [FailureContextExtension] incident
 * where the class existed but its services file had been orphaned and never merged.
 *
 * Runs as part of `:shared:jvmTest` alongside [ArchitectureTest].
 */
@Tag("slow")
class ExtensionServiceRegistrationTest {

    private val serviceFileName = "META-INF/services/org.junit.jupiter.api.extension.Extension"

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `every registered extension class is loadable`() {
        val loader = Thread.currentThread().contextClassLoader
        val serviceUrl = loader.getResource(serviceFileName)
            ?: kotlin.test.fail(
                "Service file not found on classpath: $serviceFileName — " +
                    "add META-INF/services/org.junit.jupiter.api.extension.Extension with extension class names",
            )

        val classNames = serviceUrl.readText()
            .lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }

        if (classNames.isEmpty()) {
            kotlin.test.fail(
                "Service file $serviceFileName is empty — " +
                    "at least one extension is expected (FailureContextExtension)",
            )
        }

        for (className in classNames) {
            val trimmed = className.trim()
            runCatching {
                val clazz = loader.loadClass(trimmed)
                // Verify it is actually an Extension subtype (not just any class)
                if (!Extension::class.java.isAssignableFrom(clazz)) {
                    kotlin.test.fail(
                        "$trimmed is registered in $serviceFileName " +
                            "but does not implement org.junit.jupiter.api.extension.Extension",
                    )
                }
            }.onFailure { e ->
                kotlin.test.fail("Failed to load extension class '$trimmed': ${e.message}")
            }
        }
    }
}

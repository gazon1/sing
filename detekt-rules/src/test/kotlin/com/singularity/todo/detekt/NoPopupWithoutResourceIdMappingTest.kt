package com.singularity.todo.detekt

import dev.detekt.test.FakeLanguageVersionSettings
import dev.detekt.test.TestConfig
import dev.detekt.test.utils.compileContentForTest
import org.jetbrains.kotlin.config.ExplicitApiMode
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests for [NoPopupWithoutResourceIdMapping].
 *
 * The rule flags a Popup-owning composable (ModalBottomSheet, Popup, AlertDialog,
 * DropdownMenu) whose content lambda contains a `testTag()` call but whose root
 * modifier does not chain `.mapTestTagsAsResourceIds()`.
 *
 * Without the mapping, the testTag stays scoped to the Popup's semantic tree and
 * never reaches the Android resource-id layer — Maestro `id:` selectors silently fail.
 */
class NoPopupWithoutResourceIdMappingTest {

    private val languageSettings = FakeLanguageVersionSettings(ExplicitApiMode.STRICT)

    private fun findings(rule: NoPopupWithoutResourceIdMapping, code: String) =
        rule.visitFile(
            compileContentForTest(code.trimIndent(), Path.of("Fixture.kt")),
            languageSettings,
        )

    private fun rule() = NoPopupWithoutResourceIdMapping(TestConfig())

    @Test
    fun `ModalBottomSheet with testTag inside and no mapTestTagsAsResourceIds is flagged`() {
        val code = """
            package com.singularity.todo.feature.example

            @Composable
            fun MySheet() {
                ModalBottomSheet(onDismissRequest = { }) {
                    Column(modifier = Modifier.testTag("my_sheet")) {
                        Text("Hello")
                    }
                }
            }
        """
        val result = findings(rule(), code)
        assertTrue(
            result.any { it.message.contains("mapTestTagsAsResourceIds") },
            "expected a finding about missing mapTestTagsAsResourceIds, got ${result.map { it.message }}",
        )
    }

    @Test
    fun `ModalBottomSheet with mapTestTagsAsResourceIds on modifier is not flagged`() {
        val code = """
            package com.singularity.todo.feature.example

            @Composable
            fun MySheet() {
                ModalBottomSheet(
                    modifier = Modifier.mapTestTagsAsResourceIds(),
                    onDismissRequest = { },
                ) {
                    Column(modifier = Modifier.testTag("my_sheet")) {
                        Text("Hello")
                    }
                }
            }
        """
        val result = findings(rule(), code)
        assertTrue(
            result.none { it.message.contains("mapTestTagsAsResourceIds") },
            "expected no finding when modifier has mapTestTagsAsResourceIds, got ${result.map { it.message }}",
        )
    }

    @Test
    fun `AlertDialog with chained mapTestTagsAsResourceIds is not flagged`() {
        val code = """
            package com.singularity.todo.feature.example

            @Composable
            fun MyDialog() {
                AlertDialog(
                    modifier = Modifier
                        .testTag("result_dialog")
                        .mapTestTagsAsResourceIds(),
                    onDismissRequest = { },
                ) {
                    Text("Confirm")
                }
            }
        """
        val result = findings(rule(), code)
        assertTrue(
            result.none { it.message.contains("mapTestTagsAsResourceIds") },
            "expected no finding when chained mapTestTagsAsResourceIds, got ${result.map { it.message }}",
        )
    }

    @Test
    fun `AlertDialog with testTag inside content lambda and no mapping is flagged`() {
        val code = """
            package com.singularity.todo.feature.example

            @Composable
            fun MyDialog() {
                AlertDialog(
                    onDismissRequest = { },
                ) {
                    Column(modifier = Modifier.testTag("result_dialog")) {
                        Text("Confirm")
                    }
                }
            }
        """
        val result = findings(rule(), code)
        assertTrue(
            result.any { it.message.contains("AlertDialog") },
            "expected a finding for AlertDialog without mapping, got ${result.map { it.message }}",
        )
    }

    @Test
    fun `Popup with testTag inside and no mapping is flagged`() {
        val code = """
            package com.singularity.todo.feature.example

            @Composable
            fun MyPopup() {
                Popup(onDismissRequest = { }) {
                    Box(modifier = Modifier.testTag("my_popup")) {
                        Text("Content")
                    }
                }
            }
        """
        val result = findings(rule(), code)
        assertTrue(
            result.any { it.message.contains("Popup") },
            "expected a finding for Popup without mapping, got ${result.map { it.message }}",
        )
    }

    @Test
    fun `DropdownMenu with testTag inside and no mapping is flagged`() {
        val code = """
            package com.singularity.todo.feature.example

            @Composable
            fun MyMenu() {
                DropdownMenu(expanded = true, onDismissRequest = { }) {
                    DropdownMenuItem(
                        text = { Text("Item") },
                        onClick = { },
                        modifier = Modifier.testTag("menu_item"),
                    )
                }
            }
        """
        val result = findings(rule(), code)
        assertTrue(
            result.any { it.message.contains("DropdownMenu") },
            "expected a finding for DropdownMenu without mapping, got ${result.map { it.message }}",
        )
    }

    @Test
    fun `Popup without testTag inside is not flagged`() {
        val code = """
            package com.singularity.todo.feature.example

            @Composable
            fun MyPopup() {
                Popup(onDismissRequest = { }) {
                    Box {
                        Text("No tag here")
                    }
                }
            }
        """
        val result = findings(rule(), code)
        assertTrue(
            result.none(),
            "expected no finding when no testTag inside, got ${result.map { it.message }}",
        )
    }

    @Test
    fun `preview function is exempt`() {
        val code = """
            package com.singularity.todo.feature.example

            @Preview
            @Composable
            fun MySheetPreview() {
                ModalBottomSheet(onDismissRequest = { }) {
                    Column(modifier = Modifier.testTag("my_sheet")) {
                        Text("Hello")
                    }
                }
            }
        """
        val result = findings(rule(), code)
        assertTrue(
            result.none(),
            "expected no finding in preview, got ${result.map { it.message }}",
        )
    }
}

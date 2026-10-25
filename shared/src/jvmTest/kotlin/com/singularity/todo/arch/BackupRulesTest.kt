package com.singularity.todo.arch

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Tag
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The credential store is excluded from backup and from device transfer.
 *
 * ## Why the rule exists
 *
 * `EncryptedSharedPreferences` writes a file whose ciphertext is worthless without
 * the Android Keystore key that made it — and that key is deleted on uninstall and
 * is never backed up. Android's Auto Backup copies `shared_prefs/` verbatim
 * anyway, so the reinstalled app inherits a file it can never read: every read
 * raises `GeneralSecurityException` for the rest of the install's life.
 *
 * AndroidX documents the exclusion as mandatory. What is *not* documented is that
 * there are two rule files — `backup_rules.xml` for Android 11 and below and
 * `data_extraction_rules.xml` for Android 12+ — and that the second one has two
 * sections, cloud backup and device transfer. Excluding the file in only one of
 * the three places leaves it excluded on exactly the devices most likely to hit
 * the bug.
 *
 * ## Why the store's representation changing does not change this
 *
 * The store went from four keychain values to one. The *file* is the same file —
 * the same `EncryptedSharedPreferences`, under the same name — and the name is
 * what the rules exclude. A representation that moved to a different file would
 * need a different exclusion, and this test is what would notice.
 */
@Tag("fast")
class BackupRulesTest {

    @Test
    fun `the secure store is excluded from cloud backup`() {
        val rules = File(androidRes(), "backup_rules.xml")
        assertTrue(rules.isFile, "the backup rules are missing at ${rules.path}")
        assertTrue(
            excludes(rules).contains(SECURE_STORE),
            "the secure store is backed up: the ciphertext would be restored to a device " +
                "whose Keystore key is gone, and every read of it would throw for the rest " +
                "of the install's life — ${excludes(rules)}",
        )
    }

    @Test
    fun `the secure store is excluded from device transfer as well`() {
        val rules = File(androidRes(), "data_extraction_rules.xml")
        assertTrue(rules.isFile, "the data extraction rules are missing at ${rules.path}")

        for (section in listOf("cloud-backup", "device-transfer")) {
            val excluded = excludes(rules, section)
            assertTrue(
                excluded.contains(SECURE_STORE),
                "the secure store crosses $section: the ciphertext would be moved to a " +
                    "device whose Keystore key is gone — $excluded",
            )
        }
    }

    /**
     * The excluded paths of a rule file, optionally within one section.
     *
     * `domain` and `path` are read together: an exclusion of the right path under
     * the wrong domain excludes nothing, and it looks exactly like the right one in
     * the file.
     */
    private fun excludes(rules: File, section: String? = null): Set<Pair<String, String>> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(rules)
        val scope = if (section == null) {
            document.documentElement
        } else {
            document.getElementsByTagName(section).item(0) as? Element
                ?: error("the rule file has no $section section")
        }
        val nodes = scope.getElementsByTagName("exclude")
        return (0 until nodes.length).mapTo(mutableSetOf()) { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("domain") to element.getAttribute("path")
        }
    }

    /** The androidApp's resource directory, found from the tree the build points at. */
    private fun androidRes(): String {
        val commonMain = File(
            System.getProperty("commonMain.root")
                ?: error("commonMain.root system property is not set"),
        )
        // <repo>/shared/src/commonMain/kotlin -> <repo>/androidApp/src/main/res/xml
        return commonMain.parentFile.parentFile.parentFile.parentFile
            .resolve("androidApp/src/main/res/xml")
            .path
    }

    private companion object {
        /** The `EncryptedSharedPreferences` file, by domain and path. */
        val SECURE_STORE = "sharedpref" to "singularity_secure_prefs.xml"
    }
}

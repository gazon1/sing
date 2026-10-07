package com.singularity.todo.core.ui

import java.nio.file.Files
import java.nio.file.Paths

/**
 * Renders the generated section of [TAGS.md][TagsMd.tagsMdPath] from [TestTagsCatalog].
 *
 * The generated section covers the **Static constants** table and the **Dynamic
 * functions** table.  Everything outside the `<!-- GENERATED:BEGIN -->` …
 * `<!-- GENERATED:END -->` markers is manually maintained and is not affected.
 *
 * The subsection structure is derived from the comment headers in `TestTags.kt`:
 * each `// ─── Foo ────` line starts a new subsection.
 *
 * Run with `-Dupdate.goldens=true` to rewrite the generated section:
 * ```
 * ./gradlew :shared:jvmTest --tests "TagsMdGoldenTest" -Dupdate.goldens=true
 * ```
 */
object TagsMd {

    /**
     * Path to `Maestro/TAGS.md`, relative to the workspace root.
     * Derived by going up 4 levels from `commonMain.root` (`:shared/src/commonMain/kotlin`).
     */
    private val tagsMdPath: String by lazy {
        val commonMainRoot = System.getProperty("commonMain.root")
            ?: error("commonMain.root is not set — see shared/build.gradle.kts")
        val workspaceRoot = Paths.get(commonMainRoot).parent.parent.parent.parent
        workspaceRoot.resolve("Maestro/TAGS.md").toString()
    }

    // ─── Subsection order (matches the comment blocks in TestTags.kt) ─────────

    private val staticSubsections: List<String> = listOf(
        "Auth",
        "Navigation",
        "Tasks",
        "Agenda",
        "Pomodoro",
        "Tags",
        "Notes",
        "Note Editor",
        "Note Preview",
        "Search",
        "Projects",
        "Settings",
        "Dialog",
        "Editor Overflow menu",
        "Snackbar / transient UI",
        "Backup",
        "Profile",
        "AI",
        "Calendar",
        // Added 2026-10-07 with `TestTags.CalendarSync` and `TestTags.SearchFilter`.
        "Calendar sync",
        "Search filters",
    )

    private val dynamicSubsections: List<String> = listOf(
        "Navigation",
        "Settings",
        "Menu",
        "Tasks",
        "Notes",
        "Agenda",
        "Projects",
        "Pomodoro",
        "Dialog",
        "Profile",
        "AI",
        "Calendar sync",
        "Search filters",
    )

    // ─── Rendering ─────────────────────────────────────────────────────────────

    /**
     * Renders the complete generated section (static constants + dynamic functions)
     * in the same format as TAGS.md.
     */
    fun renderGeneratedSection(catalog: TestTagsCatalog): String = buildString {
        appendLine("## Static constants")
        appendStaticConstants(catalog)
        appendLine()
        appendLine("## Dynamic functions")
        appendDynamicFunctions(catalog)
    }

    private fun StringBuilder.appendStaticConstants(catalog: TestTagsCatalog): StringBuilder {
        val byCategory = mutableMapOf<String, MutableList<Pair<String, String>>>()

        // Group constants by subsection using prefix heuristics that match TestTags.kt
        // section comment blocks.  The subsection order follows staticSubsections.
        for ((constName, value) in catalog.staticTags()) {
            val category = staticCategoryOf(constName)
            byCategory.getOrPut(category) { mutableListOf() }.add(constName to value)
        }

        // A constant the categorizer does not recognize would be silently dropped from
        // the generated table, making the golden test pass on a constant TAGS.md does not
        // document. Fail loudly instead: a new constant must be classified.
        //
        // The check is over *every* category the render loop will not emit, not just
        // `"Other"`. Classifying a constant into a named category that is missing from
        // [staticSubsections] drops it just as silently — which is exactly what happened
        // to `TestTags.CalendarSync` and `TestTags.SearchFilter`: both were classified,
        // both were rendered nowhere, and the golden test passed on a TAGS.md that
        // documented none of them. The `"Other"` bucket was the only place the old check
        // looked, so a mis-placed category was the one failure mode it could not see.
        val unclassified = byCategory.filterKeys { it !in staticSubsections }.values.flatten()
        check(unclassified.isEmpty()) {
            "TagsMd classified constants into a category that is not rendered: " +
                "${unclassified.map { it.first }.sorted()}. Either add the category to " +
                "staticSubsections so it is documented, or return \"Other\" from " +
                "staticCategoryOf and let the branch below report it."
        }

        for (section in staticSubsections) {
            val entries = byCategory[section] ?: continue
            appendLine()
            appendLine("### $section")
            appendLine("| Constant | Value | Where |")
            appendLine("|---|---|---|")
            for ((constName, value) in entries.sortedBy { it.first }) {
                appendLine("| `$constName` | `$value` | |")
            }
        }
        return this
    }

    private fun StringBuilder.appendDynamicFunctions(catalog: TestTagsCatalog): StringBuilder {
        appendLine()
        appendLine("Use `TestTags.<function>(<input>)` in Kotlin. In a Maestro flow, hard-code")
        appendLine("the expanded form (the `id:` Maestro accepts does not call functions — flows")
        appendLine("use the expanded string directly).")
        appendLine()
        appendLine("| Function | Input example | Expanded id | Used for |")
        appendLine("|---|---|---|---|")

        val staticTags = catalog.staticTags().toMap()
        val byCategory: MutableMap<String, MutableList<DynamicMeta>> = mutableMapOf()
        for ((fnName, prefix) in catalog.dynamicFunctions()) {
            byCategory.getOrPut(dynamicMeta(fnName, prefix).category) { mutableListOf() }
                .add(dynamicMeta(fnName, prefix))
        }

        for (section in dynamicSubsections) {
            val entries = byCategory[section] ?: continue
            for (meta in entries.sortedBy { it.function }) {
                val rawPrefix = catalog.dynamicFunctions()
                    .first { it.first == meta.function }.second
                // Resolve interpolated constants (e.g. "${PROFILE_ITEM_PREFIX}" → "profile_item_")
                val resolvedPrefix = resolveInterpolatedPrefix(rawPrefix, staticTags)
                val expanded = "${resolvedPrefix}${slug(meta.example)}"
                    .lowercase()
                    .replace(Regex("[^a-z0-9]+"), "_")
                    .removePrefix("_")
                    .removeSuffix("_")
                appendLine(
                    "| `${meta.function}(\"${meta.example}\")` | `\"${meta.example}\"` | " +
                        "`$expanded` | ${meta.usedFor} |",
                )
            }
        }
        return this
    }

    /**
     * Resolves `${CONST_NAME}` interpolations in a tag prefix using [staticTags].
     * For example: `"${PROFILE_ITEM_PREFIX}"` → `"profile_item_"`.
     */
    private fun resolveInterpolatedPrefix(prefix: String, staticTags: Map<String, String>): String {
        val constRef = Regex("""\$\{(\w+)\}""").find(prefix)?.groupValues?.get(1)
            ?: return prefix
        return staticTags[constRef] ?: prefix
    }

    // ─── Metadata for dynamic functions ─────────────────────────────────────────
    //
    // The "Used for" and "Input example" columns require domain knowledge not
    // derivable from TestTags.kt source.  Hardcoded here; update when adding a
    // new dynamic function.

    private data class DynamicMeta(
        val function: String,
        val category: String,
        val example: String,
        val usedFor: String,
    )

    // A flat lookup table: every branch is one row, and there is no logic to
    // extract. Splitting it would make the table harder to read and to extend —
    // adding a dynamic test tag means adding a row here, and that should stay a
    // one-line change.
    @Suppress("CyclomaticComplexMethod")
    private fun dynamicMeta(fnName: String, prefix: String): DynamicMeta = when (fnName) {
        "navTab" -> DynamicMeta(fnName, "Navigation", "Today", "Bottom nav tabs")

        "CalendarSync.providerSegment" ->
            DynamicMeta(fnName, "Calendar sync", "Google Calendar", "Provider segments in the panel")

        "CalendarSync.googleCalendarRow" ->
            DynamicMeta(fnName, "Calendar sync", "primary-cal", "One row per writable Google calendar")

        "settingsTab" -> DynamicMeta(fnName, "Settings", "Interface", "Settings nav rail tabs")

        "Settings.content" -> DynamicMeta(fnName, "Settings", "Interface", "Settings tab content area")

        "menuItem" -> DynamicMeta(fnName, "Menu", "Settings", "Menu bottom sheet items")

        "taskItem" -> DynamicMeta(fnName, "Tasks", "Buy milk", "Task list rows")

        "taskCheckbox" -> DynamicMeta(fnName, "Tasks", "Buy milk", "Task checkboxes")

        "noteItem" -> DynamicMeta(fnName, "Notes", "01BXFF...", "Note cards (desktop unit tests)")

        "noteItemByTitle" -> DynamicMeta(fnName, "Notes", "Meeting notes", "Note cards (automation)")

        "savedAgendaCard" -> DynamicMeta(fnName, "Agenda", "Work", "Saved agenda cards")

        "agendaSection" -> DynamicMeta(fnName, "Agenda", "Today", "Agenda section headers")

        "agendaSectionTemplate" ->
            DynamicMeta(fnName, "Agenda", "By tag", "Section *types* in the add-section sheet")

        "agendaSelectorOption" ->
            DynamicMeta(fnName, "Agenda", "work", "Values in the section parameter picker")

        "projectCard" -> DynamicMeta(fnName, "Projects", "Project Alpha", "Project cards")

        "pomodoroTaskChip" -> DynamicMeta(fnName, "Pomodoro", "Buy milk", "Pomodoro focus-task chips")

        "genUi" -> DynamicMeta(fnName, "AI", "whatsnew", "GenUI surfaces")

        "tagRename" -> DynamicMeta(fnName, "Tags", "inbox", "Rename affordance on a tag card")

        "taskAction" -> DynamicMeta(fnName, "Dialog", "Archive", "Long-press action rows")

        "Dialog.title" -> DynamicMeta(fnName, "Dialog", "discard", "Dialog title")

        "profileItem" -> DynamicMeta(fnName, "Profile", "Personal", "Profile list items")

        // A new dynamic function must be described here, not silently dropped:
        // "Used for" and the example input are documentation, not derivable from source.
        else -> error(
            "TagsMd.dynamicMeta has no entry for TestTags.$fnName (prefix \"$prefix\"). " +
                "Add a DynamicMeta(...) row so the function appears in TAGS.md.",
        )
    }

    // ─── Category inference for static constants ─────────────────────────────────

    /**
     * Nested-object name → TAGS.md subsection. The owning object is where the author
     * already grouped these constants in `TestTags.kt`.
     */
    private val ownerSections: Map<String, String> = mapOf(
        "Pomodoro" to "Pomodoro",
        "Settings" to "Settings",
        "Dialog" to "Dialog",
        "DatePicker" to "Dialog",
        "EditorOverflow" to "Editor Overflow menu",
        // The start/stop chip on the task detail. Filed under Tasks rather than
        // given its own heading because it is a row of the task editor, the same
        // call the recurrence and priority rows make — a heading per control
        // would make the table a list of two-row sections.
        "TimeTracking" to "Tasks",
        // Settings → Calendar. Filed under its own heading rather than under
        // Settings because it is the only panel there that configures a *remote*
        // account: a reader looking for a control in Settings should find out which
        // ones need a Google grant.
        "CalendarSync" to "Calendar sync",
        // Inside `SimpleFilterSheet`, a ModalBottomSheet — rendered in a separate
        // semantics root on desktop, so these exist for the Android/Maestro tier.
        "SearchFilter" to "Search filters",
    )

    /**
     * Name fragment → TAGS.md subsection for top-level constants. Order matters:
     * the first matching fragment wins, so more specific fragments come first.
     * Matching on a fragment rather than a pure prefix keeps
     * `PROJECT_EDITOR_NOTIFICATION_HOST` with the other Projects constants.
     */
    private val prefixSections: List<Pair<String, String>> = listOf(
        "AUTH_" to "Auth",
        "NAV_" to "Navigation",
        "TOP_BAR_" to "Navigation",
        "MENU_" to "Navigation",
        "TASK_EDITOR_" to "Tasks",
        "TASK_CONTEXT_" to "Tasks",
        "TASKS_" to "Tasks",
        "TASK_" to "Tasks",
        "PRIORITY_" to "Tasks",
        // Recurrence options live in the task editor's recurrence picker, so
        // they belong with the other task-editor rows. Anchored like every other
        // entry: an unanchored match would also swallow unrelated names.
        "RECURRENCE_" to "Tasks",
        "AGENDA_" to "Agenda",
        "SAVED_" to "Agenda",
        "POMODORO_" to "Pomodoro",
        "TAGS_" to "Tags",
        "TAG_" to "Tags",
        "NOTE" to "Notes",
        "DIALOG_" to "Dialog",
        "OVERFLOW_" to "Editor Overflow menu",
        "SNACKBAR_" to "Snackbar / transient UI",
        "BACKUP_" to "Backup",
        "PROFILE_" to "Profile",
        "SETTINGS_" to "Settings",
        "GENUI_" to "AI",
        "CALENDAR_" to "Calendar",
        "SEARCH_" to "Search",
        "PROJECT_" to "Projects",
    )

    /**
     * Maps a constant's (possibly qualified) name to the TAGS.md subsection it
     * belongs under. Returns `"Other"` when no rule matches, which
     * [appendStaticConstants] treats as an error rather than silently omitting it.
     *
     * Prefixes are anchored to the start of the name. An unanchored match is wrong in
     * both directions: `TOP_BAR_` would drag `BACKUP_TOP_BAR_BACK` into Navigation, and
     * `MENU_` would drag `TASK_CONTEXT_MENU_SHEET` there too. A screen's own
     * notification host is a suffix pattern instead, so `PROJECT_EDITOR_NOTIFICATION_HOST`
     * still lands under Projects while `CHAT_NOTIFICATION_HOST` lands under the hosts.
     */
    private fun staticCategoryOf(qualifiedName: String): String {
        val owner = qualifiedName.substringBeforeLast('.', missingDelimiterValue = "")
        if (owner.isNotEmpty()) return ownerSections[owner] ?: "Other"
        prefixSections.firstOrNull { (prefix, _) -> qualifiedName.startsWith(prefix) }
            ?.let { return it.second }
        return if (qualifiedName.endsWith("_NOTIFICATION_HOST")) "Snackbar / transient UI" else "Other"
    }

    // ─── TAGS.md parsing ─────────────────────────────────────────────────────────

    private const val BEGIN_MARKER = "<!-- GENERATED:BEGIN -->"
    private const val END_MARKER = "<!-- GENERATED:END -->"

    /** Char offsets around the generated section, or `null` if a marker is missing. */
    private data class MarkerSpan(
        /** Start of the BEGIN marker line. */
        val begin: Int,
        /** First character after the BEGIN marker line — where generated content starts. */
        val contentStart: Int,
        /** Start of the END marker line. */
        val end: Int,
    )

    /**
     * Locates the marker lines in [content].
     *
     * The markers are matched as *whole lines*, not as substrings. A plain
     * `indexOf` matches the first occurrence anywhere, so a sentence in the
     * hand-written header that mentions the marker hijacks it — the generated
     * section then gets appended to the header instead of replacing the real one,
     * duplicating the whole catalogue.
     */
    private fun findMarkers(content: String): MarkerSpan? {
        val beginLine = content.lines().indexOfFirst { it.trim() == BEGIN_MARKER }
        if (beginLine < 0) return null
        val endLine = content.lines().indexOfFirst { it.trim() == END_MARKER }
        if (endLine < 0 || endLine < beginLine) return null
        return MarkerSpan(
            // `begin` cuts *at* the BEGIN marker so a rewrite re-emits it; `contentStart`
            // is the first character after the marker line, so extraction does not
            // return the marker as if it were generated content.
            begin = content.offsetOfLine(beginLine),
            contentStart = content.offsetOfLine(beginLine + 1),
            end = content.offsetOfLine(endLine),
        )
    }

    private fun String.offsetOfLine(lineIndex: Int): Int {
        var offset = 0
        repeat(lineIndex) {
            val newline = indexOf('\n', offset)
            if (newline < 0) return length
            offset = newline + 1
        }
        return offset
    }

    /**
     * Reads [tagsMdPath] and returns the content between the BEGIN and END marker
     * lines, or `null` if either marker line is missing.
     */
    fun extractGeneratedSection(): String? {
        val content = Files.readString(Paths.get(tagsMdPath))
        val span = findMarkers(content) ?: return null
        return content.substring(span.contentStart, span.end)
    }

    /**
     * Replaces the content between the marker lines in [tagsMdPath] with [newSection],
     * leaving everything before BEGIN and after END untouched.
     */
    fun replaceGeneratedSection(newSection: String) {
        val content = Files.readString(Paths.get(tagsMdPath))
        val span = checkNotNull(findMarkers(content)) {
            "$tagsMdPath needs a line containing exactly `$BEGIN_MARKER` and a line " +
                "containing exactly `$END_MARKER`."
        }
        val updated = buildString {
            append(content, 0, span.begin)
            append(BEGIN_MARKER).append('\n')
            append(newSection)
            append(content, span.end, content.length)
        }
        Files.writeString(Paths.get(tagsMdPath), updated)
    }
}

@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.flows.desktop

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import co.touchlab.kermit.Logger
import com.singularity.todo.core.backup.ArchiveImporter
import com.singularity.todo.core.backup.BackupCodec
import com.singularity.todo.core.backup.BackupImporter
import com.singularity.todo.core.backup.BackupPayload
import com.singularity.todo.core.backup.BackupRepository
import com.singularity.todo.core.backup.ChecklistItemDto
import com.singularity.todo.core.backup.JvmBackupCodec
import com.singularity.todo.core.backup.TaskDto
import com.singularity.todo.core.database.AppDatabase
import com.singularity.todo.core.database.AppDatabaseFactory
import com.singularity.todo.core.database.RoomUnitOfWork
import com.singularity.todo.core.database.UnitOfWork
import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.files.FileSourceFactory
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.JvmFileSourceFactory
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.importer.GenerateProfile
import com.singularity.todo.importer.SyntheticArchive
import com.singularity.todo.importer.SyntheticBackupArchive
import com.singularity.todo.importer.SyntheticBackupGenerator
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.buildDesktopAppKoin
import com.singularity.todo.test.helpers.clickTagScrolled
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File
import kotlin.time.Instant

/*
 * The dataset the desktop half of the frame-time baseline measures.
 *
 * The dataset is the one of the phone (#3090): the same generator, the same
 * seed and the same profile the Android run measured, restored through the
 * app's own importer. A dataset the test makes up is a dataset no other
 * platform ever saw, and a number measured on it describes the test rather
 * than the app.
 */

/** The seed of the shared dataset: the seed the phone measured with. */
internal const val DATASET_SEED = 42L

/**
 * The moment the dataset is measured at.
 *
 * The generator writes its dates from the first of January 2025, so a clock
 * that stands somewhere else would put every task of the dataset in the
 * past, and the timeline the run measures would be empty.
 */
internal val DATASET_NOW: Instant = Instant.parse("2025-01-01T10:00:00Z")

/**
 * The day the timeline of the dataset starts at.
 *
 * The agenda reads its "today" from the clock of the app and the time zone of
 * the machine, so the day is read the same way here: the future of the
 * dataset is what is due after this day.
 */
internal val DATASET_TODAY: LocalDate =
    DATASET_NOW.toLocalDateTime(TimeZone.currentSystemDefault()).date

/**
 * How many kinds of relations a task can hold.
 *
 * Children, tags, dependencies, notes and checklist items - the five kinds
 * the detail screen draws a section for. A task that holds all of them draws
 * all of them, and a run that checks the relations of such a task checks every
 * section the screen can show.
 */
internal const val MAX_RELATION_KINDS = 5

/** What the run opened, and what the report says about it. */
internal class FrameTimeSubject(
    val report: Map<String, Any>,
    val projectName: String? = null,
    val projectTaskCount: Int? = null,
    val projectTaskTags: List<String> = emptyList(),
    val task: FrameTimeOpenedTask? = null,
)

/** The task the detail screen opened, and what is attached to it. */
internal class FrameTimeOpenedTask(
    val title: String,
    val relations: FrameTimeRelations,
    val checklistItems: List<ChecklistItemDto>,
    val relatedTaskIds: List<String>,
)

/** How much is attached to a task, by the kind of what is attached. */
internal class FrameTimeRelations(
    val children: Int,
    val tags: Int,
    val dependencies: Int,
    val notes: Int,
    val checklist: Int,
    val childTitles: List<String> = emptyList(),
    val noteTitles: List<String> = emptyList(),
) {
    val total: Int get() = children + tags + dependencies + notes + checklist

    /**
     * How many kinds of relations the task holds.
     *
     * The kinds are what the run can check on the screen: a relation the
     * screen does not draw is a relation the run cannot promise anything
     * about. The row the run opens is the one with the most kinds, so the
     * kinds that are counted are the kinds that are drawn.
     */
    val kinds: Int
        get() = listOf(children, tags, dependencies, notes, checklist).count { it > 0 }

    /** The relations as the report carries them. */
    fun asReport(): Map<String, Any> =
        mapOf(
            "children" to children,
            "tags" to tags,
            "dependencies" to dependencies,
            "notes" to notes,
            "checklist" to checklist,
        )
}

// ─── the dataset ──────────────────────────────────────────────────────────

/**
 * The dataset the run measured: the archive of the shared generator, and
 * what is in it.
 *
 * The archive is written by the same code that wrote the archive the
 * phone restored, and the rows reach the app through the app's own
 * importer - the restore path the user takes, not a write into the
 * database that skips it.
 */
internal class FrameTimeDataset(
    val archive: SyntheticArchive,
    val relations: Map<String, FrameTimeRelations>,
    val tasksByTag: Map<String, TaskDto>,
    val projectsByTag: Map<String, String>,
    val checklistByTask: Map<String, List<ChecklistItemDto>>,
    val database: AppDatabase,
    val projectTaskCounts: Map<String, Int>,
    val taskTagsByProject: Map<String, List<String>>,
    val dependenciesByTask: Map<String, List<String>>,
    val richestTimelineTask: TaskDto?,
) {
    /**
     * The project with the most tasks of the dataset.
     *
     * The Android run measured the task list of this project, and the desktop
     * measures the opening of it: the two platforms have to look at the same
     * project, or their numbers are the numbers of two different projects.
     * The name breaks a tie, so every run of every machine chooses the same
     * one.
     */
    fun projectWithMostTasks(): String {
        check(projectTaskCounts.isNotEmpty()) { "the dataset holds no project at all" }
        return projectTaskCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .first()
            .key
    }

    /** What the report says about the dataset the runs measured. */
    fun report(): Map<String, Any?> {
        val manifest = archive.dataset.manifest
        val counts = manifest.actualCounts
        return mapOf(
            "generator" to "synthetic-backup-generator (#3090)",
            "profile" to manifest.profile,
            "seed" to manifest.seed,
            "payloadChecksum" to manifest.payloadChecksum,
            "restoredThrough" to "the app's own importer (BackupRepository.import)",
            "database" to
                "the production Room database of the desktop app (AppDatabaseFactory.build, " +
                "the same file-backed database the app opens) - the measured screens read " +
                "the rows through the real queries, the real indexes and the real " +
                "invalidation, not through an in-memory stand-in",
            "counts" to
                mapOf(
                    "tasks" to counts.tasks,
                    "projects" to counts.projects,
                    "tags" to counts.tags,
                    "notes" to counts.notes,
                    "taskTags" to counts.taskTags,
                    "taskDependencies" to counts.taskDependencies,
                    "checklistItems" to counts.checklistItems,
                ),
        )
    }
}

/**
 * Generates the shared dataset and restores it through the app.
 *
 * The generator is the one of #3090 with the profile and the seed the
 * phone measured, so the desktop measures the same rows the phone did.
 * The restore goes through the importer of the app: an archive the app
 * refuses is a fact about the app, and a dataset written straight into
 * the database is a dataset the app never had.
 *
 * The database is the production one - the same Room, the same driver and
 * the same pragmas the user's own rows live in. A baseline measured on a
 * stand-in for the database describes the stand-in: the queries, the
 * indexes and the invalidation the screens actually pay for are the ones
 * this baseline has to include.
 */
internal suspend fun restoreFrameTimeDataset(): FrameTimeDataset {
    val directory = File(repoRoot(), "build/perf/frame-time-desktop-dataset")
    directory.mkdirs()
    val archive = File(directory, "synthetic-${GenerateProfile.TASKS_10K.cliName}.zip")
    val params = SyntheticBackupGenerator.defaultParams(GenerateProfile.TASKS_10K, DATASET_SEED)
    val written = SyntheticBackupArchive.write(params, archive.path)

    // The rows go into a database of their own, and every test of the class
    // mounts the app on that very database: the restore is the preparation of
    // the screen, and it is done once.
    val database = productionDatabase(directory)
    val koin =
        buildDesktopAppKoin(
            clock = FakeClock(DATASET_NOW),
            overrides = frameTimeRestoreModule(database),
        ).koin

    val started = System.nanoTime()
    val restored = koin.get<BackupRepository>().import(archive.path)
    check(restored.isSuccess) {
        "the shared dataset could not be restored: ${restored.exceptionOrNull()}"
    }
    println(
        "FRAME_TIME_PROGRESS dataset restored in ${(System.nanoTime() - started) / 1_000_000}ms " +
            "tasks=${written.dataset.payload.tasks.size}",
    )

    val payload = written.dataset.payload
    val relations = frameTimeRelationsOf(payload)
    return FrameTimeDataset(
        archive = written,
        relations = relations,
        tasksByTag = payload.tasks.associateBy { TestTags.taskItem(it.title) },
        projectsByTag = payload.projects.associate { TestTags.projectCard(it.name) to it.name },
        checklistByTask = payload.checklistItems.groupBy { it.taskId },
        database = database,
        projectTaskCounts = frameTimeProjectTaskCounts(payload),
        taskTagsByProject = frameTimeTaskTagsByProject(payload),
        dependenciesByTask = frameTimeDependenciesByTask(payload),
        richestTimelineTask = frameTimeRichestTimelineTask(payload, relations, DATASET_TODAY),
    )
}

/**
 * The task of the timeline that holds the most kinds of relations.
 *
 * The timeline of the app shows the tasks that are still ahead: the ones that
 * are due after today and are not finished. The run opens the one of them
 * that holds the most kinds of relations - children, tags, dependencies, notes
 * and checklist items - because the kinds are what the detail screen draws a
 * section for. A task that holds three of the five kinds draws three
 * sections, and the baseline of such a task is the baseline of a screen
 * without the other two.
 *
 * The amount breaks the tie: a task with three kinds and nine rows of them
 * draws more than a task with three kinds and three rows. The title breaks
 * the last tie, so every machine of every run chooses the same task.
 *
 * A task that is a child of another task is not a row of the timeline - it
 * lives under its parent - so it is not a candidate.
 */
internal fun frameTimeRichestTimelineTask(
    payload: BackupPayload,
    relations: Map<String, FrameTimeRelations>,
    today: LocalDate,
): TaskDto? =
    payload.tasks
        .filter { task ->
            val dueDate = task.dueDate ?: return@filter false
            dueDate > today.toString() &&
                task.completedAt == null &&
                task.archivedAt == null &&
                task.parentTaskId == null
        }.sortedWith(
            compareByDescending<TaskDto> { relations.getValue(it.id).kinds }
                .thenByDescending { relations.getValue(it.id).total }
                .thenBy { it.title },
        ).firstOrNull()

/**
 * How many tasks every project of the dataset holds.
 *
 * The counts are the ones of the generator's own payload, so the project the
 * run opens is chosen by the data and not by the order of the screen.
 */
internal fun frameTimeProjectTaskCounts(payload: BackupPayload): Map<String, Int> {
    val counts = payload.tasks.groupingBy { it.projectId }.eachCount()
    return payload.projects.associate { project -> project.name to (counts[project.id] ?: 0) }
}

/**
 * The tags of the task rows of every project.
 *
 * The rows of the project detail are the tasks of the project, and their tags
 * are what tells one task from another on the screen.
 */
internal fun frameTimeTaskTagsByProject(payload: BackupPayload): Map<String, List<String>> {
    val names = payload.projects.associate { it.id to it.name }
    return payload.tasks
        .filter { it.projectId != null }
        .groupBy { it.projectId!! }
        .mapNotNull { (projectId, tasks) ->
            val name = names[projectId] ?: return@mapNotNull null
            name to tasks.map { TestTags.taskItem(it.title) }
        }.toMap()
}

/**
 * The tasks every task waits for.
 *
 * The ids are the ones the detail screen draws as the related tasks of the
 * task, so the run can tell a section with rows from a section with a header.
 */
internal fun frameTimeDependenciesByTask(payload: BackupPayload): Map<String, List<String>> =
    payload.taskDependencies
        .groupBy { it.taskId }
        .mapValues { (_, dependencies) -> dependencies.map { it.dependsOnTaskId } }

/**
 * The database the run measures on: the production one, on a file of its own.
 *
 * The file is the same kind of file the desktop app opens - a real SQLite
 * database with WAL, the pragmas of the platform and the migrations of the
 * app - and it is a file of its own, so the run never touches the rows of
 * the developer. The rows of the previous run are removed first: a run
 * measures the rows it restored itself.
 */
private fun productionDatabase(directory: File): AppDatabase {
    val path = File(directory, "frame-time.db")
    path.delete()
    File(path.path + "-wal").delete()
    File(path.path + "-shm").delete()
    return AppDatabaseFactory.build(createSqlDriver(), path.path)
}

/**
 * The bindings the restore needs.
 *
 * The harness binds the file system, the codec and the file source of the
 * tests - they do nothing. The restore goes through the real ones, and the
 * importer is bound against the same database the screens will read: the
 * restore is one transaction over the database of the run, exactly as the
 * restore the user takes is one transaction over their own.
 */
internal fun frameTimeRestoreModule(database: AppDatabase): Module =
    module {
        single<AppDatabase> { database }
        single<UnitOfWork> { RoomUnitOfWork(database) }
        single<FileSystem> { JvmFileSystem() }
        single<BackupCodec> { JvmBackupCodec() }
        single<FileSourceFactory> { JvmFileSourceFactory() }
        single<ArchiveImporter> {
            BackupImporter(
                Logger.withTag("BackupImporter"),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                createFileSource = get(),
                tasksSettings = get(),
                database = database,
            )
        }
    }

/**
 * The bindings the measured screens need.
 *
 * The rows are already in the database of the class, so the only thing the
 * test has to say is which database the app reads - and that a write it may
 * do goes through the real unit of work of the production database.
 */
internal fun frameTimeDatasetModule(database: AppDatabase): Module =
    module {
        single<AppDatabase> { database }
        single<UnitOfWork> { RoomUnitOfWork(database) }
    }

/**
 * What is attached to each task of the dataset.
 *
 * The counts are the ones of the generator's own payload, and they are
 * counted the way the Android run counts them: children are the tasks
 * below a task, the dependencies are the ones a task waits for, and the
 * tags, the notes and the checklist items are the rows that name the
 * task.
 */
internal fun frameTimeRelationsOf(payload: BackupPayload): Map<String, FrameTimeRelations> {
    val children = HashMap<String, Int>()
    val childTitles = HashMap<String, MutableList<String>>()
    payload.tasks.forEach { task ->
        task.parentTaskId?.let { parent ->
            children[parent] = (children[parent] ?: 0) + 1
            childTitles.getOrPut(parent) { mutableListOf() }.add(task.title)
        }
    }
    val tags = payload.taskTags.groupingBy { it.taskId }.eachCount()
    val dependencies = payload.taskDependencies.groupingBy { it.taskId }.eachCount()
    val notes =
        payload.notes
            .mapNotNull { it.taskId }
            .groupingBy { it }
            .eachCount()
    val noteTitles =
        payload.notes
            .mapNotNull { note -> note.taskId?.let { it to note.title } }
            .groupBy({ it.first }, { it.second })
    val checklist = payload.checklistItems.groupingBy { it.taskId }.eachCount()
    return payload.tasks.associate { task ->
        task.id to
            FrameTimeRelations(
                children = children[task.id] ?: 0,
                tags = tags[task.id] ?: 0,
                dependencies = dependencies[task.id] ?: 0,
                notes = notes[task.id] ?: 0,
                checklist = checklist[task.id] ?: 0,
                childTitles = childTitles[task.id].orEmpty(),
                noteTitles = noteTitles[task.id].orEmpty(),
            )
    }
}

/**
 * The row of the timeline the detail screen opens.
 *
 * The row with the most of what a task can hold wins, because the first
 * row of a sorted list is usually the emptiest one. The kinds of the
 * relations decide before their amount does: the run opens the row that
 * holds the most kinds of relations, so every kind that is counted for the
 * measured task is also a kind that is drawn on its screen and can be
 * checked. The amount breaks the tie.
 *
 * The richest row of the timeline is deep in the list - the timeline holds
 * thousands of rows - so the list is asked to scroll to it before it is
 * opened. The scroll is not part of the measurement: the run measures the
 * opening of the screen, not the way to its row.
 *
 * The relations of the row that wins travel into the report, so the number
 * of the screen can be read next to the task it was measured on.
 */
internal fun DesktopComposeUiTest.frameTimeSubjectOfTheRichestRow(dataset: FrameTimeDataset): FrameTimeSubject {
    val richest = dataset.richestTimelineTask
    if (richest != null) {
        scrollTimelineToRow(TestTags.taskItem(richest.title))
    }
    val visible = taskRows()
    check(visible.isNotEmpty()) { "the agenda showed no task row to open" }
    val candidates = visible.mapNotNull { dataset.tasksByTag[it] }
    check(candidates.isNotEmpty()) {
        "none of the ${visible.size} visible rows belongs to the dataset: $visible"
    }
    val chosen =
        candidates.firstOrNull { it.id == richest?.id }
            ?: candidates.maxWith(
                compareBy({ dataset.relations.getValue(it.id).kinds }, { dataset.relations.getValue(it.id).total }),
            )
    val relations = dataset.relations.getValue(chosen.id)
    check(relations.total > 0) {
        "the task '${chosen.title}' the run opened holds nothing at all: a detail " +
            "screen that shows a title and nothing else measures nothing"
    }
    check(relations.kinds == MAX_RELATION_KINDS) {
        "the task '${chosen.title}' the run opened holds ${relations.kinds} kinds of " +
            "relations of the $MAX_RELATION_KINDS the detail screen draws a section for: " +
            "the baseline of a task that carries only some of them is the baseline of a " +
            "screen without the others"
    }
    return FrameTimeSubject(
        report =
            mapOf(
                "screen" to "Task detail",
                "task" to chosen.title,
                "relations" to relations.asReport(),
            ),
        task =
            FrameTimeOpenedTask(
                title = chosen.title,
                relations = relations,
                checklistItems = dataset.checklistByTask[chosen.id].orEmpty(),
                relatedTaskIds = dataset.dependenciesByTask[chosen.id].orEmpty(),
            ),
    )
}

/**
 * Brings the row of a task on the screen of the timeline.
 *
 * The list is asked to scroll to the row instead of being dragged towards it:
 * a drag moves the list by a distance that depends on the window and on the
 * row heights, and the row the run needs can be thousands of rows deep. A row
 * the timeline does not hold cannot be scrolled to - the answer is then false,
 * and the caller falls back to the rows it can see.
 */
private fun DesktopComposeUiTest.scrollTimelineToRow(tag: String): Boolean =
    try {
        val index = scrollableHolding(TASK_ROW_PREFIX)
        onAllNodes(hasScrollAction(), useUnmergedTree = true)[index]
            .performScrollToNode(hasTestTag(tag))
        waitForIdle()
        tagExists(tag)
    } catch (_: Throwable) {
        false
    }

/**
 * Opens a section until its rows are on the screen.
 *
 * The header of a section is a toggle: a click opens a closed section and
 * closes an open one. The rows are therefore looked for after the first
 * click and after a second one, and a section that was open when the run
 * found it is left open.
 */
private fun DesktopComposeUiTest.openSectionWithRows(
    sectionTag: String,
    rows: List<String>,
    rowTag: (String) -> String,
) {
    if (rows.isEmpty()) return
    if (rows.all { tagExists(rowTag(it)) }) return
    clickTagScrolled(sectionTag)
    waitForIdle()
    if (rows.all { tagExists(rowTag(it)) }) return
    // The click closed a section that was open: open it again.
    clickTagScrolled(sectionTag)
    waitForIdle()
}

/**
 * How many tasks the detail screen of a project draws.
 *
 * The project detail shows a preview of the tasks of the project and a way
 * into the full list - the list itself lives on a screen the desktop does not
 * have. The number mirrors the preview limit of the screen.
 */
internal const val PROJECT_PREVIEW_LIMIT = 5

/**
 * Checks that the project the run opened is on the screen, with its tasks.
 *
 * A detail screen that shows no task of the project measures an empty screen,
 * and a screen that shows the tasks of another project measures that one: the
 * rows of the screen are compared with the rows of the dataset, and the count
 * of the project is read from the screen itself.
 */
internal fun DesktopComposeUiTest.verifyFrameTimeProject(subject: FrameTimeSubject) {
    val name = subject.projectName ?: return
    val count = subject.projectTaskCount ?: return
    check(count > PROJECT_PREVIEW_LIMIT) {
        "the project '$name' holds $count tasks: a project with fewer than " +
            "$PROJECT_PREVIEW_LIMIT of them has no preview to draw"
    }
    // The preview of the project is what the screen is about: a detail screen
    // that draws no task of the project measures an empty screen. The rows
    // that belong to the project are the rows of the dataset's own project -
    // a row of another project on the screen is not a row of this one.
    val own = taskRows().filter { it in subject.projectTaskTags }
    check(own.isNotEmpty()) {
        "the detail screen of '$name' draws no task of the project"
    }
    // The screen says how many tasks the project holds: the count of the
    // dataset, read back from the screen - the proof that the screen is this
    // project and not another one.
    check(onAllNodes(hasText("See all $count tasks")).fetchSemanticsNodes().isNotEmpty()) {
        "the detail screen of '$name' does not say that it holds $count tasks"
    }
}

/**
 * Checks that the relations of the opened task are on the screen.
 *
 * The detail screen draws a section per kind of relation, and it draws
 * the section of the checklist and of the subtasks whether they are
 * empty or not - the header is the way in. The sections of the
 * dependencies and of the tags are drawn when the task has them, so
 * their presence is the fact that the relations reached the screen.
 *
 * The header of the related tasks is not enough: the rows of the related
 * tasks are the content of the section, and a screen that draws the header
 * and none of the rows has drawn the promise and not the thing. The same
 * holds for the subtasks and for the notes: every kind of relation the
 * report counts for the task is checked on the screen, so a relation that
 * stopped being drawn cannot hide behind a count.
 */
internal fun DesktopComposeUiTest.verifyFrameTimeRelations(subject: FrameTimeSubject) {
    val task = subject.task ?: return
    if (task.relations.dependencies > 0) {
        check(tagExists(TestTags.TASK_DETAIL_RELATED_TASKS_SECTION)) {
            "the task '${task.title}' waits for ${task.relations.dependencies} other tasks, " +
                "but the detail screen draws no related-tasks section"
        }
        // The section is a closed header until it is opened, and the rows
        // live in the open section.
        if (task.relatedTaskIds.any { !tagExists(TestTags.relatedTaskItem(it)) }) {
            clickTagScrolled(TestTags.TASK_DETAIL_RELATED_TASKS_SECTION)
            waitForIdle()
        }
        task.relatedTaskIds.forEach { id ->
            check(tagExists(TestTags.relatedTaskItem(id))) {
                "the related task '$id' of '${task.title}' is not on the screen"
            }
        }
    }
    if (task.relations.tags > 0) {
        check(tagExists(TestTags.TASK_EDITOR_TAGS_ROW)) {
            "the task '${task.title}' carries ${task.relations.tags} tags, but the detail " +
                "screen draws no tags row"
        }
        val label = textOf(TestTags.TASK_EDITOR_TAGS_ROW)
        check(label.contains(task.relations.tags.toString())) {
            "the task '${task.title}' carries ${task.relations.tags} tags, but the tags " +
                "row of the detail screen says '$label'"
        }
    }
    if (task.relations.children > 0) {
        check(tagExists(TestTags.SUBTASKS_SECTION)) {
            "the task '${task.title}' holds ${task.relations.children} subtasks, but the " +
                "detail screen draws no subtasks section"
        }
        // The subtasks live under their own header, and a long list keeps
        // its tail behind a "show all" - both are opened before the rows are
        // looked for.
        openSectionWithRows(
            sectionTag = TestTags.SUBTASKS_SECTION,
            rows = task.relations.childTitles,
            rowTag = TestTags::subtaskItem,
        )
        if (task.relations.childTitles.any { !tagExists(TestTags.subtaskItem(it)) } &&
            tagExists(TestTags.SUBTASKS_SHOW_ALL)
        ) {
            clickTagScrolled(TestTags.SUBTASKS_SHOW_ALL)
            waitForIdle()
        }
        task.relations.childTitles.forEach { title ->
            check(tagExists(TestTags.subtaskItem(title))) {
                "the subtask '$title' of '${task.title}' is not on the screen"
            }
        }
    }
    if (task.relations.notes > 0) {
        check(tagExists(TestTags.LOGBOOK_SECTION)) {
            "the task '${task.title}' holds ${task.relations.notes} notes, but the detail " +
                "screen draws no logbook section"
        }
        // The logbook keeps its notes under its header until it is opened.
        openSectionWithRows(
            sectionTag = TestTags.LOGBOOK_SECTION,
            rows = task.relations.noteTitles,
            rowTag = TestTags::logbookNoteByTitle,
        )
        task.relations.noteTitles.forEach { title ->
            check(tagExists(TestTags.logbookNoteByTitle(title))) {
                "the note '$title' of '${task.title}' is not on the screen"
            }
        }
    }
    if (task.checklistItems.isEmpty()) return

    // The checklist is a closed header until it is opened; the items are
    // what the detail screen shows of the task's checklist.
    check(tagExists(TestTags.TASK_CHECKLIST_SECTION)) {
        "the detail screen of '${task.title}' draws no checklist section"
    }
    if (task.checklistItems.any { !tagExists(TestTags.checklistItem(it.id, it.title)) }) {
        clickTagScrolled(TestTags.TASK_CHECKLIST_SECTION)
        waitForIdle()
    }
    task.checklistItems.forEach { item ->
        check(tagExists(TestTags.checklistItem(item.id, item.title))) {
            "the checklist item '${item.title}' of '${task.title}' is not on the screen"
        }
    }
}

/**
 * The root of the repository.
 *
 * The working directory of the test is the directory of the module, so a
 * relative path would put the report into the build directory of the
 * module - and `./gw clean` would take it away.
 */
internal fun repoRoot(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null && !File(dir, "settings.gradle.kts").isFile) {
        dir = dir.parentFile
    }
    return dir ?: error("the repository root is not above ${System.getProperty("user.dir")}")
}

@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.singularity.todo.feature.flows.desktop

import androidx.compose.ui.test.DesktopComposeUiTest
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
import com.singularity.todo.core.files.FileSourceFactory
import com.singularity.todo.core.files.FileSystem
import com.singularity.todo.core.files.JvmFileSourceFactory
import com.singularity.todo.core.files.JvmFileSystem
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.importer.GenerateProfile
import com.singularity.todo.importer.SyntheticArchive
import com.singularity.todo.importer.SyntheticBackupArchive
import com.singularity.todo.importer.SyntheticBackupGenerator
import com.singularity.todo.test.fakes.FakeAppDatabase
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.helpers.buildDesktopAppKoin
import com.singularity.todo.test.helpers.clickTagScrolled
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

/** What the run opened, and what the report says about it. */
internal class FrameTimeSubject(
    val report: Map<String, Any>,
    val projectName: String? = null,
    val task: FrameTimeOpenedTask? = null,
)

/** The task the detail screen opened, and what is attached to it. */
internal class FrameTimeOpenedTask(
    val title: String,
    val relations: FrameTimeRelations,
    val checklistItems: List<ChecklistItemDto>,
)

/** How much is attached to a task, by the kind of what is attached. */
internal class FrameTimeRelations(
    val children: Int,
    val tags: Int,
    val dependencies: Int,
    val notes: Int,
    val checklist: Int,
) {
    val total: Int get() = children + tags + dependencies + notes + checklist

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
) {
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
    val database = FakeAppDatabase()
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
    return FrameTimeDataset(
        archive = written,
        relations = frameTimeRelationsOf(payload),
        tasksByTag = payload.tasks.associateBy { TestTags.taskItem(it.title) },
        projectsByTag = payload.projects.associate { TestTags.projectCard(it.name) to it.name },
        checklistByTask = payload.checklistItems.groupBy { it.taskId },
        database = database,
    )
}

/**
 * The bindings the restore needs.
 *
 * The harness binds the file system, the codec and the file source of the
 * tests - they do nothing. The restore goes through the real ones, and the
 * importer is bound without a database: the harness keeps its rows in memory,
 * and a transaction over a database that was never opened is a failure of the
 * harness, not of the restore.
 */
internal fun frameTimeRestoreModule(database: AppDatabase): Module =
    module {
        single<AppDatabase> { database }
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
                database = null,
            )
        }
    }

/**
 * The bindings the measured screens need.
 *
 * The rows are already in the database of the class, so the only thing the
 * test has to say is which database the app reads.
 */
internal fun frameTimeDatasetModule(database: AppDatabase): Module =
    module {
        single<AppDatabase> { database }
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
    payload.tasks.forEach { task ->
        task.parentTaskId?.let { parent ->
            children[parent] = (children[parent] ?: 0) + 1
        }
    }
    val tags = payload.taskTags.groupingBy { it.taskId }.eachCount()
    val dependencies = payload.taskDependencies.groupingBy { it.taskId }.eachCount()
    val notes =
        payload.notes
            .mapNotNull { it.taskId }
            .groupingBy { it }
            .eachCount()
    val checklist = payload.checklistItems.groupingBy { it.taskId }.eachCount()
    return payload.tasks.associate { task ->
        task.id to
            FrameTimeRelations(
                children = children[task.id] ?: 0,
                tags = tags[task.id] ?: 0,
                dependencies = dependencies[task.id] ?: 0,
                notes = notes[task.id] ?: 0,
                checklist = checklist[task.id] ?: 0,
            )
    }
}

/**
 * The row of the timeline the detail screen opens.
 *
 * The row with the most of what a task can hold wins, because the first
 * row of a sorted list is usually the emptiest one. The relations of the
 * row that wins travel into the report, so the number of the screen can
 * be read next to the task it was measured on.
 */
internal fun DesktopComposeUiTest.frameTimeSubjectOfTheRichestRow(dataset: FrameTimeDataset): FrameTimeSubject {
    val visible = taskRows()
    check(visible.isNotEmpty()) { "the agenda showed no task row to open" }
    val candidates = visible.mapNotNull { dataset.tasksByTag[it] }
    check(candidates.isNotEmpty()) {
        "none of the ${visible.size} visible rows belongs to the dataset: $visible"
    }
    val chosen = candidates.maxBy { dataset.relations.getValue(it.id).total }
    val relations = dataset.relations.getValue(chosen.id)
    check(relations.total > 0) {
        "the task '${chosen.title}' the run opened holds nothing at all: a detail " +
            "screen that shows a title and nothing else measures nothing"
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
            ),
    )
}

/**
 * Checks that the relations of the opened task are on the screen.
 *
 * The detail screen draws a section per kind of relation, and it draws
 * the section of the checklist and of the subtasks whether they are
 * empty or not - the header is the way in. The sections of the
 * dependencies and of the tags are drawn when the task has them, so
 * their presence is the fact that the relations reached the screen.
 */
internal fun DesktopComposeUiTest.verifyFrameTimeRelations(subject: FrameTimeSubject) {
    val task = subject.task ?: return
    if (task.relations.dependencies > 0) {
        check(tagExists(TestTags.TASK_DETAIL_RELATED_TASKS_SECTION)) {
            "the task '${task.title}' waits for ${task.relations.dependencies} other tasks, " +
                "but the detail screen draws no related-tasks section"
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

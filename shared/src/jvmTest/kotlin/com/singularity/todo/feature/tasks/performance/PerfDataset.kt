package com.singularity.todo.feature.tasks.performance

import java.io.File

/**
 * The dataset a perf harness measures, and the form it comes in.
 *
 * The two forms are different things: an **archive** is what the app restores,
 * and it answers to the backup archive budget; a **dataset directory** is what
 * the generator writes when the dataset does not fit an archive (#3197). The
 * form decides the reader, so it is named — a path that happens to be a
 * directory is not enough to guess which reader was meant.
 */
internal data class PerfDataset(val path: String, val form: Form) {
    /** What the dataset is: a backup archive, or a generated dataset directory. */
    internal enum class Form {
        Archive,
        Directory,
    }

    /** The dataset on disk. */
    val file: File get() = File(path)

    /**
     * How much room the dataset takes, in bytes.
     *
     * A directory has no length of its own: the size is the sum of the files it
     * holds, so the report says how big the data was, not how big the folder is.
     */
    fun sizeBytes(): Long =
        if (file.isDirectory) {
            file.listFiles().orEmpty().sumOf { it.length() }
        } else {
            file.length()
        }

    companion object {
        /**
         * Picks the dataset out of the harness arguments.
         *
         * Exactly one of `--archive` and `--dataset` may be given: they are two
         * forms of the same input, and a run that names both has no single
         * answer to what it measured.
         */
        fun parse(values: Map<String, String>): PerfDataset {
            val archive = values["archive"]
            val dataset = values["dataset"]
            check(archive == null || dataset == null) {
                "two datasets to measure — pass --archive or --dataset, not both"
            }
            val path =
                archive
                    ?: dataset
                    ?: error(
                        "no dataset to measure — pass --archive=<path to a generated backup zip> " +
                            "or --dataset=<path to a generated dataset directory>",
                    )
            return PerfDataset(path = path, form = if (dataset != null) Form.Directory else Form.Archive)
        }
    }
}

/**
 * The `--key=value` arguments of a harness run.
 *
 * An argument without a value is not an argument: the gradle task passes an
 * empty `--archive=` when no archive was named, and that empty value must not
 * read as "the dataset is the empty string".
 */
internal fun perfArgs(args: Array<String>): Map<String, String> =
    args
        .mapNotNull { arg ->
            if (!arg.startsWith("--")) return@mapNotNull null
            val key = arg.removePrefix("--").substringBefore('=')
            val value = arg.substringAfter('=', "")
            if (value.isEmpty()) null else key to value
        }.toMap()

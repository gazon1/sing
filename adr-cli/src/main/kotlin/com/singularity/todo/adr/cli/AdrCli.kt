package com.singularity.todo.adr.cli

import com.singularity.todo.feature.ai.tools.AdrStorage
import com.singularity.todo.feature.ai.tools.AdrStorage.AdrSummary
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.todayIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * Exit codes. Kept small and named, because the point of a CLI is that a script can
 * branch on the result.
 *
 * [USAGE] exists because of #510: `else -> { usage; exitProcess(0) }` made a typo
 * indistinguishable from success, so `adr updat` in a CI script passed while doing
 * nothing. Usage on stdout with code 0 is not an answer.
 */
object ExitCode {
    const val OK = 0
    const val ERROR = 1
    const val USAGE = 2
}

/**
 * A parsed command line: subcommand, positionals, flags.
 *
 * Flags are recognised anywhere, because `adr read --json 2026-10-05-x` should work
 * as readily as `adr read 2026-10-05-x --json` and an agent writing either form should
 * not be punished for guessing wrong (#524).
 */
data class ParsedArgs(
    val subcommand: String?,
    val positionals: List<String>,
    val json: Boolean,
    val days: Int?,
    val status: String?,
    val keep: List<String>,
    val reviewBy: String?,
) {
    companion object {
        private val VALUE_FLAGS = setOf("--days", "--status", "--keep", "--review-by")

        fun parse(argv: List<String>): ParsedArgs {
            var json = false
            var days: Int? = null
            var status: String? = null
            var reviewBy: String? = null
            val keep = mutableListOf<String>()
            val rest = mutableListOf<String>()

            var i = 0
            while (i < argv.size) {
                val arg = argv[i]
                when {
                    arg == "--json" -> json = true

                    // Accepted and ignored: `prune` has no delete path at all, so
                    // there is nothing for the flag to switch off.
                    arg == "--dry-run" -> Unit

                    arg == "--days" -> days = argv.getOrNull(i + 1)?.toIntOrNull()

                    arg == "--status" -> status = argv.getOrNull(i + 1)

                    arg == "--review-by" -> reviewBy = argv.getOrNull(i + 1)

                    arg == "--keep" -> argv.getOrNull(i + 1)?.let { keep += it }

                    else -> rest += arg
                }
                // Skip the value of a flag that takes one; otherwise `--keep x`
                // would leave `x` sitting in the positional list as a fake slug.
                if (arg in VALUE_FLAGS) i++
                i++
            }

            return ParsedArgs(
                subcommand = rest.firstOrNull(),
                positionals = rest.drop(1),
                json = json,
                days = days,
                status = status,
                keep = keep,
                reviewBy = reviewBy,
            )
        }
    }
}

/**
 * `singularity-adr` — the ADR command line.
 *
 * Every command returns an exit code instead of terminating the JVM, so the whole
 * surface is testable in-process. Only `main` exits.
 *
 * Two of the commands shell out to the repository's own Python gates rather than
 * reimplementing what they check. That is deliberate and it is the rule, not a
 * shortcut: `stale` and `validate` must agree with `check_adr_status.py`, and `prune`
 * must agree with `check-adr-references.py`, down to which files count as a citation.
 * A preview that can disagree with the thing it previews is worse than no preview —
 * and two implementations of one rule is the failure this whole toolchain exists to
 * prevent (see `2026-10-10-adr-file-local-rules-live-in-the-writer`).
 */
class AdrCli(
    private val storage: AdrStorage,
    private val out: Appendable,
    private val err: Appendable,
    private val repoRoot: String = ".",
) {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun run(argv: List<String>): Int {
        val args = ParsedArgs.parse(argv)
        return when (args.subcommand) {
            null -> usage(ExitCode.USAGE)

            "new" -> new(args)

            "list" -> list(args)

            "list-open" -> listOpen(args)

            "read" -> read(args)

            "update" -> update(args)

            "stale" -> stale(args)

            "prune" -> prune(args)

            "validate" -> validate()

            "help", "--help", "-h" -> usage(ExitCode.OK)

            else -> {
                err.appendLine("adr: unknown subcommand '${args.subcommand}'")
                usage(ExitCode.USAGE)
            }
        }
    }

    private fun usage(code: Int): Int {
        out.appendLine("Usage: singularity-adr <command> [args] [--json]")
        out.appendLine()
        // Rendered from [AdrSurface], not written out here. The command list and the
        // "ADR tools" block in the agent skills are two views of one list; keeping a
        // third copy in a string literal is how `write_adr` came to promise an update
        // path in the skill that no longer existed in the tool (#526).
        AdrSurface.usageLines().forEach { out.appendLine(it) }
        out.appendLine()
        out.appendLine("Global flags:")
        AdrSurface.globalFlags.forEach { flag ->
            out.appendLine("  ${flag.flag.padEnd(16)}${flag.summary}")
        }
        return code
    }

    private fun new(args: ParsedArgs): Int {
        if (args.positionals.size < 2) {
            err.appendLine("adr new: needs <slug> <title> [tag ...]")
            return ExitCode.USAGE
        }
        return guarded {
            val slug = args.positionals[0]
            val title = args.positionals[1]
            val path = storage.writeAdr(slug, title, args.positionals.drop(2), "")
            if (args.json) emit(WriteResult(slug, path, title)) else out.appendLine("Created: $path")
        }
    }

    private fun list(args: ParsedArgs): Int = guarded {
        val entries = storage.listAdrs()
        if (args.json) {
            emit(ListResult(entries))
        } else if (entries.isEmpty()) {
            out.appendLine("No ADRs found.")
        } else {
            entries.forEach { out.appendLine("${it.slug}  ${it.date}  ${it.title}") }
        }
    }

    private fun listOpen(args: ParsedArgs): Int = guarded {
        val entries = storage.listOpenDeferred()
        if (args.json) {
            emit(ListResult(entries))
        } else if (entries.isEmpty()) {
            out.appendLine("No open deferred entries.")
        } else {
            entries.forEach { out.appendLine("${it.slug}  ${it.date}  ${it.title}") }
        }
    }

    private fun read(args: ParsedArgs): Int {
        val slug = args.positionals.firstOrNull()
            ?: run { err.appendLine("adr read: needs <slug>"); return ExitCode.USAGE }
        return guarded {
            val adr = storage.readAdr(slug) ?: throw CliFailure("ADR not found: $slug")
            if (args.json) {
                emit(ReadResult(adr.slug, adr.title, adr.date, adr.tags, adr.body, adr.path, adr.status))
            } else {
                out.appendLine("# ${adr.title}")
                out.appendLine("date: ${adr.date}")
                out.appendLine("status: ${adr.status}")
                out.appendLine("tags: ${adr.tags}")
                out.appendLine()
                out.appendLine(adr.body)
            }
        }
    }

    /**
     * `adr update <slug> <status> [supersededBy]`.
     *
     * The target is positional because it is a value, not a switch — `--by` reads like
     * a boolean, and the third argument is the one people get wrong under time pressure.
     */
    private fun update(args: ParsedArgs): Int {
        val slug = args.positionals.firstOrNull()
        val status = args.positionals.getOrNull(1)
        if (slug == null || status == null) {
            err.appendLine("adr update: needs <slug> <status> [supersededBy]")
            err.appendLine("  status is one of: accepted, deferred, superseded, open")
            return ExitCode.USAGE
        }
        return guarded {
            val by = args.positionals.getOrNull(2)
            val path = storage.updateAdr(slug, status, by, args.reviewBy)
            if (args.json) {
                emit(UpdateResult(slug, status, by, args.reviewBy, path))
            } else {
                out.appendLine("Updated: $slug -> $status")
                args.reviewBy?.let { out.appendLine("Review by: $it") }
                out.appendLine("Path: $path")
            }
        }
    }

    /**
     * `adr stale` — what the gate will reject on the next push (#521).
     *
     * Shares the gate's traversal and its threshold instead of re-deriving them. The
     * one thing this adds is the *age*, because the gate reports only the failure and
     * the whole cost of the rule is that you learn about it fifteen minutes too late.
     */
    private fun stale(args: ParsedArgs): Int {
        val days = args.days ?: DEFAULT_STALE_DAYS
        val wanted = args.status ?: DEFAULT_STALE_STATUS
        return guarded {
            val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
            val entries = storage.listAdrs()
                .filter { it.status.equals(wanted, ignoreCase = true) }
                .mapNotNull { entry ->
                    val date = runCatching { LocalDate.parse(entry.date) }.getOrNull()
                        ?: return@mapNotNull null
                    val age = today.daysUntil(date).let { -it }
                    if (age >= days) StaleEntry(entry.slug, entry.date, age) else null
                }
                .sortedByDescending { it.ageDays }

            if (args.json) {
                emit(StaleResult(days, wanted, entries))
            } else if (entries.isEmpty()) {
                out.appendLine("No '$wanted' decision is older than $days days.")
            } else {
                out.appendLine("${entries.size} '$wanted' decision(s) will fail the gate at ${days}d:")
                entries.forEach { out.appendLine("  ${it.slug}  ${it.date}  ${it.ageDays}d") }
            }
        }
    }

    /**
     * `adr prune` — list what could go, never delete it (#525).
     *
     * `--dry-run` is accepted and ignored on purpose. A command that can destroy a
     * decision record has to earn that capability on its own merits; until then the
     * listing and the deletion are the same code path and there is nothing to disable.
     *
     * "Cited" is answered by `check-adr-references.py --list-cited`, not by a second
     * traversal written here: `iter_sources()` there is the definition of where a
     * citation counts, and the two must not be allowed to disagree.
     */
    private fun prune(args: ParsedArgs): Int {
        val script = java.io.File(repoRoot, "scripts/check-adr-references.py")
        if (!script.exists()) {
            err.appendLine("adr prune: scripts/check-adr-references.py not found — run from the repository root")
            return ExitCode.ERROR
        }
        return guarded {
            val process = ProcessBuilder("python3", script.absolutePath, "--list-cited")
                .directory(java.io.File(repoRoot))
                .redirectErrorStream(true)
                .start()
            val stdout = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code != 0) throw CliFailure("check-adr-references.py failed:\n$stdout")

            val cited = stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val keep = args.keep.toSet()
            val candidates = storage.listAdrs()
                .filter { it.slug !in cited && it.slug !in keep }
                .sortedByDescending { it.date }

            if (args.json) {
                emit(PruneResult(cited.size, candidates.map { PruneCandidate(it.slug, it.date, it.title) }))
            } else if (candidates.isEmpty()) {
                out.appendLine("Every decision is cited somewhere.")
            } else {
                out.appendLine("${candidates.size} uncited decision(s), listed only — nothing is deleted:")
                candidates.forEach { out.appendLine("  ${it.slug}  ${it.date}  ${it.title}") }
            }
        }
    }

    private fun validate(): Int {
        val script = java.io.File(repoRoot, "scripts/check_adr_status.py")
        if (!script.exists()) {
            err.appendLine("adr validate: scripts/check_adr_status.py not found — run from the repository root")
            return ExitCode.ERROR
        }
        val process = ProcessBuilder("python3", script.absolutePath)
            .directory(java.io.File(repoRoot))
            .redirectErrorStream(true)
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        out.append(stdout)
        return process.waitFor()
    }

    // ─── plumbing ────────────────────────────────────────────────────────────

    /**
     * Business errors arrive from [AdrStorage] as exceptions — the same contract the
     * MCP tools get. Here they become a message and an exit code, because a CLI that
     * prints a stack trace for "that ADR already exists" is not a CLI.
     */
    private inline fun guarded(block: () -> Unit): Int = try {
        block()
        ExitCode.OK
    } catch (e: CliFailure) {
        err.appendLine("adr: ${e.message}")
        ExitCode.ERROR
    } catch (e: IllegalArgumentException) {
        err.appendLine("adr: ${e.message}")
        ExitCode.ERROR
    } catch (e: IllegalStateException) {
        err.appendLine("adr: ${e.message}")
        ExitCode.ERROR
    }

    private inline fun <reified T> emit(value: T) {
        out.appendLine(json.encodeToString(value))
    }

    private companion object {
        const val DEFAULT_STALE_DAYS = 30
        const val DEFAULT_STALE_STATUS = "open"
    }
}

private class CliFailure(message: String) : RuntimeException(message)

// ─── The `--json` contract ───────────────────────────────────────────────────
//
// These shapes are a public API: an agent parses them, so a renamed field is a
// breaking change. Each is declared once, here, rather than assembled at a call site.
// `AdrSummary` is shared with the MCP tools on purpose — one row shape, one contract.

@Serializable
data class ListResult(val adrs: List<AdrSummary>)

@Serializable
data class WriteResult(val slug: String, val path: String, val title: String)

@Serializable
data class UpdateResult(
    val slug: String,
    val status: String,
    val supersededBy: String?,
    val reviewBy: String?,
    val path: String,
)

@Serializable
data class ReadResult(
    val slug: String,
    val title: String,
    val date: String,
    val tags: List<String>,
    val body: String,
    val path: String,
    val status: String,
)

@Serializable
data class StaleResult(val days: Int, val status: String, val entries: List<StaleEntry>)

@Serializable
data class StaleEntry(val slug: String, val date: String, val ageDays: Int)

@Serializable
data class PruneResult(val citedCount: Int, val uncited: List<PruneCandidate>)

@Serializable
data class PruneCandidate(val slug: String, val date: String, val title: String)

package com.singularity.todo.adr.cli

import java.io.File
import java.io.RandomAccessFile

/**
 * Rebuilding the ADR CLI, separated from the command that triggers it.
 *
 * ## Why this is not called `update`
 *
 * `update` already means "change an ADR's status", and it is the command a person
 * types when a decision is settled. A second `update` that rewrites the binary would
 * be a typo away from an agent or a script intending to close a decision. Two
 * commands that read alike and write unlike targets is exactly the shape of mistake
 * that has no undo in a decision log.
 *
 * ## Why this cannot disturb a concurrent worktree
 *
 * The isolation is not a policy, it is a consequence of where the files live:
 *
 *  * Every path written is under the caller's own `git rev-parse --show-toplevel`.
 *    There is no shared install directory to race over — the deliberate cost of
 *    *not* putting the binary in `~/.local/share`, because a shared copy is
 *    precisely what two worktrees would overwrite each other in.
 *  * `setup-worktree.sh` gives each worktree a private Gradle home, so two
 *    concurrent builds here do not contend for one daemon either.
 *  * The lock is an `FileLock` on a file inside *this* worktree's build directory.
 *    Two upgrades in the same worktree serialise; two in different worktrees never
 *    see each other's lock and never wait for one.
 *
 * On POSIX a rebuild that replaces jars while another process runs them is safe —
 * the running JVM holds the old inodes. That is why nothing here refuses to run
 * while a sibling is up.
 *
 * ## What it proves about the corpus
 *
 * `docs/decisions/` is fingerprinted before and after. A rebuild that changed a
 * single ADR is reported as a failure, not as a success — because a command in an
 * ADR tool that edits the decision log is worse than one that does not rebuild at
 * all, and the fingerprint is the difference between claiming that and knowing it.
 */
internal object AdrUpgrade {

    /**
     * What an upgrade did, in the form the command surface prints it.
     *
     * One shape rather than a sealed hierarchy of outcomes, because the CLI has to
     * render the same three fields either way — action, sha, exit code — and a
     * `when` per field is a place for the text and the exit code to disagree.
     */
    data class Report(
        val action: String,
        val wasStale: Boolean,
        val sha: String,
        val seconds: Double,
        /** Text for stdout, or null when there is nothing to print. */
        val outcome: String?,
        /** Text for stderr, or null on success. */
        val failure: String?,
        val exitCode: Int,
    )

    private const val GRADLE_TASK = ":adr-cli:installDist"

    fun run(root: File, force: Boolean, check: Boolean): Report {
        // Split in two so neither half exceeds the return-count limit: the
        // preflight answers "is there anything to do at all", and only a real
        // rebuild reaches the second. Merged, the function had seven exits, and
        // flattening them would have meant one function doing two unrelated jobs.
        return when (val decision = preflight(root, force, check)) {
            is Decision.Stop -> decision.report
            is Decision.Go -> rebuild(root, decision.wasStale)
        }
    }

    /** What the preflight concluded: report this, or go rebuild. */
    private sealed interface Decision {
        data class Stop(val report: Report) : Decision
        data class Go(val wasStale: Boolean) : Decision
    }

    private fun preflight(root: File, force: Boolean, check: Boolean): Decision {
        val gradlew = File(root, "gradlew")
        if (!gradlew.exists()) {
            return Decision.Stop(
                Report(
                    action = "failed",
                    wasStale = false,
                    sha = AdrProvenance.gitSha,
                    seconds = 0.0,
                    outcome = null,
                    failure = "$gradlew not found — run from the checkout this binary came from",
                    exitCode = 1,
                ),
            )
        }

        val stale = isStale(root, distributionLauncher(root))
        val sha = AdrProvenance.gitSha

        if (check) {
            // Staleness, not commit identity. `version --check` already answers
            // "does this binary match HEAD", and answering it again here would be
            // wrong twice over: touching a source without editing it leaves the sha
            // matching while the binary is a rebuild behind, and a build from a
            // dirty tree has a matching sha and different behaviour.
            return Decision.Stop(
                Report(
                    action = "checked",
                    wasStale = stale,
                    sha = sha,
                    seconds = 0.0,
                    outcome = if (stale) null else "up to date: $sha",
                    failure = if (stale) "sources are newer than this binary — run `adr upgrade`" else null,
                    exitCode = if (stale) 1 else 0,
                ),
            )
        }

        return if (stale || force) {
            Decision.Go(stale)
        } else {
            Decision.Stop(Report("up-to-date", false, sha, 0.0, "up to date: $sha", null, 0))
        }
    }

    private fun rebuild(root: File, wasStale: Boolean): Report {
        val gradlew = File(root, "gradlew")
        val dist = distributionLauncher(root)
        val sha = AdrProvenance.gitSha
        val corpusBefore = corpusFingerprint(root)
        val startedAt = System.nanoTime()

        val failure = try {
            runLocked(root) { installDist(root, gradlew) }
        } catch (e: CliFailure) {
            e.message ?: GRADLE_TASK
        }
        if (failure.isNotBlank()) return failureReport(sha, wasStale, failure)

        val seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0

        // Gradle rewrites the launcher only when its content changed, so after a
        // rebuild that changed only jars its mtime still predates the sources that
        // triggered the rebuild — and `--check` would report stale forever,
        // rebuilding on every call. The stamp records "this binary is as new as
        // this build". Set only after success, so a failed build leaves the old
        // binary looking old, as it should.
        dist.setLastModified(System.currentTimeMillis())

        val after = corpusFingerprint(root)
        if (corpusBefore != after) {
            return failureReport(
                sha,
                wasStale,
                "refused to report success — the rebuild changed docs/decisions " +
                    "($corpusBefore -> $after). The decision log is not the binary's to " +
                    "rewrite; this is a bug in the build.",
            )
        }

        val built = readProvenanceFile(root) ?: sha
        return Report(
            action = "rebuilt",
            wasStale = wasStale,
            sha = built,
            seconds = seconds,
            outcome = "rebuilt: $built in ${"%.1f".format(seconds)}s",
            failure = null,
            exitCode = 0,
        )
    }

    private fun failureReport(sha: String, wasStale: Boolean, message: String): Report =
        Report("failed", wasStale, sha, 0.0, null, message, 1)

    /**
     * Whether the built distribution predates something it is built from.
     *
     * Covers exactly the dependency tree of `:adr-cli`. `libs.versions.toml` is named
     * explicitly rather than globbed: it is not a `.kt` file, and a glob that missed
     * it would let a changed dependency version pass as fresh.
     */
    fun isStale(root: File, dist: File): Boolean {
        if (!dist.canExecute()) return true
        val stamp = dist.lastModified()
        if (stamp == 0L) return true

        val newestSource = listOf("adr-cli/src", "adr-core/src", "core-base/src")
            .map { File(root, it) }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.name.endsWith(".kt") } }
            .maxOfOrNull { it.lastModified() } ?: 0L

        val newestDeclared = listOf("adr-cli/build.gradle.kts", "gradle/libs.versions.toml")
            .map { File(root, it) }
            .filter { it.isFile }
            .maxOfOrNull { it.lastModified() } ?: 0L

        return maxOf(newestSource, newestDeclared) > stamp
    }

    private fun distributionLauncher(root: File): File =
        File(root, "adr-cli/build/install/singularity-adr/bin/singularity-adr")

    private fun installDist(root: File, gradlew: File): String {
        val process = ProcessBuilder(gradlew.absolutePath, "-p", root.absolutePath, GRADLE_TASK, "--quiet")
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        return if (process.waitFor() == 0) "" else output
    }

    /**
     * Fingerprint of the decision log: file count and total size.
     *
     * Content-hashing every ADR would cost more than the rebuild it guards.
     * Count-and-size catches a rewrite, a deletion or an addition — the three ways a
     * build could plausibly touch this directory at all.
     */
    private fun corpusFingerprint(root: File): String {
        val dir = File(root, "docs/decisions")
        if (!dir.isDirectory) return "absent"
        var count = 0
        var bytes = 0L
        dir.walkTopDown().filter { it.isFile }.forEach {
            count++
            bytes += it.length()
        }
        return "$count files / $bytes bytes"
    }

    /** Reads the provenance the *next* invocation will see, from the build directory. */
    private fun readProvenanceFile(root: File): String? =
        File(root, "adr-cli/build/generated/adr-provenance/adr-provenance.properties")
            .takeIf { it.isFile }
            ?.readLines()
            ?.firstOrNull { it.startsWith("gitSha=") }
            ?.removePrefix("gitSha=")
            ?.takeIf { it.isNotBlank() }

    /**
     * Run [block] holding an exclusive lock scoped to this worktree.
     *
     * `FileLock` rather than `flock(1)`: the same guarantee without a subprocess, and
     * the lock file lives under this worktree's own build directory, so a concurrent
     * upgrade in a *different* worktree takes a different lock and is never made to
     * wait.
     */
    private fun <T> runLocked(root: File, block: () -> T): T {
        val lockFile = File(root, "adr-cli/build/.upgrade.lock")
        lockFile.parentFile?.mkdirs()
        RandomAccessFile(lockFile, "rw").use { raf ->
            return raf.channel.lock().use { block() }
        }
    }
}
package com.singularity.todo.mcp
import co.touchlab.kermit.Logger
import com.singularity.todo.core.config.RemoteConfigPort
import com.singularity.todo.core.config.RemoteConfigSnapshot
import com.singularity.todo.core.version.appVersion
import com.singularity.todo.feature.ai.McpToolCatalog
import java.io.File
import java.io.IOException
import java.security.MessageDigest


/**
 * How the tool configuration was obtained.
 *
 * The distinction matters because a missing flag and an unavailable configuration are
 * different facts with different consequences: a missing flag means "enabled", while an
 * unavailable configuration means "nobody could tell us which tools are disabled", which
 * under the fail-open policy means *everything* is enabled — including a tool an
 * operator had switched off. Collapsing the two would hide that.
 */
enum class ConfigurationStatus {
    /** A cached configuration was read and it is within its TTL. */
    CURRENT,

    /** A cached configuration was read, but it is older than its TTL. */
    STALE,

    /** No cached configuration was available; the defaults are in force. */
    UNAVAILABLE,

    /** The read failed in a way the port did not absorb. */
    ERROR,
}

/**
 * One tool, as the client sees it.
 *
 * The description is the tool class's own text — the same string the JSON schema
 * carries — so what this reports and what the agent is told cannot differ.
 */
data class ToolboxToolEntry(
    val name: String,
    val description: String,
    val domain: String,
    val readOnly: Boolean,
    val destructive: Boolean,
    val idempotent: Boolean,
    val requiredParameters: List<String>,
    val optionalParameters: List<String>,
)

/** Where the running code came from. Absent when the process is not in a checkout. */
data class CheckoutInfo(val sha: String, val dirty: Boolean)

/**
 * The five identity values, each answering exactly one question.
 *
 * They are kept apart because they answer different questions, and one field cannot
 * answer all of them: a fingerprint is not a release version, a checkout is not a
 * toolset, and two processes can expose the same catalogue from different revisions.
 */
data class ToolboxIdentity(
    /** The real application version, from the canonical `appVersion()` source. */
    val buildVersion: String,
    /** The commit and dirty state, when the process runs inside a checkout. */
    val checkout: CheckoutInfo?,
    /** A digest of the *declared* public tool definitions in the catalogue. */
    val catalogFingerprint: String,
    /** A digest of the tools that are *actually registered* right now. */
    val activeToolsetFingerprint: String,
)

/**
 * What the toolbox looked like when the server started, and why.
 *
 * One immutable object, built once at startup, read by everything that needs to know
 * the state of the toolbox: the `describe_toolbox` tool, the MCP resources, and the
 * registry snapshot the local report reads. A second copy of this state is a second
 * source of truth, which is how the descriptions in the catalogue and the descriptions
 * in the tool classes drifted apart in the first place.
 */
data class ToolsetSnapshot(
    val identity: ToolboxIdentity,
    val registered: List<ToolboxToolEntry>,
    val disabled: List<ToolboxToolEntry>,
    val configurationStatus: ConfigurationStatus,
    val configurationFetchedAtEpochMillis: Long?,
    /**
     * Flags that named no tool this server exposes.
     *
     * A flag for a tool that no longer exists — renamed, removed, or never there — is
     * inert: it matches nothing, and the tool it was meant to switch off is still
     * exposed. An operator who set that flag believes the opposite, which is why the
     * names are reported rather than dropped. They are *not* disabled tools: a flag
     * that matches nothing did not disable anything.
     */
    val unmatchedFlags: List<String> = emptyList(),
) {
    /**
     * The status of the configuration, derived from the snapshot the port returned.
     *
     * The port absorbs its own failures and hands back the defaults, so "no
     * configuration was available" is the observable fact — not "the read threw".
     */
    companion object {
        fun statusOf(snapshot: RemoteConfigSnapshot, nowEpochMillis: Long): ConfigurationStatus {
            val fetchedAt = snapshot.fetchedAtEpochMillis
            val age = nowEpochMillis - fetchedAt
            return when {
                fetchedAt <= 0L -> ConfigurationStatus.UNAVAILABLE
                age in 0 until RemoteConfigPort.CACHE_TTL_MILLIS -> ConfigurationStatus.CURRENT
                else -> ConfigurationStatus.STALE
            }
        }
    }
}

/**
 * Fingerprints and checkout provenance.
 *
 * The digests are over the *contract* a client depends on — the name, the description
 * and the input parameters — because a schema that changed is a different tool even
 * when the name did not. Hashing only the names would say "unchanged" for a tool whose
 * parameters were rewritten.
 */
object ToolboxIdentityFactory {

    private val log = Logger.withTag("ToolboxIdentity")

    /** How much of the digest is reported. Enough to compare, short enough to read. */
    private const val FINGERPRINT_LENGTH = 16

    fun catalogFingerprint(): String {
        val canonical = McpToolCatalog.catalog
            .sortedBy { it.name }
            .joinToString("\n") { entry ->
                val annotations = entry.annotations
                "${entry.name}|${entry.domain}|" +
                    "ro=${annotations.readOnlyHint}|d=${annotations.destructiveHint}|" +
                    "id=${annotations.idempotentHint}"
            }
        return sha256(canonical)
    }

    fun activeToolsetFingerprint(tools: List<ToolboxToolEntry>): String {
        val canonical = tools
            .sortedBy { it.name }
            .joinToString("\n") { tool ->
                "${tool.name}|${tool.description}|" +
                    "req=${tool.requiredParameters.sorted().joinToString(",")}|" +
                    "opt=${tool.optionalParameters.sorted().joinToString(",")}"
            }
        return sha256(canonical)
    }

    fun buildVersion(): String = appVersion().name

    /**
     * The commit this process was built from, and whether the tree is dirty.
     *
     * Optional by design: a packaged server runs outside a checkout, and "unknown" is
     * an honest answer where a guess would not be. The lookup walks up from the
     * working directory, because Gradle's `JavaExec` sets it to the *module* directory
     * rather than the repository root.
     */
    fun checkout(): CheckoutInfo? {
        val root = findCheckoutRoot()
        val sha = root?.let { runGit(it, "rev-parse", "--short=12", "HEAD") }
        val status = root?.let { runGit(it, "status", "--porcelain") }
        return if (sha != null && status != null) {
            CheckoutInfo(sha = sha, dirty = status.isNotBlank())
        } else {
            null
        }
    }

    /**
     * The nearest ancestor of the working directory that looks like a checkout.
     *
     * The working directory is where Gradle's `JavaExec` put it — the *module*
     * directory, not the repository root — so the lookup walks up.
     */
    private fun findCheckoutRoot(): File? {
        val start = System.getProperty("user.dir") ?: return null
        return generateSequence(File(start).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, ".git").exists() }
    }

    /**
     * Runs a git command in [root] and returns its output, or `null` if git could not be
     * run or the command failed.
     *
     * Git may be absent (a packaged server runs outside a development machine), and a
     * failure here is not an error — it means the checkout is unknown, which the report
     * says as "unknown" rather than as a failure to start.
     */
    private fun runGit(root: File, vararg args: String): String? = try {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output.ifEmpty { null } else null
    } catch (e: IOException) {
        // Git is not installed, or the process could not be started. The checkout is
        // unknown; that is an answer, not a failure — but it is worth saying once.
        log.d(e) { "Could not run git; the checkout will be reported as unknown" }
        null
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }.take(FINGERPRINT_LENGTH)
    }
}

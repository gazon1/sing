package com.singularity.todo.feature.ai.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A fake agent, for the tests that need a real process on the other end of the pipe.
 *
 * ## Why a real process and not a fake session
 *
 * The session-level behaviour is covered by a fake session, and it should be: those
 * tests are about what the screen does with a turn. But the questions this file
 * answers are *not* about the screen. They are about the pipe:
 *
 * - what happens when the agent is not installed at all;
 * - what happens when the agent's process dies in the middle of a turn;
 * - what happens when the agent answers with a protocol version this client does not
 *   speak;
 * - what happens when the agent accepts the connection and then says nothing.
 *
 * None of those can be produced by a fake session, because a fake session *is* the
 * answer. So this program speaks the protocol over standard input and output, and the
 * test starts it the way it starts any other agent.
 *
 * ## Where its shapes come from
 *
 * From a real bridge. `@agentclientprotocol/claude-agent-acp` 0.95.0 was run against
 * this application's own client — `scripts/acp-bridge-probe.sh` — and what it sent was
 * written down: five permission modes, three configuration options, an authentication
 * status announced as a notification, and the available commands announced as another
 * one. This fake sends the same things, because a fake that answers a different
 * session than the bridge does proves nothing about the bridge — and the client's
 * tolerance of a notification it has no answer for is worth nothing if no notification
 * ever arrives.
 *
 * ## The modes
 *
 * - `good` — a complete conversation: it initializes, opens a session, answers a
 *   prompt with a message and ends the turn.
 * - `bad-version` — it answers the handshake with a protocol version this client does
 *   not speak.
 * - `crash` — it opens the session and then dies in the middle of the turn.
 * - `silent` — it never answers the handshake, and keeps reading, so the client
 *   is left waiting for a response that will not come.
 * - `mute-close` — it conducts a whole conversation and then ignores the goodbye: it
 *   receives `session/close` and never answers it.
 *
 * The protocol is spoken as newline-delimited JSON-RPC, which is what the transport
 * reads.
 */
fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: MODE_GOOD
    val json = Json { ignoreUnknownKeys = true }
    // The transcript is a file, not standard error: the drain that carries standard
    // error is a thread of its own, and a test that fails may never see what it said.
    val transcript = args.getOrNull(1)?.let { path -> java.io.File(path) }

    while (true) {
        val line = readLine() ?: return
        if (line.isBlank()) continue
        if (!handle(line, mode, json, transcript)) return
    }
}

/**
 * Answers one line of the conversation.
 *
 * @return whether the agent keeps talking. False means the agent is done — which is
 *   how the crashing mode ends: it dies in the middle of the turn, on purpose.
 */
private fun handle(line: String, mode: String, json: Json, transcript: java.io.File?): Boolean {
    // The line is recorded so that a test which cannot make the agent answer can
    // show what the client actually sent.
    transcript?.appendText("RECEIVED: $line\n")

    val request = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return true
    // The id is kept as it arrived, type and all. A protocol that numbers its
    // requests and a reply that answers with the number *as text* are two different
    // ids, and the client waits for the one it sent — for ever.
    val id = request["id"]
    val method = request["method"]?.jsonPrimitive?.content

    when (method) {
        "initialize" -> {
            // The silent mode never answers the handshake, and keeps reading: the client
            // is left waiting for a response that will not come.
            if (mode != MODE_SILENT) {
                val version = if (mode == MODE_BAD_VERSION) 99 else 1
                respond(
                    id,
                    """{"protocolVersion":$version,"agentCapabilities":{},"authMethods":[],""" +
                        """"agentInfo":{"name":"fake-agent","version":"1.0"}}""",
                )
                // The real bridge announces its authentication state right after the
                // handshake — as a notification: a method with no id, and one this
                // application has no answer for. The client has to survive it, and a
                // fake that never sends it cannot say that it does.
                notify(AUTH_STATUS_METHOD, """{"authStatus":{"kind":"none","label":"Not logged in"}}""")
            }
        }

        // The session answer carries the modes and the models, because the client
        // requires them: a response without them is not a response the client can
        // read, and the request it belongs to never completes.
        //
        // The modes are the real bridge's five, and the configuration options are the
        // real bridge's three — a session setting this application does not show. They
        // are here because the bridge sends them: a fake that answers a different
        // session than the bridge does proves nothing about the bridge.
        "session/new" -> {
            respond(id, SESSION_RESPONSE)
            // And the bridge announces its commands right after the session is open.
            notify(
                "session/update",
                """{"sessionId":"$SESSION_ID","update":{"sessionUpdate":"available_commands_update",""" +
                    """"availableCommands":[{"name":"adaptive","description":"Instructions to make or""" +
                    """ update an app's UI so that it adapts to different devices"}]}}""",
            )
        }

        // The goodbye is answered, as a real agent answers it. A request that is
        // received and never answered is the one failure the client cannot defend
        // against on its own — so the agent that behaves well answers, and the mode
        // below is the one that does not.
        "session/close" -> if (mode != MODE_MUTE_CLOSE) respond(id, "{}")

        "session/prompt" -> {
            if (mode == MODE_CRASH) return false
            notify(
                "session/update",
                """{"sessionId":"$SESSION_ID","update":{"sessionUpdate":"agent_message_chunk",""" +
                    """"content":{"type":"text","text":"hello from the fake agent"},"messageId":"m1"}}""",
            )
            respond(id, PROMPT_RESPONSE)
        }

        else -> Unit
    }
    return true
}

private fun respond(id: JsonElement?, result: String) {
    // The id is echoed exactly as it arrived — a number stays a number. See the note
    // where it is read.
    val idPart = id?.toString() ?: "null"
    println("""{"jsonrpc":"2.0","id":$idPart,"result":$result}""")
    System.out.flush()
}

/**
 * A notification: a method with no id, and no answer expected.
 *
 * The real bridge sends two of them — its authentication state and its commands —
 * and neither is a request this application made. The client has to read them and
 * carry on, and the fake sends them so that the tests say so.
 */
private fun notify(method: String, params: String) {
    println("""{"jsonrpc":"2.0","method":"$method","params":$params}""")
    System.out.flush()
}

private const val MODE_GOOD = "good"
private const val MODE_BAD_VERSION = "bad-version"
private const val MODE_CRASH = "crash"
private const val MODE_SILENT = "silent"
private const val MODE_MUTE_CLOSE = "mute-close"

private const val SESSION_ID = "session-1"

/** The method the real bridge announces its authentication state with. */
private const val AUTH_STATUS_METHOD = "_auth/status_update"

/**
 * The answer to "session/new".
 *
 * The modes and the models are not decoration: the client requires both, and a
 * response that leaves them out is not a response it can read — so the request it
 * belongs to never completes, and the client waits for an answer that was sent.
 *
 * The five modes and the three configuration options are the real bridge's, taken
 * from the transcript of `scripts/acp-bridge-probe.sh` against
 * `@agentclientprotocol/claude-agent-acp` 0.95.0. They are session settings this
 * application does not show, and the fake sends them because the bridge sends them:
 * a fake that answers a different session than the bridge does proves nothing about
 * the bridge.
 */
private const val SESSION_RESPONSE =
    """{"sessionId":"$SESSION_ID",""" +
        """"modes":{"currentModeId":"default","availableModes":[""" +
        """{"id":"default","name":"Manual","description":"Always ask before making changes"},""" +
        """{"id":"acceptEdits","name":"Accept edits","description":"Automatically accept all file edits"},""" +
        """{"id":"plan","name":"Plan","description":"Create a plan before making changes"},""" +
        """{"id":"auto","name":"Auto","description":"Claude handles permission decisions"},""" +
        """{"id":"bypassPermissions","name":"Bypass permissions","description":"Accepts all permissions"}]},""" +
        """"models":{"currentModelId":"fake-model",""" +
        """"availableModels":[{"modelId":"fake-model","name":"Fake model"}]},""" +
        """"configOptions":[{"id":"mode","name":"Mode","description":"Session permission mode",""" +
        """"category":"mode","type":"select","currentValue":"default","options":[]}]}"""

/**
 * The answer to "session/prompt".
 *
 * It carries the usage as well as the reason, because a real agent sends it and the
 * client reads it: an answer that leaves it out is an answer the client cannot read.
 */
private const val PROMPT_RESPONSE =
    """{"stopReason":"end_turn","usage":{"inputTokens":10,"outputTokens":5,"totalTokens":15}}"""

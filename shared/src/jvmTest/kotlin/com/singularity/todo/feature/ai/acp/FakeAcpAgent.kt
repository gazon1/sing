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
            }
        }

        // The session answer carries the modes and the models, because the client
        // requires them: a response without them is not a response the client can
        // read, and the request it belongs to never completes.
        "session/new" -> respond(id, SESSION_RESPONSE)

        // The goodbye is answered, as a real agent answers it. A request that is
        // received and never answered is the one failure the client cannot defend
        // against on its own — so the agent that behaves well answers, and the mode
        // below is the one that does not.
        "session/close" -> if (mode != MODE_MUTE_CLOSE) respond(id, "{}")

        "session/prompt" -> {
            if (mode == MODE_CRASH) return false
            notify(
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

private fun notify(params: String) {
    println("""{"jsonrpc":"2.0","method":"session/update","params":$params}""")
    System.out.flush()
}

private const val MODE_GOOD = "good"
private const val MODE_BAD_VERSION = "bad-version"
private const val MODE_CRASH = "crash"
private const val MODE_SILENT = "silent"
private const val MODE_MUTE_CLOSE = "mute-close"

private const val SESSION_ID = "session-1"

/**
 * The answer to "session/new".
 *
 * The modes and the models are not decoration: the client requires both, and a
 * response that leaves them out is not a response it can read — so the request it
 * belongs to never completes, and the client waits for an answer that was sent.
 */
private const val SESSION_RESPONSE =
    """{"sessionId":"$SESSION_ID",""" +
        """"modes":{"currentModeId":"default","availableModes":[{"id":"default","name":"Default"}]},""" +
        """"models":{"currentModelId":"fake-model","availableModels":[{"modelId":"fake-model","name":"Fake model"}]}}"""

/**
 * The answer to "session/prompt".
 *
 * It carries the usage as well as the reason, because a real agent sends it and the
 * client reads it: an answer that leaves it out is an answer the client cannot read.
 */
private const val PROMPT_RESPONSE =
    """{"stopReason":"end_turn","usage":{"inputTokens":10,"outputTokens":5,"totalTokens":15}}"""

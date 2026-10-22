package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.TypeToken
import ai.koog.utils.time.KoogClock
import co.touchlab.kermit.Logger
import com.singularity.todo.core.ai.ToolOutputJson
import com.singularity.todo.feature.ai.prompts.Prompts
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

/**
 * The task, as the user wrote it.
 */
@Serializable
data class FirstStepInput(val title: String, val description: String? = null)

/**
 * The first action of a task.
 *
 * @property action the one thing to do first, in one sentence, starting with a verb.
 * @property doneWhen the checkable outcome that ends this step.
 * @property rationale one short sentence on why this is the first step.
 */
@Serializable
data class FirstStepOutput(val action: String, val doneWhen: String = "", val rationale: String = "")

/**
 * Names the first physical action of a task.
 *
 * ## One action, not a plan
 *
 * The tool that decomposes a task answers "what are the three to seven pieces of
 * this?". This one answers "what do I do first?" — and the difference is the whole
 * point. A task that begins with "figure out X" cannot be started, because starting
 * it requires a decision nobody has made. Naming the first *physical* action turns
 * it into something that can be started: open the file, write the question, send the
 * message.
 *
 * "Done" is part of the answer, because a step nobody can check is a step nobody can
 * be finished with.
 */
class FirstStepTool(private val promptExecutor: PromptExecutor, private val model: LLModel) :
    SimpleTool<FirstStepInput>(TypeToken.of(FirstStepInput::class.java), NAME, DESCRIPTION) {

    private val logger = Logger.withTag("FirstStep")

    override suspend fun execute(args: FirstStepInput): String {
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(Prompts.firstStepSystem)
            user(Prompts.firstStepUser(args.title, args.description))
        }
        val response = promptExecutor.execute(p, model, emptyList())
        val text = extractText(response)

        val output = try {
            ToolOutputJson.decodeFromString(FirstStepOutput.serializer(), text)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: SerializationException) {
            logger.w(failure) { "the model answered in prose" }
            // The model answered in prose. The first non-empty line is the action, and
            // the rest is the best guess at the outcome — a partial answer beats none.
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            FirstStepOutput(
                action = lines.firstOrNull().orEmpty(),
                doneWhen = lines.getOrNull(1).orEmpty(),
            )
        }

        return ToolOutputJson.encodeToString(FirstStepOutput.serializer(), output)
    }

    companion object {
        const val NAME = "first_step"

        /**
         * The description the model reads.
         *
         * It says when to use the tool — a task too vague to start — because the tool
         * that is used for every task is a tool nobody reads the answer of.
         */
        const val DESCRIPTION =
            "Names the first physical action of a task, with the checkable outcome that " +
                "ends it. Use when a task is too vague to start — when the first move " +
                "would itself need a decision. Returns one action, not a plan."

        private fun extractText(response: Message.Assistant): String =
            response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.trim()
    }
}

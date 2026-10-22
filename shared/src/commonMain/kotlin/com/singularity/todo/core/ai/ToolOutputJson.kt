package com.singularity.todo.core.ai

import kotlinx.serialization.json.Json

/**
 * The JSON the AI tools write their answers in.
 *
 * ## Why defaults are written
 *
 * The default encoder leaves out a field whose value happens to be the default. So a
 * selection with nothing blocked in it comes back without a "blocked" field at all,
 * and a reader — a model, or a test — has to decide whether a missing field means
 * "zero" or "not answered". It cannot tell, and it will guess.
 *
 * Writing the defaults makes the answer the same shape every time: every field is
 * there, and a field that is absent means the thing did not happen.
 */
val ToolOutputJson: Json = Json { encodeDefaults = true }

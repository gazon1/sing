package com.singularity.todo.feature.genui.render

import com.singularity.todo.core.ui.textfields.OwnedText
import kotlinx.coroutines.flow.Flow

/**
 * A bound field's source: the value now, and the stream of what it becomes.
 *
 * A field that is bound to a data-model path needs two things at once: the source it
 * starts from, and the source that follows it. They are the same source read twice —
 * once now, once over time — and a renderer that had to assemble them itself would be
 * assembling the answer to "who may rewrite this field?", which is not a question a
 * renderer can answer.
 *
 * The context hands out both. The renderer passes the source to the bridge and does not
 * touch it.
 *
 * @property current the source as it is right now — the seed the bridge starts from.
 * @property updates the source as it changes.
 * @property fallback the text the source falls back to while the model holds nothing at
 *   the path. It is part of the source because it decides what the source *is* while
 *   the model is empty, and a source built with a different fallback is a different
 *   source.
 */
class BoundTextSource(val current: OwnedText, val updates: Flow<OwnedText>, val fallback: String)

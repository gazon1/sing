package com.singularity.todo.core.sync

import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.domain.model.TagGroup
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.timetracking.domain.TimeEntry
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer

/**
 * Single declarative source of truth for the sync protocol contract.
 *
 * Everything the sync protocol defines — versions, RPC methods, synchronisable
 * document types, and the field lists — is declared here.  All other sync code
 * derives from this object rather than repeating the facts.
 *
 * ## The field lists are derived, not written
 *
 * The list of names a device may put on the wire is the model's own list: the
 * element names of the model's serial descriptor, which is what the encoder
 * writes from.  A list written out by hand beside the model is a copy, and a
 * copy has to be kept in step by somebody's memory.
 *
 * Two lists come out of that derivation, and each means one thing:
 *
 * - [WIRE_FIELDS] — the names a device puts on the wire.  Every field of every
 *   model travels: the device serialises the whole model, so a field that does
 *   not travel is a field the model does not have.
 * - [SERVER_FIELDS] — the names the server accepts in a patch, which is the wire's names
 *   minus the fields the server owns.  This is the one hand-written part of the
 *   contract, and it is four names long.
 *
 * The server's list must agree with the server-side `sync_field_allowlist`
 * table: a field that is absent from that table cannot be written by any
 * patch, whatever the client sends.  The JVM test
 * `SyncContractIsTheSourceOfTruthTest` says that the wire and the wire's list
 * are the same set, and the gate `sync allowlist seed matches contract`
 * compares the server's table with the derived list.
 *
 * ## Version history
 *
 * - protocol v1: initial release (2026-10-05)
 * - event schema v1: initial release (2026-10-05)
 */
object SyncContract {

    // ── Versions ───────────────────────────────────────────────────────────────

    /** Protocol version sent in every [DeltaPatch]. Events with a higher version are dropped. */
    const val CURRENT_PROTOCOL_VERSION = 1

    /** Event schema version. Events with a higher schema version are dropped. */
    const val EVENT_SCHEMA_VERSION = 1

    // ── Synchronisable document types ─────────────────────────────────────────

    /** Every [DocType] this client can sync. */
    val SYNCABLE_DOC_TYPES: Set<DocType> = DocType.entries.toSet()

    // ── The field lists ─────────────────────────────────────────────────────────
    //
    // The names a device puts on the wire are the model's own names.  The
    // derivation reads the model's serial descriptor — the same descriptor the
    // encoder writes from — and takes its element names, so a `@SerialName`
    // comes out as the wire name and a field added to a model is in the list
    // the moment the model changes.
    //
    // What is written by hand is the exception, not the list: the four fields
    // the server owns.

    /**
     * The fields the server owns, and no document may write.
     *
     * - `serverVersion` — the server's own counter.  The device's copy is its
     *   own reading of it, and the server does not take it from a patch.
     * - `hlc` — the clock.  A patch carries its clock separately, and the
     *   server orders by that one.
     * - `userId` — the owner.  It comes from the session, not from the
     *   document: a device that could write it would be writing down whose
     *   work this is.
     * - `id` — the key of the row, not a field of the document.
     *
     * The same four for every type: every model here has them, and the server
     * owns them all the same way.  A name here that a model does not have is a
     * name the subtraction does not subtract: the field stays in the server's
     * list, the server is told it may write it, and the model's own
     * bookkeeping becomes writable — `SyncContractIsTheSourceOfTruthTest` says
     * so.
     *
     * Internal rather than private: the test reads it, because the check that
     * a subtracted name is a name the model has has to read the subtraction.
     */
    internal val SERVER_OWNED: Set<String> = setOf("serverVersion", "hlc", "userId", "id")

    /**
     * The model each document type is an instance of.
     *
     * The descriptor is the model's own: the same one the encoder writes the
     * document from, so the names it holds are the names on the wire.
     */
    private val MODEL_DESCRIPTORS: Map<DocType, SerialDescriptor> = mapOf(
        DocType.Task to serializer<Task>().descriptor,
        DocType.Note to serializer<Note>().descriptor,
        DocType.Project to serializer<Project>().descriptor,
        DocType.Tag to serializer<Tag>().descriptor,
        DocType.TagGroup to serializer<TagGroup>().descriptor,
        DocType.TimeEntry to serializer<TimeEntry>().descriptor,
    )

    /**
     * The names a device puts on the wire, per document type.
     *
     * The model's own names.  Every field of every model travels — the device
     * serialises the whole model — so a field that does not travel is a field
     * the model does not have, and there is nothing to subtract here.
     *
     * The order is the model's declaration order, which is the order the
     * encoder writes them in.
     */
    val WIRE_FIELDS: Map<DocType, List<String>> = MODEL_DESCRIPTORS.mapValues { (_, descriptor) ->
        descriptor.wireNames()
    }

    /**
     * The names the server accepts in a patch, per document type.
     *
     * The wire's names, minus the fields the server owns.  This is what the
     * server's `sync_field_allowlist` table holds, and the seed is generated
     * from it.
     */
    val SERVER_FIELDS: Map<DocType, List<String>> = WIRE_FIELDS.mapValues { (_, fields) ->
        fields - SERVER_OWNED
    }

    /**
     * The names a device puts on the wire for [docType], or an empty list if the
     * type is not a known syncable document type.
     */
    fun wireFieldsFor(docType: DocType): List<String> =
        WIRE_FIELDS[docType] ?: emptyList()

    /**
     * The names the server accepts in a patch for [docType], or an empty list if the type
     * is not a known syncable document type.
     */
    fun serverFieldsFor(docType: DocType): List<String> =
        SERVER_FIELDS[docType] ?: emptyList()

    init {
        // Every document type needs a model, or its list would be missing and
        // everything the type carries would look like a field nobody sends.
        require(MODEL_DESCRIPTORS.keys == DocType.entries.toSet()) {
            "every document type needs a model: " +
                "the models are ${MODEL_DESCRIPTORS.keys.map { it.key }}, " +
                "the types are ${DocType.entries.map { it.key }}"
        }
    }
}

/**
 * The names the descriptor holds, in the order it holds them.
 *
 * `getElementName` returns the *serial* name, so a `@SerialName` comes out as
 * the wire name and not as the Kotlin property's name.
 */
private fun SerialDescriptor.wireNames(): List<String> =
    (0 until elementsCount).map { getElementName(it) }

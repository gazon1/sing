package com.singularity.todo.feature.projects.domain.model

import com.singularity.todo.feature.tags.domain.model.TagGroupId

/**
 * A project document: a project, and the claim it makes about the inheritance.
 *
 * ## The claim is nullable on purpose
 *
 * `null` means *the document says nothing about the inheritance*. It does not
 * mean "the project inherits nothing" — an empty set is a claim, and a device
 * that never sends the key says nothing at all.
 *
 * The distinction is not academic. The inheritance does not live on the row:
 * `ProjectEntity` has no column for it, and it lives in a cross-reference table
 * instead. A write that always replaced that table read the absence of the key
 * as an empty set, and the project's inherited tag groups went away on this
 * device — silently, because the row was written and the pull reported success.
 *
 * ## What the pull does with a document
 *
 * It applies what the document says and leaves what it does not say alone. The
 * row is read against the row that is already here, and the table of inherited
 * groups is replaced only when the document makes a claim about it.
 */
data class ProjectDocument(
    val project: Project,
    /** The inherited tag groups the document carries; `null` when it says nothing about them. */
    val inheritedTagGroupIds: Set<TagGroupId>?,
)

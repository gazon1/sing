package com.singularity.todo.feature.tags.domain.usecase

import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.Tag
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.TagsRepository
import com.singularity.todo.feature.tags.domain.model.TagGroupId
import com.singularity.todo.feature.tags.domain.port.TagGroupRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Resolves the effective tag set for a task, combining:
 * 1. Tags the task owns directly (task's own tag IDs)
 * 2. Tags inherited from tag groups assigned to the task's project
 *
 * Merges use [LinkedHashSet] to preserve insertion order and deduplicate.
 * Tags from groups are appended after own tags, so own tags take priority
 * when the same tag ID appears in both.
 */
class EffectiveTagsResolver(private val tagRepo: TagsRepository, private val tagGroupRepo: TagGroupRepository) {
    /**
     * Returns a flow of all effective tags for a task.
     *
     * @param taskOwnTagIds Tags the task has been assigned directly.
     * @param projectId The project the task belongs to (used to resolve inherited groups).
     */
    fun resolveEffectiveTags(taskOwnTagIds: Set<TagId>, projectId: ProjectId?): Flow<List<Tag>> {
        if (projectId == null) {
            return tagRepo.observeAll().map { allTags ->
                filterAndPreserveOrder(taskOwnTagIds, allTags)
            }
        }
        return combine(
            tagRepo.observeAll(),
            tagGroupRepo.observeInheritedByProject(projectId),
        ) { allTags, inheritedGroupIds ->
            val inheritedTagIds = resolveGroupTagIds(inheritedGroupIds, allTags)
            val combined = LinkedHashSet<TagId>()
            combined.addAll(taskOwnTagIds)
            combined.addAll(inheritedTagIds)
            allTags.filter { it.id in combined }
        }
    }

    /**
     * Returns the full set of effective tag IDs (own + inherited) as a [Set].
     */
    fun resolveEffectiveTagIds(
        taskOwnTagIds: Set<TagId>,
        projectId: ProjectId?,
        allTags: List<Tag>,
        inheritedGroupIds: Set<TagGroupId>,
    ): Set<TagId> {
        val inheritedTagIds = resolveGroupTagIds(inheritedGroupIds, allTags)
        val combined = LinkedHashSet<TagId>()
        combined.addAll(taskOwnTagIds)
        combined.addAll(inheritedTagIds)
        return combined
    }

    private fun resolveGroupTagIds(groupIds: Set<TagGroupId>, allTags: List<Tag>): Set<TagId> {
        if (groupIds.isEmpty()) return emptySet()
        return allTags
            .filter { it.groupId in groupIds }
            .mapTo(LinkedHashSet()) { it.id }
    }

    private fun filterAndPreserveOrder(ownTagIds: Set<TagId>, allTags: List<Tag>): List<Tag> {
        val ownSet = LinkedHashSet(ownTagIds)
        return allTags.filter { it.id in ownSet }
    }
}

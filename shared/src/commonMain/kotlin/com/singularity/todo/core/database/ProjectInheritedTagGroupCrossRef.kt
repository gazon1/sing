package com.singularity.todo.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

/**
 * Many-to-many join table: which tag groups a project inherits tags from.
 * When a project inherits a tag group, all tags in that group are visible
 * to tasks belonging to this project.
 */
@Entity(
    tableName = "project_tag_groups",
    primaryKeys = ["project_id", "tag_group_id"],
    indices = [Index(value = ["tag_group_id"])],
)
data class ProjectInheritedTagGroupCrossRef(
    @ColumnInfo("project_id") val projectId: String,
    @ColumnInfo("tag_group_id") val tagGroupId: String,
)

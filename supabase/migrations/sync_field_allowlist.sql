-- DO NOT EDIT BY HAND.  Generated from SyncContract.FIELD_ALLOWLIST.
-- Run `./gradlew :shared:generateSyncSql` to regenerate.

-- Idempotent: DELETE + INSERT replaces all rows for each entity type.
-- To remove a field, remove it from SyncContract.FIELD_ALLOWLIST first,
-- then regenerate this file.

-- task
delete from sync_field_allowlist where entity_type = 'task';
insert into sync_field_allowlist (entity_type, field, writable) values
    ('task', 'accentColor', true), ('task', 'aiSuppressedTagIds', true), ('task', 'archivedAt', true), ('task', 'completedAt', true), ('task', 'createdAt', true), ('task', 'dependsOn', true), ('task', 'description', true),
    ('task', 'dueDate', true), ('task', 'dueTime', true), ('task', 'emoji', true), ('task', 'endDate', true), ('task', 'endTime', true), ('task', 'estimateMinutes', true), ('task', 'hlc', true),
    ('task', 'id', true), ('task', 'isPinned', true), ('task', 'kind', true), ('task', 'parentTaskId', true), ('task', 'priority', true), ('task', 'projectId', true), ('task', 'recurrence', true),
    ('task', 'serverVersion', true), ('task', 'someday', true), ('task', 'startDate', true), ('task', 'startTime', true), ('task', 'tags', true), ('task', 'title', true), ('task', 'updatedAt', true),
    ('task', 'userId', true);
    on conflict (entity_type, field) do nothing;
-- note
delete from sync_field_allowlist where entity_type = 'note';
insert into sync_field_allowlist (entity_type, field, writable) values
    ('note', 'archivedAt', true), ('note', 'bodyHtml', true), ('note', 'bodyMarkdown', true), ('note', 'charCount', true), ('note', 'color', true), ('note', 'createdAt', true), ('note', 'deletedAt', true),
    ('note', 'hlc', true), ('note', 'id', true), ('note', 'isFolder', true), ('note', 'isPinned', true), ('note', 'kind', true), ('note', 'outgoingLinks', true), ('note', 'parentNoteId', true),
    ('note', 'pinnedAt', true), ('note', 'serverVersion', true), ('note', 'sortOrder', true), ('note', 'taskId', true), ('note', 'title', true), ('note', 'updatedAt', true), ('note', 'userId', true),
    ('note', 'wordCount', true);
    on conflict (entity_type, field) do nothing;
-- project
delete from sync_field_allowlist where entity_type = 'project';
insert into sync_field_allowlist (entity_type, field, writable) values
    ('project', 'color', true), ('project', 'createdAt', true), ('project', 'deletedAt', true), ('project', 'description', true), ('project', 'dueDate', true), ('project', 'externalId', true), ('project', 'hlc', true),
    ('project', 'icon', true), ('project', 'id', true), ('project', 'idempotencyKey', true), ('project', 'inheritedTagGroupIds', true), ('project', 'isDefault', true), ('project', 'isDeleted', true), ('project', 'name', true),
    ('project', 'parentId', true), ('project', 'serverVersion', true), ('project', 'sortOrder', true), ('project', 'team', true), ('project', 'updatedAt', true), ('project', 'userId', true);
    on conflict (entity_type, field) do nothing;
-- tag
delete from sync_field_allowlist where entity_type = 'tag';
insert into sync_field_allowlist (entity_type, field, writable) values
    ('tag', 'color', true), ('tag', 'createdAt', true), ('tag', 'deletedAt', true), ('tag', 'groupId', true), ('tag', 'hlc', true), ('tag', 'id', true), ('tag', 'name', true),
    ('tag', 'serverVersion', true), ('tag', 'sortOrder', true), ('tag', 'updatedAt', true), ('tag', 'userId', true);
    on conflict (entity_type, field) do nothing;
-- tag_group
delete from sync_field_allowlist where entity_type = 'tag_group';
insert into sync_field_allowlist (entity_type, field, writable) values
    ('tag_group', 'color', true), ('tag_group', 'createdAt', true), ('tag_group', 'deletedAt', true), ('tag_group', 'hlc', true), ('tag_group', 'id', true), ('tag_group', 'name', true), ('tag_group', 'serverVersion', true),
    ('tag_group', 'updatedAt', true), ('tag_group', 'userId', true);
    on conflict (entity_type, field) do nothing;
-- time_entry
delete from sync_field_allowlist where entity_type = 'time_entry';
insert into sync_field_allowlist (entity_type, field, writable) values
    ('time_entry', 'createdAt', true), ('time_entry', 'deletedAt', true), ('time_entry', 'endedAt', true), ('time_entry', 'hlc', true), ('time_entry', 'id', true), ('time_entry', 'kind', true), ('time_entry', 'note', true),
    ('time_entry', 'serverVersion', true), ('time_entry', 'source', true), ('time_entry', 'startedAt', true), ('time_entry', 'taskId', true), ('time_entry', 'updatedAt', true), ('time_entry', 'userId', true);
    on conflict (entity_type, field) do nothing;

-- The workflow state of a task: the state the user has claimed for its work.
--
-- `Open` (nothing claimed), `NextUp` (the one the user will do next) and
-- `InProgress` (the one being worked on now).
--
-- The client puts the field on the wire by itself — the wire field list is
-- derived from the model, so the field is on every task document the moment it
-- is on the model. What the server does with it is a separate question, and
-- this table is the answer: a field absent from `sync_field_allowlist` cannot
-- be written by any patch, whatever the client asks for.
--
-- So without this row the feature works on one device and silently does not
-- work across devices: the patch succeeds and the state is dropped. That is
-- the same debt the server still carries for `task.seriesId` and
-- `project.closedAt` (open issue #1050), and it is worth not repeating.
--
-- `on conflict do nothing` so re-applying the file is safe, matching the
-- seeding style of the schema migration.

insert into sync_field_allowlist (entity_type, field, writable) values
    ('task', 'workflowState', true)
on conflict (entity_type, field) do nothing;

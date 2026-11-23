-- The note's project attachment.
--
-- `notes.project_id` has existed on the client since the attachment work: it is what
-- makes a note the brief *of* a project rather than one note among many that mentions
-- it. The client's derived allowlist has carried `note.projectId` since the lists were
-- derived from the models, and the server's table did not — so a brief written on one
-- device arrived on the other without its attachment, and became an ordinary note.
--
-- The same gap, for the same reason, as the `task.seriesId` and `project.closedAt`
-- rows issue #1050 records: the table was seeded before the field existed, and the
-- check that compares the two lists reports the gap rather than failing on it.
--
-- `on conflict do nothing` so re-applying the file is safe.

insert into sync_field_allowlist (entity_type, field, writable) values
    ('note', 'projectId', true)
on conflict (entity_type, field) do nothing;

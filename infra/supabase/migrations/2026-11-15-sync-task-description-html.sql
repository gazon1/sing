-- The task's rich description.
--
-- `description` stays the plain-text projection: the device's full-text index is
-- built over it. `descriptionHtml` carries the HTML the rich editor writes, and
-- the server must accept it in a patch — a field absent from
-- `sync_field_allowlist` is silently dropped, which is the debt the workflow
-- state migration above documents.
--
-- The client puts the field on the wire by itself: the wire list is derived
-- from the model. This row is what makes the server store it.
--
-- `on conflict do nothing` so re-applying the file is safe.

insert into sync_field_allowlist (entity_type, field, writable) values
    ('task', 'descriptionHtml', true)
on conflict (entity_type, field) do nothing;

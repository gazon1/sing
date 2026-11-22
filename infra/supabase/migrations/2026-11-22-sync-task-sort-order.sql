-- Subtask order on the task row.
--
-- Issue #2924 makes subtask order a persisted fact: `sort_order` is the
-- position among direct children of the same parent, restored on reopen and
-- across devices. The field lives on the task document, so the server must
-- accept it in a patch — a field absent from `sync_field_allowlist` is
-- silently dropped.
--
-- The client puts the field on the wire by itself: the wire list is derived
-- from the model. This row is what makes the server store it.
--
-- `on conflict do nothing` so re-applying the file is safe.

insert into sync_field_allowlist (entity_type, field, writable) values
    ('task', 'sortOrder', true)
on conflict (entity_type, field) do nothing;

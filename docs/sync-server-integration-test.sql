-- Integration test for the sync server side.
--
-- Run it against a real database with a signed-in session; every assertion here is
-- a behaviour the client depends on and cannot verify on its own. It exists as a file
-- rather than as ad-hoc probes because the version that found the four defects below
-- was run by hand, and a hand-run test is a test that stops being run.
--
-- How to run: paste the two `create auth user` statements, then the probe block, into
-- a session with the service role, and read the notices. The notices are the
-- assertions; the final SELECT is the summary.
--
-- Setup (once, as a privileged role — `authenticated` has no INSERT on auth.users):
--
--   insert into auth.users (id, aud, role, email, encrypted_password, email_confirmed_at,
--                           raw_app_meta_data, raw_user_meta_data, created_at, updated_at, instance_id)
--   values ('11111111-1111-1111-1111-111111111111', 'authenticated', 'authenticated',
--           'sync-test-a@example.com', '', now(),
--           '{"provider":"email","providers":["email"]}'::jsonb, '{}'::jsonb,
--           now(), now(), '00000000-0000-0000-0000-000000000000')
--   on conflict (id) do nothing;
--   -- and the same for ...2222...2222 for account B.
--
-- Reset between runs:
--
--   truncate sync_tasks, sync_notes, sync_projects, sync_tags, sync_tag_groups,
--            sync_time_entries, sync_events, sync_applied_patches restart identity;

do $$
declare
    v_r jsonb;
begin
    perform set_config('request.jwt.claims',
        json_build_object('sub', '11111111-1111-1111-1111-111111111111')::text, true);
    perform set_config('role', 'authenticated', true);

    -- REQ-OS-003: two devices, two different fields, both edits survive.
    -- 1. Account A creates a task with two fields, clock 1000.
    v_r := sync_batch_apply(jsonb_build_array(jsonb_build_object(
        'patchId', 'p1', 'entityType', 'task', 'entityId', 't1', 'profileId', 'prof-1',
        'ops', jsonb_build_array(
            jsonb_build_object('field', 'title',    'op', 'set', 'value', 'Buy milk'),
            jsonb_build_object('field', 'dueDate',  'op', 'set', 'value', '2026-10-05T00:00:00Z'),
            jsonb_build_object('field', 'updatedAt','op', 'set', 'value', '2026-10-05T00:00:00Z')),
        'hlc', jsonb_build_object('p', 1000, 'c', 0, 'n', 'a'))));
    raise notice '1 create      -> %', v_r::text;   -- expect created=1, applied=0

    -- 2. A second device changes ONLY the dueDate, with an OLDER clock. It must lose,
    --    and it must not disturb the title. This is the row-checksum behaviour the
    --    per-field policy replaced, and it is the reason the patch carries a clock.
    v_r := sync_batch_apply(jsonb_build_array(jsonb_build_object(
        'patchId', 'p2', 'entityType', 'task', 'entityId', 't1', 'profileId', 'prof-1',
        'ops', jsonb_build_array(
            jsonb_build_object('field', 'dueDate', 'op', 'set', 'value', '1999-01-01T00:00:00Z')),
        'hlc', jsonb_build_object('p', 999, 'c', 0, 'n', 'b'))));
    raise notice '2 stale clock -> %', v_r::text;   -- expect lost=1, applied=0

    -- 3. A newer clock on a different field wins, leaving the others alone.
    v_r := sync_batch_apply(jsonb_build_array(jsonb_build_object(
        'patchId', 'p4', 'entityType', 'task', 'entityId', 't1', 'profileId', 'prof-1',
        'ops', jsonb_build_array(
            jsonb_build_object('field', 'title', 'op', 'set', 'value', 'Buy oat milk')),
        'hlc', jsonb_build_object('p', 2000, 'c', 0, 'n', 'b'))));
    raise notice '3 newer title -> %', v_r::text;   -- expect applied=1

    -- REQ-OS-010: a replayed patch is applied once and returns the first result.
    v_r := sync_batch_apply(jsonb_build_array(jsonb_build_object(
        'patchId', 'p1', 'entityType', 'task', 'entityId', 't1', 'profileId', 'prof-1',
        'ops', jsonb_build_array(
            jsonb_build_object('field', 'title', 'op', 'set', 'value', 'MUTATED')),
        'hlc', jsonb_build_object('p', 1000, 'c', 0, 'n', 'a'))));
    raise notice '4 replay      -> %', v_r::text;   -- expect cached=true, and no change

    -- The allowlist is a runtime check, not a code-review convention: a patch naming
    -- a field outside it is refused, and the refusal is *reported*, so the client does
    -- not advance its shadow past a value the server never took.
    v_r := sync_batch_apply(jsonb_build_array(jsonb_build_object(
        'patchId', 'p3', 'entityType', 'task', 'entityId', 't1', 'profileId', 'prof-1',
        'ops', jsonb_build_array(
            jsonb_build_object('field', 'owner_id', 'op', 'set', 'value', 'escalated')),
        'hlc', jsonb_build_object('p', 9000, 'c', 0, 'n', 'a'))));
    raise notice '5 allowlist   -> %', v_r::text;   -- expect ok=false, field_not_writable

    -- A legacy client: no clock, no operations, a full snapshot. It must still create
    -- the row -- an older build on a second device has to be *safe*, not merely
    -- tolerated, and the first legacy patch for an entity always has no row to update.
    v_r := sync_batch_apply(jsonb_build_array(jsonb_build_object(
        'patchId', 'L1', 'entityType', 'note', 'entityId', 'n1', 'profileId', 'prof-1',
        'doc', jsonb_build_object(
            'title', 'Old client note', 'updatedAt', '2026-10-06T00:00:00Z'),
        'timestampMs', 1760000000000)));
    raise notice '6 legacy      -> %', v_r::text;   -- expect created=1, legacy=true

    -- REQ-OS-005: another account's data is neither visible nor writable.
    perform set_config('request.jwt.claims',
        json_build_object('sub', '22222222-2222-2222-2222-222222222222')::text, true);
    raise notice '7 B sees events=%', sync_events_since(0, 50)::text;   -- expect []

    v_r := sync_batch_apply(jsonb_build_array(jsonb_build_object(
        'patchId', 'q1', 'entityType', 'task', 'entityId', 't1', 'profileId', 'prof-1',
        'ops', jsonb_build_array(
            jsonb_build_object('field', 'title', 'op', 'set', 'value', 'HIJACK')),
        'hlc', jsonb_build_object('p', 99999, 'c', 0, 'n', 'evil'))));
    raise notice '8 hijack      -> %', v_r::text;   -- expect ok=false, row_unavailable

    begin
        v_r := sync_transfer_ownership('33333333-3333-3333-3333-333333333333');
        raise notice '9 transfer    -> UNEXPECTED SUCCESS %', v_r::text;
    exception when others then
        raise notice '9 transfer    -> refused: %', sqlerrm;
    end;

    -- Scenario 10. The event log must carry the entity as it stands AFTER the
    -- merge. This patch is what a current client sends: field operations and a
    -- clock, and no `doc` at all. An implementation that logs `p->'doc'` records
    -- `{}` here, and every other client deserialises `data` as a whole entity --
    -- so it reconstructs a task with a blank title rather than skipping the event.
    -- Nothing above this scenario would have noticed: a legacy snapshot patch
    -- carries `doc`, and its event looks perfectly correct.
    v_r := sync_batch_apply(jsonb_build_array(jsonb_build_object(
        'patchId', 'p10', 'entityType', 'task', 'entityId', 't10', 'profileId', 'prof-1',
        'ops', jsonb_build_array(
            jsonb_build_object('field', 'title', 'op', 'set', 'value', 'Diff-only client')),
        'hlc', jsonb_build_object('p', 3000, 'c', 0, 'n', 'p10'))));
    raise notice '10 diff-patch -> %', v_r::text;   -- expect applied=1, created=1
end
$$;

-- Scenario 11. Grants on the entry points, checked separately because it is the
-- only assertion here that is about the ACL rather than about the data -- and it
-- is the one that breaks silently.
--
-- A `create or replace` preserves the existing ACL, so the grant survives every
-- ordinary migration. It does not survive a `revoke ... from public, anon,
-- authenticated` written to clean up the *helpers*, if that revoke is applied to
-- the entry point as well: nothing about the schema changes, the deploy succeeds,
-- and the client gets "permission denied for function sync_batch_apply" on the
-- first push, with no server-side error to trace it to.
--
--   expected: the four entry points true, the helpers false. A helper that is
--   true is a hole -- a client could write a row without going through the merge.
select p.proname,
       has_function_privilege('authenticated', p.oid, 'EXECUTE') as authenticated_may_execute,
       has_function_privilege('anon', p.oid, 'EXECUTE')          as anon_may_execute
from pg_proc p
join pg_namespace n on n.oid = p.pronamespace
where n.nspname = 'public' and p.proname like 'sync\_%'
order by p.proname;

-- Summary. Run as a privileged role: `authenticated` has no direct SELECT on any
-- synchronised table, which is the point.
--
--   expected:
--     doc     {"title": "Buy oat milk", "dueDate": "2026-10-05T00:00:00Z", "updatedAt": ...}
--     sv      1                      -- the stale patch bumped nothing
--     fv      title@p2000, dueDate@p1000, updatedAt@p1000
--     note    {"title": "Old client note", ...}
--     rows    1                      -- the hijack created nothing
--     ledger  L1,p1,p2,p4            -- no p3, no q1
--     events  3                      -- p1, p4 and L1; not the stale p2
select
  (select doc::text from sync_tasks where id = 't1')                        as task_doc,
  (select server_version from sync_tasks where id = 't1')                  as sv,
  (select field_versions::text from sync_tasks where id = 't1')            as field_versions,
  (select doc::text from sync_notes where id = 'n1')                       as legacy_note,
  (select count(*) from sync_tasks where id = 't1')                        as t1_rows,
  (select count(distinct owner_id) from sync_tasks)                        as distinct_task_owners,
  (select string_agg(patch_id, ',' order by patch_id) from sync_applied_patches) as ledger,
  (select count(*) from sync_events)                                       as events;

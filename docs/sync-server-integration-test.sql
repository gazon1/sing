-- Integration test for the sync server side.
--
-- Run it against a real database with a signed-in session; every assertion here is
-- a behaviour the client depends on and cannot verify on its own. It exists as a file
-- rather than as ad-hoc probes because the version that found the four defects below
-- was run by hand, and a hand-run test is a test that stops being run.
--
-- How to run: paste the two `create auth user` statements, then the probe block, into
-- a session with the service role.
--
-- **The run fails.** Every check goes through `pg_temp.sq_check`, which raises an
-- exception on a mismatch and aborts the block, so a wrong result is a failed run
-- naming the scenario and the field. It used to be 11 `raise notice` calls with the
-- expected value in a trailing comment, which printed a wrong answer and exited 0 --
-- the reason #184 was filed, and the reason it stopped being run.
--
-- Calibrated against the live project on 2026-10-05, and every expectation below was
-- observed rather than assumed. That caught two things the comments had wrong:
-- SQ-10 expected `applied=1` where a create reports `applied=0, created=1`, and SQ-09
-- named no message at all for a refusal that is really
-- 'ownership can only be claimed by its current owner' (SQLSTATE 42501).
--
-- **Wrap the whole thing in a transaction and roll it back** if the project has data
-- you care about: the block truncates the sync tables on the way in.
--
--   begin;
--   -- ...paste here...
--   rollback;
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

-- ═══════════════════════════════════════════════════════════════════════════
-- The assertion helper, and why the file has one at all
-- ═══════════════════════════════════════════════════════════════════════════
--
-- Every check below used to be a `raise notice` with the expected value in a
-- trailing comment. A wrong result printed a wrong notice and the run still
-- exited 0 — the script could not fail. That is the defect #184 records, and it
-- is the reason it stopped being run: a check that cannot fail is not a check.
--
-- `pg_temp` so nothing is left behind on a project this is pasted into, and so
-- the file needs no privileges beyond the ones the probes already need. An
-- exception aborts the whole `do` block, which is the point: the run fails, and
-- the first failure is the one that stopped it.
--
-- `is distinct from` rather than `<>`, because a null is a legitimate value for
-- half of these keys and `null <> 0` is null, not true — an assertion that
-- silently passes on a missing field is the same defect one level down.
create or replace function pg_temp.sq_check(
    p_scenario text,
    p_label    text,
    p_actual   anyelement,
    p_expected anyelement
) returns void
language plpgsql
as $$
begin
    if p_actual is distinct from p_expected then
        raise exception
            'SQ FAIL % %: got %, expected %',
            p_scenario, p_label, coalesce(p_actual::text, '<null>'),
            coalesce(p_expected::text, '<null>');
    end if;
    raise notice '  ok  % % = %', p_scenario, p_label, p_actual;
end;
$$;

do $$
declare
    v_r jsonb;
    v_scenario text;
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
    v_scenario := 'SQ-01 create';
    perform pg_temp.sq_check(v_scenario, 'created', v_r ->> 'created', '1');
    perform pg_temp.sq_check(v_scenario, 'applied', v_r ->> 'applied', '0');
    perform pg_temp.sq_check(v_scenario, 'ok',      v_r #>> '{results,0,ok}', 'true');
    perform pg_temp.sq_check(v_scenario, 'lost',    v_r #>> '{results,0,lost}', 'false');

    -- 2. A second device changes ONLY the dueDate, with an OLDER clock. It must lose,
    --    and it must not disturb the title. This is the row-checksum behaviour the
    --    per-field policy replaced, and it is the reason the patch carries a clock.
    v_r := sync_batch_apply(jsonb_build_array(jsonb_build_object(
        'patchId', 'p2', 'entityType', 'task', 'entityId', 't1', 'profileId', 'prof-1',
        'ops', jsonb_build_array(
            jsonb_build_object('field', 'dueDate', 'op', 'set', 'value', '1999-01-01T00:00:00Z')),
        'hlc', jsonb_build_object('p', 999, 'c', 0, 'n', 'b'))));
    v_scenario := 'SQ-02 stale clock';
    perform pg_temp.sq_check(v_scenario, 'lost',    v_r ->> 'lost', '1');
    perform pg_temp.sq_check(v_scenario, 'applied', v_r ->> 'applied', '0');
    -- The whole point of the field-level policy: a write the server discarded is
    -- reported as ok=true with lost=true, NOT as a refusal. A client that branches
    -- on `ok` alone records this as delivered. See #203.
    perform pg_temp.sq_check(v_scenario, 'ok',      v_r #>> '{results,0,ok}', 'true');
    perform pg_temp.sq_check(v_scenario, 'lost',    v_r #>> '{results,0,lost}', 'true');

    -- 3. A newer clock on a different field wins, leaving the others alone.
    v_r := sync_batch_apply(jsonb_build_array(jsonb_build_object(
        'patchId', 'p4', 'entityType', 'task', 'entityId', 't1', 'profileId', 'prof-1',
        'ops', jsonb_build_array(
            jsonb_build_object('field', 'title', 'op', 'set', 'value', 'Buy oat milk')),
        'hlc', jsonb_build_object('p', 2000, 'c', 0, 'n', 'b'))));
    v_scenario := 'SQ-03 newer title';
    perform pg_temp.sq_check(v_scenario, 'applied', v_r ->> 'applied', '1');
    perform pg_temp.sq_check(v_scenario, 'lost',    v_r ->> 'lost', '0');
    perform pg_temp.sq_check(v_scenario, 'lost',    v_r #>> '{results,0,lost}', 'false');

    -- REQ-OS-010: a replayed patch is applied once and returns the first result.
    v_r := sync_batch_apply(jsonb_build_array(jsonb_build_object(
        'patchId', 'p1', 'entityType', 'task', 'entityId', 't1', 'profileId', 'prof-1',
        'ops', jsonb_build_array(
            jsonb_build_object('field', 'title', 'op', 'set', 'value', 'MUTATED')),
        'hlc', jsonb_build_object('p', 1000, 'c', 0, 'n', 'a'))));
    v_scenario := 'SQ-04 replay';
    -- Replayed from the ledger, so nothing is applied a second time and the result
    -- is the stored one plus cached=true.
    perform pg_temp.sq_check(v_scenario, 'cached',  v_r #>> '{results,0,cached}', 'true');
    perform pg_temp.sq_check(v_scenario, 'applied', v_r ->> 'applied', '0');
    perform pg_temp.sq_check(v_scenario, 'created', v_r ->> 'created', '0');

    -- The allowlist is a runtime check, not a code-review convention: a patch naming
    -- a field outside it is refused, and the refusal is *reported*, so the client does
    -- not advance its shadow past a value the server never took.
    v_r := sync_batch_apply(jsonb_build_array(jsonb_build_object(
        'patchId', 'p3', 'entityType', 'task', 'entityId', 't1', 'profileId', 'prof-1',
        'ops', jsonb_build_array(
            jsonb_build_object('field', 'owner_id', 'op', 'set', 'value', 'escalated')),
        'hlc', jsonb_build_object('p', 9000, 'c', 0, 'n', 'a'))));
    v_scenario := 'SQ-05 allowlist';
    perform pg_temp.sq_check(v_scenario, 'ok',    v_r #>> '{results,0,ok}', 'false');
    perform pg_temp.sq_check(v_scenario, 'error', v_r #>> '{results,0,error}', 'field_not_writable');
    perform pg_temp.sq_check(v_scenario, 'failed', v_r ->> 'failed', '1');

    -- A legacy client: no clock, no operations, a full snapshot. It must still create
    -- the row -- an older build on a second device has to be *safe*, not merely
    -- tolerated, and the first legacy patch for an entity always has no row to update.
    v_r := sync_batch_apply(jsonb_build_array(jsonb_build_object(
        'patchId', 'L1', 'entityType', 'note', 'entityId', 'n1', 'profileId', 'prof-1',
        'doc', jsonb_build_object(
            'title', 'Old client note', 'updatedAt', '2026-10-06T00:00:00Z'),
        'timestampMs', 1760000000000)));
    v_scenario := 'SQ-06 legacy snapshot';
    perform pg_temp.sq_check(v_scenario, 'created', v_r ->> 'created', '1');
    perform pg_temp.sq_check(v_scenario, 'legacy',  v_r #>> '{results,0,legacy}', 'true');
    perform pg_temp.sq_check(v_scenario, 'ok',      v_r #>> '{results,0,ok}', 'true');

    -- REQ-OS-005: another account's data is neither visible nor writable.
    perform set_config('request.jwt.claims',
        json_build_object('sub', '22222222-2222-2222-2222-222222222222')::text, true);
    v_scenario := 'SQ-07 other account sees nothing';
    v_r := sync_events_since(0, 50);
    perform pg_temp.sq_check(v_scenario, 'events', jsonb_array_length(v_r), 0);

    v_r := sync_batch_apply(jsonb_build_array(jsonb_build_object(
        'patchId', 'q1', 'entityType', 'task', 'entityId', 't1', 'profileId', 'prof-1',
        'ops', jsonb_build_array(
            jsonb_build_object('field', 'title', 'op', 'set', 'value', 'HIJACK')),
        'hlc', jsonb_build_object('p', 99999, 'c', 0, 'n', 'evil'))));
    v_scenario := 'SQ-08 hijack';
    perform pg_temp.sq_check(v_scenario, 'ok',    v_r #>> '{results,0,ok}', 'false');
    perform pg_temp.sq_check(v_scenario, 'error', v_r #>> '{results,0,error}', 'row_unavailable');
    perform pg_temp.sq_check(v_scenario, 'created', v_r ->> 'created', '0');

    -- SQ-09. The one place the file already had real control flow, and it was
    -- still only half a check: it asserted that *an* exception was raised, without
    -- looking at which one. A function that fails for the wrong reason — a typo in
    -- a column name, a revoked privilege, a missing argument — passes this exactly
    -- as a correct refusal does. The message is the part that distinguishes them.
    v_scenario := 'SQ-09 transfer refused';
    begin
        v_r := sync_transfer_ownership('33333333-3333-3333-3333-333333333333');
        raise exception
            'SQ FAIL %: transfer to a third account SUCCEEDED, returned %',
            v_scenario, v_r::text;
    exception
        when raise_exception then
            -- Our own failure above, re-raised by this block. Not a refusal.
            raise;
        when others then
            -- Both the message and the SQLSTATE, and the message is the part that
            -- matters. 42501 is "insufficient_privilege", which a missing grant
            -- would also produce; the text says the refusal was about ownership,
            -- which is the behaviour under test. Observed against the project on
            -- 2026-10-05 — the message is the server's to change, so a reword is a
            -- legitimate edit here and a wrong refusal is not.
            perform pg_temp.sq_check(
                v_scenario, 'refused with',
                sqlerrm, 'ownership can only be claimed by its current owner');
            perform pg_temp.sq_check(v_scenario, 'sqlstate', sqlstate, '42501');
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
    v_scenario := 'SQ-10 diff-only patch';
    -- `applied=0, created=1`, and this is the one place the old comment was simply
    -- wrong: it read "expect applied=1, created=1". A create increments `created` and
    -- not `applied` — `applied` counts the update path (see the `else` arm in
    -- sync_batch_apply), and SQ-01 is the other create, so it already asserted
    -- `applied=0`. Nobody noticed for the whole life of the file, because the
    -- expectation was a trailing comment on a notice rather than a comparison.
    perform pg_temp.sq_check(v_scenario, 'applied', v_r ->> 'applied', '0');
    perform pg_temp.sq_check(v_scenario, 'created', v_r ->> 'created', '1');
    perform pg_temp.sq_check(v_scenario, 'legacy',  v_r #>> '{results,0,legacy}', 'false');
    -- The event carries the merged row, not the patch's `doc`. A client that
    -- deserialises the payload as a whole entity must not get a blank title here.
    -- Through `sync_events_since`, not `select … from sync_events`. The direct read
    -- is what the ACL check below forbids, and it is refused — 42501, permission
    -- denied for table sync_events — because `authenticated` has no SELECT there.
    -- The first version of this check read the table, so the isolation the rest of
    -- the file asserts was itself broken by the assertion. This is the shape a client
    -- actually has, and it is the only one that can be true on a correct schema.
    perform pg_temp.sq_check(
        v_scenario, 'event carries the merged row',
        (select e -> 'data' ->> 'title'
         from jsonb_array_elements(sync_events_since(0, 50)) e
         where e ->> 'entityId' = 't10'),
        'Diff-only client');

    -- SQ-11 / RL-05. The ACL, which is the assertion here that is not about data
    -- and is the one that breaks silently.
    --
    -- A `create or replace` preserves the existing ACL, so the grant survives every
    -- ordinary migration. It does not survive a `revoke ... from public, anon,
    -- authenticated` written to clean up the *helpers*, if that revoke is applied to
    -- the entry point as well: nothing about the schema changes, the deploy
    -- succeeds, and the client gets "permission denied for function sync_batch_apply"
    -- on the first push, with no server-side error to trace it to.
    --
    -- Every count here is `count(*)::bigint` compared against an explicitly cast
    -- literal. `anyelement` will not pair a bigint with an integer literal and says
    -- so at run time rather than coercing, which is the helper refusing to guess.
    --
    -- The four entry points are the only `sync_*` functions `authenticated` may run.
    -- A helper that is reachable is a hole — a client could write a row without
    -- going through the merge. Asserted as a count, not as a per-function list, so
    -- that adding a fifth entry point is a deliberate edit to the expected number
    -- rather than something the check quietly tolerates.
    v_scenario := 'SQ-11 entry points';
    perform pg_temp.sq_check(v_scenario, 'entry points reachable by authenticated',
        (select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
          where n.nspname = 'public' and p.proname like 'sync\_%'
            and has_function_privilege('authenticated', p.oid, 'EXECUTE')),
        4::bigint);
    perform pg_temp.sq_check(v_scenario, 'entry points reachable by anon',
        (select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
          where n.nspname = 'public' and p.proname like 'sync\_%'
            and has_function_privilege('anon', p.oid, 'EXECUTE')),
        0);

    -- The half that was missing. The ADR says "Direct table access is revoked. The
    -- clients call four functions and nothing else", and the test plan's RL-05
    -- assumed the opposite — that policies are `for all` so a user could write
    -- directly. Only the *function* privileges were ever checked, so a direct
    -- `insert into sync_tasks` by `authenticated` would have gone unnoticed, and it
    -- is the one that bypasses the allowlist, the per-field merge and the event log
    -- all at once. Observed 2026-10-05: the count is 0, the ADR is right.
    v_scenario := 'RL-05 direct table access';
    perform pg_temp.sq_check(v_scenario, 'entity tables writable directly by authenticated',
        (select count(*) from information_schema.role_table_grants
          where grantee = 'authenticated' and table_schema = 'public'
            and table_name like 'sync\_%'
            and table_name <> 'sync_field_allowlist'
            and privilege_type in ('INSERT', 'UPDATE', 'DELETE')),
        0::bigint);
    perform pg_temp.sq_check(v_scenario, 'entity tables readable directly by authenticated',
        (select count(*) from information_schema.role_table_grants
          where grantee = 'authenticated' and table_schema = 'public'
            and table_name like 'sync\_%'
            and table_name <> 'sync_field_allowlist'
            and privilege_type = 'SELECT'),
        0::bigint);
    -- The allowlist is the one exception, and it is an exception for a reason: a
    -- client cannot know which fields are writable without reading it. Excluded from
    -- the two checks above rather than asserted at zero, so that the exclusion is a
    -- visible decision in this file instead of a special case buried in a count.
    perform pg_temp.sq_check(v_scenario, 'allowlist readable (the one intended grant)',
        (select count(*) from information_schema.role_table_grants
          where grantee = 'authenticated' and table_schema = 'public'
            and table_name = 'sync_field_allowlist' and privilege_type = 'SELECT'),
        1::bigint);
    perform pg_temp.sq_check(v_scenario, 'allowlist not writable',
        (select count(*) from information_schema.role_table_grants
          where grantee = 'authenticated' and table_schema = 'public'
            and table_name = 'sync_field_allowlist'
            and privilege_type in ('INSERT', 'UPDATE', 'DELETE')),
        0::bigint);
end
$$;

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

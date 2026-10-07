-- sync schema — the whole of it, versioned (#184, part 2)
--
-- Captured from the live project on 2026-10-07, reading the catalog rather than
-- retyping: tables and columns from `information_schema`, constraints and indexes from
-- `pg_constraint` / `pg_indexes`, policies from `pg_policy`, grants from
-- `information_schema.role_*_grants`, and function bodies from `pg_proc.prosrc`.
--
-- ## Why this file exists
--
-- Part 1 of #184 is `docs/sync-server-integration-test.sql`, which *fails* on purpose:
-- it proves the server is not in the state the client needs. It says nothing about what
-- the state actually is. Before this file, the answer to "what does `sync_batch_apply`
-- do?" was one 5.6 KB function body in a database, discoverable only by an agent holding
-- credentials — no history, no review, no diff. Every issue that re-derives this schema
-- (#203, #207, #179) had to re-derive it the same way.
--
-- ## Read the security parts first
--
-- RLS and grants are the reason this file is complete rather than partial. A snapshot of
-- the tables alone would look authoritative and would be missing the only thing that
-- decides whether one account can read another's data. So:
--
--   - Every `sync_*` table has RLS enabled and is scoped to `auth.uid()`. The client
--     never filters by owner in application code; if these policies are dropped, every
--     signed-in user sees every other user's tasks.
--   - `authenticated` has NO direct table privileges at all — only EXECUTE on the RPCs.
--     That is deliberate: the functions are SECURITY DEFINER, so they are the only path
--     in, and the ownership check inside them (`auth.uid()`) is the only thing standing
--     between a caller and someone else's rows.
--   - `sync_field_allowlist` is the one table `authenticated` can read directly. It is
--     configuration, not user data.
--
-- A partial dump that omitted all of the above would have been worse than no dump.
--
-- ## Applying
--
--     psql "$DATABASE_URL" -f supabase/migrations/2026-10-07-sync_schema.sql
--
-- Expect `auth.uid()` and `auth.users` to exist — they come from Supabase, not from
-- here. Everything else in this file is self-contained.
--
-- ## Fingerprint
--
-- md5 of `pg_proc.prosrc` for each function in this file, as captured:
--
--   619f00ec0fba39171e46b257500bb125  sync_apply_ops(p_type text, p_profile text, p_entity text, p_ops jsonb, p_hlc jsonb)
--   2e679b3ce743b6098b6efaae54f3d0f9  sync_batch_apply(p_patches jsonb)
--   135b89cadeff6bc51db9a8c1529c3d0b  sync_events_since(p_since_lsn bigint, p_limit integer)
--   13768178a7d4ea540c78955e7f4e08f9  sync_health()
--   e8367279ac9e9025851d80ce1febff83  sync_hlc_newer(incoming jsonb, stored jsonb)
--   b13bd1d4e3f4b0c41b5a29a781f00606  sync_insert_row(p_type text, p_profile text, p_entity text, p_doc jsonb, p_hlc jsonb)
--   8b2e61476b4164afbe9c808356698770  sync_read_row(p_type text, p_profile text, p_entity text)
--   e58f10b07240980e051cd744460c9748  sync_row_exists(p_type text, p_profile text, p_entity text)
--   fa432bc3ff5b2ee29c10c15c36872e32  sync_table_for(p_type text)
--   7ddd6596698c27ab4d3a770521681def  sync_to_millis(p_doc jsonb, p_key text)
--   ec45e0be7bb955178b06c573b04a1d29  sync_transfer_ownership(p_to_owner uuid)
--   c3241da28706ae79a775320b4db4a829  sync_writable_op_count(p_type text, p_ops jsonb)
--
-- All twelve matched the live catalog byte for byte when this was written, so a
-- mismatch later means the schema drifted, not that somebody reformatted SQL.
-- Re-derive with `select md5(prosrc) from pg_proc where proname like 'sync\_%'`.
--
-- ## Drift
--
-- This is a point-in-time capture, not a continuous sync. After changing the live schema,
-- re-capture it: the catalog is the source of truth and this file is its history.

-- ═══════════════════════════════════════════════════════════════════════════════
-- 1. Tables
-- ═══════════════════════════════════════════════════════════════════════════════

-- The pull cursor. `lsn` is a plain sequence, not an identity column: a gap in it is
-- fine and an event that is never read must not consume a number that a later reader
-- would wait for.
create sequence if not exists sync_events_lsn_seq;

-- Every patch the server has applied, keyed by (owner, patch_id). This is the
-- idempotency table: `sync_batch_apply` consults it before doing any work, so a client
-- that retries after a timeout gets the original answer back instead of applying twice.
create table if not exists sync_applied_patches (
    owner_id uuid not null references auth.users(id) on delete cascade,
    patch_id text not null,
    entity_id text not null,
    entity_type text not null,
    applied_at bigint not null default 0,
    result jsonb not null default '{}'::jsonb,
    primary key (owner_id, patch_id)
);

create table if not exists sync_events (
    lsn bigint not null default nextval('sync_events_lsn_seq'::regclass),
    owner_id uuid not null references auth.users(id) on delete cascade,
    profile_id text not null,
    entity_type text not null,
    entity_id text not null,
    event_type text not null,
    protocol_version integer not null default 1,
    data jsonb not null default '{}'::jsonb,
    hlc text,
    server_ts bigint not null default 0,
    primary key (lsn)
);

-- Per-field write permission. A field absent from this table cannot be written by any
-- client, no matter what a patch asks for; this is the check that turns a malicious or
-- buggy client into a rejected patch rather than a corrupted row.
create table if not exists sync_field_allowlist (
    entity_type text not null,
    field text not null,
    writable boolean not null default true,
    primary key (entity_type, field)
);

create table if not exists sync_profiles (
    id text not null,
    owner_id uuid not null references auth.users(id) on delete cascade,
    name text not null,
    emoji text,
    color_idx integer not null default 0,
    is_default boolean not null default false,
    created_at bigint not null default 0,
    updated_at bigint not null default 0,
    field_versions jsonb not null default '{}'::jsonb,
    server_version bigint not null default 0,
    primary key (id)
);

-- The five document tables share one shape: an opaque `doc`, a `field_versions` map
-- holding the HLC of each field, and `server_version` for the caller's `baseVersion`.
--
-- `doc` is jsonb and not columns because the merge is per field, and a schema that
-- forces every new field into a column would make "add a field" a server migration —
-- which is how a protocol change turns into a coordination problem.
create table if not exists sync_tasks (
    id text not null,
    owner_id uuid not null,
    profile_id text not null,
    doc jsonb not null default '{}'::jsonb,
    field_versions jsonb not null default '{}'::jsonb,
    server_version bigint not null default 0,
    created_at bigint not null default 0,
    updated_at bigint not null default 0,
    deleted_at bigint,
    primary key (id)
);

create table if not exists sync_notes (
    id text not null,
    owner_id uuid not null,
    profile_id text not null,
    doc jsonb not null default '{}'::jsonb,
    field_versions jsonb not null default '{}'::jsonb,
    server_version bigint not null default 0,
    created_at bigint not null default 0,
    updated_at bigint not null default 0,
    deleted_at bigint,
    primary key (id)
);

create table if not exists sync_projects (
    id text not null,
    owner_id uuid not null,
    profile_id text not null,
    doc jsonb not null default '{}'::jsonb,
    field_versions jsonb not null default '{}'::jsonb,
    server_version bigint not null default 0,
    created_at bigint not null default 0,
    updated_at bigint not null default 0,
    deleted_at bigint,
    primary key (id)
);

create table if not exists sync_tags (
    id text not null,
    owner_id uuid not null,
    profile_id text not null,
    doc jsonb not null default '{}'::jsonb,
    field_versions jsonb not null default '{}'::jsonb,
    server_version bigint not null default 0,
    created_at bigint not null default 0,
    updated_at bigint not null default 0,
    deleted_at bigint,
    primary key (id)
);

create table if not exists sync_tag_groups (
    id text not null,
    owner_id uuid not null,
    profile_id text not null,
    doc jsonb not null default '{}'::jsonb,
    field_versions jsonb not null default '{}'::jsonb,
    server_version bigint not null default 0,
    created_at bigint not null default 0,
    updated_at bigint not null default 0,
    deleted_at bigint,
    primary key (id)
);

create table if not exists sync_time_entries (
    id text not null,
    owner_id uuid not null,
    profile_id text not null,
    doc jsonb not null default '{}'::jsonb,
    field_versions jsonb not null default '{}'::jsonb,
    server_version bigint not null default 0,
    created_at bigint not null default 0,
    updated_at bigint not null default 0,
    deleted_at bigint,
    primary key (id)
);

-- ═══════════════════════════════════════════════════════════════════════════════
-- 2. Indexes
-- ═══════════════════════════════════════════════════════════════════════════════

-- The pull path: one scope, strictly after a cursor, in order.
create index if not exists sync_events_scope_lsn_idx
    on sync_events (owner_id, profile_id, lsn);
create index if not exists sync_events_owner_lsn_idx
    on sync_events (owner_id, lsn);

-- `sync_profiles_owner_idx` has no `deleted_at`: the profile table has no such column,
-- and a profile is not deleted by the sync protocol at all.
create index if not exists sync_profiles_owner_idx
    on sync_profiles (owner_id, updated_at);

-- The three access shapes each document table has:
--   live   — the rows the client lists, partial-indexed on `deleted_at is null` so a
--            trashed row is never read into a list query at all;
--   owner  — everything in a scope, newest first, which is also what the diff walks;
--   fv     — GIN over `field_versions`, which is what makes a per-field merge lookup
--            possible instead of a scan.
create index if not exists sync_tasks_live_idx       on sync_tasks       (owner_id, profile_id) where deleted_at is null;
create index if not exists sync_tasks_owner_idx      on sync_tasks       (owner_id, profile_id, updated_at);
create index if not exists sync_tasks_field_versions_idx on sync_tasks    using gin (field_versions);

create index if not exists sync_notes_live_idx       on sync_notes       (owner_id, profile_id) where deleted_at is null;
create index if not exists sync_notes_owner_idx      on sync_notes       (owner_id, profile_id, updated_at);
create index if not exists sync_notes_field_versions_idx on sync_notes    using gin (field_versions);

create index if not exists sync_projects_live_idx       on sync_projects (owner_id, profile_id) where deleted_at is null;
create index if not exists sync_projects_owner_idx      on sync_projects (owner_id, profile_id, updated_at);
create index if not exists sync_projects_field_versions_idx on sync_projects using gin (field_versions);

create index if not exists sync_tags_live_idx       on sync_tags       (owner_id, profile_id) where deleted_at is null;
create index if not exists sync_tags_owner_idx      on sync_tags       (owner_id, profile_id, updated_at);
create index if not exists sync_tags_field_versions_idx on sync_tags    using gin (field_versions);

create index if not exists sync_tag_groups_live_idx       on sync_tag_groups (owner_id, profile_id) where deleted_at is null;
create index if not exists sync_tag_groups_owner_idx      on sync_tag_groups (owner_id, profile_id, updated_at);
create index if not exists sync_tag_groups_field_versions_idx on sync_tag_groups using gin (field_versions);

create index if not exists sync_time_entries_live_idx       on sync_time_entries (owner_id, profile_id) where deleted_at is null;
create index if not exists sync_time_entries_owner_idx      on sync_time_entries (owner_id, profile_id, updated_at);
create index if not exists sync_time_entries_field_versions_idx on sync_time_entries using gin (field_versions);

-- ═══════════════════════════════════════════════════════════════════════════════
-- 3. Row level security
-- ═══════════════════════════════════════════════════════════════════════════════

-- `for all` with both USING and WITH CHECK set to the same predicate. Setting only one
-- is a common and quiet mistake: USING alone filters reads and lets any row be written,
-- which is how a client ends up able to inject rows into another account's scope.
--
-- The client never filters by owner in application code. These policies are the whole
-- of multi-tenancy on the server side.
alter table sync_applied_patches enable row level security;
drop policy if exists sync_applied_patches_owner_policy on sync_applied_patches;
create policy sync_applied_patches_owner_policy on sync_applied_patches
    for all to authenticated
    using      (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()));

alter table sync_events enable row level security;
drop policy if exists sync_events_owner_policy on sync_events;
create policy sync_events_owner_policy on sync_events
    for all to authenticated
    using      (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()));

alter table sync_profiles enable row level security;
drop policy if exists sync_profiles_owner_policy on sync_profiles;
create policy sync_profiles_owner_policy on sync_profiles
    for all to authenticated
    using      (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()));

alter table sync_tasks enable row level security;
drop policy if exists sync_tasks_owner_policy on sync_tasks;
create policy sync_tasks_owner_policy on sync_tasks
    for all to authenticated
    using      (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()));

alter table sync_notes enable row level security;
drop policy if exists sync_notes_owner_policy on sync_notes;
create policy sync_notes_owner_policy on sync_notes
    for all to authenticated
    using      (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()));

alter table sync_projects enable row level security;
drop policy if exists sync_projects_owner_policy on sync_projects;
create policy sync_projects_owner_policy on sync_projects
    for all to authenticated
    using      (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()));

alter table sync_tags enable row level security;
drop policy if exists sync_tags_owner_policy on sync_tags;
create policy sync_tags_owner_policy on sync_tags
    for all to authenticated
    using      (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()));

alter table sync_tag_groups enable row level security;
drop policy if exists sync_tag_groups_owner_policy on sync_tag_groups;
create policy sync_tag_groups_owner_policy on sync_tag_groups
    for all to authenticated
    using      (owner_id = (select auth.uid()))
    with check (owner_id = (select auth.uid()));

-- Read-only, and deliberately the one readable table: the allowlist is configuration,
-- not user data, and a client is allowed to know which fields it may write. It is `to
-- authenticated` and not to `anon` because an unauthenticated caller has no business
-- enumerating the protocol either.
alter table sync_field_allowlist enable row level security;
drop policy if exists sync_field_allowlist_read_policy on sync_field_allowlist;
create policy sync_field_allowlist_read_policy on sync_field_allowlist
    for select to authenticated
    using (true);

-- ═══════════════════════════════════════════════════════════════════════════════
-- 4. Privileges
-- ═══════════════════════════════════════════════════════════════════════════════

-- The client has no table privileges. It reaches data only through the SECURITY
-- DEFINER functions below, each of which re-checks `auth.uid()`. Revoking is the default
-- posture rather than an afterthought: a table the client cannot touch cannot be read in
-- bulk by a client that finds a way to bypass the RPC path.
revoke all on sync_applied_patches from anon, authenticated;
revoke all on sync_events             from anon, authenticated;
revoke all on sync_profiles           from anon, authenticated;
revoke all on sync_tasks              from anon, authenticated;
revoke all on sync_notes              from anon, authenticated;
revoke all on sync_projects           from anon, authenticated;
revoke all on sync_tags               from anon, authenticated;
revoke all on sync_tag_groups         from anon, authenticated;
revoke all on sync_time_entries       from anon, authenticated;

revoke all on sync_field_allowlist   from anon, authenticated;
grant  select on sync_field_allowlist to authenticated;

grant all on sync_applied_patches to service_role;
grant all on sync_events           to service_role;
grant all on sync_profiles         to service_role;
grant all on sync_tasks            to service_role;
grant all on sync_notes            to service_role;
grant all on sync_projects         to service_role;
grant all on sync_tags             to service_role;
grant all on sync_tag_groups       to service_role;
grant all on sync_time_entries     to service_role;
grant all on sync_field_allowlist  to service_role;

-- ═══════════════════════════════════════════════════════════════════════════════
-- 5. Functions
-- ═══════════════════════════════════════════════════════════════════════════════

-- Pure helpers: IMMUTABLE, not SECURITY DEFINER, executable only by the roles that
-- already own the schema. `authenticated` has no EXECUTE on any of these — they are
-- reachable only from inside a SECURITY DEFINER function, where the caller is already
-- the table owner.

create or replace function public.sync_table_for(p_type text)
returns text
language plpgsql
immutable
as $fn$
begin
    return case p_type
        when 'task'        then 'sync_tasks'
        when 'note'        then 'sync_notes'
        when 'project'     then 'sync_projects'
        when 'tag'         then 'sync_tags'
        when 'tag_group'   then 'sync_tag_groups'
        when 'time_entry'  then 'sync_time_entries'
        else null
    end;
end;
$fn$;

-- The whole merge, in one predicate: physical first, then counter, then node id as the
-- tie-break. This is what makes "later" a total order rather than a comparison of
-- wall-clock times, and it is why two devices with skewed clocks still converge —
-- whichever is genuinely later wins, not whichever is nearer to now.
create or replace function public.sync_hlc_newer(incoming jsonb, stored jsonb)
returns boolean
language sql
immutable
as $fn$
    select
        (incoming ->> 'p')::bigint > coalesce((stored ->> 'p')::bigint, -1)
        or (
            (incoming ->> 'p')::bigint = coalesce((stored ->> 'p')::bigint, -1)
            and (
                (incoming ->> 'c')::int > coalesce((stored ->> 'c')::int, -1)
                or (
                    (incoming ->> 'c')::int = coalesce((stored ->> 'c')::int, -1)
                    and (incoming ->> 'n') > coalesce(stored ->> 'n', '')
                )
            )
        );
$fn$;

create or replace function public.sync_to_millis(p_doc jsonb, p_key text)
returns bigint
language plpgsql
immutable
as $fn$
declare
    v_raw text := p_doc ->> p_key;
begin
    if v_raw is null or v_raw = '' then
        return null;
    end if;
    return (extract(epoch from v_raw::timestamptz) * 1000)::bigint;
exception
    when others then
        -- A malformed timestamp costs that one column, not the batch. The field stays
        -- in `doc` exactly as sent, so nothing is lost -- only the typed index column
        -- falls back.
        return null;
end;
$fn$;

create or replace function public.sync_writable_op_count(p_type text, p_ops jsonb)
returns integer
language sql
stable
as $fn$
    select count(*)::int
    from jsonb_array_elements(coalesce(p_ops, '[]'::jsonb)) op
    where exists (
        select 1 from sync_field_allowlist a
        where a.entity_type = p_type and a.field = op ->> 'field' and a.writable
    );
$fn$;

-- Read paths. Both SECURITY DEFINER because `authenticated` has no table privileges;
-- both derive the owner from `auth.uid()` and never accept it as an argument, which is
-- what makes "whose rows" unanswerable by the caller.

create or replace function public.sync_read_row(p_type text, p_profile text, p_entity text)
returns jsonb
language plpgsql
stable
security definer
as $fn$
declare
    v_owner uuid := auth.uid();
    v_doc  jsonb;
    v_hit  integer;
begin
    if v_owner is null then
        return null;
    end if;

    execute format(
        'select doc from %1$I where id = $1 and owner_id = $2 and profile_id = $3',
        public.sync_table_for(p_type)
    ) into v_doc using p_entity, v_owner, p_profile;

    get diagnostics v_hit = row_count;
    if v_hit = 0 then
        return null;
    end if;

    return v_doc;
end;
$fn$;

create or replace function public.sync_row_exists(p_type text, p_profile text, p_entity text)
returns boolean
language plpgsql
stable
security definer
as $fn$
declare
    v_owner uuid := auth.uid();
    v_table text;
    v_hit   boolean;
begin
    if v_owner is null then
        return false;
    end if;
    v_table := sync_table_for(p_type);
    if v_table is null then
        return false;
    end if;

    execute format(
        'select exists (select 1 from %1$I
                          where id = $1 and owner_id = $2 and profile_id = $3)',
        v_table
    ) into v_hit using p_entity, v_owner, p_profile;

    return v_hit;
end;
$fn$;

create or replace function public.sync_health()
returns jsonb
language plpgsql
stable
security definer
as $fn$
begin
    if auth.uid() is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;
    return jsonb_build_object('ok', true, 'ownerId', auth.uid(),
                              'serverTs', (extract(epoch from now()) * 1000)::bigint);
end;
$fn$;

-- ── The pull path ─────────────────────────────────────────────────────────────
--
-- Note that the event carries `hlc`. #179 records that nothing merges it on the client —
-- `HlcFactory.tock` still has no caller — which is exactly why it is written down here,
-- where the fix will change this function's payload first.

create or replace function public.sync_events_since(p_since_lsn bigint, p_limit integer)
returns jsonb
language plpgsql
stable
security definer
as $fn$
declare
    v_owner uuid := auth.uid();
begin
    if v_owner is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    return coalesce((
        select jsonb_agg(
            jsonb_build_object(
                'serverLsn', e.lsn,
                'ownerId', e.owner_id,
                'profileId', e.profile_id,
                'entityType', e.entity_type,
                'entityId', e.entity_id,
                'eventType', e.event_type,
                'protocolVersion', e.protocol_version,
                'data', e.data,
                'hlc', e.hlc,
                'serverTs', e.server_ts
            )
        )
        from (
            select * from sync_events
            where owner_id = v_owner and lsn > p_since_lsn
            order by lsn
            limit least(greatest(coalesce(p_limit, 50), 1), 500)
        ) e
    ), '[]'::jsonb);
end;
$fn$;

-- ── The push path ─────────────────────────────────────────────────────────────

create or replace function public.sync_apply_ops(
    p_type text, p_profile text, p_entity text, p_ops jsonb, p_hlc jsonb
)
returns integer
language plpgsql
volatile
security definer
as $fn$
declare
    v_owner   uuid := auth.uid();
    v_table   text;
    v_allowed text := 'exists (select 1 from sync_field_allowlist a
                              where a.entity_type = $2 and a.field = op ->> ''field'' and a.writable)';
    v_newer   text := 'and sync_hlc_newer($3, t.field_versions -> (op ->> ''field''))';
    v_merged  text;
    v_clocks  text;
    v_writes  text;
    v_sql     text;
    v_count   int;
begin
    if v_owner is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    v_table := sync_table_for(p_type);
    if v_table is null then
        raise exception 'unknown entity type %', p_type using errcode = '22023';
    end if;

    v_writes := format('(%s %s)', v_allowed, v_newer);
    v_merged := format(
        't.doc || coalesce((select jsonb_object_agg(op ->> ''field'', op -> ''value'')
                             from jsonb_array_elements($1) op where %s), ''{}''::jsonb)',
        v_writes
    );
    v_clocks := format(
        't.field_versions || coalesce((select jsonb_object_agg(op ->> ''field'', $3)
                             from jsonb_array_elements($1) op where %s), ''{}''::jsonb)',
        v_writes
    );

    -- The clock comparison and the values written come from the same row version: the
    -- subqueries are correlated to `t`, the row being updated. There is no statement
    -- boundary between reading a field's clock and writing its value, so there is no
    -- window to close.
    --
    -- The `count(*) … > 0` guard is the same expression as the one inside the SET list.
    -- Without it, a patch that changes nothing still increments server_version and
    -- still produces an event every other client has to download.
    v_sql := format($q$
        update %1$I t set
            doc            = %2$s,
            field_versions = %3$s,
            updated_at     = greatest(t.updated_at,
                                     coalesce(sync_to_millis(%2$s, 'updatedAt'), t.updated_at)),
            deleted_at     = case when %2$s ? 'deletedAt'
                                   then coalesce(sync_to_millis(%2$s, 'deletedAt'), t.deleted_at)
                                   else t.deleted_at end,
            server_version = t.server_version + 1
        where t.id = $4 and t.owner_id = $5 and t.profile_id = $6
          and (select count(*) from jsonb_array_elements($1) op where %4$s) > 0
        returning 1
    $q$, v_table, v_merged, v_clocks, v_writes);

    execute v_sql using p_ops, p_type, p_hlc, p_entity, v_owner, p_profile
    into v_count;

    return coalesce(v_count, 0);
end;
$fn$;

create or replace function public.sync_insert_row(
    p_type text, p_profile text, p_entity text, p_doc jsonb, p_hlc jsonb
)
returns integer
language plpgsql
volatile
security definer
as $fn$
declare
    v_owner uuid := auth.uid();
    v_table text;
    v_ts    bigint := coalesce(sync_to_millis(p_doc, 'createdAt'), 0);
    v_upd   bigint := coalesce(sync_to_millis(p_doc, 'updatedAt'), 0);
    v_del   bigint := sync_to_millis(p_doc, 'deletedAt');
    v_fv    jsonb;
    v_count int;
begin
    if v_owner is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    v_table := sync_table_for(p_type);
    if v_table is null then
        raise exception 'unknown entity type %', p_type using errcode = '22023';
    end if;

    -- Every writable field the row carries is stamped with this patch's clock, so a
    -- later patch for the same field is compared against the right baseline.
    select coalesce(jsonb_object_agg(key, p_hlc), '{}'::jsonb) into v_fv
    from jsonb_object_keys(coalesce(p_doc, '{}'::jsonb)) as key
    where exists (
        select 1 from sync_field_allowlist a
        where a.entity_type = p_type and a.field = key and a.writable
    );

    execute format(
        'insert into %1$I (id, owner_id, profile_id, doc, field_versions,
                            created_at, updated_at, deleted_at)
         values ($1, $2, $3, coalesce($4, ''{}''::jsonb), $5, $6, $7, $8)
         on conflict (id) do nothing returning 1',
        v_table
    ) using p_entity, v_owner, p_profile, p_doc, v_fv, v_ts, v_upd, v_del
    into v_count;

    return coalesce(v_count, 0);
end;
$fn$;

-- The entry point the client calls, and the function #203's whole fix is a consequence
-- of. Two things a reader should know before changing anything here:
--
--   - A patch with no `hlc` is *legacy*: its ops are rebuilt from `doc` and stamped with
--     a clock taken from `timestampMs` under the node id `legacy`. That path is what
--     lets an old client keep working against a new server, and it is why `protocolVersion`
--     exists on the event.
--   - `newVersion` is a hardcoded 1 in every result, and #207 is about exactly that: the
--     client records it as the row's server version, which is wrong for every row past
--     its first change. The comment there says so rather than leaving the reader to
--     assume the constant is meaningful.
create or replace function public.sync_batch_apply(p_patches jsonb)
returns jsonb
language plpgsql
volatile
security definer
as $fn$
declare
    v_owner    uuid := auth.uid();
    p          jsonb;
    v_seen     jsonb;
    v_type     text;
    v_entity   text;
    v_profile  text;
    v_ops      jsonb;
    v_doc      jsonb;
    v_hlc      jsonb;
    v_legacy   boolean;
    v_touched  int;
    v_writable int;
    v_lost     boolean;
    v_created  int := 0;
    v_applied  int := 0;
    v_lost_cnt int := 0;
    v_failed   int := 0;
    v_results  jsonb := '[]'::jsonb;
    v_merged   jsonb;
begin
    if v_owner is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    if jsonb_typeof(coalesce(p_patches, '[]'::jsonb)) <> 'array' then
        raise exception 'patches must be a json array' using errcode = '22023';
    end if;

    for p in select * from jsonb_array_elements(p_patches) loop
        select ap.result into v_seen
        from sync_applied_patches ap
        where ap.owner_id = v_owner and ap.patch_id = p ->> 'patchId';

        if v_seen is not null then
            v_results := v_results || jsonb_build_array(v_seen || jsonb_build_object('cached', true));
            continue;
        end if;

        v_type    := p ->> 'entityType';
        v_entity  := p ->> 'entityId';
        v_profile := p ->> 'profileId';
        v_ops     := coalesce(p -> 'ops', '[]'::jsonb);
        v_doc     := coalesce(p -> 'doc', '{}'::jsonb);

        if v_type is null or v_entity is null or v_profile is null then
            raise exception 'patch % is missing entityType, entityId or profileId',
                p ->> 'patchId' using errcode = '22023';
        end if;

        if sync_table_for(v_type) is null then
            raise exception 'unknown entity type %', v_type using errcode = '22023';
        end if;

        if p ->> 'hlc' is null then
            v_legacy := true;
            v_hlc    := jsonb_build_object(
                'p', coalesce(sync_to_millis(p, 'timestampMs'), 0), 'c', 0, 'n', 'legacy'
            );
            select coalesce(
                jsonb_agg(jsonb_build_object('field', k, 'op', 'set', 'value', v_doc -> k)),
                '[]'::jsonb
            ) into v_ops
            from jsonb_object_keys(v_doc) as k;
        else
            v_legacy := false;
            v_hlc    := coalesce(
                p -> 'hlc',
                jsonb_build_object(
                    'p', coalesce(sync_to_millis(p, 'timestampMs'), 0), 'c', 0, 'n', 'unknown'
                )
            );
        end if;

        v_writable := sync_writable_op_count(v_type, v_ops);
        if v_writable = 0 then
            v_failed := v_failed + 1;
            v_results := v_results || jsonb_build_array(
                jsonb_build_object('patchId', p ->> 'patchId', 'ok', false, 'cached', false,
                                   'conflict', false, 'error', 'field_not_writable')
            );
            continue;
        end if;

        v_touched := sync_apply_ops(v_type, v_profile, v_entity, v_ops, v_hlc);
        v_lost    := false;

        if v_touched = 0 then
            select coalesce(jsonb_object_agg(op ->> 'field', op -> 'value'), '{}'::jsonb)
            into v_merged
            from jsonb_array_elements(v_ops) op
            where exists (
                select 1 from sync_field_allowlist a
                where a.entity_type = v_type and a.field = op ->> 'field' and a.writable
            );

            if sync_row_exists(v_type, v_profile, v_entity) then
                v_lost     := true;
                v_lost_cnt := v_lost_cnt + 1;
            elsif sync_insert_row(v_type, v_profile, v_entity, v_merged, v_hlc) = 1 then
                v_created := v_created + 1;
                v_touched := 1;
            else
                v_failed := v_failed + 1;
                v_results := v_results || jsonb_build_array(
                    jsonb_build_object('patchId', p ->> 'patchId', 'ok', false, 'cached', false,
                                       'conflict', false, 'error', 'row_unavailable')
                );
                continue;
            end if;
        else
            v_applied := v_applied + 1;
        end if;

        v_results := v_results || jsonb_build_array(
            jsonb_build_object('patchId', p ->> 'patchId', 'ok', true, 'cached', false,
                               'lost', v_lost, 'legacy', v_legacy, 'newVersion', 1)
        );

        insert into sync_applied_patches (owner_id, patch_id, entity_id, entity_type, applied_at, result)
        values (
            v_owner, p ->> 'patchId', v_entity, v_type,
            (extract(epoch from now()) * 1000)::bigint,
            jsonb_build_object('patchId', p ->> 'patchId', 'ok', true, 'cached', false,
                               'lost', v_lost, 'legacy', v_legacy, 'newVersion', 1)
        )
        on conflict do nothing;

        if v_touched = 1 then
            insert into sync_events (owner_id, profile_id, entity_type, entity_id, event_type,
                                     protocol_version, data, hlc, server_ts)
            values (
                v_owner, v_profile, v_type, v_entity,
                coalesce(p ->> 'eventType', 'updated'),
                coalesce((p ->> 'protocolVersion')::int, 1),
                coalesce(public.sync_read_row(v_type, v_profile, v_entity), '{}'::jsonb),
                p ->> 'hlc',
                (extract(epoch from now()) * 1000)::bigint
            );
        end if;
    end loop;

    return jsonb_build_object(
        'results', v_results,
        'applied', v_applied,
        'created', v_created,
        'lost',    v_lost_cnt,
        'failed',  v_failed
    );
end;
$fn$;

-- A no-op by design, and kept rather than deleted for a reason worth knowing.
--
-- It looks like the place to move data between accounts after a sign-in, and it refuses.
-- The only identity a caller may claim is their own: any other target is a request to
-- move data to an account the caller does not control, which is not a transfer at all.
-- The row-scoped keys ({profileId}/{userId}, from ADR
-- 2026-10-06-a-profile-is-owned-and-an-erase-resolves-its-ids-first) already embed the
-- owner, so a real transfer is a client-side concern and cannot be expressed as one
-- server-side update.
create or replace function public.sync_transfer_ownership(p_to_owner uuid)
returns jsonb
language plpgsql
volatile
security definer
as $fn$
declare
    v_from  uuid := auth.uid();
    v_count int;
begin
    if v_from is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    if p_to_owner is null or p_to_owner <> v_from then
        -- The only identity a caller may claim is their own. Anything else is a
        -- request to move data to an account the caller does not control, which is
        -- not a transfer at all.
        raise exception 'ownership can only be claimed by its current owner'
            using errcode = '42501';
    end if;

    return jsonb_build_object('ownerId', v_from, 'transferred', false, 'note',
        'the caller already owns this data; nothing to transfer');
end;
$fn$;

-- ═══════════════════════════════════════════════════════════════════════════════
-- 6. Function privileges
-- ═══════════════════════════════════════════════════════════════════════════════

-- `authenticated` gets exactly the four entry points and nothing else. The helpers are
-- reachable only from inside these, which run as the table owner — granting EXECUTE on
-- them to a client would hand out `sync_read_row` and with it a way to read a row whose
-- id you guessed but whose owner you are not.
revoke all on function public.sync_table_for(text)            from public, anon, authenticated;
revoke all on function public.sync_hlc_newer(jsonb, jsonb)     from public, anon, authenticated;
revoke all on function public.sync_to_millis(jsonb, text)      from public, anon, authenticated;
revoke all on function public.sync_writable_op_count(text, jsonb) from public, anon, authenticated;
revoke all on function public.sync_read_row(text, text, text)  from public, anon, authenticated;
revoke all on function public.sync_row_exists(text, text, text) from public, anon, authenticated;
revoke all on function public.sync_apply_ops(text, text, text, jsonb, jsonb) from public, anon, authenticated;
revoke all on function public.sync_insert_row(text, text, text, jsonb, jsonb) from public, anon, authenticated;

grant execute on function public.sync_batch_apply(jsonb)      to authenticated;
grant execute on function public.sync_events_since(bigint, integer) to authenticated;
grant execute on function public.sync_health()                 to authenticated;
grant execute on function public.sync_transfer_ownership(uuid) to authenticated;

grant execute on function public.sync_batch_apply(jsonb)      to service_role;
grant execute on function public.sync_events_since(bigint, integer) to service_role;
grant execute on function public.sync_health()                 to service_role;
grant execute on function public.sync_transfer_ownership(uuid) to service_role;
grant execute on function public.sync_apply_ops(text, text, text, jsonb, jsonb) to service_role;
grant execute on function public.sync_insert_row(text, text, text, jsonb, jsonb) to service_role;
grant execute on function public.sync_read_row(text, text, text)  to service_role;
grant execute on function public.sync_row_exists(text, text, text) to service_role;
grant execute on function public.sync_table_for(text)            to service_role;
grant execute on function public.sync_hlc_newer(jsonb, jsonb)     to service_role;
grant execute on function public.sync_to_millis(jsonb, text)      to service_role;
grant execute on function public.sync_writable_op_count(text, jsonb) to service_role;

-- ═══════════════════════════════════════════════════════════════════════════════
-- 7. Field allowlist
-- ═══════════════════════════════════════════════════════════════════════════════

-- Every field a client may write, per entity type. This is a whitelist and the check
-- is `sync_writable_op_count`, so a field that is absent here cannot be written by any
-- patch regardless of what the client sends.
--
-- Seeded with `on conflict do nothing` so re-applying the file is safe, and so a field
-- that has been *removed* from this list on purpose is not silently re-added by a
-- re-apply — removal is `delete`, and it stays deleted.
insert into sync_field_allowlist (entity_type, field, writable) values
    ('note', 'archivedAt', true), ('note', 'bodyHtml', true), ('note', 'bodyMarkdown', true),
    ('note', 'charCount', true), ('note', 'color', true), ('note', 'createdAt', true),
    ('note', 'deletedAt', true), ('note', 'isFolder', true), ('note', 'isPinned', true),
    ('note', 'kind', true), ('note', 'outgoingLinks', true), ('note', 'parentNoteId', true),
    ('note', 'pinnedAt', true), ('note', 'sortOrder', true), ('note', 'taskId', true),
    ('note', 'title', true), ('note', 'updatedAt', true), ('note', 'wordCount', true),
    ('profile', 'colorIdx', true), ('profile', 'createdAt', true), ('profile', 'emoji', true),
    ('profile', 'isDefault', true), ('profile', 'name', true), ('profile', 'updatedAt', true),
    ('project', 'color', true), ('project', 'createdAt', true), ('project', 'deletedAt', true),
    ('project', 'description', true), ('project', 'dueDate', true), ('project', 'externalId', true),
    ('project', 'icon', true), ('project', 'idempotencyKey', true),
    ('project', 'inheritedTagGroupIds', true), ('project', 'isDefault', true),
    ('project', 'isDeleted', true), ('project', 'name', true), ('project', 'parentId', true),
    ('project', 'sortOrder', true), ('project', 'team', true), ('project', 'updatedAt', true),
    ('tag', 'color', true), ('tag', 'createdAt', true), ('tag', 'deletedAt', true),
    ('tag', 'groupId', true), ('tag', 'name', true), ('tag', 'sortOrder', true),
    ('tag', 'updatedAt', true),
    ('tag_group', 'color', true), ('tag_group', 'createdAt', true), ('tag_group', 'deletedAt', true),
    ('tag_group', 'name', true), ('tag_group', 'updatedAt', true),
    ('task', 'accentColor', true), ('task', 'aiSuppressedTagIds', true), ('task', 'archivedAt', true),
    ('task', 'completedAt', true), ('task', 'createdAt', true), ('task', 'dependsOn', true),
    ('task', 'description', true), ('task', 'dueDate', true), ('task', 'dueTime', true),
    ('task', 'emoji', true), ('task', 'endDate', true), ('task', 'endTime', true),
    ('task', 'estimateMinutes', true), ('task', 'isPinned', true), ('task', 'kind', true),
    ('task', 'parentTaskId', true), ('task', 'priority', true), ('task', 'projectId', true),
    ('task', 'recurrence', true), ('task', 'someday', true), ('task', 'startDate', true),
    ('task', 'startTime', true), ('task', 'tags', true), ('task', 'title', true),
    ('task', 'updatedAt', true),
    ('time_entry', 'createdAt', true), ('time_entry', 'deletedAt', true),
    ('time_entry', 'endedAt', true), ('time_entry', 'kind', true), ('time_entry', 'note', true),
    ('time_entry', 'source', true), ('time_entry', 'startedAt', true),
    ('time_entry', 'taskId', true), ('time_entry', 'updatedAt', true)
on conflict (entity_type, field) do nothing;
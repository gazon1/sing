-- The bucket the app pushes backup archives into, and the policies that let a
-- signed-in user put a file there and take one out.
--
-- The app uploads with `storage.from("backups").upload(path, bytes) { upsert = true }`.
-- The bucket has to exist in the project, and — this is the part that is easy to
-- miss — the objects inside it have to be *writable*. `storage.objects` has row
-- level security on, and a project with no policies on it denies every insert with
-- "new row violates row-level security policy": a message that names the database
-- and says nothing about the setting that is missing.
--
-- The scripts are idempotent, so they can be applied to a project that already has
-- the bucket and the policies. The policies are dropped and re-created rather than
-- created "if not exists": a policy that exists with the wrong body is the failure
-- this migration exists to prevent, and "if not exists" would leave it in place.

insert into storage.buckets (id, name, public)
values ('backups', 'backups', false)
on conflict (id) do nothing;

-- Whose archive is this?
--
-- The path of an archive is "<owner id>/<file name>", and the owner id is the
-- profile-scoped user id: "<auth uid>" for the default profile and
-- "<profile id>/<auth uid>" for every other one. The profile is a local concept —
-- the server has never heard of it — so the policy cannot check the *first* folder
-- of the path: for a non-default profile the first folder is the profile id, and
-- nothing on the server can know which profile ids belong to which account.
--
-- The last folder of the path is the auth uid in both shapes, so that is what the
-- policy checks:
--
--     (storage.foldername(name))[array_length(storage.foldername(name), 1)]
--
-- is the last folder, and `auth.uid()` is the caller's own id. A user can therefore
-- reach exactly the objects under their own uid folder — whatever profile prefix
-- their install puts in front of it — and nothing else.
--
-- A policy that checked only `bucket_id = 'backups'` would let any signed-in
-- account read, overwrite and delete every other account's archives as long as it
-- could guess the path. The path keeps the archives apart on the client; this is
-- what keeps them apart on the server.

drop policy if exists "backups: signed-in users may read" on storage.objects;
create policy "backups: signed-in users may read"
    on storage.objects for select
    to authenticated
    using (
        bucket_id = 'backups'
        and (storage.foldername(name))[array_length(storage.foldername(name), 1)] = auth.uid()::text
    );

drop policy if exists "backups: signed-in users may write" on storage.objects;
create policy "backups: signed-in users may write"
    on storage.objects for insert
    to authenticated
    with check (
        bucket_id = 'backups'
        and (storage.foldername(name))[array_length(storage.foldername(name), 1)] = auth.uid()::text
    );

drop policy if exists "backups: signed-in users may replace" on storage.objects;
create policy "backups: signed-in users may replace"
    on storage.objects for update
    to authenticated
    using (
        bucket_id = 'backups'
        and (storage.foldername(name))[array_length(storage.foldername(name), 1)] = auth.uid()::text
    )
    with check (
        bucket_id = 'backups'
        and (storage.foldername(name))[array_length(storage.foldername(name), 1)] = auth.uid()::text
    );

drop policy if exists "backups: signed-in users may delete" on storage.objects;
create policy "backups: signed-in users may delete"
    on storage.objects for delete
    to authenticated
    using (
        bucket_id = 'backups'
        and (storage.foldername(name))[array_length(storage.foldername(name), 1)] = auth.uid()::text
    );

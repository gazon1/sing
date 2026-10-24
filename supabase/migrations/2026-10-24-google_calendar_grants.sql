-- The Google Calendar grants, one row per user.
--
-- ## Why the refresh token lives here and not on the device
--
-- Google's offline flow gives the app a one-time code. Redeeming that code needs the
-- OAuth client *secret*, and a secret inside an APK is a secret that has leaked: the
-- APK is a zip file anyone can unzip. So the app sends the code to the Edge Function
-- (`supabase/functions/google-oauth-exchange`), the function redeems it against
-- Google, and the refresh token stays here.
--
-- That is the whole reason the function exists. The access token the device uses is
-- short-lived — about an hour — and the calendar sync runs unattended, so somebody
-- has to be able to renew it without the user present. That somebody is this table.
--
-- ## The security model
--
--   - RLS is enabled and scoped to `auth.uid()`. The client never filters by owner in
--     application code; if these policies are dropped, every signed-in user sees every
--     other user's Google grant — and through it, their calendar.
--   - `authenticated` has no direct table privileges. The Edge Function is the only
--     path in, and it uses the service role, so the policies above are a second line
--     of defence rather than the only one.
--   - The refresh token is stored in the clear in this column. It is a credential the
--     server must be able to use, and Postgres is the project's own database; the
--     column is not exposed to the client by any policy.
--
-- ## Applying
--
--     psql "$DATABASE_URL" -f supabase/migrations/2026-10-24-google_calendar_grants.sql

create table if not exists public.google_calendar_grants (
    -- The app's own user. The grant belongs to this account, and to nobody else.
    user_id uuid primary key references auth.users (id) on delete cascade,

    -- The long-lived credential. Google issues it once, on the offline exchange, and
    -- never again: a second consent while a grant is live silently omits it.
    refresh_token text not null,

    -- The short-lived credential, cached so a call does not have to wait for a round
    -- trip to Google. Null after it expires — the function renews it on the next call.
    access_token text,

    -- When the cached access token stops being valid.
    expires_at timestamptz,

    -- What was granted, so the app can show it and the function can refuse a token
    -- it did not ask for.
    scopes text,

    -- The Google account the grant belongs to, for the screen that says whose
    -- calendar this is.
    google_email text,

    updated_at timestamptz not null default now()
);

comment on table public.google_calendar_grants is
    'One Google Calendar grant per user. The refresh token never leaves the server.';

alter table public.google_calendar_grants enable row level security;

-- The owner may read their own grant. Writes go through the Edge Function, which
-- runs as the service role — the client has no path that changes this table.
create policy "a user reads their own Google grant"
    on public.google_calendar_grants
    for select
    to authenticated
    using (auth.uid() = user_id);

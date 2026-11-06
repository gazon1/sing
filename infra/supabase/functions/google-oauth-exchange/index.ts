// The Google Calendar grant, redeemed on the server.
//
// ## What this is for
//
// The app cannot hold the OAuth client secret, so it cannot redeem the one-time code
// Google's offline flow hands it. It sends the code here instead; this function
// redeems it against Google, keeps the refresh token in `google_calendar_grants`, and
// hands the device an access token.
//
// The refresh token never leaves the server. That is the whole point: the device asks
// for a token when it needs one, and the token it gets works for an hour. The device
// has no credential of its own to lose.
//
// ## The three actions
//
//   - `{"action": "exchange", "code": "<server auth code>"}` — the first time. Redeems
//     the code, stores the refresh token, returns an access token.
//   - `{"action": "token"}` — every time after. Returns a valid access token, renewing
//     it first if the cached one has expired.
//   - `{"action": "revoke"}` — when the person disconnects. Deletes the grant row AND
//     revokes the refresh token at Google, in the same request. A revoke that only
//     forgets the token leaves it alive at Google, and the grant keeps working for
//     whoever holds it.
//
// ## Who may call this
//
// The caller is the signed-in user: the function reads the caller's JWT and binds the
// grant to that id. A request without a session is refused, and a request cannot
// reach another user's grant.
//
// ## The secrets
//
//   - `GOOGLE_CLIENT_ID` — the web client id. Not a secret; it ships inside the app.
//   - `GOOGLE_CLIENT_SECRET` — the client secret. **This is the reason the function
//     exists**: it never reaches the device.
//   - `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY` — set by the platform.
//
// ## Why this file is shaped the way it is
//
// Everything the handler needs from the outside world — the store of grants, the
// resolver of the caller's identity, and the HTTP client — arrives through
// [HandlerDeps]. The tests hand it fakes and drive the real handler; the production
// wiring at the bottom hands it the Supabase client and `fetch`. The reason is the
// server half of the OAuth exchange: it holds a client secret, redeems a one-time
// code and stores a refresh token, and every one of those is a case where "the code
// was already used" and "the redirect URI does not match" and "Google did not answer"
// must be told apart — because the device's only answer to all three would otherwise
// be "the connection failed", and the person would have nothing to act on.
//
// The wiring imports the Supabase client lazily, inside [productionDeps], so that
// importing this file in a test does not fetch the SDK from the network.

export interface Env {
  /** The Supabase project the grants live in. */
  SUPABASE_URL: string;
  /** The service-role key. The grants are read and written with it, never with a user token. */
  SUPABASE_SERVICE_ROLE_KEY: string;
  /** The web client id. Not a secret. */
  GOOGLE_CLIENT_ID: string;
  /** The client secret. Never leaves the server. */
  GOOGLE_CLIENT_SECRET: string;
  /** The redirect URI the app used when it asked for the code. */
  GOOGLE_REDIRECT_URI?: string;
}

/** The grant, as it is stored. */
export interface GrantRow {
  refresh_token: string;
  access_token: string;
  /** ISO-8601 instant. */
  expires_at: string;
  scopes: string;
  google_email: string | null;
}

/** Where the grants live. The seam the tests replace. */
export interface GrantStore {
  /** The caller's grant, or null when there is none. */
  find(userId: string): Promise<GrantRow | null>;
  /** Writes the grant for the caller, replacing what was there. */
  save(userId: string, grant: GrantRow): Promise<void>;
  /** Forgets the grant. The row is gone; a token at Google is revoked separately. */
  remove(userId: string): Promise<void>;
}

/** Who the caller is. The seam the tests replace. */
export interface IdentityResolver {
  /** The caller's id, or null when the token is not a session. */
  userId(token: string): Promise<string | null>;
}

/** The HTTP client. The seam the tests replace. */
export type FetchLike = (
  input: string | URL | Request,
  init?: RequestInit,
) => Promise<Response>;

/** Everything the handler needs from the outside world. */
export interface HandlerDeps {
  env: Env;
  store: GrantStore;
  identity: IdentityResolver;
  fetchImpl: FetchLike;
  /** The clock. The tests move it. */
  now?: () => number;
  /** How long a call to Google may take before the request is abandoned. */
  timeoutMs?: number;
}

const GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
const GOOGLE_REVOKE_URL = "https://oauth2.googleapis.com/revoke";
const GOOGLE_USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo";

const JSON_HEADERS = { "Content-Type": "application/json" };

/** How long a call to Google may take. A phone on a bad connection needs a bound. */
const DEFAULT_TIMEOUT_MS = 10_000;

/** A cached token is treated as expired a minute before it really is. */
const EXPIRY_MARGIN_MS = 60_000;

/**
 * The handler.
 *
 * The failure contract is the point of this file, so it is worth stating: every
 * failure leaves here as a status code and a message that names the cause.
 *
 *   400 — the request is wrong: no code, an unknown action, a redirect URI that
 *         does not match the one Google saw.
 *   401 — there is no session.
 *   404 — there is no grant for this account.
 *   409 — the code was already used. Google issues a code once; a second attempt
 *         with the same code is not an error to retry, and saying so is what lets
 *         the device tell "my code expired" from "my code is spent".
 *   504 — Google did not answer in time.
 *   500 — anything else.
 */
export async function handle(req: Request, deps: HandlerDeps): Promise<Response> {
  const now = deps.now ?? (() => Date.now());
  const timeoutMs = deps.timeoutMs ?? DEFAULT_TIMEOUT_MS;

  try {
    const authHeader = req.headers.get("Authorization") ?? "";
    const token = authHeader.replace(/^Bearer\s+/i, "").trim();
    if (!token) {
      return json({ error: "not signed in" }, 401);
    }

    const userId = await deps.identity.userId(token);
    if (!userId) {
      return json({ error: "not signed in" }, 401);
    }

    const body = await readBody(req);
    const action = body.action;

    if (action === "exchange") {
      return await exchange(deps, userId, body.code, now, timeoutMs);
    }
    if (action === "token") {
      return await accessToken(deps, userId, now, timeoutMs);
    }
    if (action === "revoke") {
      return await revoke(deps, userId, timeoutMs);
    }
    return json({ error: `unknown action: ${action}` }, 400);
  } catch (error) {
    if (error instanceof GoogleTimeout) {
      return json({ error: "Google did not answer in time" }, 504);
    }
    return json({ error: String(error) }, 500);
  }
}

/**
 * Redeems the one-time code and stores the grant.
 *
 * The refresh token is only present on this response: Google issues it once, and a
 * second consent while a grant is live silently omits it. Losing it would leave the
 * account connected for an hour and then permanently disconnected, so a response
 * without one is refused rather than stored.
 */
async function exchange(
  deps: HandlerDeps,
  userId: string,
  code: string | undefined,
  now: () => number,
  timeoutMs: number,
): Promise<Response> {
  if (!code) {
    return json({ error: "no code" }, 400);
  }

  let token: TokenResponse;
  try {
    token = await postToken(
      deps,
      {
        grant_type: "authorization_code",
        code,
        client_id: deps.env.GOOGLE_CLIENT_ID,
        client_secret: deps.env.GOOGLE_CLIENT_SECRET,
        redirect_uri: deps.env.GOOGLE_REDIRECT_URI ?? "",
      },
      timeoutMs,
    );
  } catch (error) {
    return googleFailure(error, "the code could not be redeemed");
  }

  if (token.refresh_token === undefined) {
    return json(
      { error: "Google did not return a refresh token. Ask the user to connect again." },
      400,
    );
  }

  const email = await googleEmail(deps, token.access_token, timeoutMs);
  const expiresAt = new Date(now() + (token.expires_in ?? 3600) * 1000).toISOString();

  await deps.store.save(userId, {
    refresh_token: token.refresh_token,
    access_token: token.access_token,
    expires_at: expiresAt,
    scopes: token.scope ?? "",
    google_email: email,
  });

  return json({
    access_token: token.access_token,
    expires_at_ms: Date.parse(expiresAt),
    email,
  });
}

/**
 * A valid access token, renewing the stored one when it has expired.
 *
 * The renewal happens here because the refresh token is here: the device cannot renew
 * it, and a token that is quietly dead is a sync that stops with no explanation.
 */
async function accessToken(
  deps: HandlerDeps,
  userId: string,
  now: () => number,
  timeoutMs: number,
): Promise<Response> {
  const grant = await deps.store.find(userId);
  if (!grant) {
    return json({ error: "no Google grant for this account" }, 404);
  }

  const expiresAtMs = grant.expires_at ? Date.parse(grant.expires_at) : 0;
  const stillValid = expiresAtMs > now() + EXPIRY_MARGIN_MS;
  if (stillValid && grant.access_token) {
    return json({
      access_token: grant.access_token,
      expires_at_ms: expiresAtMs,
      email: grant.google_email,
    });
  }

  let renewed: TokenResponse;
  try {
    renewed = await postToken(
      deps,
      {
        grant_type: "refresh_token",
        refresh_token: grant.refresh_token,
        client_id: deps.env.GOOGLE_CLIENT_ID,
        client_secret: deps.env.GOOGLE_CLIENT_SECRET,
      },
      timeoutMs,
    );
  } catch (error) {
    return googleFailure(error, "the token could not be renewed");
  }

  const renewedExpiry = new Date(now() + (renewed.expires_in ?? 3600) * 1000).toISOString();
  await deps.store.save(userId, {
    ...grant,
    access_token: renewed.access_token,
    expires_at: renewedExpiry,
  });

  return json({
    access_token: renewed.access_token,
    expires_at_ms: Date.parse(renewedExpiry),
    email: grant.google_email,
  });
}

/**
 * Forgets the grant, and makes the refresh token useless.
 *
 * Both halves, in one request: the row goes, and the token is revoked at Google. A
 * revoke that only deleted the row would leave a live refresh token at Google that
 * the device no longer knows about — and the person who pressed "disconnect" would
 * have a credential of theirs still standing somewhere they cannot see.
 *
 * The order is the one that fails safe: Google's answer first, the row second. A
 * request that fails in the middle must not leave a row that points at a token
 * nobody revoked.
 */
async function revoke(deps: HandlerDeps, userId: string, timeoutMs: number): Promise<Response> {
  const grant = await deps.store.find(userId);
  if (!grant) {
    // Nothing to revoke: the account is already disconnected, and saying so is
    // the answer. The device is not going to be told "there was nothing there".
    return json({ revoked: true, revoked_at_google: false });
  }

  try {
    await revokeAtGoogle(deps, grant.refresh_token, timeoutMs);
  } catch (error) {
    return googleFailure(error, "the token could not be revoked");
  }

  await deps.store.remove(userId);
  return json({ revoked: true, revoked_at_google: true });
}

/** Thrown when Google did not answer within the timeout. */
class GoogleTimeout extends Error {}

/** Thrown when Google could not be reached at all: the network, not Google. */
class GoogleUnreachable extends Error {}

/** Thrown when Google answered, and the answer was no. */
class GoogleRefused extends Error {
  constructor(
    message: string,
    /** Google's own error code: `invalid_grant`, `redirect_uri_mismatch`, … */
    readonly reason: string,
  ) {
    super(message);
  }
}

interface TokenResponse {
  access_token: string;
  refresh_token?: string;
  expires_in?: number;
  scope?: string;
}

/**
 * What Google's refusal means, as a status code.
 *
 * The distinction that matters is between "try again" and "do not": a network
 * failure is worth a retry, a spent code is not, and a redirect URI that does not
 * match is a configuration mistake that no retry will fix.
 */
function googleFailure(error: unknown, what: string): Response {
  if (error instanceof GoogleTimeout) {
    return json({ error: "Google did not answer in time" }, 504);
  }
  if (error instanceof GoogleUnreachable) {
    return json({ error: `${what}: Google could not be reached` }, 502);
  }
  if (error instanceof GoogleRefused) {
    if (error.reason === "invalid_grant") {
      return json({ error: `${what}: the code has already been used`, reason: error.reason }, 409);
    }
    if (error.reason === "redirect_uri_mismatch") {
      return json(
        { error: `${what}: the redirect URI does not match the one Google saw`, reason: error.reason },
        400,
      );
    }
    return json({ error: `${what}: ${error.message}`, reason: error.reason }, 400);
  }
  return json({ error: `${what}: ${String(error)}` }, 500);
}

/** Talks to Google's token endpoint. */
async function postToken(
  deps: HandlerDeps,
  params: Record<string, string>,
  timeoutMs: number,
): Promise<TokenResponse> {
  const response = await fetchWithTimeout(
    deps,
    GOOGLE_TOKEN_URL,
    {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams(params),
    },
    timeoutMs,
  );
  const payload = await response.json();
  if (!response.ok) {
    const reason = payload?.error ?? "invalid_request";
    throw new GoogleRefused(
      payload?.error_description ?? payload?.error ?? "Google refused the exchange",
      reason,
    );
  }
  return payload;
}

/** Asks Google to make a refresh token useless. */
async function revokeAtGoogle(
  deps: HandlerDeps,
  refreshToken: string,
  timeoutMs: number,
): Promise<void> {
  const response = await fetchWithTimeout(
    deps,
    GOOGLE_REVOKE_URL,
    {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ token: refreshToken }),
    },
    timeoutMs,
  );
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}));
    throw new GoogleRefused(
      payload?.error_description ?? payload?.error ?? "Google refused the revocation",
      payload?.error ?? "invalid_request",
    );
  }
}

/**
 * The Google account the grant belongs to.
 *
 * Best effort: the grant is valid without it, and a failure here must not fail the
 * exchange. The account is shown to the user; it is not what the grant is checked
 * against.
 */
async function googleEmail(
  deps: HandlerDeps,
  accessToken: string,
  timeoutMs: number,
): Promise<string | null> {
  try {
    const response = await fetchWithTimeout(
      deps,
      GOOGLE_USERINFO_URL,
      { headers: { Authorization: `Bearer ${accessToken}` } },
      timeoutMs,
    );
    if (!response.ok) return null;
    const payload = await response.json();
    return payload?.email ?? null;
  } catch {
    return null;
  }
}

/**
 * A call to Google with a bound on how long it may take.
 *
 * Without the bound a phone on a lost connection waits for the socket's own
 * timeout, which on some platforms is minutes — and the person is left looking at a
 * spinner that means nothing.
 */
async function fetchWithTimeout(
  deps: HandlerDeps,
  url: string,
  init: RequestInit,
  timeoutMs: number,
): Promise<Response> {
  const signal = AbortSignal.timeout(timeoutMs);
  try {
    return await deps.fetchImpl(url, { ...init, signal });
  } catch (error) {
    if (isTimeout(error, signal)) {
      throw new GoogleTimeout(`Google did not answer within ${timeoutMs} ms`);
    }
    throw new GoogleUnreachable(`Google could not be reached: ${String(error)}`);
  }
}

/** Whether a failure was the timeout, and not the network saying no. */
function isTimeout(error: unknown, signal: AbortSignal): boolean {
  if (signal.aborted) return true;
  if (error instanceof Error && error.name === "TimeoutError") return true;
  if (error instanceof Error && error.name === "AbortError") return true;
  return false;
}

/** Reads the request body as JSON, tolerating an empty one. */
async function readBody(req: Request): Promise<Record<string, any>> {
  try {
    const text = await req.text();
    return text ? JSON.parse(text) : {};
  } catch {
    return {};
  }
}

function json(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), { status, headers: JSON_HEADERS });
}

// ── Production wiring ───────────────────────────────────────────────────────────

/**
 * The real dependencies.
 *
 * The Supabase client is imported here and not at the top of the file: a test that
 * imports this module must not fetch the SDK from the network, and a dynamic
 * import is only fetched when it is reached.
 */
async function productionDeps(): Promise<HandlerDeps> {
  const { createClient } = await import("https://esm.sh/@supabase/supabase-js@2");

  const env: Env = {
    SUPABASE_URL: Deno.env.get("SUPABASE_URL") ?? "",
    SUPABASE_SERVICE_ROLE_KEY: Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "",
    GOOGLE_CLIENT_ID: Deno.env.get("GOOGLE_CLIENT_ID") ?? "",
    GOOGLE_CLIENT_SECRET: Deno.env.get("GOOGLE_CLIENT_SECRET") ?? "",
    GOOGLE_REDIRECT_URI: Deno.env.get("GOOGLE_REDIRECT_URI"),
  };
  if (!env.SUPABASE_URL || !env.SUPABASE_SERVICE_ROLE_KEY) {
    throw new Error("SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY must be set");
  }
  if (!env.GOOGLE_CLIENT_ID || !env.GOOGLE_CLIENT_SECRET) {
    throw new Error("GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET must be set");
  }

  const admin = createClient(env.SUPABASE_URL, env.SUPABASE_SERVICE_ROLE_KEY, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const store: GrantStore = {
    async find(userId: string): Promise<GrantRow | null> {
      const { data, error } = await admin
        .from("google_calendar_grants")
        .select("access_token, refresh_token, expires_at, scopes, google_email")
        .eq("user_id", userId)
        .maybeSingle();
      if (error) throw error;
      return (data as GrantRow | null) ?? null;
    },
    async save(userId: string, grant: GrantRow): Promise<void> {
      const { error } = await admin.from("google_calendar_grants").upsert(
        {
          user_id: userId,
          refresh_token: grant.refresh_token,
          access_token: grant.access_token,
          expires_at: grant.expires_at,
          scopes: grant.scopes,
          google_email: grant.google_email,
          updated_at: new Date().toISOString(),
        },
        { onConflict: "user_id" },
      );
      if (error) throw error;
    },
    async remove(userId: string): Promise<void> {
      const { error } = await admin
        .from("google_calendar_grants")
        .delete()
        .eq("user_id", userId);
      if (error) throw error;
    },
  };

  const identity: IdentityResolver = {
    async userId(token: string): Promise<string | null> {
      const { data, error } = await admin.auth.getUser(token);
      if (error || !data?.user) return null;
      return data.user.id;
    },
  };

  return { env, store, identity, fetchImpl: (input, init) => fetch(input, init) };
}

if (import.meta.main) {
  Deno.serve(async (req) => {
    try {
      const deps = await productionDeps();
      return await handle(req, deps);
    } catch (error) {
      return json({ error: String(error) }, 500);
    }
  });
}

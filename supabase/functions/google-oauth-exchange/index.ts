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
// ## The two actions
//
//   - `{"action": "exchange", "code": "<server auth code>"}` — the first time. Redeems
//     the code, stores the refresh token, returns an access token.
//   - `{"action": "token"}` — every time after. Returns a valid access token, renewing
//     it first if the cached one has expired.
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

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
const GOOGLE_USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo";

const JSON_HEADERS = { "Content-Type": "application/json" };

Deno.serve(async (req) => {
  try {
    const authHeader = req.headers.get("Authorization") ?? "";
    const token = authHeader.replace(/^Bearer\s+/i, "").trim();
    if (!token) {
      return json({ error: "not signed in" }, 401);
    }

    const supabaseUrl = Deno.env.get("SUPABASE_URL");
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    const clientId = Deno.env.get("GOOGLE_CLIENT_ID");
    const clientSecret = Deno.env.get("GOOGLE_CLIENT_SECRET");
    if (!supabaseUrl || !serviceKey || !clientId || !clientSecret) {
      return json({ error: "the server is not configured" }, 500);
    }

    const admin = createClient(supabaseUrl, serviceKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });

    const { data: userData, error: userError } = await admin.auth.getUser(token);
    if (userError || !userData?.user) {
      return json({ error: "not signed in" }, 401);
    }
    const userId = userData.user.id;

    const body = await readBody(req);
    const action = body.action;

    if (action === "exchange") {
      return await exchange(admin, userId, body.code, clientId, clientSecret);
    }
    if (action === "token") {
      return await accessToken(admin, userId, clientId, clientSecret);
    }
    return json({ error: `unknown action: ${action}` }, 400);
  } catch (error) {
    return json({ error: String(error) }, 500);
  }
});

/**
 * Redeems the one-time code and stores the grant.
 *
 * The refresh token is only present on this response: Google issues it once, and a
 * second consent while a grant is live silently omits it. Losing it would leave the
 * account connected for an hour and then permanently disconnected, so a response
 * without one is refused rather than stored.
 */
async function exchange(admin, userId, code, clientId, clientSecret) {
  if (!code) {
    return json({ error: "no code" }, 400);
  }

  const token = await postToken({
    grant_type: "authorization_code",
    code,
    client_id: clientId,
    client_secret: clientSecret,
    redirect_uri: Deno.env.get("GOOGLE_REDIRECT_URI") ?? "",
  });

  if (!token.refresh_token) {
    return json(
      { error: "Google did not return a refresh token. Ask the user to connect again." },
      400,
    );
  }

  const email = await googleEmail(token.access_token);
  const expiresAt = new Date(Date.now() + (token.expires_in ?? 3600) * 1000).toISOString();

  const { error } = await admin.from("google_calendar_grants").upsert(
    {
      user_id: userId,
      refresh_token: token.refresh_token,
      access_token: token.access_token,
      expires_at: expiresAt,
      scopes: token.scope ?? "",
      google_email: email,
      updated_at: new Date().toISOString(),
    },
    { onConflict: "user_id" },
  );
  if (error) {
    return json({ error: "the grant could not be stored" }, 500);
  }

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
async function accessToken(admin, userId, clientId, clientSecret) {
  const { data, error } = await admin
    .from("google_calendar_grants")
    .select("access_token, refresh_token, expires_at, google_email")
    .eq("user_id", userId)
    .maybeSingle();

  if (error || !data) {
    return json({ error: "no Google grant for this account" }, 404);
  }

  const expiresAtMs = data.expires_at ? Date.parse(data.expires_at) : 0;
  const stillValid = expiresAtMs > Date.now() + 60_000;
  if (stillValid && data.access_token) {
    return json({
      access_token: data.access_token,
      expires_at_ms: expiresAtMs,
      email: data.google_email,
    });
  }

  const renewed = await postToken({
    grant_type: "refresh_token",
    refresh_token: data.refresh_token,
    client_id: clientId,
    client_secret: clientSecret,
  });

  const renewedExpiry = new Date(Date.now() + (renewed.expires_in ?? 3600) * 1000).toISOString();
  await admin
    .from("google_calendar_grants")
    .update({
      access_token: renewed.access_token,
      expires_at: renewedExpiry,
      updated_at: new Date().toISOString(),
    })
    .eq("user_id", userId);

  return json({
    access_token: renewed.access_token,
    expires_at_ms: Date.parse(renewedExpiry),
    email: data.google_email,
  });
}

/** Talks to Google's token endpoint. */
async function postToken(params) {
  const response = await fetch(GOOGLE_TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(params),
  });
  const payload = await response.json();
  if (!response.ok) {
    throw new Error(payload?.error_description ?? payload?.error ?? "Google refused the exchange");
  }
  return payload;
}

/**
 * The Google account the grant belongs to.
 *
 * Best effort: the grant is valid without it, and a failure here must not fail the
 * exchange. The account is shown to the user; it is not what the grant is checked
 * against.
 */
async function googleEmail(accessToken) {
  try {
    const response = await fetch(GOOGLE_USERINFO_URL, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    if (!response.ok) return null;
    const payload = await response.json();
    return payload?.email ?? null;
  } catch {
    return null;
  }
}

/** Reads the request body as JSON, tolerating an empty one. */
async function readBody(req) {
  try {
    const text = await req.text();
    return text ? JSON.parse(text) : {};
  } catch {
    return {};
  }
}

function json(payload, status = 200) {
  return new Response(JSON.stringify(payload), { status, headers: JSON_HEADERS });
}

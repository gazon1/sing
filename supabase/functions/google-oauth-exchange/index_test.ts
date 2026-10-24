// The server half of the Google Calendar grant, tested.
//
// The function holds a client secret, redeems a one-time code and stores a refresh
// token. Every one of those has a failure mode the device cannot see from here:
//
//   * the redirect URI does not match the one Google saw — a configuration mistake
//     that no retry will fix;
//   * Google did not answer — a phone on a lost connection;
//   * the same code is redeemed twice — Google issues a code once, and a second
//     attempt is not an error to retry.
//
// Without these tests all three arrived at the device as "the connection failed",
// and the only thing the person could do was try again.
//
// The tests drive the real handler with fakes for the three things it reaches out
// to: the store of grants, the resolver of the caller's identity, and HTTP. No
// network, no secrets, no server.
//
// Run with: deno test supabase/functions/google-oauth-exchange/


/**
 * The two assertions this file needs, written out.
 *
 * `node:assert` is a module with its own types, and the shape of those types
 * differs between runtimes; a test that fails to type-check is a test that does
 * not run. Two functions have no such problem.
 */
function assertEquals(actual: unknown, expected: unknown, message?: string): void {
  const same = Object.is(actual, expected) ||
    (typeof actual === "object" && actual !== null &&
      typeof expected === "object" && expected !== null &&
      JSON.stringify(actual) === JSON.stringify(expected));
  if (!same) {
    throw new Error(
      message ?? `expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`,
    );
  }
}

/**
 * An assertion that narrows.
 *
 * `node:assert`'s own assertion is not an assertion *function* in Deno's type
 * declarations, so the checker does not narrow after it, and every use of the
 * grant below would be a complaint about a value that is there.
 */
function must<T>(value: T | null | undefined, message: string): T {
  if (value === null || value === undefined) {
    throw new Error(message);
  }
  return value;
}

import {
  handle,
  type Env,
  type FetchLike,
  type GrantRow,
  type GrantStore,
  type HandlerDeps,
} from "./index.ts";

const ENV: Env = {
  SUPABASE_URL: "https://project.supabase.co",
  SUPABASE_SERVICE_ROLE_KEY: "service-role-key",
  GOOGLE_CLIENT_ID: "web-client-id",
  GOOGLE_CLIENT_SECRET: "client-secret",
  GOOGLE_REDIRECT_URI: "https://app.example/callback",
};

/** A store that remembers what it was told, and nothing else. */
class FakeStore implements GrantStore {
  rows = new Map<string, GrantRow>();
  removed: string[] = [];

  find(userId: string): Promise<GrantRow | null> {
    return Promise.resolve(this.rows.get(userId) ?? null);
  }

  save(userId: string, grant: GrantRow): Promise<void> {
    this.rows.set(userId, { ...grant });
    return Promise.resolve();
  }

  remove(userId: string): Promise<void> {
    this.rows.delete(userId);
    this.removed.push(userId);
    return Promise.resolve();
  }
}

/** One call to Google: what it was asked, and what it answered. */
interface Call {
  url: string;
  body: string;
}

/** A Google that answers with what the test tells it to. */
function google(
  answers: (url: string, body: string) => Response | Promise<Response>,
): { fetchImpl: FetchLike; calls: Call[] } {
  const calls: Call[] = [];
  const fetchImpl: FetchLike = (input, init) => {
    const url = String(input);
    const body = String(init?.body ?? "");
    calls.push({ url, body });
    return Promise.resolve(answers(url, body));
  };
  return { fetchImpl, calls };
}

function jsonResponse(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function deps(overrides: Partial<HandlerDeps> = {}): HandlerDeps & { store: FakeStore } {
  const store = new FakeStore();
  return {
    env: ENV,
    store,
    identity: { userId: () => Promise.resolve("user-1") },
    fetchImpl: () => Promise.reject(new Error("no Google in this test")),
    now: () => 1_700_000_000_000,
    timeoutMs: 50,
    ...overrides,
  } as HandlerDeps & { store: FakeStore };
}

function request(body: unknown, token = "session-token"): Request {
  return new Request("https://function.supabase.co/google-oauth-exchange", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  });
}

Deno.test("the exchange stores the grant and hands back an access token", async () => {
  const store = new FakeStore();
  const { fetchImpl, calls } = google((url) => {
    if (url.includes("userinfo")) {
      return jsonResponse({ email: "person@example.com" });
    }
    return jsonResponse({
      access_token: "access-1",
      refresh_token: "refresh-1",
      expires_in: 3600,
      scope: "calendar",
    });
  });
  const d = deps({ store, fetchImpl });

  const response = await handle(request({ action: "exchange", code: "the-code" }), d);
  assertEquals(response.status, 200);
  const body = await response.json();
  assertEquals(body.access_token, "access-1");
  assertEquals(body.email, "person@example.com");

  const stored = must(store.rows.get("user-1"), "the grant was not stored");
  assertEquals(stored.refresh_token, "refresh-1");
  assertEquals(calls[0].url, "https://oauth2.googleapis.com/token");
  assertEquals(calls[0].body.includes("redirect_uri=https%3A%2F%2Fapp.example%2Fcallback"), true);
});

Deno.test("a redirect URI that does not match is refused, and nothing is stored", async () => {
  const store = new FakeStore();
  const { fetchImpl } = google(() =>
    jsonResponse(
      {
        error: "redirect_uri_mismatch",
        error_description: "The redirect_uri does not match the one used in the request.",
      },
      400,
    ),
  );
  const d = deps({ store, fetchImpl });

  const response = await handle(request({ action: "exchange", code: "the-code" }), d);
  assertEquals(response.status, 400);
  const body = await response.json();
  assertEquals(body.reason, "redirect_uri_mismatch");
  assertEquals(
    body.error.includes("redirect URI does not match"),
    true,
    `the message does not name the cause: ${body.error}`,
  );
  assertEquals(store.rows.size, 0, "a refused exchange left a grant behind");
});

Deno.test("the same code redeemed twice is answered as spent, and the grant stands", async () => {
  const store = new FakeStore();
  let calls = 0;
  const { fetchImpl } = google((url) => {
    if (url.includes("userinfo")) return jsonResponse({ email: "person@example.com" });
    calls += 1;
    if (calls === 1) {
      return jsonResponse({ access_token: "access-1", refresh_token: "refresh-1", expires_in: 3600 });
    }
    // Google: a code is issued once, and the second attempt is invalid_grant.
    return jsonResponse(
      { error: "invalid_grant", error_description: "Bad Request" },
      400,
    );
  });
  const d = deps({ store, fetchImpl });

  const first = await handle(request({ action: "exchange", code: "the-code" }), d);
  assertEquals(first.status, 200);

  const second = await handle(request({ action: "exchange", code: "the-code" }), d);
  assertEquals(second.status, 409);
  const body = await second.json();
  assertEquals(body.reason, "invalid_grant");
  assertEquals(
    body.error.includes("already been used"),
    true,
    `the message does not say the code was spent: ${body.error}`,
  );

  const stored = must(store.rows.get("user-1"), "the grant was lost by the second attempt");
  assertEquals(stored.refresh_token, "refresh-1");
  assertEquals(stored.access_token, "access-1");
});

Deno.test("Google that does not answer in time is a timeout, not a failure", async () => {
  const store = new FakeStore();
  const fetchImpl: FetchLike = (_input, init) =>
    new Promise((_resolve, reject) => {
      const signal = init?.signal as AbortSignal | undefined;
      signal?.addEventListener("abort", () => {
        const error = new Error("aborted");
        error.name = "TimeoutError";
        reject(error);
      });
    });
  const d = deps({ store, fetchImpl, timeoutMs: 20 });

  const response = await handle(request({ action: "exchange", code: "the-code" }), d);
  assertEquals(response.status, 504);
  assertEquals(store.rows.size, 0, "a timed-out exchange left a grant behind");
});

Deno.test("Google that cannot be reached is a different answer from one that says no", async () => {
  const store = new FakeStore();
  const fetchImpl: FetchLike = () => Promise.reject(new TypeError("connection refused"));
  const d = deps({ store, fetchImpl });

  const response = await handle(request({ action: "exchange", code: "the-code" }), d);
  assertEquals(response.status, 502);
  assertEquals(store.rows.size, 0);
});

Deno.test("a response without a refresh token is refused rather than stored", async () => {
  const store = new FakeStore();
  const { fetchImpl } = google((url) => {
    if (url.includes("userinfo")) return jsonResponse({ email: "person@example.com" });
    return jsonResponse({ access_token: "access-1", expires_in: 3600 });
  });
  const d = deps({ store, fetchImpl });

  const response = await handle(request({ action: "exchange", code: "the-code" }), d);
  assertEquals(response.status, 400);
  assertEquals(store.rows.size, 0);
});

Deno.test("a cached token is handed back while it is still good", async () => {
  const store = new FakeStore();
  store.rows.set("user-1", {
    refresh_token: "refresh-1",
    access_token: "access-1",
    expires_at: new Date(1_700_000_000_000 + 3_600_000).toISOString(),
    scopes: "calendar",
    google_email: "person@example.com",
  });
  const { fetchImpl, calls } = google(() => jsonResponse({}));
  const d = deps({ store, fetchImpl });

  const response = await handle(request({ action: "token" }), d);
  assertEquals(response.status, 200);
  const body = await response.json();
  assertEquals(body.access_token, "access-1");
  assertEquals(calls.length, 0, "a good token should not cost a call to Google");
});

Deno.test("an expired token is renewed before it is handed back", async () => {
  const store = new FakeStore();
  store.rows.set("user-1", {
    refresh_token: "refresh-1",
    access_token: "access-old",
    expires_at: new Date(1_700_000_000_000 - 1000).toISOString(),
    scopes: "calendar",
    google_email: "person@example.com",
  });
  const { fetchImpl, calls } = google(() => jsonResponse({ access_token: "access-new", expires_in: 3600 }));
  const d = deps({ store, fetchImpl });

  const response = await handle(request({ action: "token" }), d);
  assertEquals(response.status, 200);
  const body = await response.json();
  assertEquals(body.access_token, "access-new");
  assertEquals(calls.length, 1);
  assertEquals(calls[0].body.includes("grant_type=refresh_token"), true);
  assertEquals(store.rows.get("user-1")?.access_token, "access-new");
});

Deno.test("a request without a session is refused", async () => {
  const d = deps();
  const response = await handle(request({ action: "token" }, ""), d);
  assertEquals(response.status, 401);
});

Deno.test("a session the server does not know is refused", async () => {
  const d = deps({ identity: { userId: () => Promise.resolve(null) } });
  const response = await handle(request({ action: "token" }), d);
  assertEquals(response.status, 401);
});

Deno.test("an unknown action is refused", async () => {
  const d = deps();
  const response = await handle(request({ action: "wat" }), d);
  assertEquals(response.status, 400);
});

// ── The disconnect ───────────────────────────────────────────────────────────────

Deno.test("the revoke deletes the grant and revokes the token at Google", async () => {
  const store = new FakeStore();
  store.rows.set("user-1", {
    refresh_token: "refresh-1",
    access_token: "access-1",
    expires_at: new Date(1_700_000_000_000 + 3_600_000).toISOString(),
    scopes: "calendar",
    google_email: "person@example.com",
  });
  const { fetchImpl, calls } = google(() => new Response("", { status: 200 }));
  const d = deps({ store, fetchImpl });

  const response = await handle(request({ action: "revoke" }), d);
  assertEquals(response.status, 200);
  const body = await response.json();
  assertEquals(body.revoked, true);
  assertEquals(body.revoked_at_google, true);

  assertEquals(calls.length, 1, "the token was not revoked at Google");
  assertEquals(calls[0].url, "https://oauth2.googleapis.com/revoke");
  assertEquals(calls[0].body.includes("token=refresh-1"), true);

  assertEquals(store.rows.size, 0, "the grant row survived the disconnect");
  assertEquals(store.removed, ["user-1"]);
});

Deno.test("a revoke that Google refuses keeps the row, so the token is not orphaned", async () => {
  const store = new FakeStore();
  store.rows.set("user-1", {
    refresh_token: "refresh-1",
    access_token: "access-1",
    expires_at: new Date(1_700_000_000_000 + 3_600_000).toISOString(),
    scopes: "calendar",
    google_email: "person@example.com",
  });
  const { fetchImpl } = google(() => jsonResponse({ error: "invalid_token" }, 400));
  const d = deps({ store, fetchImpl });

  const response = await handle(request({ action: "revoke" }), d);
  assertEquals(response.status, 400);
  assertEquals(
    store.rows.has("user-1"),
    true,
    "the row was deleted although Google did not revoke the token",
  );
});

Deno.test("a revoke with nothing to revoke says so instead of failing", async () => {
  const store = new FakeStore();
  const { fetchImpl, calls } = google(() => new Response("", { status: 200 }));
  const d = deps({ store, fetchImpl });

  const response = await handle(request({ action: "revoke" }), d);
  assertEquals(response.status, 200);
  const body = await response.json();
  assertEquals(body.revoked, true);
  assertEquals(body.revoked_at_google, false);
  assertEquals(calls.length, 0);
});

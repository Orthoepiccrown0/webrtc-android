import { createRemoteJWKSet, importPKCS8, jwtVerify, SignJWT } from "jose";
import { ApiError } from "./coordinator.ts";
import { CoordinationStore } from "./store.ts";
import { budgetDatabase, QueryBudgetExceeded } from "./query-budget.ts";
import type { Scope, Job } from "./store.ts";
import type { State, Session, PairingRequest } from "./coordinator.ts";
export interface Env {
  COORDINATION_DB: D1Database;
  D1_QUERY_BUDGET?: string;
  MAINTENANCE_JOB_LIMIT?: string;
  MAINTENANCE_MUTATION_LIMIT?: string;
  MAINTENANCE_MODE?: string;
  FIREBASE_PROJECT_ID: string;
  FIREBASE_DATABASE_URL: string;
  GOOGLE_SERVICE_ACCOUNT_JSON: string;
  TURN_ENABLED: string;
  TURN_KEY_ID: string;
  TURN_API_TOKEN?: string;
  UID_LIMITER: RateLimit;
  IP_LIMITER: RateLimit;
}
const keys = createRemoteJWKSet(
  new URL(
    "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com",
  ),
);
export async function authenticate(request: Request, env: Env): Promise<string> {
  const token = request.headers.get("Authorization")?.match(/^Bearer (.+)$/)?.[1];
  if (!token) throw new ApiError(401, "Authentication required.");
  try {
    const { payload } = await jwtVerify(token, keys, {
      algorithms: ["RS256"],
      audience: env.FIREBASE_PROJECT_ID,
      issuer: `https://securetoken.google.com/${env.FIREBASE_PROJECT_ID}`,
      requiredClaims: ["sub", "iat", "exp", "auth_time"],
    });
    if (
      !payload.sub ||
      payload.sub.length > 128 ||
      ["__proto__", "constructor", "prototype"].includes(payload.sub) ||
      /[.#$\[\]\/]/.test(payload.sub)
    )
      throw new Error("Invalid UID");
    if (
      typeof payload.iat !== "number" ||
      payload.iat > Date.now() / 1000 + 30 ||
      typeof payload.auth_time !== "number" ||
      payload.auth_time > Date.now() / 1000 + 30
    )
      throw new Error("Invalid auth time");
    return payload.sub;
  } catch {
    throw new ApiError(401, "Invalid or expired authentication.");
  }
}
let accessToken: { token: string; expiresAt: number; account: string } | null = null;
async function googleToken(env: Env): Promise<string> {
  if (
    accessToken?.account === env.FIREBASE_PROJECT_ID &&
    accessToken.expiresAt > Date.now() + 60_000
  )
    return accessToken.token;
  const account = JSON.parse(env.GOOGLE_SERVICE_ACCOUNT_JSON) as {
    client_email: string;
    private_key: string;
    project_id: string;
  };
  if (account.project_id !== env.FIREBASE_PROJECT_ID)
    throw new Error("Service account project mismatch");
  const key = await importPKCS8(account.private_key, "RS256");
  const assertion = await new SignJWT({
    scope:
      "https://www.googleapis.com/auth/firebase.database https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/firebase.messaging",
  })
    .setProtectedHeader({ alg: "RS256", typ: "JWT" })
    .setIssuer(account.client_email)
    .setAudience("https://oauth2.googleapis.com/token")
    .setIssuedAt()
    .setExpirationTime("1h")
    .sign(key);
  const response = await fetch("https://oauth2.googleapis.com/token", {
    signal: AbortSignal.timeout(5_000),
    method: "POST",
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!response.ok) throw new Error("Google authorization failed");
  const value = (await response.json()) as { access_token: string; expires_in: number };
  accessToken = {
    token: value.access_token,
    expiresAt: Date.now() + value.expires_in * 1000,
    account: env.FIREBASE_PROJECT_ID,
  };
  return value.access_token;
}
export async function database(env: Env, path: string, init: RequestInit = {}): Promise<Response> {
  const url = new URL(`${env.FIREBASE_DATABASE_URL.replace(/\/$/, "")}/${path}.json`);
  if (url.protocol !== "https:" || !/\.(firebasedatabase\.app|firebaseio\.com)$/.test(url.hostname))
    throw new Error("Invalid database URL");
  return fetch(url, {
    ...init,
    signal: AbortSignal.timeout(5_000),
    headers: {
      Authorization: `Bearer ${await googleToken(env)}`,
      "Content-Type": "application/json",
      ...init.headers,
    },
  });
}
/** Never updates Firebase's global control node. Durable jobs commit with authoritative records. */
export async function transact<T>(
  env: Env,
  scope: Scope,
  operation: (state: State, now: number) => T,
): Promise<T> {
  const store = new CoordinationStore(env.COORDINATION_DB);
  const mutation = await store.transact(scope, operation);
  // Authorization/revocation must be mirrored before acknowledging a successful session operation.
  for (const job of mutation.jobs.filter((j) => j.kind === "session")) await deliver(env, job);
  for (const job of mutation.jobs.filter((j) => j.kind !== "session")) {
    try {
      await deliver(env, job);
    } catch (error) {
      if (error instanceof QueryBudgetExceeded) break;
      await store.retry(job);
      console.warn("Coordination event delivery deferred", job.kind);
    }
  }
  return mutation.value;
}

/** Per-path CAS prevents a delayed delivery from restoring older state. */
export async function publishVersioned(
  env: Env,
  path: string,
  value: Record<string, unknown>,
  revision: number,
) {
  for (let retry = 0; retry < 5; retry++) {
    const response = await database(env, path, { headers: { "X-Firebase-ETag": "true" } });
    if (!response.ok) throw new Error("Projection read unavailable");
    const old = (await response.json()) as { _revision?: number } | null;
    if ((old?._revision ?? -1) >= revision) return;
    const etag = response.headers.get("ETag");
    if (!etag) throw new Error("Projection version unavailable");
    const written = await database(env, path, {
      method: "PUT",
      headers: { "If-Match": etag },
      body: JSON.stringify({ ...value, _revision: revision }),
    });
    if (written.ok) return;
    if (written.status !== 412) throw new Error("Projection delivery unavailable");
  }
  throw new Error("Projection delivery is busy");
}

export async function materializeRoom(env: Env, session: Session) {
  // Ordinary status reads do not rewrite or acknowledge outbox records.
  // A failed approval still repairs its outstanding publication before returning success.
  const job = await new CoordinationStore(env.COORDINATION_DB).pendingJob(
    "session",
    session.roomId,
  );
  if (job) await deliver(env, job);
}

/** Retried logout cannot acknowledge revocation while known authorization work remains. */
export async function flushAuthorizations(env: Env, uid: string) {
  const store = new CoordinationStore(env.COORDINATION_DB);
  const jobs = await store.authorizationJobs(uid);
  for (const job of jobs) await deliver(env, job);
  if ((await store.authorizationJobs(uid)).length)
    throw new ApiError(503, "Revocation cleanup remains pending. Retry shortly.");
}

export async function deliver(env: Env, job: Job) {
  const store = new CoordinationStore(env.COORDINATION_DB);
  if (job.kind === "participant") {
    const projection = await store.participant(job.id);
    await publishVersioned(
      env,
      `control/events/${job.id}`,
      projection.value as unknown as Record<string, unknown>,
      projection.version,
    );
    await store.complete({ ...job, revision: projection.version });
  } else if (job.kind === "session") {
    const row = await store.row("sessions", job.id);
    if (!row?.data) {
      if (row) {
        await publishVersioned(
          env,
          `control/sessions/${job.id}`,
          { status: "ended", expiresAt: 0 },
          row.version,
        );
        const removed = await database(env, `rooms/${job.id}`, { method: "DELETE" });
        if (!removed.ok) throw new Error("Room cleanup unavailable");
      }
      await store.complete({ ...job, revision: row?.version ?? job.revision });
      return;
    }
    const session = JSON.parse(row.data) as Session;
    await publishVersioned(
      env,
      `control/sessions/${job.id}`,
      session as unknown as Record<string, unknown>,
      row.version,
    );
    await publishVersioned(
      env,
      `rooms/${job.id}/metadata`,
      {
        hostUid: session.hostUid,
        guestUid: session.guestUid,
        mode: session.mode ?? "Video",
        createdAt: session.createdAt,
        expiresAt: session.expiresAt,
        status: session.status === "active" ? "negotiating" : "ended",
      },
      row.version,
    );
    if (session.status === "ended") {
      const removed = await database(env, `rooms/${job.id}`, { method: "DELETE" });
      if (!removed.ok) throw new Error("Room cleanup unavailable");
    }
    await store.complete({ ...job, revision: row.version });
  } else {
    const row = await store.row("requests", job.id);
    if (!row?.data) {
      await store.complete(job);
      return;
    }
    const request = JSON.parse(row.data) as PairingRequest;
    const device = await store.row("devices", request.senderUid);
    const token = device?.data ? (JSON.parse(device.data) as { token?: string }).token : undefined;
    if (token) await notify(env, token, request);
    await store.complete({ ...job, revision: row.version });
  }
}

export function incomingCallMessage(token: string, request: PairingRequest, now = Date.now()) {
  const ttl = Math.max(0, Math.floor((request.expiresAt - now) / 1000));
  if (!ttl || request.status !== "pending") return null;
  return {
    message: {
      token,
      data: {
        requestId: request.requestId,
        expiresAt: String(request.expiresAt),
        type: "directstream.incoming",
        protocolVersion: "1",
        mode: request.mode ?? "Video",
      },
      android: { priority: "HIGH", ttl: `${ttl}s` },
    },
  };
}
export async function notify(env: Env, token: string, request: PairingRequest): Promise<void> {
  try {
    const message =
      request.status === "pending"
        ? incomingCallMessage(token, request)
        : {
            message: {
              token,
              data: {
                requestId: request.requestId,
                type: "directstream.incoming",
                protocolVersion: "1",
              },
              android: { priority: "HIGH", ttl: "60s" },
            },
          };
    if (!message) return;
    const response = await fetch(
      `https://fcm.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/messages:send`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${await googleToken(env)}`,
          "Content-Type": "application/json",
        },
        signal: AbortSignal.timeout(5_000),
        // Data-only delivery invokes Android's handler while the app is backgrounded.
        body: JSON.stringify(message),
      },
    );
    if (!response.ok) {
      const body = (await response.json()) as { error?: { details?: { errorCode?: string }[] } };
      if (body.error?.details?.some((d) => d.errorCode === "UNREGISTERED")) {
        await transact(env, { uid: request.senderUid }, (state) => {
          if (state.devices[request.senderUid]?.token === token)
            delete state.devices[request.senderUid].token;
        });
        return;
      }
      throw new Error("FCM delivery unavailable");
    }
  } catch {
    throw new Error("FCM delivery unavailable");
  }
}
export async function turnCredentials(env: Env, session: Session) {
  if (env.TURN_ENABLED !== "true")
    return {
      iceServers: [{ urls: ["stun:stun.cloudflare.com:3478"] }],
      expiresAt: session.expiresAt,
      relayEnabled: false,
    };
  if (!env.TURN_KEY_ID || !env.TURN_API_TOKEN) throw new ApiError(503, "Relay is not configured.");
  const ttl = Math.min(1200, Math.max(60, Math.ceil((session.expiresAt - Date.now()) / 1000)));
  const response = await fetch(
    `https://rtc.live.cloudflare.com/v1/turn/keys/${encodeURIComponent(env.TURN_KEY_ID)}/credentials/generate-ice-servers`,
    {
      method: "POST",
      signal: AbortSignal.timeout(5_000),
      headers: {
        Authorization: `Bearer ${env.TURN_API_TOKEN}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ ttl }),
    },
  );
  if (!response.ok) throw new ApiError(503, "Relay credentials unavailable.");
  const data = (await response.json()) as {
    iceServers?: { urls: string | string[]; username?: string; credential?: string }[];
  };
  if (!Array.isArray(data.iceServers)) throw new ApiError(503, "Invalid relay response.");
  const iceServers = data.iceServers.map((server) => ({
    urls: (Array.isArray(server.urls) ? server.urls : [server.urls]).filter((url) =>
      /^(stun:|turn:|turns:)/.test(url),
    ),
    username: server.username ?? "",
    credential: server.credential ?? "",
  }));
  return {
    iceServers,
    expiresAt: Math.min(session.expiresAt, Date.now() + ttl * 1000),
    relayEnabled: true,
  };
}

/** Bounded indexed expiry and job retries; this is server maintenance, not client polling. */
export async function cleanup(configuration: Env): Promise<void> {
  if (configuration.MAINTENANCE_MODE === "true" || !configuration.COORDINATION_DB) return;
  const budget = budgetDatabase(configuration.COORDINATION_DB, configuration.D1_QUERY_BUDGET);
  const env = { ...configuration, COORDINATION_DB: budget.db };
  const store = new CoordinationStore(env.COORDINATION_DB);
  const bounded = (value: string | undefined, fallback: number) => {
    const parsed = Number(value);
    return Number.isInteger(parsed) && parsed >= 1 ? Math.min(parsed, 100) : fallback;
  };
  try {
    if (!(await store.ready())) return;
    // Deliver retained authorization/notification work before maintenance creates additional jobs.
    for (const job of await store.due(bounded(env.MAINTENANCE_JOB_LIMIT, 2))) {
      try {
        await deliver(env, job);
      } catch (error) {
        if (error instanceof QueryBudgetExceeded) throw error;
        await store.retry(job);
        console.warn("Coordination delivery deferred", job.kind);
      }
    }
    for (const scope of await store.expired(bounded(env.MAINTENANCE_MUTATION_LIMIT, 1))) {
      try {
        await store.transact(scope, () => null);
      } catch (error) {
        if (error instanceof QueryBudgetExceeded) throw error;
        console.warn("Expiry deferred");
      }
    }
    for (const scope of await store.prune(bounded(env.MAINTENANCE_MUTATION_LIMIT, 1))) {
      try {
        await store.transact(scope, (state) => {
          if (scope.requestId) delete state.requests[scope.requestId];
          if (scope.roomId) delete state.sessions[scope.roomId];
        });
      } catch (error) {
        if (error instanceof QueryBudgetExceeded) throw error;
        console.warn("Retention cleanup deferred");
      }
    }
    for (const item of (await store.tombstones(1)).slice(0, 1)) {
      if (item.table === "sessions") {
        const removed = await database(env, `control/sessions/${item.id}`, { method: "DELETE" });
        if (!removed.ok) continue;
      }
      await store.db
        .prepare(`DELETE FROM ${item.table} WHERE id=? AND data IS NULL`)
        .bind(item.id)
        .run();
    }
  } catch (error) {
    if (!(error instanceof QueryBudgetExceeded)) throw error;
    console.warn("Coordination maintenance budget reached; remaining work retained");
  }
}

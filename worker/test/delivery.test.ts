import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPair, exportPKCS8 } from "jose";
import { publishVersioned, transact, deliver, materializeRoom } from "../src/services.ts";
import type { Env } from "../src/services.ts";
import { register, requestConnection, approve, endSession, revoke } from "../src/coordinator.ts";
import { CoordinationStore } from "../src/store.ts";
import { sqliteDatabase } from "./sqlite.ts";

async function fixture() {
  const f = sqliteDatabase();
  const { privateKey } = await generateKeyPair("RS256", { extractable: true });
  const env = {
    COORDINATION_DB: f.db,
    FIREBASE_PROJECT_ID: "demo-delivery",
    FIREBASE_DATABASE_URL: "https://demo-delivery.firebaseio.com",
    GOOGLE_SERVICE_ACCOUNT_JSON: JSON.stringify({
      project_id: "demo-delivery",
      client_email: "test@example.com",
      private_key: await exportPKCS8(privateKey),
    }),
    TURN_ENABLED: "false",
  } as Env;
  const values = new Map<string, any>();
  let etag = 0;
  let failing = false;
  const original = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const url = new URL(String(input));
    if (url.hostname === "oauth2.googleapis.com")
      return Response.json({ access_token: "test-token", expires_in: 3600 });
    if (failing) return new Response(null, { status: 503 });
    assert.notEqual(url.pathname, "/control.json", "no global coordination traffic");
    if (init?.method === "DELETE") {
      for (const key of values.keys())
        if (key === url.pathname || key.startsWith(url.pathname.replace(/\.json$/, "/")))
          values.delete(key);
    }
    if (init?.method === "PUT") {
      if ((init.headers as Record<string, string>)["If-Match"] !== `"${etag}"`)
        return new Response(null, { status: 412 });
      values.set(url.pathname, JSON.parse(init.body as string));
      etag++;
    }
    return Response.json(values.get(url.pathname) ?? null, { headers: { ETag: `"${etag}"` } });
  };
  return {
    ...f,
    env,
    values,
    store: new CoordinationStore(f.db),
    fail(value: boolean) {
      failing = value;
    },
    restore() {
      globalThis.fetch = original;
    },
  };
}

test("projection revisions reject delayed writes and participant views exclude private identity/token records", async () => {
  const f = await fixture();
  try {
    await publishVersioned(f.env, "control/events/test", { incoming: null }, 2);
    await publishVersioned(f.env, "control/events/test", { incoming: { status: "pending" } }, 1);
    assert.equal(f.values.get("/control/events/test.json").incoming, null);
    await transact(f.env, { uid: "target" }, (s) => {
      const d = register(s, "target", () => "ABC234");
      d.token = "private-token";
      return d;
    });
    const projection = f.values.get("/control/events/target.json");
    assert.equal(JSON.stringify(projection).includes("private-token"), false);
    assert.equal(projection.incoming, null);
  } finally {
    f.restore();
  }
});

test("failed approval projection remains retryable and a stale delivery cannot reauthorize an ended session", async () => {
  const f = await fixture();
  try {
    await transact(f.env, { uid: "target" }, (s) => register(s, "target", () => "ABC234"));
    await transact(
      f.env,
      { uid: "caller", code: "ABC234", requestId: "delivery-request" },
      (s, n) => requestConnection(s, "caller", "ABC234", "delivery-request", n),
    );
    f.fail(true);
    await assert.rejects(() =>
      transact(f.env, { uid: "target", requestId: "delivery-request" }, (s, n) =>
        approve(s, "target", "delivery-request", n),
      ),
    );
    const sessionJob = (await f.store.due()).find((j) => j.kind === "session")!;
    assert.ok(sessionJob);
    f.fail(false);
    const room = (
      await f.store.transact({ uid: "target", requestId: "delivery-request" }, (s, n) =>
        approve(s, "target", "delivery-request", n),
      )
    ).value;
    await materializeRoom(f.env, room);
    assert.equal(f.values.get(`/control/sessions/${room.roomId}.json`).status, "active");
    await transact(f.env, { uid: "caller", roomId: room.roomId }, (s) =>
      endSession(s, "caller", room.roomId),
    );
    await deliver(f.env, sessionJob);
    assert.equal(f.values.get(`/control/sessions/${room.roomId}.json`).status, "ended");
    assert.equal(f.values.has(`/rooms/${room.roomId}/metadata.json`), false);
    assert.equal(
      f.sqlite.prepare("SELECT count(*) AS n FROM sessions WHERE data IS NOT NULL").get()!.n,
      1,
    );
  } finally {
    f.restore();
  }
});

test("maintenance retries durable jobs after a publication outage", async () => {
  const f = await fixture();
  try {
    f.fail(true);
    await transact(f.env, { uid: "target" }, (s) => register(s, "target", () => "ABC234"));
    assert.ok(f.sqlite.prepare("SELECT count(*) AS n FROM outbox").get()!.n > 0);
    f.fail(false);
    f.sqlite.exec("UPDATE outbox SET available=0");
    const before = f.metrics.statements;
    const { cleanup } = await import("../src/services.ts");
    await cleanup(f.env);
    assert.ok(
      f.metrics.statements - before <= 50,
      "maintenance respects a Free invocation query budget",
    );
    assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM outbox").get()!.n, 0);
    assert.equal(f.values.get("/control/events/target.json").incoming, null);
  } finally {
    f.restore();
  }
});

test("retried logout remains pending until outstanding Firebase authorization is revoked", async () => {
  const f = await fixture();
  try {
    const { flushAuthorizations } = await import("../src/services.ts");
    await transact(f.env, { uid: "target" }, (s) => register(s, "target", () => "ABC234"));
    await transact(f.env, { uid: "caller", code: "ABC234", requestId: "logout-request" }, (s, n) =>
      requestConnection(s, "caller", "ABC234", "logout-request", n),
    );
    const room = await transact(f.env, { uid: "target", requestId: "logout-request" }, (s, n) =>
      approve(s, "target", "logout-request", n),
    );
    f.fail(true);
    await assert.rejects(() => transact(f.env, { uid: "target" }, (s) => revoke(s, "target")));
    assert.equal((await f.store.row("devices", "target"))!.data, null);
    // The next domain revoke is idempotent, but the transport must still finish known cleanup.
    await transact(f.env, { uid: "target" }, (s) => revoke(s, "target"));
    await assert.rejects(() => flushAuthorizations(f.env, "target"));
    f.fail(false);
    await flushAuthorizations(f.env, "target");
    assert.equal(f.values.get(`/control/sessions/${room.roomId}.json`).status, "ended");
    assert.equal((await f.store.authorizationJobs("target")).length, 0);
  } finally {
    f.restore();
  }
});

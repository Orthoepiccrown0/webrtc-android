import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPair, exportJWK, exportPKCS8, SignJWT } from "jose";
import worker from "../src/index.ts";
import type { Env } from "../src/services.ts";
import { sqliteDatabase } from "./sqlite.ts";

test("first-release API preserves codes, names, audio/video admission and participant privacy", async () => {
  const pair = await generateKeyPair("RS256", { extractable: true });
  const jwk = {
    ...(await exportJWK(pair.publicKey)),
    kid: "compatibility-test",
    alg: "RS256",
    use: "sig",
  };
  const originalFetch = globalThis.fetch;
  const storage = sqliteDatabase();
  const mirror = new Map<string, unknown>();
  let etag = 0;
  const metadata = new Map<string, unknown>();
  const env = {
    COORDINATION_DB: storage.db,
    FIREBASE_PROJECT_ID: "demo-names",
    FIREBASE_DATABASE_URL: "https://demo-names.firebaseio.com",
    GOOGLE_SERVICE_ACCOUNT_JSON: JSON.stringify({
      project_id: "demo-names",
      client_email: "test@example.com",
      private_key: await exportPKCS8(pair.privateKey),
    }),
    TURN_ENABLED: "false",
    TURN_KEY_ID: "",
    IP_LIMITER: { limit: async () => ({ success: true }) },
    UID_LIMITER: { limit: async () => ({ success: true }) },
  } as unknown as Env;
  globalThis.fetch = async (input, options) => {
    const url = String(input);
    if (url.includes("/jwk/")) return Response.json({ keys: [jwk] });
    if (url === "https://oauth2.googleapis.com/token")
      return Response.json({ access_token: "fake-access-token", expires_in: 3600 });
    const path = new URL(url).pathname;
    if (path === "/control.json") throw new Error("Global coordination access is forbidden");
    if (path.startsWith("/control/") || path.startsWith("/rooms/")) {
      if (options?.method === "DELETE") {
        mirror.delete(path);
        return Response.json(null);
      }
      if (options?.method === "PUT") {
        const value = JSON.parse(options.body as string);
        mirror.set(path, value);
        etag++;
        if (path.startsWith("/rooms/")) metadata.set(path, value);
      }
      return Response.json(mirror.get(path) ?? null, { headers: { ETag: `"${etag}"` } });
    }
    throw new Error("Unexpected test network call");
  };
  async function token(uid: string) {
    return new SignJWT({ auth_time: Math.floor(Date.now() / 1000) })
      .setProtectedHeader({ alg: "RS256", kid: jwk.kid })
      .setIssuer("https://securetoken.google.com/demo-names")
      .setAudience("demo-names")
      .setSubject(uid)
      .setIssuedAt()
      .setExpirationTime("5m")
      .sign(pair.privateKey);
  }
  async function post(uid: string, path: string, body: unknown = {}) {
    const queriesBefore = storage.metrics.statements;
    const response = await worker.fetch(
      new Request(`https://example.workers.dev/v1/${path}`, {
        method: "POST",
        headers: { Authorization: `Bearer ${await token(uid)}` },
        body: JSON.stringify(body),
      }),
      env,
      { waitUntil() {} } as unknown as ExecutionContext,
    );
    assert.ok(
      storage.metrics.statements - queriesBefore <= 50,
      `Free-tier D1 query budget exceeded for ${path}: ${storage.metrics.statements - queriesBefore}`,
    );
    return { status: response.status, body: (await response.json()) as Record<string, any> };
  }
  try {
    const registration = await post("target", "device/register");
    assert.equal(registration.status, 200);
    assert.equal(registration.body.eventProtocolVersion, 1);
    const named = await post("target", "device/register", { participantName: " Owl " });
    assert.equal(named.body.code, registration.body.code);
    assert.equal(named.body.participantName, "Owl");
    assert.equal(named.body.codecProtocolVersion, 1);
    assert.deepEqual(named.body.supportedCallModes, ["Audio", "Video"]);
    await post("target", "device/register"); // Omitted optional metadata does not erase the name.
    const caller = await post("caller", "device/register", { participantName: "Fox" });
    assert.equal(caller.status, 200);
    const requestId = "http-compat-request-12345";
    const created = await post("caller", "requests/create", {
      code: named.body.code,
      requestId,
    });
    assert.equal(created.status, 200);
    assert.equal(created.body.peerName, "Owl");
    assert.equal((await post("target", "requests/incoming")).body.request.peerName, "Fox");
    assert.equal((await post("outsider", "requests/status", { requestId })).status, 404);
    assert.equal(
      (await post("target", "device/register", { participantName: "New Owl" })).status,
      200,
    );
    assert.equal((await post("caller", "requests/status", { requestId })).body.peerName, "Owl");
    const approved = await post("target", "requests/approve", { requestId });
    assert.equal(approved.status, 200);
    assert.equal(metadata.size, 1);
    const writes = storage.metrics.writes;
    assert.equal((await post("caller", "requests/status", { requestId })).body.status, "approved");
    assert.equal(storage.metrics.writes, writes, "ordinary approved status must be read-only");
    assert.equal(
      (await post("caller", "sessions/end", { roomId: approved.body.roomId })).status,
      200,
    );
    assert.equal((await post("target", "device/register")).body.code, registration.body.code);
    assert.equal(
      (await post("caller", "device/register", { participantName: "bad\nname" })).status,
      400,
    );
    const audioId = "audio-request-for-first-release";
    const audio = await post("caller", "requests/create", {
      code: named.body.code,
      requestId: audioId,
      mode: "Audio",
    });
    assert.equal(audio.status, 200);
    assert.equal(audio.body.mode, "Audio");
    const audioRoom = await post("target", "requests/approve", { requestId: audioId });
    assert.equal(audioRoom.status, 200);
    assert.equal(
      (await post("caller", "sessions/end", { roomId: audioRoom.body.roomId })).status,
      200,
    );
    assert.equal((await post("target", "device/revoke")).status, 200);
    assert.equal((await post("target", "device/revoke")).status, 200);
    assert.equal(
      storage.sqlite.prepare("SELECT data FROM devices WHERE id='target'").get()!.data,
      null,
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

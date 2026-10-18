import test from "node:test";
import assert from "node:assert/strict";
import { CoordinationStore } from "../src/store.ts";
import {
  register,
  requestConnection,
  approve,
  finishRequest,
  reset,
  revoke,
  endSession,
  ApiError,
} from "../src/coordinator.ts";
import { sqliteDatabase } from "./sqlite.ts";

async function fixture() {
  const storage = sqliteDatabase();
  const store = new CoordinationStore(storage.db);
  const target = (
    await store.transact({ uid: "target" }, (s) => register(s, "target", () => "ABC234"))
  ).value;
  return { ...storage, store, target };
}

test("concurrent requests reserve both participants atomically and repeated approval returns one room", async () => {
  const f = await fixture();
  let batches = 0;
  let release!: () => void;
  const gate = new Promise<void>((resolve) => (release = resolve));
  f.onBatch(async () => {
    if (++batches <= 2) {
      if (batches === 2) release();
      await gate;
    }
  });
  const results = await Promise.allSettled(
    ["one", "two"].map((uid) =>
      f.store.transact({ uid, code: f.target.code, requestId: `request-${uid}` }, (s, n) =>
        requestConnection(s, uid, f.target.code, `request-${uid}`, n),
      ),
    ),
  );
  assert.equal(results.filter((r) => r.status === "fulfilled").length, 1);
  f.onBatch();
  const request = results.find((r) => r.status === "fulfilled")!.value.value.request;
  const rooms = await Promise.all(
    [1, 2].map(() =>
      f.store.transact({ uid: "target", requestId: request.requestId }, (s, n) =>
        approve(s, "target", request.requestId, n),
      ),
    ),
  );
  assert.equal(rooms[0].value.roomId, rooms[1].value.roomId);
  assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM sessions").get()!.n, 1);
  assert.equal(
    (await f.store.participant(request.receiverUid)).value.sessions[rooms[0].value.roomId].active,
    true,
  );
  assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM mutation_guards").get()!.n, 0);
});

test("one caller cannot submit competing requests to different targets", async () => {
  const f = await fixture();
  const other = (
    await f.store.transact({ uid: "other" }, (s) => register(s, "other", () => "DEF567"))
  ).value;
  const results = await Promise.allSettled(
    [f.target.code, other.code].map((code, i) =>
      f.store.transact({ uid: "caller", code, requestId: `outgoing-${i}` }, (s, n) =>
        requestConnection(s, "caller", code, `outgoing-${i}`, n),
      ),
    ),
  );
  assert.equal(results.filter((r) => r.status === "fulfilled").length, 1);
});

test("unrelated devices do not contend on a global version and status reads perform no writes", async () => {
  const f = await fixture();
  await Promise.all(
    Array.from({ length: 40 }, (_, i) =>
      f.store.transact({ uid: `device-${i}` }, (s) => register(s, `device-${i}`)),
    ),
  );
  const before = f.metrics.writes;
  await f.store.transact({ uid: "target" }, (s) => s.devices.target);
  assert.equal(f.metrics.writes, before);
  const baseline = f.metrics.reads;
  await f.store.transact({ uid: "device-39" }, (s) => s.devices["device-39"]);
  assert.ok(f.metrics.reads - baseline <= 2, "lookup work is independent of directory size");
});

test("reset/cancel/revoke/expiry release slots and jobs persist with the mutation", async () => {
  const f = await fixture();
  const request = (
    await f.store.transact(
      { uid: "caller", code: f.target.code, requestId: "pending-request" },
      (s, n) => requestConnection(s, "caller", f.target.code, "pending-request", n),
    )
  ).value.request;
  await f.store.transact({ uid: "caller", requestId: request.requestId }, (s) =>
    finishRequest(s, "caller", request.requestId, "cancel"),
  );
  assert.equal((await f.store.participant("target")).value.incoming, null);
  await f.store.transact(
    { uid: "caller", code: f.target.code, requestId: "next-request" },
    (s, n) => requestConnection(s, "caller", f.target.code, "next-request", n),
  );
  await f.store.transact({ uid: "target" }, (s) => reset(s, "target", () => "GHJ678"));
  assert.equal(
    (await f.store.participant("caller")).value.requests["next-request"].status,
    "cancelled",
  );
  const device = JSON.parse((await f.store.row("devices", "target"))!.data!);
  await f.store.transact({ uid: "caller", code: device.code, requestId: "last-request" }, (s, n) =>
    requestConnection(s, "caller", device.code, "last-request", n),
  );
  await f.store.transact({ uid: "caller" }, (s) => revoke(s, "caller"));
  assert.equal((await f.store.row("devices", "caller"))!.data, null);
  assert.equal((await f.store.participant("target")).value.incoming, null);
  assert.ok((await f.store.due()).length > 0);
});

test("expired reservations can be reused without global maintenance", async () => {
  const f = await fixture();
  const now = Date.now();
  await f.store.transact(
    { uid: "caller", code: f.target.code, requestId: "expires-request" },
    (s, n) => requestConnection(s, "caller", f.target.code, "expires-request", n),
    now - 300_001,
  );
  await f.store.transact(
    { uid: "new-caller", code: f.target.code, requestId: "fresh-request" },
    (s, n) => requestConnection(s, "new-caller", f.target.code, "fresh-request", n),
    now,
  );
  assert.equal((await f.store.participant("target")).value.incoming?.requestId, "fresh-request");
});

test("an older job acknowledgement cannot discard a newer update", async () => {
  const f = await fixture();
  const job = (await f.store.due()).find((j) => j.kind === "participant")!;
  await f.store.transact({ uid: "target" }, (s) => {
    s.devices.target.participantName = "New name";
  });
  await f.store.complete(job);
  const current = (await f.store.due()).find((j) => j.kind === "participant")!;
  assert.ok(current.revision > job.revision);
});

test("targeted lookup work stays constant with 10,000 registered devices", async () => {
  const f = await fixture();
  f.sqlite.exec("BEGIN");
  const codeFor = (number: number) => {
    const alphabet = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    let code = "";
    for (let i = 0; i < 6; i++) {
      code = alphabet[number % alphabet.length] + code;
      number = Math.floor(number / alphabet.length);
    }
    return code;
  };
  const putCode = f.sqlite.prepare("INSERT INTO codes(id,data,version) VALUES(?,?,1)");
  const put = f.sqlite.prepare("INSERT INTO devices(id,data,version) VALUES(?,?,1)");
  for (let i = 0; i < 10_000; i++) {
    const code = codeFor(i);
    put.run(
      `load-${i}`,
      JSON.stringify({ code, requestsEnabled: true, token: "test-token-".repeat(20) }),
    );
    putCode.run(code, JSON.stringify({ senderUid: `load-${i}`, retired: false }));
  }
  f.sqlite.exec("COMMIT");
  const before = f.metrics.reads;
  const result = await f.store.transact({ uid: "target" }, (s) => s.devices.target.code);
  assert.equal(result.value, "ABC234");
  assert.equal(f.metrics.reads - before, 2);
});

test("code collisions roll back the whole mutation rather than stealing another registration", async () => {
  const f = await fixture();
  let attempt = 0;
  const result = await f.store.transact({ uid: "other" }, (s) =>
    register(s, "other", () => (++attempt === 1 ? "ABC234" : "GHJ678")),
  );
  assert.equal(result.value.code, "GHJ678");
  assert.equal(JSON.parse((await f.store.row("codes", "ABC234"))!.data!).senderUid, "target");
});

test("retention removes terminal payloads and generates participant cleanup jobs", async () => {
  const f = await fixture();
  const now = Date.now() - 2 * 86_400_000;
  const r = (
    await f.store.transact(
      { uid: "caller", code: f.target.code, requestId: "old-request" },
      (s, n) => requestConnection(s, "caller", f.target.code, "old-request", n),
      now,
    )
  ).value.request;
  await f.store.transact(
    { uid: "caller", requestId: r.requestId },
    (s) => finishRequest(s, "caller", r.requestId, "cancel"),
    now + 1,
  );
  for (const job of await f.store.due()) await f.store.complete(job);
  const scopes = await f.store.prune();
  assert.equal(scopes.length, 1);
  await f.store.transact(scopes[0], (s) => {
    delete s.requests[r.requestId];
  });
  assert.equal((await f.store.row("requests", r.requestId))!.data, null);
  assert.equal((await f.store.participant("target")).value.requests[r.requestId], undefined);
  assert.ok((await f.store.due()).some((j) => j.kind === "participant"));
});

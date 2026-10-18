/** Run explicitly with npm run test:runtime. Uses Wrangler/workerd's real local D1 binding. */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { getPlatformProxy } from "wrangler";
import { CoordinationStore } from "../src/store.ts";
import { budgetDatabase } from "../src/query-budget.ts";
import { register, requestConnection, approve, endSession } from "../src/coordinator.ts";
const proxy = await getPlatformProxy<{ COORDINATION_DB: D1Database }>({
  configPath: new URL("../wrangler.toml", import.meta.url).pathname,
  persist: false,
});
try {
  const db = proxy.env.COORDINATION_DB;
  for (const statement of readFileSync(
    new URL("../migrations/0001_coordination.sql", import.meta.url),
    "utf8",
  ).split(";")) {
    if (statement.trim()) await db.prepare(statement).run();
  }
  const store = new CoordinationStore(budgetDatabase(db, "1000").db);
  await store.transact({ uid: "runtime-target" }, (s) =>
    register(s, "runtime-target", () => "ABC234"),
  );
  const results = await Promise.allSettled(
    ["one", "two", "three"].map((uid) =>
      store.transact({ uid, code: "ABC234", requestId: `runtime-${uid}` }, (s, n) =>
        requestConnection(s, uid, "ABC234", `runtime-${uid}`, n),
      ),
    ),
  );
  assert.equal(results.filter((r) => r.status === "fulfilled").length, 1);
  const request = results.find((r) => r.status === "fulfilled")!.value.value.request;
  const rooms = await Promise.all(
    [1, 2].map(() =>
      store.transact({ uid: "runtime-target", requestId: request.requestId }, (s, n) =>
        approve(s, "runtime-target", request.requestId, n),
      ),
    ),
  );
  assert.equal(rooms[0].value.roomId, rooms[1].value.roomId);
  await store.transact({ uid: "runtime-target", roomId: rooms[0].value.roomId }, (s) =>
    endSession(s, "runtime-target", rooms[0].value.roomId),
  );
  assert.equal(
    (await store.participant("runtime-target")).value.sessions[rooms[0].value.roomId].active,
    false,
  );
  assert.equal(
    (await db.prepare("SELECT count(*) AS n FROM mutation_guards").first<{ n: number }>())!.n,
    0,
  );
  console.log(
    "workerd D1: competing reservations, batch rollback, idempotent approval and termination passed",
  );
} finally {
  await proxy.dispose();
}

import { ApiError, emptyState, expire, participantEvents } from "./coordinator.ts";
import type { State, ParticipantEvents } from "./coordinator.ts";

const tables = ["devices", "codes", "requests", "sessions"] as const;
type Table = (typeof tables)[number];
type Row = { id: string; data: string | null; version: number };
export type Scope = { uid?: string; code?: string; requestId?: string; roomId?: string };
export type Job = {
  kind: "participant" | "session" | "push";
  id: string;
  revision: number;
  attempts: number;
};
export type Mutation<T> = { value: T; jobs: Job[] };
const retention = 86_400_000;
const conflict = (error: unknown) =>
  /CHECK constraint failed|UNIQUE constraint failed/.test(String(error));

/** A bounded connected set of device slots, never a scan of the device directory. */
class Snapshot {
  state = emptyState();
  rows = new Map<string, Row | null>();
  private db: D1Database;
  constructor(db: D1Database) {
    this.db = db;
  }
  async read(table: Table, id: string) {
    const key = `${table}:${id}`;
    if (this.rows.has(key)) return;
    const row = await this.db
      .prepare(`SELECT id,data,version FROM ${table} WHERE id=?`)
      .bind(id)
      .first<Row>();
    this.rows.set(key, row);
    if (row?.data) (this.state[table] as Record<string, unknown>)[id] = JSON.parse(row.data);
  }
  async load(scope: Scope) {
    if (scope.code) await this.read("codes", scope.code);
    if (scope.requestId) await this.read("requests", scope.requestId);
    if (scope.roomId) await this.read("sessions", scope.roomId);
    const queue = [scope.uid, scope.code && this.state.codes[scope.code]?.senderUid].filter(
      Boolean,
    ) as string[];
    const visited = new Set<string>();
    for (let i = 0; i < queue.length; i++) {
      const uid = queue[i];
      if (visited.has(uid)) continue;
      if (visited.size >= 16) throw new ApiError(503, "Admission is busy. Retry shortly.");
      visited.add(uid);
      await this.read("devices", uid);
      const device = this.state.devices[uid];
      if (device) {
        await this.read("codes", device.code);
        for (const id of [device.pending, device.outgoing]) if (id) await this.read("requests", id);
        if (device.active) await this.read("sessions", device.active);
      }
      for (const request of Object.values(this.state.requests))
        queue.push(request.senderUid, request.receiverUid);
      for (const room of Object.values(this.state.sessions))
        queue.push(room.hostUid, room.guestUid);
    }
    // A request/session scope may introduce participants before a device was queued.
    for (const request of Object.values(this.state.requests)) {
      await this.read("devices", request.senderUid);
      await this.read("devices", request.receiverUid);
    }
    for (const room of Object.values(this.state.sessions)) {
      await this.read("devices", room.hostUid);
      await this.read("devices", room.guestUid);
    }
  }
}

/** Indexed D1 records with optimistic validation inside one transactional batch. */
export class CoordinationStore {
  readonly db: D1Database;
  constructor(db: D1Database) {
    this.db = db;
  }

  async transact<T>(
    scope: Scope,
    operation: (state: State, now: number) => T,
    now = Date.now(),
  ): Promise<Mutation<T>> {
    for (let retry = 0; retry < 8; retry++) {
      const snapshot = new Snapshot(this.db);
      // Read participants first so their reservation slots protect indexed admission predicates.
      if (scope.requestId) {
        await snapshot.read("requests", scope.requestId);
        const r = snapshot.state.requests[scope.requestId];
        if (r) await snapshot.load({ uid: r.senderUid });
      }
      if (scope.roomId) {
        await snapshot.read("sessions", scope.roomId);
        const r = snapshot.state.sessions[scope.roomId];
        if (r) await snapshot.load({ uid: r.hostUid });
      }
      await snapshot.load(scope);
      const before = structuredClone(snapshot.state);
      expire(snapshot.state, now);
      const expired = structuredClone(snapshot.state);
      let value: T | undefined;
      let failure: unknown;
      try {
        value = operation(snapshot.state, now);
      } catch (error) {
        failure = error;
        snapshot.state = expired;
      }
      const changes: { table: Table; id: string; data: string | null; version: number }[] = [];
      for (const table of tables) {
        for (const id of new Set([
          ...Object.keys(before[table]),
          ...Object.keys(snapshot.state[table]),
        ])) {
          const old = (before[table] as Record<string, unknown>)[id];
          const next = (snapshot.state[table] as Record<string, unknown>)[id];
          if (JSON.stringify(old) !== JSON.stringify(next))
            changes.push({
              table,
              id,
              data: next ? JSON.stringify(next) : null,
              version: (snapshot.rows.get(`${table}:${id}`)?.version ?? 0) + 1,
            });
        }
      }
      if (!changes.length) {
        if (failure) throw failure;
        return { value: value!, jobs: [] };
      }
      const statements: D1PreparedStatement[] = [];
      const guard = crypto.randomUUID();
      const checks: string[] = [];
      const bindings: (string | number)[] = [guard];
      for (const [key, row] of snapshot.rows) {
        const separator = key.indexOf(":");
        const table = key.slice(0, separator);
        const id = key.slice(separator + 1);
        checks.push(`COALESCE((SELECT version FROM ${table} WHERE id=?),0)=?`);
        bindings.push(id, row?.version ?? 0);
      }
      statements.push(
        this.db
          .prepare(
            `INSERT INTO mutation_guards(id,valid) VALUES(?,CASE WHEN ${checks.join(" AND ") || "1"} THEN 1 ELSE 0 END)`,
          )
          .bind(...bindings),
      );
      const participants = new Set<string>();
      const jobs: Job[] = [];
      const participantJobs: { index: number; job: Job }[] = [];
      for (const change of changes) {
        const previous = snapshot.rows.get(`${change.table}:${change.id}`);
        statements.push(
          previous
            ? this.db
                .prepare(`UPDATE ${change.table} SET data=?,version=?,updated=? WHERE id=?`)
                .bind(change.data, change.version, now, change.id)
            : this.db
                .prepare(`INSERT INTO ${change.table}(id,data,version,updated) VALUES(?,?,?,?)`)
                .bind(change.id, change.data, change.version, now),
        );
        if (change.table === "devices") participants.add(change.id);
        if (change.table === "requests" || change.table === "sessions") {
          const item =
            (snapshot.state[change.table] as Record<string, any>)[change.id] ??
            (before[change.table] as Record<string, any>)[change.id];
          for (const uid of change.table === "requests"
            ? [item.senderUid, item.receiverUid]
            : [item.hostUid, item.guestUid])
            participants.add(uid);
          const kind = change.table === "sessions" ? "session" : "push";
          statements.push(this.enqueue(kind, change.id, change.version, now));
          jobs.push({ kind, id: change.id, revision: change.version, attempts: 0 });
        }
      }
      for (const uid of participants) {
        statements.push(
          this.db
            .prepare(
              "INSERT INTO participant_versions(id,version) VALUES(?,1) ON CONFLICT(id) DO UPDATE SET version=version+1",
            )
            .bind(uid),
        );
        statements.push(
          this.db
            .prepare(
              "INSERT INTO outbox(kind,id,revision,available) SELECT 'participant',id,version,? FROM participant_versions WHERE id=? ON CONFLICT(kind,id) DO UPDATE SET revision=excluded.revision,available=excluded.available,attempts=0 RETURNING revision",
            )
            .bind(now, uid),
        );
        const job: Job = { kind: "participant", id: uid, revision: 0, attempts: 0 };
        jobs.push(job);
        participantJobs.push({ index: statements.length - 1, job });
      }
      statements.push(this.db.prepare("DELETE FROM mutation_guards WHERE id=?").bind(guard));
      try {
        const committed = await this.db.batch<{ revision: number }>(statements);
        for (const { index, job } of participantJobs)
          job.revision = committed[index].results[0].revision;
      } catch (error) {
        if (conflict(error)) {
          await new Promise((resolve) => setTimeout(resolve, Math.min(25, 2 ** retry)));
          continue;
        }
        throw error;
      }
      if (failure) throw failure;
      return { value: value!, jobs };
    }
    throw new ApiError(503, "Admission is busy. Retry shortly.");
  }

  private enqueue(kind: Job["kind"], id: string, revision: number, now: number) {
    return this.db
      .prepare(
        "INSERT INTO outbox(kind,id,revision,available) VALUES(?,?,?,?) ON CONFLICT(kind,id) DO UPDATE SET revision=excluded.revision,available=excluded.available,attempts=0",
      )
      .bind(kind, id, revision, now);
  }
  async row(table: Table, id: string): Promise<Row | null> {
    return this.db.prepare(`SELECT id,data,version FROM ${table} WHERE id=?`).bind(id).first<Row>();
  }
  async participant(uid: string): Promise<{ version: number; value: ParticipantEvents }> {
    for (let retry = 0; retry < 4; retry++) {
      const version =
        (
          await this.db
            .prepare("SELECT version FROM participant_versions WHERE id=?")
            .bind(uid)
            .first<{ version: number }>()
        )?.version ?? 0;
      const state = emptyState();
      const device = await this.row("devices", uid);
      if (device?.data) state.devices[uid] = JSON.parse(device.data);
      const cutoff = Date.now() - retention;
      for (const [table, a, b] of [
        ["requests", "sender", "receiver"],
        ["sessions", "host", "guest"],
      ] as const) {
        const rows = await this.db
          .prepare(`SELECT id,data,version FROM ${table} WHERE (${a}=? OR ${b}=?) AND expires>=?`)
          .bind(uid, uid, cutoff)
          .all<Row>();
        for (const row of rows.results)
          if (row.data) (state[table] as Record<string, unknown>)[row.id] = JSON.parse(row.data);
      }
      const peers = await this.db
        .prepare(
          `SELECT id,data,version FROM devices WHERE id IN (
        SELECT sender FROM requests WHERE receiver=? AND expires>=?
        UNION SELECT receiver FROM requests WHERE sender=? AND expires>=?
      )`,
        )
        .bind(uid, cutoff, uid, cutoff)
        .all<Row>();
      for (const row of peers.results) if (row.data) state.devices[row.id] = JSON.parse(row.data);
      const after =
        (
          await this.db
            .prepare("SELECT version FROM participant_versions WHERE id=?")
            .bind(uid)
            .first<{ version: number }>()
        )?.version ?? 0;
      if (version === after)
        return {
          version,
          value: participantEvents(state)[uid] ?? { incoming: null, requests: {}, sessions: {} },
        };
    }
    throw new ApiError(503, "Participant events are busy. Retry shortly.");
  }
  async pendingJob(kind: Job["kind"], id: string): Promise<Job | null> {
    return this.db
      .prepare("SELECT kind,id,revision,attempts FROM outbox WHERE kind=? AND id=?")
      .bind(kind, id)
      .first<Job>();
  }
  async authorizationJobs(uid: string): Promise<Job[]> {
    return (
      await this.db
        .prepare(
          `SELECT kind,id,revision,attempts FROM outbox WHERE kind='session' AND id IN (
      SELECT id FROM sessions WHERE host=? UNION SELECT id FROM sessions WHERE guest=?
    ) LIMIT 5`,
        )
        .bind(uid, uid)
        .all<Job>()
    ).results;
  }
  async due(limit = 16): Promise<Job[]> {
    const jobs: Job[] = [];
    // Each priority range is an indexed bounded read, not a sort of the entire backlog.
    for (const kind of ["session", "participant", "push"] as const) {
      if (jobs.length >= limit) break;
      const rows = await this.db
        .prepare(
          "SELECT kind,id,revision,attempts FROM outbox WHERE kind=? AND available<=? ORDER BY available LIMIT ?",
        )
        .bind(kind, Date.now(), limit - jobs.length)
        .all<Job>();
      jobs.push(...rows.results);
    }
    return jobs;
  }
  async complete(job: Job) {
    await this.db
      .prepare("DELETE FROM outbox WHERE kind=? AND id=? AND revision<=?")
      .bind(job.kind, job.id, job.revision)
      .run();
  }
  async retry(job: Job) {
    await this.db
      .prepare(
        "UPDATE outbox SET attempts=attempts+1,available=? WHERE kind=? AND id=? AND revision=?",
      )
      .bind(
        Date.now() + Math.min(300_000, 1000 * 2 ** Math.min(job.attempts, 8)),
        job.kind,
        job.id,
        job.revision,
      )
      .run();
  }
  async ready(): Promise<boolean> {
    return (
      (
        await this.db
          .prepare("SELECT value FROM service_metadata WHERE key='ready'")
          .first<{ value: string }>()
      )?.value === "1"
    );
  }
  async prune(limit = 10): Promise<Scope[]> {
    const result: Scope[] = [];
    for (const [table, field, active] of [
      ["requests", "requestId", "pending"],
      ["sessions", "roomId", "active"],
    ] as const) {
      const rows = await this.db
        .prepare(
          `SELECT id FROM ${table} WHERE status<>? AND expires<? AND NOT EXISTS(SELECT 1 FROM outbox WHERE outbox.id=${table}.id AND kind=?) LIMIT ?`,
        )
        .bind(active, Date.now() - retention, table === "requests" ? "push" : "session", limit)
        .all<{ id: string }>();
      for (const row of rows.results) result.push({ [field]: row.id });
    }
    return result;
  }
  async tombstones(limit = 10): Promise<{ table: "requests" | "sessions"; id: string }[]> {
    const result: { table: "requests" | "sessions"; id: string }[] = [];
    for (const table of ["requests", "sessions"] as const) {
      const rows = await this.db
        .prepare(
          `SELECT id FROM ${table} WHERE data IS NULL AND updated<? AND NOT EXISTS(SELECT 1 FROM outbox WHERE outbox.id=${table}.id) LIMIT ?`,
        )
        .bind(Date.now() - 7 * retention, limit)
        .all<{ id: string }>();
      for (const row of rows.results) result.push({ table, id: row.id });
    }
    return result;
  }
  async expired(limit = 20): Promise<Scope[]> {
    const result: Scope[] = [];
    for (const [table, field] of [
      ["requests", "requestId"],
      ["sessions", "roomId"],
    ] as const) {
      const rows = await this.db
        .prepare(`SELECT id FROM ${table} WHERE status=? AND expires<=? LIMIT ?`)
        .bind(table === "requests" ? "pending" : "active", Date.now(), limit)
        .all<{ id: string }>();
      for (const row of rows.results) result.push({ [field]: row.id });
    }
    return result;
  }
}

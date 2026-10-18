import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";

/** Actual SQLite transactions/constraints, adapted to D1's public prepared-statement API. */
export function sqliteDatabase() {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec(
    readFileSync(new URL("../migrations/0001_coordination.sql", import.meta.url), "utf8"),
  );
  sqlite.exec("INSERT INTO service_metadata(key,value) VALUES('ready','1')");
  const metrics = { reads: 0, writes: 0, statements: 0 };
  let beforeBatch: (() => Promise<void>) | undefined;
  const prepared = (sql: string, args: unknown[] = []) => ({
    bind(...values: unknown[]) {
      return prepared(sql, values);
    },
    async first() {
      metrics.reads++;
      metrics.statements++;
      return sqlite.prepare(sql).get(...args) ?? null;
    },
    async all() {
      metrics.reads++;
      metrics.statements++;
      return { success: true, results: sqlite.prepare(sql).all(...args) };
    },
    async run() {
      metrics.writes++;
      metrics.statements++;
      return { success: true, meta: sqlite.prepare(sql).run(...args) };
    },
    sql,
    args,
  });
  const db = {
    prepare: prepared,
    async batch(statements: { sql: string; args: unknown[] }[]) {
      if (beforeBatch) await beforeBatch();
      sqlite.exec("BEGIN IMMEDIATE");
      try {
        const result = statements.map((s) => {
          metrics.writes++;
          metrics.statements++;
          if (/RETURNING/i.test(s.sql))
            return { success: true, results: sqlite.prepare(s.sql).all(...s.args), meta: {} };
          return { success: true, results: [], meta: sqlite.prepare(s.sql).run(...s.args) };
        });
        sqlite.exec("COMMIT");
        return result;
      } catch (error) {
        sqlite.exec("ROLLBACK");
        throw error;
      }
    },
  };
  return {
    db: db as unknown as D1Database,
    sqlite,
    metrics,
    onBatch(fn?: () => Promise<void>) {
      beforeBatch = fn;
    },
  };
}

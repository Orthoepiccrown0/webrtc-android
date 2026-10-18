import { ApiError } from "./coordinator.ts";

export class QueryBudgetExceeded extends ApiError {
  constructor() {
    super(503, "Coordination work deferred. Retry shortly.");
  }
}

/** Stop before an invocation exceeds its configured D1 budget, never midway through a batch. */
export function budgetDatabase(database: D1Database, configured = "50") {
  const limit = Number(configured);
  const maximum = Number.isInteger(limit) && limit >= 1 && limit <= 1000 ? limit : 50;
  let used = 0;
  const native = new WeakMap<object, D1PreparedStatement>();
  function reserve(count: number) {
    if (used + count > maximum) throw new QueryBudgetExceeded();
    used += count;
  }
  function statement(original: D1PreparedStatement): D1PreparedStatement {
    const wrapped = {
      bind(...values: unknown[]) {
        return statement(original.bind(...values));
      },
      async first<T>(column?: string) {
        reserve(1);
        return original.first<T>(column!);
      },
      async all<T>() {
        reserve(1);
        return original.all<T>();
      },
      async run<T>() {
        reserve(1);
        return original.run<T>();
      },
      async raw<T>(options?: { columnNames?: boolean }) {
        reserve(1);
        return original.raw<T>(options as { columnNames: false });
      },
    } as D1PreparedStatement;
    native.set(wrapped, original);
    return wrapped;
  }
  const db = {
    prepare(sql: string) {
      return statement(database.prepare(sql));
    },
    async batch<T>(statements: D1PreparedStatement[]) {
      reserve(statements.length);
      return database.batch<T>(statements.map((s) => native.get(s) ?? s));
    },
  } as D1Database;
  return { db, used: () => used, limit: maximum };
}

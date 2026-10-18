import test from "node:test";
import assert from "node:assert/strict";
import { budgetDatabase, QueryBudgetExceeded } from "../src/query-budget.ts";
import { sqliteDatabase } from "./sqlite.ts";

test("query budgeting never submits part of an atomic batch", async () => {
  const f = sqliteDatabase();
  const budget = budgetDatabase(f.db, "2");
  await budget.db.prepare("SELECT 1").first();
  await assert.rejects(
    () =>
      budget.db.batch([
        budget.db.prepare("INSERT INTO participant_versions VALUES('one',1)"),
        budget.db.prepare("INSERT INTO participant_versions VALUES('two',1)"),
      ]),
    QueryBudgetExceeded,
  );
  assert.equal(f.sqlite.prepare("SELECT count(*) AS n FROM participant_versions").get()!.n, 0);
  assert.equal(budget.used(), 1);
});

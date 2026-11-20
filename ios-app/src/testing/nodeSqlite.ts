// Test helper: a real SQLite (Node's built-in `node:sqlite`) behind the app's Db interface.
import { DatabaseSync } from 'node:sqlite';
import { Db, type SqlParam } from '../data/db';
import { migrate } from '../data/migrations';
import type { Clock } from '../core/clock';

export function openMemoryDb(file = ':memory:'): { db: Db; raw: DatabaseSync } {
  const raw = new DatabaseSync(file);
  const db = new Db({
    exec: async (sql) => void raw.exec(sql),
    run: async (sql, params: SqlParam[] = []) => {
      const r = raw.prepare(sql).run(...params);
      return { lastInsertRowId: Number(r.lastInsertRowid), changes: Number(r.changes) };
    },
    all: async <T>(sql: string, params: SqlParam[] = []) => raw.prepare(sql).all(...params) as T[],
  });
  return { db, raw };
}

export async function migratedDb() {
  const x = openMemoryDb();
  await migrate(x.db);
  return x;
}

/** A clock the tests move by hand. Detroit in winter by default (UTC-05:00). */
export class FakeClock implements Clock {
  constructor(
    public time = Date.UTC(2026, 10, 19, 19, 30),
    private zoneId = 'America/Detroit',
    private offset = -300,
  ) {}
  now() {
    return this.time;
  }
  zone() {
    return this.zoneId;
  }
  offsetMinutes() {
    return this.offset;
  }
  advance(ms = 1000) {
    this.time += ms;
  }
}

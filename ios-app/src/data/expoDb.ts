import * as SQLite from 'expo-sqlite';
import { Db } from './db';
import { migrate } from './migrations';

/** Opens `edith.db` (the Kotlin app's file name), migrates it to the latest schema and returns it. */
export async function openDatabase(name = 'edith.db'): Promise<Db> {
  const raw = await SQLite.openDatabaseAsync(name);
  const db = new Db({
    exec: (sql) => raw.execAsync(sql),
    run: async (sql, params) => {
      const r = await raw.runAsync(sql, params ?? []);
      return { lastInsertRowId: r.lastInsertRowId, changes: r.changes };
    },
    all: <T>(sql: string, params?: (string | number | null)[]) => raw.getAllAsync<T>(sql, params ?? []),
  });
  await migrate(db);
  return db;
}

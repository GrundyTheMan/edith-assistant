import { log } from '../core/log';
import type { Db } from './db';

/**
 * The Kotlin app's schema, version 2 (01-architecture §5.1), migrated by `PRAGMA user_version`. A fresh install runs
 * every step in order, so the migration path is the one that's tested. Never destructive: C06b's lesson is that
 * dropping `conversations` with foreign keys on cascade-deletes every message, so version 2 only ADDs columns.
 */
export const MIGRATIONS: { version: number; sql: string }[] = [
  {
    version: 1,
    sql: `
      CREATE TABLE conversations (
        id INTEGER PRIMARY KEY NOT NULL,
        title TEXT NOT NULL,
        created_at INTEGER NOT NULL
      );
      CREATE TABLE messages (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        conversation_id INTEGER NOT NULL,
        role TEXT NOT NULL,
        content TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        status TEXT NOT NULL,
        source TEXT NOT NULL,
        model TEXT,
        tools_used TEXT,
        error_message TEXT,
        FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
      );
      CREATE INDEX index_messages_conversation_id_created_at ON messages (conversation_id, created_at);
      CREATE TABLE attachments (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        message_id INTEGER NOT NULL,
        number INTEGER NOT NULL,
        direction TEXT NOT NULL,
        kind TEXT NOT NULL,
        file_name TEXT NOT NULL,
        mime_type TEXT NOT NULL,
        size_bytes INTEGER NOT NULL,
        stored_path TEXT,
        resend_of INTEGER,
        created_at INTEGER NOT NULL,
        removed_at INTEGER,
        FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE,
        FOREIGN KEY (resend_of) REFERENCES attachments(id)
      );
      CREATE INDEX index_attachments_message_id ON attachments (message_id);
      CREATE INDEX index_attachments_direction_created_at ON attachments (direction, created_at);
      CREATE INDEX index_attachments_number ON attachments (number);
    `,
  },
  {
    version: 2,
    sql: `
      ALTER TABLE conversations ADD COLUMN title_source TEXT NOT NULL DEFAULT 'none';
      ALTER TABLE conversations ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0;
      UPDATE conversations SET
        updated_at = COALESCE((SELECT MAX(created_at) FROM messages WHERE messages.conversation_id = conversations.id), created_at),
        title_source = CASE WHEN title <> '' THEN 'owner' ELSE 'none' END;
      CREATE INDEX index_conversations_updated_at ON conversations (updated_at);
    `,
  },
];

export const LATEST_VERSION = MIGRATIONS[MIGRATIONS.length - 1].version;

/** Brings the database to the latest version, then checks that no row lost its parent. */
export async function migrate(db: Db): Promise<void> {
  await db.exec('PRAGMA foreign_keys = ON');
  const row = await db.first<{ user_version: number }>('PRAGMA user_version');
  const from = row?.user_version ?? 0;
  if (from > LATEST_VERSION) throw new Error(`The database is version ${from}; this app only knows up to ${LATEST_VERSION}.`);
  for (const step of MIGRATIONS) {
    if (step.version <= from) continue;
    await db.transaction(async () => {
      await db.exec(step.sql);
      await db.exec(`PRAGMA user_version = ${step.version}`);
    });
    log.info('Db', `Migrated to version ${step.version}`);
  }
  const broken = await db.all('PRAGMA foreign_key_check');
  if (broken.length > 0) throw new Error(`Database check failed: ${broken.length} rows have no parent.`);
}

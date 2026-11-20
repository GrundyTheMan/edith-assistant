/** Photos the owner attaches (C15 on iOS): picked, shrunk, sent to the model, shown in the bubble. */

import { Store } from '../core/store';

export const MAX_IMAGE_SIDE = 1536;
export const JPEG_QUALITY = 0.85;
/** Pending attachments per message. */
export const MAX_ATTACHMENTS = 6;
/** Images are sent only for the newest messages (C15 §3); older ones are labels. */
export const MEDIA_WINDOW = 10;

export interface PendingAttachment {
  /** Local id for the chip's key and Remove button, not the database id. */
  localId: string;
  kind: 'image';
  fileName: string;
  mimeType: 'image/jpeg';
  /** The JPEG, Base64-encoded, as Ollama wants it. */
  base64: string;
  sizeBytes: number;
  width: number;
  height: number;
}

export interface PickedImage {
  uri: string;
  width: number;
  height: number;
  fileName?: string | null;
}

/** What the platform does for us (expo-image-manipulator); a fake in tests. */
export interface ImageProcessor {
  /** Resizes (when `target` is set) and encodes as JPEG, quality 0.85. Applies the photo's rotation. */
  toJpeg(uri: string, target: { width: number; height: number } | null): Promise<{ base64: string; width: number; height: number }>;
}

/** Longest side at most 1536 px, never enlarged; the aspect ratio is kept. */
export function scaledSize(width: number, height: number): { width: number; height: number } | null {
  const longest = Math.max(width, height);
  if (longest <= MAX_IMAGE_SIDE || longest <= 0) return null;
  const f = MAX_IMAGE_SIDE / longest;
  return { width: Math.max(1, Math.round(width * f)), height: Math.max(1, Math.round(height * f)) };
}

/** Bytes of Base64 text: 3 bytes per 4 characters, less padding. */
export function base64Bytes(b64: string): number {
  const pad = b64.endsWith('==') ? 2 : b64.endsWith('=') ? 1 : 0;
  return Math.floor((b64.length * 3) / 4) - pad;
}

function jpegName(name: string | null | undefined, index: number): string {
  const base = (name ?? '').trim();
  if (!base) return `image-${index}.jpg`;
  const dot = base.lastIndexOf('.');
  return `${dot > 0 ? base.slice(0, dot) : base}.jpg`; // HEIC and PNG are sent as JPEG
}

let counter = 0;

/** Decodes, rotates upright and shrinks one picked photo; HEIC and PNG come out as JPEG. */
export async function prepareImage(picked: PickedImage, processor: ImageProcessor, index = 1): Promise<PendingAttachment> {
  const target = scaledSize(picked.width, picked.height);
  const out = await processor.toJpeg(picked.uri, target);
  if (!out.base64) throw new Error("That photo couldn't be read.");
  counter += 1;
  return {
    localId: `p${Date.now()}-${counter}`,
    kind: 'image',
    fileName: jpegName(picked.fileName, index),
    mimeType: 'image/jpeg',
    base64: out.base64,
    sizeBytes: base64Bytes(out.base64),
    width: out.width,
    height: out.height,
  };
}

/** Pictures on disk (C16); a fake in tests. Ids are attachment row ids. */
export interface AttachmentFiles {
  write(id: number, base64: string): Promise<void>;
  read(id: number): Promise<string | null>;
  delete(id: number): Promise<void>;
  list(): Promise<number[]>;
}

/**
 * The picture bytes of the owner's attachments (C16 §1). They are held in memory for drawing and sending, and written
 * to files so they survive a restart; retention (retention.ts) decides how many files are kept. Without `files` (tests,
 * or a failing disk) pictures last only for this run of the app, as in C43.
 */
export class AttachmentStore {
  private readonly images = new Map<number, string>();
  /** Changes whenever a picture is added or removed, so screens redraw. */
  readonly version = new Store<number>(0);

  constructor(private readonly files?: AttachmentFiles) {}

  /** Holds the picture now and writes it to disk; a write that fails leaves it in memory only. */
  async put(attachmentId: number, base64: string): Promise<void> {
    this.images.set(attachmentId, base64);
    this.version.set(this.version.get() + 1);
    try {
      await this.files?.write(attachmentId, base64);
    } catch {
      // kept for this run only
    }
  }

  /** Loads pictures that are on disk but not in memory (after a restart). */
  async hydrate(ids: number[]): Promise<void> {
    if (!this.files) return;
    let changed = false;
    for (const id of ids) {
      if (this.images.has(id)) continue;
      const b64 = await this.files.read(id);
      if (b64) {
        this.images.set(id, b64);
        changed = true;
      }
    }
    if (changed) this.version.set(this.version.get() + 1);
  }

  /** Drops pictures from memory and disk. */
  async forget(ids: number[]): Promise<void> {
    for (const id of ids) {
      this.images.delete(id);
      try {
        await this.files?.delete(id);
      } catch {
        // already gone
      }
    }
    if (ids.length > 0) this.version.set(this.version.get() + 1);
  }

  /** Ids of the files on disk. */
  async listFiles(): Promise<number[]> {
    return (await this.files?.list()) ?? [];
  }
  image(attachmentId: number): string | null {
    return this.images.get(attachmentId) ?? null;
  }
  has(attachmentId: number): boolean {
    return this.images.has(attachmentId);
  }
}

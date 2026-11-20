import * as FileSystem from 'expo-file-system/legacy';
import type { AttachmentFiles } from './attachments';

/**
 * Attachments on disk, in the app's own folder: `attachments/<row id>.bin` (Base64 text decoded to the bytes: a picture or, since C56, a text
 * file). C47 wrote pictures as `.jpg`; those are still read and deleted.
 */
const dir = () => `${FileSystem.documentDirectory}attachments/`;
const path = (id: number) => `${dir()}${id}.bin`;
const legacy = (id: number) => `${dir()}${id}.jpg`;

export const expoAttachmentFiles: AttachmentFiles = {
  async write(id, base64) {
    await FileSystem.makeDirectoryAsync(dir(), { intermediates: true }).catch(() => {});
    // Written beside the final name and moved into place, so a crash never leaves half a picture.
    const tmp = `${path(id)}.tmp`;
    await FileSystem.writeAsStringAsync(tmp, base64, { encoding: FileSystem.EncodingType.Base64 });
    await FileSystem.moveAsync({ from: tmp, to: path(id) });
  },
  async read(id) {
    try {
      return await FileSystem.readAsStringAsync(path(id), { encoding: FileSystem.EncodingType.Base64 });
    } catch {
      try {
        return await FileSystem.readAsStringAsync(legacy(id), { encoding: FileSystem.EncodingType.Base64 });
      } catch {
        return null;
      }
    }
  },
  async delete(id) {
    await FileSystem.deleteAsync(path(id), { idempotent: true });
    await FileSystem.deleteAsync(legacy(id), { idempotent: true });
  },
  async list() {
    try {
      const names = await FileSystem.readDirectoryAsync(dir());
      return [...new Set(names.flatMap((n) => (/^\d+\.(?:bin|jpg)$/.test(n) ? [Number(n.slice(0, n.indexOf('.')))] : [])))];
    } catch {
      return [];
    }
  },
};

import * as FileSystem from 'expo-file-system/legacy';
import type { AttachmentFiles } from './attachments';

/** Pictures on disk, in the app's own folder: `attachments/<row id>.jpg` (Base64 text decoded to the JPEG bytes). */
const dir = () => `${FileSystem.documentDirectory}attachments/`;
const path = (id: number) => `${dir()}${id}.jpg`;

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
      return null;
    }
  },
  async delete(id) {
    await FileSystem.deleteAsync(path(id), { idempotent: true });
  },
  async list() {
    try {
      const names = await FileSystem.readDirectoryAsync(dir());
      return names.flatMap((n) => (/^\d+\.jpg$/.test(n) ? [Number(n.slice(0, -4))] : []));
    } catch {
      return [];
    }
  },
};

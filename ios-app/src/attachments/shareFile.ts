import * as FileSystem from 'expo-file-system/legacy';
import * as Sharing from 'expo-sharing';
import { mimeForName } from '../chat/replyFormat';

/** Hands a text file to the iOS share sheet (its "Save to Files" is the save; Copy is separate). The temporary file is deleted afterwards. */
export type FileSharer = (name: string, text: string) => Promise<void>;

export const shareTextFile: FileSharer = async (name, text) => {
  const dir = `${FileSystem.cacheDirectory}share/`;
  await FileSystem.makeDirectoryAsync(dir, { intermediates: true }).catch(() => {});
  const uri = `${dir}${name}`;
  await FileSystem.writeAsStringAsync(uri, text, { encoding: FileSystem.EncodingType.UTF8 });
  try {
    if (await Sharing.isAvailableAsync()) await Sharing.shareAsync(uri, { mimeType: mimeForName(name), dialogTitle: name });
  } finally {
    await FileSystem.deleteAsync(uri, { idempotent: true }).catch(() => {});
  }
};

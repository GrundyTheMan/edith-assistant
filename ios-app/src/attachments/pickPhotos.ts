import * as ImagePicker from 'expo-image-picker';
import type { PickedImage } from './attachments';

/** The system photo picker (no permission needed on iOS 14+); up to `limit` photos at once. */
export async function pickPhotos(limit: number): Promise<PickedImage[]> {
  if (limit <= 0) return [];
  const result = await ImagePicker.launchImageLibraryAsync({
    mediaTypes: ['images'],
    allowsMultipleSelection: true,
    selectionLimit: Math.min(limit, 4),
    quality: 1,
    exif: false,
  });
  if (result.canceled) return [];
  return result.assets.map((a) => ({ uri: a.uri, width: a.width, height: a.height, fileName: a.fileName }));
}

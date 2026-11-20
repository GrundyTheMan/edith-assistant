import { SaveFormat, manipulateAsync } from 'expo-image-manipulator';
import { JPEG_QUALITY, type ImageProcessor } from './attachments';

/** expo-image-manipulator on the phone: applies EXIF rotation, resizes, re-encodes as JPEG (HEIC and PNG included). */
export const expoImageProcessor: ImageProcessor = {
  async toJpeg(uri, target) {
    const result = await manipulateAsync(uri, target ? [{ resize: target }] : [], {
      compress: JPEG_QUALITY,
      format: SaveFormat.JPEG,
      base64: true,
    });
    return { base64: result.base64 ?? '', width: result.width, height: result.height };
  },
};

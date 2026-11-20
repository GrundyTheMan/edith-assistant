import * as Notifications from 'expo-notifications';
import * as FileSystem from 'expo-file-system/legacy';
import type { Alert } from './model';
import { alertContent, alertTitle, type NotificationPermission, type Notifier } from './notifications';

/** The app is only ever alerting while it is open, so the banner has to show with the app in front. */
Notifications.setNotificationHandler({
  handleNotification: async () => ({ shouldShowBanner: true, shouldShowList: true, shouldPlaySound: true, shouldSetBadge: false }),
});

/** A notification attachment needs a file address: the picture is written to the cache and deleted when its alert is replaced or after a while. */
async function writePicture(key: string, base64: string): Promise<string> {
  const safe = key.replace(/[^A-Za-z0-9._-]/g, '_').slice(0, 60);
  const uri = `${FileSystem.cacheDirectory}alert-${safe}.jpg`;
  await FileSystem.writeAsStringAsync(uri, base64, { encoding: FileSystem.EncodingType.Base64 });
  return uri;
}

/** Local notifications through expo-notifications. Not covered by unit tests: it needs a device (C72 "Not verified"). */
export const expoNotifier: Notifier = {
  async permission(): Promise<NotificationPermission> {
    try {
      const p = await Notifications.getPermissionsAsync();
      return p.granted ? 'granted' : p.canAskAgain ? 'undetermined' : 'denied';
    } catch {
      return 'denied';
    }
  },

  async request(): Promise<boolean> {
    try {
      return (await Notifications.requestPermissionsAsync()).granted;
    } catch {
      return false;
    }
  },

  async show(alert: Alert, pictureBase64?: string): Promise<void> {
    const c = alertContent(alert);
    let attachments: { identifier: string; url: string; type: string }[] | undefined;
    if (pictureBase64) {
      try {
        attachments = [{ identifier: 'picture', url: await writePicture(alert.key, pictureBase64), type: 'public.jpeg' }];
      } catch {
        // the alert shows without its picture
      }
    }
    await Notifications.scheduleNotificationAsync({
      identifier: c.identifier,
      content: {
        title: alertTitle(alert.kind),
        body: c.body,
        data: c.data,
        categoryIdentifier: c.categoryIdentifier,
        interruptionLevel: c.interruptionLevel,
        sound: pictureBase64 ? false : true, // the second showing (with the picture) replaces the first quietly
        ...(attachments ? { attachments } : {}),
      },
      trigger: null,
    });
  },

  onOpen(open: () => void): () => void {
    const sub = Notifications.addNotificationResponseReceivedListener(() => open());
    // Tapped while the app was closed: the response is waiting.
    void Notifications.getLastNotificationResponseAsync()
      .then((last) => {
        if (last) open();
      })
      .catch(() => {});
    return () => sub.remove();
  },
};

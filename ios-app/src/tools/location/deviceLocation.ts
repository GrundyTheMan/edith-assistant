import * as Location from 'expo-location';

export type LocationResult =
  | { kind: 'ok'; latitude: number; longitude: number }
  | { kind: 'denied' }
  | { kind: 'unavailable' };

/** The phone's approximate position, asked for only when a tool needs "here", never stored (C11). */
export interface LocationProvider {
  current(signal?: AbortSignal): Promise<LocationResult>;
}

const TIMEOUT_MS = 10_000;

/** While-using permission only (no background); balanced accuracy is a city block, plenty for weather. */
export const deviceLocation: LocationProvider = {
  async current(signal) {
    try {
      let perm = await Location.getForegroundPermissionsAsync();
      if (perm.status !== 'granted' && perm.canAskAgain) perm = await Location.requestForegroundPermissionsAsync();
      if (perm.status !== 'granted') return { kind: 'denied' };
      const timeout = new Promise<null>((resolve) => setTimeout(() => resolve(null), TIMEOUT_MS));
      const aborted = new Promise<null>((resolve) => signal?.addEventListener('abort', () => resolve(null)));
      const position = await Promise.race([Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced }), timeout, aborted]);
      if (!position) return { kind: 'unavailable' };
      return { kind: 'ok', latitude: position.coords.latitude, longitude: position.coords.longitude };
    } catch {
      return { kind: 'unavailable' };
    }
  },
};

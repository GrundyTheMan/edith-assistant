import { useRouter } from 'expo-router';
import { useEffect } from 'react';
import { AppState } from 'react-native';
import { useServices } from '../core/services';

/**
 * Connects the alerts to the app (C72): the loop looks only while the app is active (`AppState`), and a tap on an alert opens the Home tab's Clips list.
 * Renders nothing. Mounted once inside the services provider.
 */
export function AlertsHost() {
  const { alerts, home } = useServices();
  const router = useRouter();
  useEffect(() => {
    alerts.loop.setActive(AppState.currentState === 'active');
    const sub = AppState.addEventListener('change', (s) => alerts.loop.setActive(s === 'active'));
    return () => sub.remove();
  }, [alerts]);
  useEffect(
    () =>
      alerts.notifier.onOpen(() => {
        router.navigate('/home');
        home.requestClips();
      }),
    [alerts, home, router],
  );
  return null;
}

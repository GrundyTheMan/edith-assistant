import { useState } from 'react';
import { Pressable, StyleSheet, Switch, Text, View } from 'react-native';
import { alertKindsOf, KIND_LABELS, type AlertKind } from '../alerts/model';
import { useServices, useStore } from '../core/services';
import { isConnected } from '../home/service';
import type { Theme } from './theme';

/**
 * Settings → Home → Alerts (C72 §6): per signed-in service and per kind, all off by default. The honest promise is in the text: iPhone does not let an app
 * watch the cameras in the background, so alerts arrive while Edith is open, and as a summary when you return.
 */
export function AlertsSettings({ theme }: { theme: Theme }) {
  const { alerts, home } = useServices();
  const choices = useStore(alerts.prefs.choices);
  useStore(home.version);
  const services = useStore(home.services);
  const [denied, setDenied] = useState(false);

  const rows: { id: string; name: string; kinds: AlertKind[] }[] = [
    ...services.filter(isConnected).map((s) => ({ id: s.id, name: s.name, kinds: alertKindsOf(s.id) })),
    ...(__DEV__ && home.demoOn ? [{ id: 'demo', name: 'Demo home', kinds: alertKindsOf('demo', true) }] : []),
  ].filter((r) => r.kinds.length > 0);

  const toggle = async (id: string, kind: AlertKind, on: boolean) => {
    if (on && !alerts.prefs.choices.get().any) {
      // Asked when the first alert is switched on, not at start-up.
      const allowed = (await alerts.notifier.permission()) === 'granted' || (await alerts.notifier.request());
      setDenied(!allowed);
      if (!allowed) return;
    } else if (on) setDenied(false);
    await alerts.prefs.set(id, kind, on);
  };

  return (
    <View style={styles.box}>
      <Text style={{ color: theme.text, fontWeight: '600' }}>Alerts</Text>
      <Text style={[styles.note, { color: theme.textMuted }]}>
        Alerts arrive while Edith is open, and when you return. iPhone doesn&apos;t let apps watch in the background, so a ring while Edith is closed shows up as a summary the next time you open it.
      </Text>
      {rows.length === 0 ? <Text style={[styles.note, { color: theme.textMuted }]}>Sign in to Ring or Blink above to switch alerts on.</Text> : null}
      {rows.map((r) => (
        <View key={r.id} style={styles.service}>
          <Text style={{ color: theme.text }}>{r.name}</Text>
          {r.kinds.map((k) => (
            <View key={k} style={styles.row}>
              <Text style={[styles.flex, { color: theme.text }]}>{KIND_LABELS[k]}</Text>
              <Switch
                value={choices.enabled(r.id, k)}
                onValueChange={(v) => void toggle(r.id, k, v)}
                accessibilityLabel={`${r.name}: ${KIND_LABELS[k]}`}
              />
            </View>
          ))}
        </View>
      ))}
      {denied ? <Text style={{ color: theme.error }}>Notifications are off for Edith. Allow them in the iPhone&apos;s Settings → Edith → Notifications to get alerts.</Text> : null}
      {__DEV__ && home.demoOn ? (
        <Pressable onPress={() => alerts.demoRing()} accessibilityRole="button" accessibilityLabel="Ring the Demo doorbell">
          <Text style={{ color: theme.primary, fontWeight: '600' }}>Ring the Demo doorbell</Text>
        </Pressable>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  box: { gap: 8 },
  flex: { flex: 1 },
  note: { fontSize: 13 },
  service: { gap: 4 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 12 },
});

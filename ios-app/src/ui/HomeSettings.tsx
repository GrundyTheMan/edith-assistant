import { useState } from 'react';
import { Pressable, StyleSheet, Switch, Text, View } from 'react-native';
import { useServices, useStore } from '../core/services';
import type { Theme } from './theme';

/** Settings → Home (C25 §7): the Demo home (development builds), hidden devices, the command log. */
export function HomeSettings({ theme }: { theme: Theme }) {
  const { home } = useServices();
  useStore(home.version);
  const [showLog, setShowLog] = useState(false);
  const hidden = home.hiddenDevices();
  const log = home.commandLog();
  return (
    <View style={{ gap: 10 }}>
      {__DEV__ ? (
        <View style={styles.row}>
          <View style={styles.flex}>
            <Text style={{ color: theme.text, fontSize: 16 }}>Demo home</Text>
            <Text style={[styles.note, { color: theme.textMuted }]}>Made-up lights, locks and a thermostat to try the Home tab and chat</Text>
          </View>
          <Switch value={home.demoOn} onValueChange={(v) => home.setDemo(v)} accessibilityLabel="Demo home" />
        </View>
      ) : (
        <Text style={[styles.note, { color: theme.textMuted }]}>No smart-home service is connected. Services come in later versions.</Text>
      )}
      {hidden.length > 0 ? (
        <View style={{ gap: 6 }}>
          <Text style={{ color: theme.text, fontWeight: '600' }}>Hidden devices</Text>
          {hidden.map((d) => (
            <View key={d.id} style={styles.row}>
              <Text style={[styles.flex, { color: theme.text }]}>{d.name}</Text>
              <Pressable onPress={() => home.setHidden(d.id, false)} accessibilityRole="button" accessibilityLabel={`Show ${d.name} again`}>
                <Text style={{ color: theme.primary, fontWeight: '600' }}>Show again</Text>
              </Pressable>
            </View>
          ))}
        </View>
      ) : null}
      <Pressable onPress={() => setShowLog(!showLog)} accessibilityRole="button" accessibilityLabel="Command log">
        <Text style={{ color: theme.primary, fontWeight: '600' }}>{showLog ? 'Hide command log' : `Command log (${log.length})`}</Text>
      </Pressable>
      {showLog
        ? log.slice(0, 30).map((e) => (
            <Text key={`${e.at}-${e.device}-${e.action}`} style={{ color: e.ok ? theme.textMuted : theme.error, fontSize: 13 }}>
              {new Date(e.at).toLocaleTimeString()} · {e.device} · {e.action.replace(/_/g, ' ')} · {e.result} · {e.source === 'chat' ? 'chat' : 'Home tab'}
            </Text>
          ))
        : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  flex: { flex: 1 },
  note: { fontSize: 13 },
});

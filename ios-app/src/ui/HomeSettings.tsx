import { useState } from 'react';
import { Alert, Pressable, StyleSheet, Switch, Text, View } from 'react-native';
import { useServices, useStore } from '../core/services';
import { stateText, type HomeService, type ServiceAction } from '../home/service';
import { PageSignInModal } from './PageSignInModal';
import { ServiceSignIn } from './ServiceSignIn';
import type { Theme } from './theme';

/** One service's row (C48 §5): its state in words and the right button: Sign in, Sign in again, or Disconnect. */
export function ServiceRow({ service, theme, onSignIn, ask = askToDisconnect }: { service: HomeService; theme: Theme; onSignIn: (again: boolean) => void; ask?: (name: string, go: () => void) => void }) {
  const state = useStore(service.state);
  const needsAgain = state.kind === 'needs_sign_in';
  const signedOut = state.kind === 'signed_out';
  const [said, setSaid] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const actions: ServiceAction[] = state.kind === 'connected' ? (service.actions?.() ?? []) : [];
  const run = async (a: ServiceAction) => {
    setBusy(a.id);
    setSaid(null);
    try {
      setSaid(await a.run());
    } catch {
      setSaid("That didn't work.");
    } finally {
      setBusy(null);
    }
  };
  return (
    <View style={{ gap: 6 }}>
    <View style={styles.row}>
      <View style={styles.flex}>
        <Text style={{ color: theme.text, fontSize: 16 }}>{service.name}</Text>
        <Text style={{ color: needsAgain || state.kind === 'error' ? theme.error : theme.textMuted, fontSize: 13 }}>{stateText(state)}</Text>
      </View>
      {signedOut || needsAgain ? (
        <Pressable onPress={() => onSignIn(needsAgain)} accessibilityRole="button" accessibilityLabel={`${needsAgain ? 'Sign in again to' : 'Sign in to'} ${service.name}`}>
          <Text style={{ color: theme.primary, fontWeight: '600' }}>{needsAgain ? 'Sign in again' : 'Sign in'}</Text>
        </Pressable>
      ) : null}
      {!signedOut ? (
        <Pressable onPress={() => ask(service.name, () => void service.disconnect())} accessibilityRole="button" accessibilityLabel={`Disconnect ${service.name}`}>
          <Text style={{ color: theme.error, fontWeight: '600' }}>Disconnect</Text>
        </Pressable>
      ) : null}
    </View>
    {actions.map((a) => (
      <Pressable key={a.id} onPress={() => void run(a)} disabled={busy !== null} accessibilityRole="button" accessibilityLabel={a.label}>
        <Text style={{ color: theme.primary, fontWeight: '600' }}>{busy === a.id ? `${a.label}…` : a.label}</Text>
      </Pressable>
    ))}
    {said ? <Text style={{ color: theme.textMuted, fontSize: 13 }}>{said}</Text> : null}
    </View>
  );
}
function askToDisconnect(name: string, go: () => void) {
  Alert.alert(`Disconnect ${name}?`, 'Edith forgets the sign-in on this phone.', [
    { text: 'Cancel', style: 'cancel' },
    { text: 'Disconnect', style: 'destructive', onPress: go },
  ]);
}

/** Settings → Home (C25 §7, C48): the services, the Demo home (development builds), hidden devices, the command log. */
export function HomeSettings({ theme }: { theme: Theme }) {
  const { home } = useServices();
  useStore(home.version);
  const services = useStore(home.services);
  const [signingIn, setSigningIn] = useState<{ service: HomeService; again: boolean } | null>(null);
  const [showLog, setShowLog] = useState(false);
  const hidden = home.hiddenDevices();
  const log = home.commandLog();
  return (
    <View style={{ gap: 10 }}>
      {services.length > 0 ? (
        <View style={{ gap: 10 }}>
          <Text style={{ color: theme.text, fontWeight: '600' }}>Services</Text>
          {services.map((s) => (
            <ServiceRow key={s.id} service={s} theme={theme} onSignIn={(again) => setSigningIn({ service: s, again })} />
          ))}
        </View>
      ) : null}
      {signingIn && signingIn.service.pageSignIn ? (
        <PageSignInModal service={signingIn.service} theme={theme} onClose={() => setSigningIn(null)} />
      ) : signingIn ? (
        <ServiceSignIn service={signingIn.service} again={signingIn.again} theme={theme} onClose={() => setSigningIn(null)} />
      ) : null}
      {__DEV__ ? (
        <View style={styles.row}>
          <View style={styles.flex}>
            <Text style={{ color: theme.text, fontSize: 16 }}>Demo home</Text>
            <Text style={[styles.note, { color: theme.textMuted }]}>Made-up lights, locks and a thermostat to try the Home tab and chat</Text>
          </View>
          <Switch value={home.demoOn} onValueChange={(v) => home.setDemo(v)} accessibilityLabel="Demo home" />
        </View>
      ) : (
        <Text style={[styles.note, { color: theme.textMuted }]}>{services.length > 0 ? 'Connect a service above.' : 'No smart-home service is available yet. Services come in later versions.'}</Text>
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
              {new Date(e.at).toLocaleTimeString()} · {e.device} · {e.action.replace(/_/g, ' ')} · {e.result} · {e.source === 'chat' ? 'chat' : 'Home tab'}{e.service ? ` · ${e.service}` : ''}
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

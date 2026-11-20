import { Ionicons } from '@expo/vector-icons';
import { router, useFocusEffect } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, AppState, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useServices, useStore } from '../../core/services';
import { phoneUses24Hour } from '../../printer/feed/format';
import { CARD_IDS } from '../../printer/feed/prefs';
import { Connections } from '../../printer/feed/wire';
import { CONNECTING, bannerOf, chipOf, isGreyed, messageOf, showsCards, stateOf, type PrinterUi, type Tone } from '../../printer/feed/page';
import type { PrinterScreenState } from '../../printer/feed/session';
import { useTheme, type Theme } from '../theme';
import { StatusCard, type LastPrint } from './StatusCard';

/** The cards that exist so far (E4: Status; the rest come in E5 and later). They are drawn in the order of `CARD_IDS`. */
export const BUILT_CARDS: readonly string[] = ['status'];

const TITLES: Record<string, string> = { status: 'Status', camera: 'Camera', temperatures: 'Temperatures', toolhead: 'Toolhead', fans: 'Fans and light', limits: 'Limits and retraction', sensors: 'Sensors', slots: 'Filament slots', macros: 'Macros', files: 'Files', objects: 'Objects', queue: 'Queue', history: 'History', console: 'Console', mesh: 'Bed mesh', system: 'System' };

const toneColor = (tone: Tone, theme: Theme) => (tone === 'good' ? theme.primary : tone === 'bad' ? theme.error : tone === 'warn' ? '#B26A00' : theme.textMuted);

/** The Printer page without the session, so a test can draw any state (C39 §8). */
export function PrinterPage({
  ui,
  theme,
  is24Hour,
  lastPrint,
  directConnected,
  onBack,
  onTryAgain,
  onPair,
  onToggleCard,
  onDismissAlert,
}: {
  ui: PrinterUi;
  theme: Theme;
  is24Hour: boolean;
  lastPrint: LastPrint | null;
  /** The direct printer (home Wi-Fi) is connected: the "no Jarvis" page says its tile still works. */
  directConnected: boolean;
  onBack: () => void;
  onTryAgain: () => void;
  onPair: () => void;
  onToggleCard: (id: string) => void;
  onDismissAlert: (time: number) => void;
}) {
  const chip = chipOf(ui);
  const banner = bannerOf(ui);
  const message = messageOf(ui);
  const state = stateOf(ui);
  const greyed = isGreyed(ui);
  return (
    <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.background }]}>
      <View style={[styles.header, { borderBottomColor: theme.border }]}>
        <Pressable onPress={onBack} accessibilityRole="button" accessibilityLabel="Back" hitSlop={10}>
          <Ionicons name="chevron-back" size={26} color={theme.text} />
        </Pressable>
        <View style={styles.headerText}>
          <Text numberOfLines={1} style={[styles.name, { color: theme.text }]} accessibilityRole="header">
            {ui.name}
          </Text>
          {chip.text ? (
            <Text style={{ color: toneColor(chip.tone, theme), fontSize: 13 }} testID="connection-chip">
              {chip.text}
            </Text>
          ) : null}
        </View>
      </View>

      {ui.screen.kind === 'connecting' && !state ? (
        <View style={styles.center}>
          <ActivityIndicator color={theme.textMuted} />
          <Text style={{ color: theme.text, marginTop: 12 }} testID="connecting">
            {CONNECTING}
          </Text>
        </View>
      ) : message !== null ? (
        <View style={styles.center}>
          <Text style={[styles.message, { color: theme.text }]} testID="printer-message">
            {message}
          </Text>
          {ui.screen.kind === 'noJarvis' ? (
            <>
              <Pressable onPress={onPair} accessibilityRole="button" accessibilityLabel="Pair with Jarvis" style={[styles.button, { backgroundColor: theme.primary }]}>
                <Text style={{ color: theme.onPrimary, fontWeight: '600' }}>Pair with Jarvis</Text>
              </Pressable>
              {directConnected ? <Text style={{ color: theme.textMuted, textAlign: 'center' }}>At home, the printer tile on Home still works.</Text> : null}
            </>
          ) : null}
          {ui.screen.kind === 'away' ? (
            <Pressable onPress={onTryAgain} accessibilityRole="button" accessibilityLabel="Try again" style={[styles.button, { backgroundColor: theme.primary }]}>
              <Text style={{ color: theme.onPrimary, fontWeight: '600' }}>Try again</Text>
            </Pressable>
          ) : null}
        </View>
      ) : state && showsCards(ui) ? (
        <ScrollView contentContainerStyle={[styles.list, greyed && { opacity: 0.6 }]} testID="printer-list">
          {banner ? (
            <View style={[styles.banner, { backgroundColor: theme.surface, borderColor: theme.error }]} testID="banner">
              <Ionicons name="alert-circle-outline" size={18} color={theme.error} />
              <Text style={{ color: theme.text, flex: 1 }}>{banner}</Text>
              {ui.screen.kind === 'away' ? (
                <Pressable onPress={onTryAgain} accessibilityRole="button" accessibilityLabel="Try again" hitSlop={8}>
                  <Text style={{ color: theme.primary, fontWeight: '600' }}>Try again</Text>
                </Pressable>
              ) : null}
            </View>
          ) : null}
          {CARD_IDS.filter((id) => BUILT_CARDS.includes(id)).map((id) => {
            const open = !ui.collapsed.has(id);
            return (
              <View key={id} style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]} testID={`card-${id}`}>
                <Pressable onPress={() => onToggleCard(id)} accessibilityRole="button" accessibilityLabel={`${open ? 'Fold' : 'Open'} ${TITLES[id]}`} style={styles.cardHead}>
                  <Text style={[styles.cardTitle, { color: theme.text }]}>{TITLES[id]}</Text>
                  <Ionicons name={open ? 'chevron-up' : 'chevron-down'} size={20} color={theme.textMuted} />
                </Pressable>
                {open && id === 'status' ? <StatusCard ui={ui} theme={theme} is24Hour={is24Hour} lastPrint={lastPrint} onDismissAlert={onDismissAlert} /> : null}
              </View>
            );
          })}
        </ScrollView>
      ) : (
        <View style={styles.center}>
          <ActivityIndicator color={theme.textMuted} />
        </View>
      )}
    </SafeAreaView>
  );
}

/** The Printer page (C39 §8, C67 E4): a stack screen over the Home tab. It asks Jarvis only while it is on screen and Edith is in front. */
export function PrinterScreen() {
  const theme = useTheme();
  const { printerSession: session, printerPrefs: prefs, home } = useServices();
  const screen = useStore(session.state);
  const shared = useStore(session.shared);
  const collapsed = useStore(prefs.collapsed);
  const [dismissed, setDismissed] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const [lastPrint, setLastPrint] = useState<LastPrint | null>(null);

  // The page is on screen (focused) and Edith is in front: ask Jarvis. Otherwise stop, and turn Controls off.
  useFocusEffect(
    useCallback(() => {
      let focused = true;
      const start = () => {
        setDismissed(null);
        setNow(Date.now());
        session.attach();
      };
      start();
      const sub = AppState.addEventListener('change', (s) => {
        if (!focused) return;
        if (s === 'active') start();
        else session.detach();
      });
      const tick = setInterval(() => setNow(Date.now()), 30_000);
      return () => {
        focused = false;
        sub.remove();
        clearInterval(tick);
        session.detach();
      };
    }, [session]),
  );

  // "Last print" is read once, only while the printer is idle and the page is showing it.
  const idle = screen.kind === 'live' && screen.data.state.connection === Connections.ready && screen.data.state.print.state === 'standby';
  useEffect(() => {
    if (!idle || lastPrint) return undefined;
    let live = true;
    void session
      .routes()
      ?.history(0)
      .then((r) => {
        if (!live || r.kind !== 'ok') return;
        const job = r.value.jobs[0];
        if (job) setLastPrint({ title: job.title || job.filename, status: job.status, at: job.end ?? job.start });
      });
    return () => {
      live = false;
    };
  }, [idle, lastPrint, session]);

  const ui: PrinterUi = { screen: screen as PrinterScreenState, name: shared?.name?.trim() ? shared.name : 'Printer', collapsed, dismissedAlert: dismissed, now };
  const directConnected = home.services.get().some((s) => s.id === 'printer' && s.state.get().kind === 'connected');
  return (
    <PrinterPage
      ui={ui}
      theme={theme}
      is24Hour={phoneUses24Hour()}
      lastPrint={lastPrint}
      directConnected={directConnected}
      onBack={() => router.back()}
      onTryAgain={() => session.attach()}
      onPair={() => router.navigate('/settings')}
      onToggleCard={(id) => void prefs.setCollapsed(id, !collapsed.has(id))}
      onDismissAlert={setDismissed}
    />
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  header: { flexDirection: 'row', alignItems: 'center', gap: 8, paddingHorizontal: 12, paddingVertical: 10, borderBottomWidth: StyleSheet.hairlineWidth },
  headerText: { flex: 1 },
  name: { fontSize: 18, fontWeight: '700' },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 32, gap: 16 },
  message: { fontSize: 16, textAlign: 'center' },
  button: { paddingHorizontal: 18, paddingVertical: 10, borderRadius: 10 },
  list: { padding: 12, gap: 10 },
  banner: { flexDirection: 'row', alignItems: 'center', gap: 8, borderWidth: 1, borderRadius: 10, padding: 12 },
  card: { borderWidth: StyleSheet.hairlineWidth, borderRadius: 12, padding: 12, gap: 10 },
  cardHead: { flexDirection: 'row', alignItems: 'center' },
  cardTitle: { flex: 1, fontSize: 15, fontWeight: '600' },
});

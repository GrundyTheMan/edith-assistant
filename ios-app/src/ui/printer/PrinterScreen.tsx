import { Ionicons } from '@expo/vector-icons';
import { router, useFocusEffect } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, AppState, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useServices, useStore } from '../../core/services';
import type { CardsUi } from '../../printer/feed/cards';
import type { ControlUi } from '../../printer/feed/control';
import { phoneUses24Hour } from '../../printer/feed/format';
import { CARD_IDS } from '../../printer/feed/prefs';
import { Connections } from '../../printer/feed/wire';
import { CONNECTING, bannerOf, chipOf, isGreyed, messageOf, showsCards, stateOf, type PrinterUi, type Tone } from '../../printer/feed/page';
import type { PrinterScreenState } from '../../printer/feed/session';
import type { PrinterState } from '../../printer/feed/wire';
import { useTheme, type Theme } from '../theme';
import { CameraCard, ConsoleCard, FansCard, FilesCard, HistoryCard, LimitsCard, MeshCard, ObjectsCard, QueueCard, SensorsCard, SlotsCard, SystemCard, TemperatureCard, ToolheadCard, type CardActions } from './Cards';
import { FirmwareRestartButton, type ControlsCtx } from './CardControls';
import { MacrosCard } from './MacrosAndConsole';
import { ConfirmSheet, ControlMessage, ControlsBar, EmergencyStop, type ControlActions } from './ControlViews';
import { StatusCard, type LastPrint } from './StatusCard';

/** The cards that exist, drawn in the order of `CARD_IDS` (a card with nothing to show is left out: see `cardShown`). */
export const BUILT_CARDS: readonly string[] = CARD_IDS;

const TITLES: Record<string, string> = { status: 'Status', camera: 'Camera', temperatures: 'Temperatures', toolhead: 'Toolhead', fans: 'Fans and light', limits: 'Limits and retraction', sensors: 'Sensors', slots: 'Filament slots', macros: 'Macros', files: 'Files', objects: 'Objects', queue: 'Queue', history: 'History', console: 'Console', mesh: 'Bed mesh', system: 'System' };

const toneColor = (tone: Tone, theme: Theme) => (tone === 'good' ? theme.primary : tone === 'bad' ? theme.error : tone === 'warn' ? '#B26A00' : theme.textMuted);

/** A card with nothing to show is left out: no queue, no mesh. */
function cardShown(id: string, state: PrinterState, mayControl: boolean): boolean {
  // Every macro button is a control: a look-only phone has no Macros card at all.
  if (id === 'macros') return mayControl;
  if (id === 'queue') return state.queue !== null;
  if (id === 'mesh') return state.mesh !== null;
  return true;
}

function CardBody({ id, ui, state, theme, is24Hour, lastPrint, cards, actions, controls, controlActions, onDismissAlert }: { id: string; ui: PrinterUi; state: PrinterState; theme: Theme; is24Hour: boolean; lastPrint: LastPrint | null; cards: CardsUi; actions: CardActions; controls: ControlUi; controlActions: ControlActions; onDismissAlert: (time: number) => void }) {
  const ctx: ControlsCtx | undefined = controls.mayControl ? { ui, controls, send: controlActions.send, theme } : undefined;
  switch (id) {
    case 'status':
      return <StatusCard ui={ui} theme={theme} is24Hour={is24Hour} lastPrint={lastPrint} onDismissAlert={onDismissAlert} controls={controls} onSend={controlActions.send} />;
    case 'camera':
      return <CameraCard state={state} camera={cards.camera} actions={actions} theme={theme} ctx={ctx} />;
    case 'temperatures':
      return <TemperatureCard state={state} cards={cards} actions={actions} theme={theme} ctx={ctx} />;
    case 'toolhead':
      return <ToolheadCard state={state} theme={theme} ctx={ctx} />;
    case 'fans':
      return <FansCard state={state} theme={theme} ctx={ctx} />;
    case 'limits':
      return <LimitsCard state={state} theme={theme} ctx={ctx} />;
    case 'sensors':
      return <SensorsCard state={state} theme={theme} ctx={ctx} />;
    case 'slots':
      return <SlotsCard state={state} theme={theme} />;
    case 'files':
      return <FilesCard state={state} cards={cards} now={ui.now} actions={actions} theme={theme} ctx={ctx} />;
    case 'objects':
      return <ObjectsCard state={state} theme={theme} ctx={ctx} />;
    case 'queue':
      return <QueueCard state={state} now={ui.now} theme={theme} ctx={ctx} />;
    case 'history':
      return <HistoryCard cards={cards} now={ui.now} actions={actions} theme={theme} />;
    case 'console':
      return <ConsoleCard cards={cards} actions={actions} theme={theme} ctx={ctx} />;
    case 'macros':
      return ctx ? <MacrosCard macros={cards.macros} ctx={ctx} onQuery={actions.setMacroQuery} onHidden={actions.setHiddenMacros} /> : null;
    case 'mesh':
      return state.mesh ? <MeshCard mesh={state.mesh} theme={theme} /> : null;
    case 'system':
      return <SystemCard state={state} theme={theme} ctx={ctx} />;
    default:
      return null;
  }
}

/** The Printer page without the session, so a test can draw any state (C39 §8). */
export function PrinterPage({
  ui,
  theme,
  is24Hour,
  lastPrint,
  cards,
  actions,
  controls,
  controlActions,
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
  cards: CardsUi;
  actions: CardActions;
  controls: ControlUi;
  controlActions: ControlActions;
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
        <EmergencyStop ui={ui} state={state} theme={theme} onPress={() => controlActions.send({ kind: 'estop' })} />
      </View>
      <ControlsBar ui={ui} c={controls} theme={theme} onToggle={controlActions.setControls} />

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
        <ScrollView contentContainerStyle={[styles.list, greyed && { opacity: 0.6 }, controls.armed && { borderWidth: 3, borderColor: theme.primary }]} testID="printer-list">
          {banner ? (
            <View style={[styles.banner, { backgroundColor: theme.surface, borderColor: theme.error }]} testID="banner">
              <Ionicons name="alert-circle-outline" size={18} color={theme.error} />
              <Text style={{ color: theme.text, flex: 1 }}>{banner}</Text>
              {controls.mayControl && state && (state.connection === Connections.klippyShutdown || state.connection === Connections.klippyError) ? (
                <FirmwareRestartButton ctx={{ ui, controls, send: controlActions.send, theme }} />
              ) : null}
              {ui.screen.kind === 'away' ? (
                <Pressable onPress={onTryAgain} accessibilityRole="button" accessibilityLabel="Try again" hitSlop={8}>
                  <Text style={{ color: theme.primary, fontWeight: '600' }}>Try again</Text>
                </Pressable>
              ) : null}
            </View>
          ) : null}
          {CARD_IDS.filter((id) => BUILT_CARDS.includes(id) && cardShown(id, state, controls.mayControl)).map((id) => {
            const open = !ui.collapsed.has(id);
            return (
              <View key={id} style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]} testID={`card-${id}`}>
                <Pressable onPress={() => onToggleCard(id)} accessibilityRole="button" accessibilityLabel={`${open ? 'Fold' : 'Open'} ${TITLES[id]}`} style={styles.cardHead}>
                  <Text style={[styles.cardTitle, { color: theme.text }]}>{TITLES[id]}</Text>
                  <Ionicons name={open ? 'chevron-up' : 'chevron-down'} size={20} color={theme.textMuted} />
                </Pressable>
                {open ? <CardBody id={id} ui={ui} state={state} theme={theme} is24Hour={is24Hour} lastPrint={lastPrint} cards={cards} actions={actions} controls={controls} controlActions={controlActions} onDismissAlert={onDismissAlert} /> : null}
              </View>
            );
          })}
        </ScrollView>
      ) : (
        <View style={styles.center}>
          <ActivityIndicator color={theme.textMuted} />
        </View>
      )}
      <ConfirmSheet c={controls} theme={theme} onConfirm={controlActions.confirm} onDismiss={controlActions.dismissSheet} />
      <ControlMessage c={controls} theme={theme} onShown={controlActions.messageShown} />
    </SafeAreaView>
  );
}

/** The Printer page (C39 §8, C67 E4): a stack screen over the Home tab. It asks Jarvis only while it is on screen and Edith is in front. */
export function PrinterScreen() {
  const theme = useTheme();
  const { printerSession: session, printerPrefs: prefs, printerCards: data, printerControl: control, home } = useServices();
  const screen = useStore(session.state);
  const shared = useStore(session.shared);
  const collapsed = useStore(prefs.collapsed);
  const cards = useStore(data.ui);
  const controls = useStore(control.ui);
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
        data.attached();
      };
      start();
      const sub = AppState.addEventListener('change', (s) => {
        if (!focused) return;
        if (s === 'active') start();
        else {
          session.detach();
          data.detached();
          control.detached();
        }
      });
      const tick = setInterval(() => setNow(Date.now()), 30_000);
      return () => {
        focused = false;
        sub.remove();
        clearInterval(tick);
        session.detach();
        data.detached();
        control.detached();
      };
    }, [session, data, control]),
  );

  // Which cards are open is told to the card data, which reads only what is on screen (the camera runs only while its card is open).
  const showing = screen.kind === 'live' || screen.kind === 'away';
  useEffect(() => {
    for (const id of BUILT_CARDS) data.visible(id, showing && !collapsed.has(id));
  }, [data, collapsed, showing]);
  // Jarvis became reachable after the cards were opened: they load now.
  const isLive = screen.kind === 'live';
  useEffect(() => {
    if (isLive) data.attached();
  }, [isLive, data]);

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

  const ui: PrinterUi = { screen: screen as PrinterScreenState, name: shared?.name?.trim() ? shared.name : 'Printer', collapsed, dismissedAlert: dismissed, now, presets: shared?.presets ?? [] };
  const directConnected = home.services.get().some((s) => s.id === 'printer' && s.state.get().kind === 'connected');
  return (
    <PrinterPage
      ui={ui}
      theme={theme}
      is24Hour={phoneUses24Hour()}
      lastPrint={lastPrint}
      cards={cards}
      actions={data}
      controls={controls}
      controlActions={control}
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

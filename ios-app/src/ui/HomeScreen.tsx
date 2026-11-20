import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect } from 'expo-router';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Pressable, RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useServices, useStore } from '../core/services';
import { ageWords, buildCommand, confirmationFor, movedWords, stateWords } from '../home/actions';
import type { Home } from '../home/home';
import type { DeviceCommand, HomeDevice } from '../home/model';
import { ImageViewer } from './AttachmentViews';
import { CatchUpBanner } from './CatchUpBanner';
import { ClipsScreen } from './ClipsScreen';
import { PrinterCard } from './PrinterCard';
import { useNow } from './useNow';
import { useTheme, type Theme } from './theme';

export const CHIPS = ['favorites', 'lights', 'plugs', 'fans', 'climate', 'locks', 'printer', 'all'] as const;
export type Chip = (typeof CHIPS)[number];
export const CHIP_LABELS: Record<Chip, string> = {
  favorites: 'Favorites',
  lights: 'Lights',
  plugs: 'Plugs and switches',
  fans: 'Fans',
  climate: 'Climate',
  locks: 'Locks and doors',
  printer: 'Printer',
  all: 'All',
};

/** Which devices a chip shows (C25 §5). */
export function inChip(d: HomeDevice, chip: Chip, isFavourite: boolean): boolean {
  switch (chip) {
    case 'favorites':
      return isFavourite;
    case 'lights':
      return d.type === 'light';
    case 'plugs':
      return d.type === 'plug' || d.type === 'switch';
    case 'fans':
      return d.type === 'fan';
    case 'climate':
      return d.type === 'thermostat';
    case 'locks':
      return d.type === 'lock' || d.type === 'garage_door';
    case 'printer':
      return d.type === 'printer';
    case 'all':
      return true;
  }
}
const isSecure = (d: HomeDevice) => d.type === 'lock' || d.type === 'garage_door';
const toggles = (d: HomeDevice) => d.capabilities.includes('on_off');

/** Sends a command from the screen; unlock, open, close and mode changes ask on a dialog first (the same rule as chat). */
export function runFromScreen(home: Home, device: HomeDevice, command: DeviceCommand, ask: (title: string, ok: string, go: () => void) => void = alertAsk) {
  const spec = confirmationFor(device, command);
  const go = () => void home.command(device, command, 'home');
  if (spec) ask(spec.title, spec.confirmLabel ?? 'Confirm', go);
  else go();
}
function alertAsk(title: string, ok: string, go: () => void) {
  Alert.alert(title, undefined, [
    { text: 'Cancel', style: 'cancel' },
    { text: ok, onPress: go },
  ]);
}

/** The Home tab (C25 §5): chips, tiles by room, controls under a tile's name, locks in their own section. */
export function HomeScreen() {
  const theme = useTheme();
  const { home, alerts } = useServices();
  useStore(home.version);
  // A tapped alert asks for the Clips list (C72): open it when the request count goes up, not for the count it already had.
  const clipsAsked = useStore(home.clipsRequest);
  const seenAsk = useRef(clipsAsked);
  const now = useNow();
  const [refreshing, setRefreshing] = useState(false);
  const [open, setOpen] = useState<string | null>(null);
  const chip = (CHIPS as readonly string[]).includes(home.selectedChip) ? (home.selectedChip as Chip) : 'favorites';
  const loadedOnce = useRef('');
  const [photo, setPhoto] = useState<string | null>(null);
  const [cameraError, setCameraError] = useState<string | null>(null);
  const [clipsOpen, setClipsOpen] = useState(false);
  useEffect(() => {
    if (clipsAsked !== seenAsk.current) {
      seenAsk.current = clipsAsked;
      setClipsOpen(true);
    }
  }, [clipsAsked]);

  const refresh = useCallback(async () => {
    setRefreshing(true);
    try {
      await home.refresh();
    } finally {
      setRefreshing(false);
    }
  }, [home]);
  // Read when the first service connects, and again whenever a different set of services is being read.
  const backendKey = home.backendKey();
  useEffect(() => {
    if (backendKey && loadedOnce.current !== backendKey) {
      loadedOnce.current = backendKey;
      void refresh();
    }
  }, [backendKey, refresh]);

  // While a print is going and this tab is showing, read the printer again every 5 s (C55); stop when the tab or the app isn't in front.
  const printing = home.visible().some((d) => d.type === 'printer' && d.state.print?.busy);
  useFocusEffect(
    useCallback(() => {
      if (!printing) return undefined;
      const t = setInterval(() => void home.refresh(), 5_000);
      return () => clearInterval(t);
    }, [printing, home]),
  );
  const camera = async (d: HomeDevice) => {
    setCameraError(null);
    try {
      setPhoto((await home.snapshot(d)).jpegBase64);
    } catch (e) {
      setCameraError(e instanceof Error ? e.message : "Couldn't get a picture.");
    }
  };

  const devices = home.visible();
  const shown = useMemo(() => devices.filter((d) => inChip(d, chip, home.isFavourite(d.id))), [devices, chip, home]);
  const locks = shown.filter(isSecure);
  const rest = shown.filter((d) => !isSecure(d));
  const rooms = [...new Set(rest.map((d) => d.room || 'No room'))].sort();

  return (
    <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.background }]}>
      <Text style={[styles.title, { color: theme.text }]}>Home</Text>
      {alerts ? <CatchUpBanner store={alerts.loop.catchUp} onOpen={() => setClipsOpen(true)} onDismiss={() => alerts.loop.dismissCatchUp()} theme={theme} /> : null}
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chips} style={styles.chipBar}>
        {CHIPS.map((c) => (
          <Pressable
            key={c}
            onPress={() => home.setChip(c)}
            accessibilityRole="button"
            accessibilityState={{ selected: c === chip }}
            style={[styles.chip, { borderColor: theme.border, backgroundColor: c === chip ? theme.primary : theme.surface }]}
          >
            <Text style={{ color: c === chip ? theme.onPrimary : theme.text }}>{CHIP_LABELS[c]}</Text>
          </Pressable>
        ))}
        {home.hasClips ? (
          <Pressable onPress={() => setClipsOpen(true)} accessibilityRole="button" accessibilityLabel="Clips" style={[styles.chip, { borderColor: theme.border, backgroundColor: theme.surface }]}>
            <Text style={{ color: theme.text }}>Clips</Text>
          </Pressable>
        ) : null}
      </ScrollView>
      <ScrollView
        contentContainerStyle={styles.body}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void refresh()} />}
        keyboardShouldPersistTaps="handled"
      >
        {home.problems.map((p) => (
          <View key={p} style={[styles.banner, { backgroundColor: theme.surface, borderColor: theme.error }]}>
            <Ionicons name="alert-circle-outline" size={18} color={theme.error} />
            <Text style={{ color: theme.text, flex: 1 }}>{p}</Text>
          </View>
        ))}
        {!home.connected ? (
          <Text style={[styles.empty, { color: theme.textMuted }]}>
            No smart-home service is connected yet. In development builds, Settings → Home has a Demo home to try this tab.
          </Text>
        ) : shown.length === 0 ? (
          <Text style={[styles.empty, { color: theme.textMuted }]}>
            {chip === 'favorites' ? 'No favorites yet. Open a device and tap the star, or pick All.' : 'Nothing here.'}
          </Text>
        ) : null}
        {locks.length > 0 ? (
          <View style={styles.section}>
            <Text style={[styles.room, { color: theme.textMuted }]}>Locks and doors</Text>
            {locks.map((d) => (
              <SecureTile key={d.id} d={d} home={home} theme={theme} now={now} />
            ))}
          </View>
        ) : null}
        {rooms.map((room) => (
          <View key={room} style={styles.section}>
            <Text style={[styles.room, { color: theme.textMuted }]}>{room}</Text>
            {rest
              .filter((d) => (d.room || 'No room') === room)
              .map((d) =>
                d.type === 'printer' ? (
                  <PrinterCard
                    key={d.id}
                    d={d}
                    theme={theme}
                    now={now}
                    onCommand={(type) => runFromScreen(home, d, { type })}
                    onCamera={() => void camera(d)}
                    cameraError={cameraError}
                  />
                ) : (
                  <Tile key={d.id} d={d} home={home} theme={theme} now={now} expanded={open === d.id} onOpen={() => setOpen(open === d.id ? null : d.id)} />
                ),
              )}
          </View>
        ))}
      </ScrollView>
      <ImageViewer base64={photo} onClose={() => setPhoto(null)} />
      {home.hasClips || clipsOpen ? <ClipsScreen visible={clipsOpen} onClose={() => setClipsOpen(false)} home={home} theme={theme} /> : null}
    </SafeAreaView>
  );
}

function Tile({ d, home, theme, now, expanded, onOpen }: { d: HomeDevice; home: Home; theme: Theme; now: number; expanded: boolean; onOpen: () => void }) {
  const on = d.state.on === true;
  const age = ageWords(d.stateAt, now);
  return (
    <View style={[styles.tile, { backgroundColor: theme.surface, borderColor: on ? theme.primary : theme.border }]}>
      <View style={styles.tileRow}>
        <Pressable onPress={onOpen} style={styles.tileText} accessibilityRole="button" accessibilityLabel={`${d.name} controls`}>
          <Text style={[styles.name, { color: theme.text }]}>{d.name}</Text>
          <Text style={{ color: d.online ? theme.textMuted : theme.error, fontSize: 13 }}>
            {stateWords(d)}
            {d.online && age ? ` · ${age}` : ''}
            {d.online && movedWords(d.state.changedAt, now) ? ` · ${movedWords(d.state.changedAt, now)}` : ''}
          </Text>
        </Pressable>
        {toggles(d) && d.online ? (
          <Pressable
            onPress={() => runFromScreen(home, d, { type: on ? 'turn_off' : 'turn_on' })}
            accessibilityRole="switch"
            accessibilityLabel={`Turn ${d.name} ${on ? 'off' : 'on'}`}
            accessibilityState={{ checked: on }}
            style={[styles.toggle, { backgroundColor: on ? theme.primary : theme.border }]}
          >
            <Ionicons name="power" size={18} color={on ? theme.onPrimary : theme.textMuted} />
          </Pressable>
        ) : null}
      </View>
      {expanded ? <Controls d={d} home={home} theme={theme} /> : null}
    </View>
  );
}

const BRIGHTNESS = [10, 25, 50, 75, 100];
const FAN = [
  ['Low', 33],
  ['Medium', 66],
  ['High', 100],
] as const;
const WHITES = ['warm', 'neutral', 'cool'];

function Controls({ d, home, theme }: { d: HomeDevice; home: Home; theme: Theme }) {
  const send = (c: DeviceCommand) => runFromScreen(home, d, c);
  const chipStyle = [styles.small, { borderColor: theme.border }];
  const [pendingSetpoint, setPendingSetpoint] = useState<number | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => void (timer.current && clearTimeout(timer.current)), []);
  const step = (delta: number) => {
    const base = pendingSetpoint ?? d.state.setpoint ?? 70;
    const next = base + delta;
    const built = buildCommand(d, 'set_temperature', String(next));
    if (!built.ok) return;
    setPendingSetpoint(next);
    if (timer.current) clearTimeout(timer.current);
    // Sent 1.2 s after the last tap (C25 §5).
    timer.current = setTimeout(() => {
      setPendingSetpoint(null);
      send(built.command);
    }, 1200);
  };
  return (
    <View style={styles.controls}>
      {d.capabilities.includes('brightness') ? (
        <View style={styles.controlRow}>
          {BRIGHTNESS.map((b) => (
            <Pressable key={b} onPress={() => send({ type: 'set_brightness', value: b })} style={chipStyle} accessibilityRole="button" accessibilityLabel={`Brightness ${b}%`}>
              <Text style={{ color: theme.text }}>{b}%</Text>
            </Pressable>
          ))}
        </View>
      ) : null}
      {d.capabilities.includes('white') ? (
        <View style={styles.controlRow}>
          {WHITES.map((w) => (
            <Pressable key={w} onPress={() => send({ type: 'set_white', value: w })} style={chipStyle} accessibilityRole="button" accessibilityLabel={`${w} white`}>
              <Text style={{ color: theme.text }}>{w[0].toUpperCase() + w.slice(1)}</Text>
            </Pressable>
          ))}
        </View>
      ) : null}
      {d.capabilities.includes('percentage') ? (
        <View style={styles.controlRow}>
          {FAN.map(([label, v]) => (
            <Pressable key={label} onPress={() => send({ type: 'set_percentage', value: v })} style={chipStyle} accessibilityRole="button" accessibilityLabel={`Fan ${label}`}>
              <Text style={{ color: theme.text }}>{label}</Text>
            </Pressable>
          ))}
        </View>
      ) : null}
      {d.capabilities.includes('thermostat') ? (
        <>
          <View style={[styles.controlRow, { alignItems: 'center' }]}>
            <Pressable onPress={() => step(-1)} style={chipStyle} accessibilityRole="button" accessibilityLabel="Lower the temperature">
              <Ionicons name="remove" size={18} color={theme.text} />
            </Pressable>
            <Text style={{ color: theme.text, fontSize: 18, minWidth: 64, textAlign: 'center' }}>
              {pendingSetpoint ?? d.state.setpoint ?? '–'}°{d.unit ?? 'F'}
            </Text>
            <Pressable onPress={() => step(1)} style={chipStyle} accessibilityRole="button" accessibilityLabel="Raise the temperature">
              <Ionicons name="add" size={18} color={theme.text} />
            </Pressable>
          </View>
          <View style={styles.controlRow}>
            {(d.modes ?? []).map((m) => (
              <Pressable
                key={m}
                onPress={() => send({ type: 'set_mode', value: m })}
                style={[chipStyle, d.state.mode === m && { backgroundColor: theme.primary }]}
                accessibilityRole="button"
                accessibilityLabel={`Mode ${m}`}
              >
                <Text style={{ color: d.state.mode === m ? theme.onPrimary : theme.text }}>{m[0].toUpperCase() + m.slice(1)}</Text>
              </Pressable>
            ))}
          </View>
        </>
      ) : null}
      <View style={styles.controlRow}>
        <Pressable onPress={() => home.toggleFavourite(d.id)} style={chipStyle} accessibilityRole="button" accessibilityLabel={home.isFavourite(d.id) ? 'Remove from favorites' : 'Add to favorites'}>
          <Ionicons name={home.isFavourite(d.id) ? 'star' : 'star-outline'} size={16} color={theme.text} />
        </Pressable>
        <Pressable onPress={() => home.setHidden(d.id, true)} style={chipStyle} accessibilityRole="button" accessibilityLabel={`Hide ${d.name}`}>
          <Text style={{ color: theme.text }}>Hide</Text>
        </Pressable>
      </View>
    </View>
  );
}

/** Locks and garage doors: explicit buttons, and Unlock / Open ask first. */
function SecureTile({ d, home, theme, now }: { d: HomeDevice; home: Home; theme: Theme; now: number }) {
  const age = ageWords(d.stateAt, now);
  const buttons: [string, DeviceCommand][] =
    d.type === 'lock'
      ? [
          ['Lock', { type: 'lock' }],
          ['Unlock', { type: 'unlock' }],
        ]
      : [
          ['Open', { type: 'open' }],
          ['Close', { type: 'close' }],
        ];
  return (
    <View style={[styles.tile, { backgroundColor: theme.surface, borderColor: theme.border }]}>
      <View style={styles.tileRow}>
        <View style={styles.tileText}>
          <Text style={[styles.name, { color: theme.text }]}>{d.name}</Text>
          <Text style={{ color: d.online ? theme.textMuted : theme.error, fontSize: 13 }}>
            {stateWords(d)}
            {d.online && age ? ` · ${age}` : ''}
            {d.online && movedWords(d.state.changedAt, now) ? ` · ${movedWords(d.state.changedAt, now)}` : ''}
          </Text>
        </View>
        {d.online && d.capabilities.includes(d.type === 'lock' ? 'lock_unlock' : 'open_close')
          ? buttons.map(([label, command]) => (
              <Pressable key={label} onPress={() => runFromScreen(home, d, command)} style={[styles.small, { borderColor: theme.border }]} accessibilityRole="button" accessibilityLabel={`${label} ${d.name}`}>
                <Text style={{ color: theme.text }}>{label}</Text>
              </Pressable>
            ))
          : null}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  title: { fontSize: 28, fontWeight: '700', paddingHorizontal: 16, paddingTop: 8 },
  chipBar: { flexGrow: 0 },
  chips: { gap: 8, paddingHorizontal: 16, paddingVertical: 10 },
  chip: { paddingHorizontal: 14, paddingVertical: 8, borderRadius: 18, borderWidth: StyleSheet.hairlineWidth },
  body: { padding: 16, gap: 12 },
  section: { gap: 8 },
  room: { fontSize: 13, fontWeight: '600', textTransform: 'uppercase', letterSpacing: 0.5 },
  tile: { borderRadius: 14, borderWidth: 1, padding: 12, gap: 8 },
  tileRow: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  tileText: { flex: 1, gap: 2 },
  name: { fontSize: 16, fontWeight: '600' },
  toggle: { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center' },
  controls: { gap: 8, paddingTop: 4 },
  controlRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  small: { paddingHorizontal: 12, paddingVertical: 8, borderRadius: 14, borderWidth: 1, minWidth: 40, alignItems: 'center' },
  banner: { flexDirection: 'row', gap: 8, alignItems: 'center', padding: 10, borderRadius: 10, borderWidth: 1 },
  empty: { fontSize: 15, textAlign: 'center', paddingVertical: 24 },
});

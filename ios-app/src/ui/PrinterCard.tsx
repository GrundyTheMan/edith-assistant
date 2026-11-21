import { Pressable, StyleSheet, Text, View } from 'react-native';
import { formatDuration } from '../printer/parse';
import { ageWords, printerWords } from '../home/actions';
import type { HomeDevice } from '../home/model';
import type { Theme } from './theme';

/**
 * The 3D printer's card on the Home tab (C55, from C34): state, the file, a progress bar, time left, the layer, the temperatures,
 * and Pause / Resume / Cancel print / Camera. Away from home it says so, keeps the last report, and shows no error.
 */
export function PrinterCard({
  d,
  theme,
  now,
  onCommand,
  onCamera,
  cameraError,
  onDetails,
  detailsLabel = 'Details',
}: {
  d: HomeDevice;
  theme: Theme;
  now: number;
  onCommand: (type: 'pause' | 'resume' | 'cancel_print') => void;
  onCamera: () => void;
  cameraError?: string | null;
  /** Opens the Printer page (C67). Always shown: with no Jarvis paired it is "Printer page" and the page explains. */
  onDetails?: () => void;
  detailsLabel?: string;
}) {
  const p = d.state.print;
  const heat = d.state.heaters;
  const printing = p?.state === 'printing';
  const paused = p?.state === 'paused';
  const live = d.online && p && !p.klipper;
  const age = ageWords(d.stateAt, now);
  const button = (label: string, onPress: () => void, danger = false) => (
    <Pressable
      key={label}
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={label}
      style={[styles.button, { borderColor: danger ? theme.error : theme.border }]}
    >
      <Text style={{ color: danger ? theme.error : theme.text }}>{label}</Text>
    </Pressable>
  );
  return (
    <View style={[styles.card, { backgroundColor: theme.surface, borderColor: printing ? theme.primary : theme.border }]} accessibilityLabel="3D printer">
      <Text style={[styles.name, { color: theme.text }]}>{d.name}</Text>
      {!d.online ? <Text style={{ color: theme.textMuted }}>Not reachable. It works on your home Wi-Fi.</Text> : null}
      <Text style={{ color: p?.klipper || p?.state === 'error' ? theme.error : theme.textMuted, fontSize: 14 }}>
        {printerWords({ ...d, online: true })}
        {age && !d.online ? ` (last seen ${age})` : ''}
      </Text>
      {p?.viaJarvis ? (
        <Text testID="printer-via-jarvis" style={{ color: theme.textMuted, fontSize: 12 }}>
          via Jarvis
        </Text>
      ) : null}
      {p && (printing || paused) ? (
        <View style={{ gap: 4 }}>
          <View style={[styles.track, { backgroundColor: theme.border }]}>
            <View style={[styles.fill, { backgroundColor: theme.primary, width: `${Math.round(p.progress * 100)}%` }]} />
          </View>
          <Text style={{ color: theme.textMuted, fontSize: 13 }}>
            {Math.round(p.progress * 100)}%
            {p.timeLeftSec !== null ? ` · about ${formatDuration(p.timeLeftSec)} left` : ''}
            {p.layer ? ` · layer ${p.layer.current}/${p.layer.total}` : ''}
          </Text>
        </View>
      ) : null}
      {heat?.nozzle || heat?.bed ? (
        <Text style={{ color: theme.textMuted, fontSize: 13 }}>
          {heat.nozzle ? `Nozzle ${Math.round(heat.nozzle.temperature)}°C${heat.nozzle.target ? ` / ${Math.round(heat.nozzle.target)}°C` : ''}` : ''}
          {heat.nozzle && heat.bed ? ' · ' : ''}
          {heat.bed ? `Bed ${Math.round(heat.bed.temperature)}°C${heat.bed.target ? ` / ${Math.round(heat.bed.target)}°C` : ''}` : ''}
        </Text>
      ) : null}
      {cameraError ? <Text style={{ color: theme.error, fontSize: 13 }}>{cameraError}</Text> : null}
      <View style={styles.buttons}>
        {live && printing ? button('Pause', () => onCommand('pause')) : null}
        {live && paused ? button('Resume', () => onCommand('resume')) : null}
        {live && (printing || paused) ? button('Cancel print', () => onCommand('cancel_print'), true) : null}
        {d.capabilities.includes('snapshot') ? button('Camera', onCamera) : null}
        {onDetails ? button(detailsLabel, onDetails) : null}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  card: { borderRadius: 14, borderWidth: 1, padding: 12, gap: 8 },
  name: { fontSize: 16, fontWeight: '600' },
  track: { height: 8, borderRadius: 4, overflow: 'hidden' },
  fill: { height: 8, borderRadius: 4 },
  buttons: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  button: { paddingHorizontal: 14, paddingVertical: 8, borderRadius: 14, borderWidth: 1 },
});

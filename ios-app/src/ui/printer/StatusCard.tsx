import { Pressable, StyleSheet, Text, View } from 'react-native';
import * as format from '../../printer/feed/format';
import { alertOf, stateOf, type PrinterUi } from '../../printer/feed/page';
import { PrinterStates, type PrintInfo } from '../../printer/feed/wire';
import type { ControlUi } from '../../printer/feed/control';
import type { PrinterCommand } from '../../printer/feed/commands';
import type { Theme } from '../theme';
import { JobButtons } from './ControlViews';

/** What the print state is called on the chip. A state from a newer Jarvis is shown as it came. */
export function printStateLabel(state: string): string {
  switch (state) {
    case PrinterStates.printing:
      return 'Printing';
    case PrinterStates.paused:
      return 'Paused';
    case PrinterStates.complete:
      return 'Complete';
    case PrinterStates.cancelled:
      return 'Cancelled';
    case PrinterStates.error:
      return 'Error';
    case PrinterStates.standby:
      return 'Standby';
    default:
      return state.charAt(0).toUpperCase() + state.slice(1);
  }
}

/** The title of the job: what Jarvis calls it ("Ready" when nothing is loaded). Never worked out from the file name here. */
export function statusTitle(print: PrintInfo): string {
  if (print.title && print.title.trim()) return print.title;
  if (print.filename && print.filename.trim()) return print.filename;
  return print.state === PrinterStates.standby ? 'Ready' : printStateLabel(print.state);
}

/** The last finished job, read lazily when the printer is idle ("Last print: Benchy, completed 2 h ago"). */
export interface LastPrint {
  title: string;
  status: string;
  at: number;
}

function stateColors(state: string, theme: Theme): { bg: string; fg: string } {
  switch (state) {
    case PrinterStates.printing:
      return { bg: theme.primary, fg: theme.onPrimary };
    case PrinterStates.paused:
      return { bg: '#FFE0A3', fg: '#3D2B00' };
    case PrinterStates.complete:
      return { bg: '#BFE8C4', fg: '#0A3315' };
    case PrinterStates.cancelled:
    case PrinterStates.error:
      return { bg: theme.error, fg: '#ffffff' };
    default:
      return { bg: theme.border, fg: theme.textMuted };
  }
}

/** The Status card (C39 §8.4): the job, its progress, and the numbers that matter while it prints. Controls come in E7. */
export function StatusCard({ ui, theme, is24Hour, lastPrint, onDismissAlert, controls, onSend }: { ui: PrinterUi; theme: Theme; is24Hour: boolean; lastPrint: LastPrint | null; onDismissAlert: (time: number) => void; controls: ControlUi; onSend: (command: PrinterCommand) => void }) {
  const state = stateOf(ui);
  if (!state) return null;
  const p = state.print;
  const colors = stateColors(p.state, theme);
  const alert = alertOf(ui);
  const note = p.timeLeft !== null ? format.timeLeftSource(p.timeLeftFrom) : p.state === PrinterStates.printing ? format.NOT_ENOUGH_PRINTED : null;
  const speed = `${Math.round(state.toolhead.liveVelocity)} mm/s · ${format.factor(state.toolhead.speedFactor)}`;
  const facts: [string, string, string?][] = [
    ['Time left', format.timeLeft(p.timeLeft), note ?? undefined],
    ['Elapsed', format.duration(p.printDuration)],
    ['Total', format.duration(p.totalDuration)],
    ['ETA', format.eta(ui.now, p.timeLeft, is24Hour)],
    ['Filament', format.filament(p.filamentUsed)],
    ...(p.layer ? ([['Layer', `${p.layer.current} / ${p.layer.total}`]] as [string, string][]) : []),
    ['Speed', speed],
    ['Flow', format.factor(state.toolhead.extrudeFactor)],
  ];
  const showProgress = p.state === PrinterStates.printing || p.state === PrinterStates.paused || p.progress > 0;
  return (
    <View style={styles.box} testID="status-card">
      <View style={styles.titleRow}>
        <Text style={[styles.title, { color: theme.text }]} accessibilityRole="header">
          {statusTitle(p)}
        </Text>
        <View style={[styles.chip, { backgroundColor: colors.bg }]}>
          <Text style={{ color: colors.fg, fontWeight: '600' }}>{printStateLabel(p.state)}</Text>
        </View>
      </View>
      {showProgress ? (
        <View style={{ gap: 4 }}>
          <View style={[styles.track, { backgroundColor: theme.border }]} accessibilityRole="progressbar" accessibilityValue={{ min: 0, max: 100, now: Math.round(p.progress * 100) }}>
            <View style={[styles.fill, { backgroundColor: theme.primary, width: `${Math.round(Math.min(1, Math.max(0, p.progress)) * 100)}%` }]} />
          </View>
          <Text style={{ color: theme.text, fontWeight: '600' }}>{format.percent(p.progress)}</Text>
        </View>
      ) : null}
      <View style={styles.facts}>
        {facts.map(([label, value, small]) => (
          <View key={label} style={styles.fact}>
            <Text style={{ color: theme.textMuted, fontSize: 12 }}>{label}</Text>
            <Text style={{ color: theme.text, fontSize: 16 }}>{value}</Text>
            {small ? <Text style={{ color: theme.textMuted, fontSize: 12 }}>{small}</Text> : null}
          </View>
        ))}
      </View>
      {controls.mayControl && (p.state === PrinterStates.printing || p.state === PrinterStates.paused) ? <JobButtons ui={ui} state={state} c={controls} theme={theme} onSend={onSend} /> : null}
      {p.message && p.message.trim() ? <Text style={{ color: theme.textMuted, fontSize: 13 }}>{p.message}</Text> : null}
      {p.state === PrinterStates.standby && lastPrint ? (
        <Text style={{ color: theme.textMuted, fontSize: 13 }} testID="last-print">
          Last print: {lastPrint.title}, {lastPrint.status} {format.age(lastPrint.at, ui.now)}
        </Text>
      ) : null}
      {alert ? (
        <View style={[styles.alert, { backgroundColor: theme.surface, borderColor: theme.error }]} testID="status-alert">
          <Text style={{ color: theme.text }}>The printer said: {alert.text}</Text>
          <View style={styles.alertRow}>
            <Text style={{ color: theme.textMuted, fontSize: 13, flex: 1 }}>{format.age(alert.time, ui.now)}</Text>
            <Pressable onPress={() => onDismissAlert(alert.time)} accessibilityRole="button" accessibilityLabel="Dismiss" hitSlop={8}>
              <Text style={{ color: theme.primary, fontWeight: '600' }}>Dismiss</Text>
            </Pressable>
          </View>
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  box: { gap: 10 },
  titleRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  title: { flex: 1, fontSize: 18, fontWeight: '600' },
  chip: { borderRadius: 12, paddingHorizontal: 10, paddingVertical: 4 },
  track: { height: 8, borderRadius: 4, overflow: 'hidden' },
  fill: { height: 8, borderRadius: 4 },
  facts: { flexDirection: 'row', flexWrap: 'wrap', columnGap: 20, rowGap: 8 },
  fact: { minWidth: 90 },
  alert: { borderWidth: 1, borderRadius: 10, padding: 12, gap: 6 },
  alertRow: { flexDirection: 'row', alignItems: 'center' },
});

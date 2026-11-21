import { useEffect, useState } from 'react';
import { ActivityIndicator, Modal, Pressable, StyleSheet, Switch, Text, View } from 'react-native';
import type { ControlUi } from '../../printer/feed/control';
import { controlKey } from '../../printer/feed/control';
import type { PrinterCommand } from '../../printer/feed/commands';
import * as format from '../../printer/feed/format';
import { isGreyed, quickOf, showsCards, type PrinterUi } from '../../printer/feed/page';
import { PrinterStates, type PrinterState } from '../../printer/feed/wire';
import type { Theme } from '../theme';

/** What the page can ask of the controls (a test passes fakes; the page passes `PrinterControl`'s methods). */
export interface ControlActions {
  setControls(on: boolean): void;
  send(command: PrinterCommand): void;
  confirm(): void;
  dismissSheet(): void;
  messageShown(): void;
}

/** What a control that sends a command must say when it cannot be pressed: Controls off, Jarvis away, or the server's own reason. */
export const TURN_ON_CONTROLS = 'Turn on Controls to use this';

/**
 * Why a control is disabled, or null when it can be used. `quickKey` is the key of the `quick` map for the parameterless commands (`pause`, `home:xyz`, ...): the
 * server's reason is shown for those. Everything with a parameter is open when Controls are on and the server's refusal is shown inline when it says no.
 */
export function disabledReason(ui: PrinterUi, c: ControlUi, quickKey: string | null = null): string | null {
  if (isGreyed(ui)) return "Jarvis isn't reachable right now.";
  if (!c.armed) return TURN_ON_CONTROLS;
  if (quickKey !== null) {
    const q = quickOf(ui)[quickKey];
    if (q && !q.allowed) return q.reason ?? 'Not now.';
  }
  return null;
}

/** "Controls on · 1:43": starts when Controls come on and counts every second (a fresh start each time, so it never begins from an old time). */
function ArmedText({ until, offsetMs, color }: { until: number | null; offsetMs: number; color: string }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1_000);
    return () => clearInterval(t);
  }, []);
  return (
    <Text testID="controls-text" style={{ flex: 1, fontWeight: '600', color }}>
      {`Controls on${until !== null ? ` · ${format.countdown(until, now, offsetMs)}` : ''}`}
    </Text>
  );
}

/**
 * The Controls switch (C39 §7.1). Off when the page opens. On: "Controls on · 1:43", counting down from Jarvis's own timer (the phone's clock may be wrong, so the
 * countdown uses Jarvis's clock). Look-only phones get one line instead.
 */
export function ControlsBar({ ui, c, theme, onToggle }: { ui: PrinterUi; c: ControlUi; theme: Theme; onToggle: (on: boolean) => void }) {
  if (!showsCards(ui)) return null;
  if (!c.mayControl) {
    return (
      <Text testID="controls-look-only" style={{ color: theme.textMuted, fontSize: 13, paddingHorizontal: 16, paddingVertical: 6 }}>
        Jarvis lets this phone look, not control.
      </Text>
    );
  }
  return (
    <View testID="controls-bar" style={[styles.bar, { backgroundColor: c.armed ? theme.primary : theme.surface }]}>
      {c.armed ? <ArmedText until={c.armUntilMs} offsetMs={c.offsetMs} color={theme.onPrimary} /> : (
        <Text testID="controls-text" style={{ flex: 1, fontWeight: '600', color: theme.text }}>
          Controls
        </Text>
      )}
      <Switch value={c.armed} onValueChange={onToggle} disabled={isGreyed(ui)} accessibilityLabel="Controls" testID="controls-switch" />
    </View>
  );
}

/** The emergency stop: shown whenever the printer is ready, works with Controls off and from a look-only phone; Jarvis decides and the owner confirms first. */
export function EmergencyStop({ ui, state, theme, onPress }: { ui: PrinterUi; state: PrinterState | null; theme: Theme; onPress: () => void }) {
  if (!state || state.connection !== 'ready' || isGreyed(ui)) return null;
  return (
    <Pressable onPress={onPress} accessibilityRole="button" accessibilityLabel="Emergency stop" testID="estop" style={[styles.estop, { backgroundColor: theme.error }]}>
      <Text style={{ color: '#ffffff', fontWeight: '700' }}>Emergency stop</Text>
    </Pressable>
  );
}

/**
 * The owner's yes (C39 §7.2): Jarvis's own question, a confirm button in Jarvis's own words (red for something dangerous), and a plain button that says "Keep going" (or
 * "Keep printing" when the confirm says Cancel). Never "OK" or "Yes", and the two never look alike. It closes itself after ten seconds (the controller does that);
 * dismissing it sends nothing.
 */
export function ConfirmSheet({ c, theme, onConfirm, onDismiss }: { c: ControlUi; theme: Theme; onConfirm: () => void; onDismiss: () => void }) {
  const sheet = c.sheet;
  if (!sheet) return null;
  return (
    <Modal visible transparent animationType="slide" onRequestClose={onDismiss}>
      <View style={styles.backdrop}>
        <View testID="confirm-sheet" style={[styles.sheet, { backgroundColor: theme.background }]}>
          <Text testID="confirm-question" accessibilityRole="header" style={{ color: theme.text, fontSize: 18, fontWeight: '600' }}>
            {sheet.question}
          </Text>
          <Pressable onPress={onConfirm} accessibilityRole="button" accessibilityLabel={sheet.confirm} testID="confirm-yes" style={[styles.sheetButton, { backgroundColor: sheet.danger ? theme.error : theme.primary }]}>
            <Text style={{ color: sheet.danger ? '#ffffff' : theme.onPrimary, fontWeight: '700' }}>{sheet.confirm}</Text>
          </Pressable>
          <Pressable onPress={onDismiss} accessibilityRole="button" accessibilityLabel={sheet.keepLabel} testID="confirm-no" style={[styles.sheetButton, { borderColor: theme.border, borderWidth: 1 }]}>
            <Text style={{ color: theme.text, fontWeight: '600' }}>{sheet.keepLabel}</Text>
          </Pressable>
        </View>
      </View>
    </Modal>
  );
}

/** A line for the owner (the server's refusal, or why Controls went off): shown once, then gone after a few seconds. */
export function ControlMessage({ c, theme, onShown }: { c: ControlUi; theme: Theme; onShown: () => void }) {
  const message = c.message;
  useEffect(() => {
    if (!message) return undefined;
    const t = setTimeout(onShown, 6_000);
    return () => clearTimeout(t);
  }, [message, onShown]);
  if (!message) return null;
  return (
    <Pressable onPress={onShown} testID="control-message" accessibilityRole="alert" accessibilityLabel={message} style={[styles.toast, { backgroundColor: theme.text }]}>
      <Text style={{ color: theme.background }}>{message}</Text>
    </Pressable>
  );
}

/** Pause or Resume (whichever the server allows) and Cancel print, with the reason under them when they cannot be used. Cancel always asks first. */
export function JobButtons({ ui, state, c, theme, onSend }: { ui: PrinterUi; state: PrinterState; c: ControlUi; theme: Theme; onSend: (command: PrinterCommand) => void }) {
  const printing = state.print.state === PrinterStates.printing;
  const main: PrinterCommand = printing ? { kind: 'pause' } : { kind: 'resume' };
  const mainWhy = disabledReason(ui, c, printing ? 'pause' : 'resume');
  const cancelWhy = disabledReason(ui, c, 'cancel');
  const mainPending = c.pending.has(controlKey(main));
  const cancelPending = c.pending.has('cancel');
  const mainOff = mainWhy !== null || mainPending;
  const cancelOff = cancelWhy !== null || cancelPending;
  const why = mainWhy ?? cancelWhy;
  return (
    <View style={{ gap: 4 }} testID="job-buttons">
      <View style={{ flexDirection: 'row', gap: 8 }}>
        <Pressable onPress={() => onSend(main)} disabled={mainOff} accessibilityRole="button" accessibilityLabel={printing ? 'Pause' : 'Resume'} accessibilityState={{ disabled: mainOff, busy: mainPending }} testID="job-main" style={[styles.job, { borderColor: theme.border, opacity: mainOff ? 0.5 : 1 }]}>
          {mainPending ? <ActivityIndicator size="small" color={theme.textMuted} /> : null}
          <Text style={{ color: theme.text, fontWeight: '600' }}>{printing ? 'Pause' : 'Resume'}</Text>
        </Pressable>
        <Pressable onPress={() => onSend({ kind: 'cancel' })} disabled={cancelOff} accessibilityRole="button" accessibilityLabel="Cancel print" accessibilityState={{ disabled: cancelOff, busy: cancelPending }} testID="job-cancel" style={[styles.job, { borderColor: theme.error, opacity: cancelOff ? 0.5 : 1 }]}>
          {cancelPending ? <ActivityIndicator size="small" color={theme.error} /> : null}
          <Text style={{ color: theme.error, fontWeight: '600' }}>Cancel print</Text>
        </Pressable>
      </View>
      {why ? (
        <Text testID="job-why" style={{ color: theme.textMuted, fontSize: 13 }}>
          {why}
        </Text>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  bar: { flexDirection: 'row', alignItems: 'center', gap: 12, paddingHorizontal: 16, paddingVertical: 6 },
  estop: { borderRadius: 8, paddingHorizontal: 10, paddingVertical: 6 },
  backdrop: { flex: 1, justifyContent: 'flex-end', backgroundColor: 'rgba(0,0,0,0.4)' },
  sheet: { padding: 20, gap: 16, borderTopLeftRadius: 16, borderTopRightRadius: 16, paddingBottom: 34 },
  sheetButton: { alignItems: 'center', borderRadius: 10, paddingVertical: 14 },
  toast: { position: 'absolute', left: 16, right: 16, bottom: 24, borderRadius: 10, padding: 14 },
  job: { flexDirection: 'row', alignItems: 'center', gap: 6, borderWidth: 1, borderRadius: 10, paddingHorizontal: 14, paddingVertical: 8 },
});

import { Ionicons } from '@expo/vector-icons';
import { useEffect, useState } from 'react';
import { Linking, Pressable, StyleSheet, Text, View } from 'react-native';
import type { VoiceNotice, VoiceState } from '../voice/voiceMemos';
import type { Theme } from './theme';

/** 0:07 */
export function formatElapsed(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

/** The input row while a memo is being recorded or transcribed: a red dot, the time, a level meter, Cancel and Send. */
export function RecordingRow({
  state,
  level,
  onCancel,
  onStop,
  theme,
  now = Date.now,
}: {
  state: Exclude<VoiceState, { kind: 'idle' }>;
  level: () => number;
  onCancel: () => void;
  onStop: () => void;
  theme: Theme;
  now?: () => number;
}) {
  const [tick, setTick] = useState(0);
  const recording = state.kind === 'recording';
  useEffect(() => {
    if (!recording) return;
    const t = setInterval(() => setTick((n) => n + 1), 100);
    return () => clearInterval(t);
  }, [recording]);
  void tick;
  const elapsed = state.kind === 'recording' ? now() - state.startedAt : 0;
  return (
    <View style={[styles.row, { backgroundColor: theme.background, borderTopColor: theme.border }]}>
      {state.kind === 'recording' ? (
        <>
          <Pressable onPress={onCancel} accessibilityRole="button" accessibilityLabel="Cancel recording" style={styles.round} hitSlop={6}>
            <Ionicons name="close" size={26} color={theme.textMuted} />
          </Pressable>
          <View style={styles.dot} />
          <Text style={[styles.time, { color: theme.text }]} accessibilityLabel={`Recording, ${formatElapsed(elapsed)}`}>
            {formatElapsed(elapsed)}
          </Text>
          <View style={[styles.meterTrack, { backgroundColor: theme.surface }]}>
            <View style={[styles.meterFill, { backgroundColor: theme.primary, width: `${Math.round(level() * 100)}%` }]} />
          </View>
          <Pressable
            onPress={onStop}
            accessibilityRole="button"
            accessibilityLabel="Stop and send recording"
            style={[styles.send, { backgroundColor: theme.primary }]}
          >
            <Ionicons name="stop" size={20} color={theme.onPrimary} />
          </Pressable>
        </>
      ) : (
        <Text style={[styles.transcribing, { color: theme.textMuted }]}>Transcribing…</Text>
      )}
    </View>
  );
}

/** "That was too short to send." and the like, with Open settings after a refused microphone. */
export function VoiceNoticeBar({ notice, onDismiss, theme }: { notice: VoiceNotice; onDismiss: () => void; theme: Theme }) {
  return (
    <View style={[styles.notice, { backgroundColor: theme.surface, borderTopColor: theme.border }]}>
      <Text style={[styles.noticeText, { color: theme.text }]}>{notice.text}</Text>
      {notice.openSettings ? (
        <Pressable onPress={() => void Linking.openSettings()} accessibilityRole="button" accessibilityLabel="Open settings" hitSlop={6}>
          <Text style={{ color: theme.primary, fontWeight: '600' }}>Open settings</Text>
        </Pressable>
      ) : null}
      <Pressable onPress={onDismiss} accessibilityRole="button" accessibilityLabel="Dismiss" hitSlop={8}>
        <Ionicons name="close" size={18} color={theme.textMuted} />
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: 10, paddingHorizontal: 10, paddingVertical: 10, borderTopWidth: StyleSheet.hairlineWidth, minHeight: 56 },
  round: { width: 40, height: 40, alignItems: 'center', justifyContent: 'center' },
  dot: { width: 10, height: 10, borderRadius: 5, backgroundColor: '#e5484d' },
  time: { fontSize: 16, fontVariant: ['tabular-nums'], minWidth: 40 },
  meterTrack: { flex: 1, height: 8, borderRadius: 4, overflow: 'hidden' },
  meterFill: { height: 8, borderRadius: 4 },
  send: { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center' },
  transcribing: { fontSize: 16, flex: 1, textAlign: 'center' },
  notice: { flexDirection: 'row', alignItems: 'center', gap: 12, paddingHorizontal: 14, paddingVertical: 10, borderTopWidth: StyleSheet.hairlineWidth },
  noticeText: { flex: 1, fontSize: 14 },
});

import { Ionicons } from '@expo/vector-icons';
import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { STATE_TEXT, type ConversationKind } from '../voice/conversation';
import type { Theme } from './theme';

/** The chat header's button (ear icon, "Conversation mode"). */
export function ConversationButton({ on, disabled, onPress, theme }: { on: boolean; disabled?: boolean; onPress: () => void; theme: Theme }) {
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      accessibilityRole="button"
      accessibilityLabel="Conversation mode"
      accessibilityState={{ selected: on, disabled: !!disabled }}
      hitSlop={8}
      style={disabled ? { opacity: 0.4 } : undefined}
    >
      <Ionicons name={on ? 'ear' : 'ear-outline'} size={24} color={on ? theme.primary : theme.text} />
    </Pressable>
  );
}

/**
 * What replaces the input row while conversation mode is on (C57 §4): the state in words, a level meter, a Stop button while Edith is speaking and
 * a large End button. The microphone is only ever live while this is showing.
 */
export function ConversationBanner({
  kind,
  level,
  onEnd,
  onStopSpeaking,
  theme,
}: {
  kind: Exclude<ConversationKind, 'off'>;
  level: () => number;
  onEnd: () => void;
  onStopSpeaking: () => void;
  theme: Theme;
}) {
  const [tick, setTick] = useState(0);
  const listening = kind === 'listening' || kind === 'capturing' || kind === 'followup';
  useEffect(() => {
    if (!listening) return undefined;
    const t = setInterval(() => setTick((n) => n + 1), 100);
    return () => clearInterval(t);
  }, [listening]);
  void tick;
  return (
    <View style={[styles.banner, { backgroundColor: theme.surface, borderTopColor: theme.border }]} accessibilityLiveRegion="polite">
      <View style={styles.top}>
        <Ionicons name="ear" size={22} color={kind === 'capturing' ? theme.primary : theme.textMuted} />
        <Text style={[styles.state, { color: theme.text }]} accessibilityRole="header">
          {STATE_TEXT[kind]}
        </Text>
        {kind === 'speaking' ? (
          <Pressable onPress={onStopSpeaking} accessibilityRole="button" accessibilityLabel="Stop speaking" hitSlop={8} style={[styles.small, { borderColor: theme.border }]}>
            <Ionicons name="stop-outline" size={18} color={theme.text} />
            <Text style={{ color: theme.text }}>Stop</Text>
          </Pressable>
        ) : null}
      </View>
      {listening ? (
        <View style={[styles.meterTrack, { backgroundColor: theme.background }]} accessibilityLabel="Microphone level">
          <View style={[styles.meterFill, { backgroundColor: kind === 'capturing' ? theme.primary : theme.textMuted, width: `${Math.round(level() * 100)}%` }]} />
        </View>
      ) : null}
      {kind === 'waiting_tap' ? <Text style={{ color: theme.textMuted }}>Tap Confirm or Cancel on the card above.</Text> : null}
      <Pressable onPress={onEnd} accessibilityRole="button" accessibilityLabel="End conversation" style={[styles.end, { backgroundColor: theme.error }]}>
        <Text style={styles.endText}>End</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  banner: { gap: 10, paddingHorizontal: 14, paddingVertical: 12, borderTopWidth: StyleSheet.hairlineWidth },
  top: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  state: { flex: 1, fontSize: 17, fontWeight: '600' },
  small: { flexDirection: 'row', alignItems: 'center', gap: 4, borderWidth: StyleSheet.hairlineWidth, borderRadius: 16, paddingHorizontal: 10, paddingVertical: 6 },
  meterTrack: { height: 8, borderRadius: 4, overflow: 'hidden' },
  meterFill: { height: 8, borderRadius: 4 },
  end: { alignItems: 'center', justifyContent: 'center', borderRadius: 24, minHeight: 48 },
  endText: { color: '#fff', fontSize: 18, fontWeight: '700' },
});

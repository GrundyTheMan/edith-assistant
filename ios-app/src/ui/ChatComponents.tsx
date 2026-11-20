import { Ionicons } from '@expo/vector-icons';
import * as Clipboard from 'expo-clipboard';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View, useWindowDimensions } from 'react-native';
import type { StoredMessage } from '../data/chatRepository';
import { MarkdownText } from './MarkdownText';
import { messageTime } from './messageTimes';
import type { Theme } from './theme';

function useMaxBubbleWidth() {
  return useWindowDimensions().width * 0.78;
}

function IconButton({
  name,
  label,
  onPress,
  theme,
  active,
  disabled,
}: {
  name: keyof typeof Ionicons.glyphMap;
  label: string;
  onPress: () => void;
  theme: Theme;
  active?: boolean;
  disabled?: boolean;
}) {
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      accessibilityRole="button"
      accessibilityLabel={label}
      hitSlop={6}
      style={[styles.iconButton, active && { backgroundColor: theme.border }, disabled && { opacity: 0.35 }]}
    >
      <Ionicons name={name} size={18} color={theme.textMuted} />
    </Pressable>
  );
}

export interface ActionRowProps {
  message: StoredMessage;
  speaking: boolean;
  canRegenerate: boolean;
  onPlay: () => void;
  onStop: () => void;
  onRegenerate: () => void;
  theme: Theme;
}

/** Copy, Play, Stop and (on the newest reply, when idle) Regenerate: a compact row under Edith's bubble. */
export function ActionRow({ message, speaking, canRegenerate, onPlay, onStop, onRegenerate, theme }: ActionRowProps) {
  return (
    <View style={styles.actionRow}>
      <IconButton name="copy-outline" label="Copy reply" theme={theme} onPress={() => void Clipboard.setStringAsync(message.content)} />
      <IconButton name="volume-high-outline" label="Play reply" theme={theme} active={speaking} onPress={onPlay} />
      <IconButton name="stop-outline" label="Stop playback" theme={theme} disabled={!speaking} onPress={onStop} />
      {canRegenerate ? <IconButton name="refresh-outline" label="Regenerate reply" theme={theme} onPress={onRegenerate} /> : null}
    </View>
  );
}

export function MessageBubble({
  message,
  theme,
  now,
  actions,
  onRetry,
}: {
  message: StoredMessage;
  theme: Theme;
  now: number;
  actions: Omit<ActionRowProps, 'message' | 'theme'> | null;
  onRetry: () => void;
}) {
  const maxWidth = useMaxBubbleWidth();
  const mine = message.role === 'user';
  const time = <Text style={[styles.time, { color: theme.textMuted }]}>{messageTime(message.createdAt, now)}</Text>;

  if (mine) {
    return (
      <View style={styles.rowEnd}>
        <View style={[styles.bubble, styles.userBubble, { backgroundColor: theme.primary, maxWidth }]}>
          <Text selectable style={[styles.userText, { color: theme.onPrimary }]}>
            {message.content}
          </Text>
        </View>
        {time}
      </View>
    );
  }

  if (message.status === 'failed') {
    return (
      <View style={styles.rowStart}>
        <View style={[styles.bubble, styles.edithBubble, { backgroundColor: theme.edithBubble, maxWidth }]}>
          <View style={styles.errorRow}>
            <Ionicons name="alert-circle" size={18} color={theme.error} />
            <Text style={[styles.errorText, { color: theme.error }]}>{message.errorMessage ?? "Edith didn't reply."}</Text>
          </View>
          <Pressable onPress={onRetry} accessibilityRole="button" accessibilityLabel="Retry" style={styles.retry}>
            <Text style={{ color: theme.primary, fontWeight: '600' }}>Retry</Text>
          </Pressable>
        </View>
        {time}
      </View>
    );
  }

  const stopped = message.status === 'cancelled';
  return (
    <View style={styles.rowStart}>
      <View style={[styles.bubble, styles.edithBubble, { backgroundColor: theme.edithBubble, maxWidth }, stopped && { opacity: 0.7 }]}>
        <MarkdownText text={message.content} color={theme.onEdithBubble} theme={theme} />
        {stopped ? <Text style={[styles.time, { color: theme.textMuted }]}>(stopped)</Text> : null}
      </View>
      {actions ? <ActionRow message={message} theme={theme} {...actions} /> : null}
      {time}
    </View>
  );
}

/** The reply being written: its text so far, or a spinner and the status label. A Stop button ends it. */
export function InFlightBubble({
  text,
  label,
  canStop,
  onStop,
  theme,
}: {
  text: string;
  label: string;
  canStop: boolean;
  onStop: () => void;
  theme: Theme;
}) {
  const maxWidth = useMaxBubbleWidth();
  return (
    <View style={styles.rowStart}>
      <View style={[styles.bubble, styles.edithBubble, { backgroundColor: theme.edithBubble, maxWidth }]}>
        {text ? (
          <MarkdownText text={text} color={theme.onEdithBubble} theme={theme} />
        ) : (
          <View style={styles.errorRow}>
            <ActivityIndicator size="small" color={theme.textMuted} />
            <Text style={{ color: theme.textMuted, fontSize: 15 }}>{label}</Text>
          </View>
        )}
      </View>
      {canStop ? (
        <View style={styles.actionRow}>
          <IconButton name="stop-circle-outline" label="Stop reply" theme={theme} onPress={onStop} />
        </View>
      ) : null}
    </View>
  );
}

export function InputRow({
  value,
  onChange,
  canSend,
  hint,
  onSend,
  theme,
}: {
  value: string;
  onChange: (t: string) => void;
  canSend: boolean;
  hint: string | null;
  onSend: () => void;
  theme: Theme;
}) {
  return (
    <View style={[styles.inputWrap, { backgroundColor: theme.background, borderTopColor: theme.border }]}>
      {hint ? <Text style={[styles.hint, { color: theme.textMuted }]}>{hint}</Text> : null}
      <View style={styles.inputRow}>
        <TextInput
          style={[styles.input, { color: theme.text, borderColor: theme.border, backgroundColor: theme.surface }]}
          value={value}
          onChangeText={onChange}
          placeholder="Message Edith"
          placeholderTextColor={theme.textMuted}
          multiline
          maxLength={20000}
          accessibilityLabel="Message Edith"
          submitBehavior="newline"
        />
        <Pressable
          onPress={onSend}
          disabled={!canSend}
          accessibilityRole="button"
          accessibilityLabel="Send"
          style={[styles.send, { backgroundColor: theme.primary }, !canSend && { opacity: 0.35 }]}
        >
          <Ionicons name="arrow-up" size={22} color={theme.onPrimary} />
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  rowStart: { alignItems: 'flex-start', gap: 2 },
  rowEnd: { alignItems: 'flex-end', gap: 2 },
  bubble: { borderRadius: 18, paddingHorizontal: 14, paddingVertical: 10 },
  userBubble: { borderBottomRightRadius: 4 },
  edithBubble: { borderBottomLeftRadius: 4 },
  userText: { fontSize: 16, lineHeight: 22 },
  time: { fontSize: 12, marginHorizontal: 6 },
  actionRow: { flexDirection: 'row', gap: 2, marginTop: -4, marginLeft: 4 },
  iconButton: { width: 32, height: 32, borderRadius: 16, alignItems: 'center', justifyContent: 'center' },
  errorRow: { flexDirection: 'row', gap: 8, alignItems: 'center' },
  errorText: { fontSize: 15, flexShrink: 1 },
  retry: { alignSelf: 'flex-start', paddingTop: 8 },
  inputWrap: { borderTopWidth: StyleSheet.hairlineWidth, paddingHorizontal: 10, paddingTop: 8, paddingBottom: 8 },
  inputRow: { flexDirection: 'row', alignItems: 'flex-end', gap: 8 },
  hint: { fontSize: 13, paddingBottom: 6, paddingHorizontal: 6 },
  input: { flex: 1, borderWidth: 1, borderRadius: 22, paddingHorizontal: 14, paddingTop: 10, paddingBottom: 10, fontSize: 16, maxHeight: 120 },
  send: { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center' },
});

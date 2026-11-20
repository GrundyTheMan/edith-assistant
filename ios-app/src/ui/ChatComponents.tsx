import { Ionicons } from '@expo/vector-icons';
import * as Clipboard from 'expo-clipboard';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View, useWindowDimensions } from 'react-native';
import type { PendingAttachment } from '../attachments/attachments';
import type { StoredMessage } from '../data/chatRepository';
import { BubbleAttachments, PendingChips, type BubbleAttachment } from './AttachmentViews';
import { MarkdownText } from './MarkdownText';
import type { ConfirmationRequest } from '../tools/framework/tool';
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
  toolNames = [],
  attachments = [],
  onOpenImage,
}: {
  message: StoredMessage;
  /** Display names of the tools used for this reply ("weather"), shown as "Used: …". */
  toolNames?: string[];
  attachments?: BubbleAttachment[];
  onOpenImage?: (base64: string) => void;
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
          <BubbleAttachments items={attachments} onOpen={(b) => onOpenImage?.(b)} theme={theme} />
          {message.content ? (
            <Text selectable style={[styles.userText, { color: theme.onPrimary }]}>
              {message.content}
            </Text>
          ) : null}
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
        <BubbleAttachments items={attachments} onOpen={(b) => onOpenImage?.(b)} theme={theme} tone="edith" />
        <MarkdownText text={message.content} color={theme.onEdithBubble} theme={theme} />
        {stopped ? <Text style={[styles.time, { color: theme.textMuted }]}>(stopped)</Text> : null}
      </View>
      {toolNames.length > 0 ? <Text style={[styles.time, { color: theme.textMuted }]}>Used: {toolNames.join(', ')}</Text> : null}
      {actions ? <ActionRow message={message} theme={theme} {...actions} /> : null}
      {time}
    </View>
  );
}

/** The reply being written: its text so far, or a spinner and the status label. A Stop button ends it. */
/** "Add to your calendar?" with its details and Cancel / Confirm (C08 §6). */
export function ConfirmationCard({ request, onAnswer, theme }: { request: ConfirmationRequest; onAnswer: (approved: boolean) => void; theme: Theme }) {
  return (
    <View style={{ gap: 8 }}>
      <Text style={{ color: theme.onEdithBubble, fontSize: 16, fontWeight: '600' }}>{request.title}</Text>
      {request.details.map(([k, v]) => (
        <View key={k} style={{ flexDirection: 'row', gap: 8 }}>
          <Text style={{ color: theme.textMuted, fontSize: 14, width: 72 }}>{k}</Text>
          <Text style={{ color: theme.onEdithBubble, fontSize: 14, flex: 1 }}>{v}</Text>
        </View>
      ))}
      <View style={{ flexDirection: 'row', gap: 10, marginTop: 4 }}>
        <Pressable
          onPress={() => onAnswer(false)}
          accessibilityRole="button"
          accessibilityLabel="Cancel action"
          style={{ paddingHorizontal: 14, paddingVertical: 8, borderRadius: 16, borderWidth: 1, borderColor: theme.border }}
        >
          <Text style={{ color: theme.onEdithBubble }}>{request.denyLabel}</Text>
        </Pressable>
        <Pressable
          onPress={() => onAnswer(true)}
          accessibilityRole="button"
          accessibilityLabel="Confirm action"
          style={{ paddingHorizontal: 14, paddingVertical: 8, borderRadius: 16, backgroundColor: theme.primary }}
        >
          <Text style={{ color: theme.onPrimary, fontWeight: '600' }}>{request.confirmLabel}</Text>
        </Pressable>
      </View>
    </View>
  );
}

export function InFlightBubble({
  text,
  label,
  canStop,
  onStop,
  theme,
  confirmation,
  onAnswer,
}: {
  text: string;
  label: string;
  canStop: boolean;
  onStop: () => void;
  theme: Theme;
  confirmation?: ConfirmationRequest;
  onAnswer?: (approved: boolean) => void;
}) {
  const maxWidth = useMaxBubbleWidth();
  return (
    <View style={styles.rowStart}>
      <View style={[styles.bubble, styles.edithBubble, { backgroundColor: theme.edithBubble, maxWidth }]}>
        {confirmation && onAnswer ? (
          <ConfirmationCard request={confirmation} onAnswer={onAnswer} theme={theme} />
        ) : text ? (
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
  pending = [],
  onAttach,
  onRemove,
  onRecord,
}: {
  onRecord?: () => void;
  pending?: PendingAttachment[];
  onAttach?: () => void;
  onRemove?: (localId: string) => void;
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
      <PendingChips items={pending} onRemove={(id) => onRemove?.(id)} theme={theme} />
      <View style={styles.inputRow}>
        {onAttach ? (
          <Pressable onPress={onAttach} accessibilityRole="button" accessibilityLabel="Attach a photo" style={styles.attach} hitSlop={6}>
            <Ionicons name="attach" size={26} color={theme.textMuted} />
          </Pressable>
        ) : null}
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
        {onRecord && !canSend ? (
          <Pressable onPress={onRecord} accessibilityRole="button" accessibilityLabel="Record audio" style={styles.attach} hitSlop={6}>
            <Ionicons name="mic-outline" size={24} color={theme.textMuted} />
          </Pressable>
        ) : null}
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
  attach: { width: 36, height: 40, alignItems: 'center', justifyContent: 'center' },
  send: { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center' },
});

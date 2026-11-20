import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, AppState, FlatList, KeyboardAvoidingView, Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MAX_ATTACHMENTS, prepareImage, type PendingAttachment } from '../attachments/attachments';
import { expoImageProcessor } from '../attachments/expoImageProcessor';
import { pickPhotos } from '../attachments/pickPhotos';
import { useServices, useStore } from '../core/services';
import { Store } from '../core/store';
import { useRepoQuery } from '../core/useRepoQuery';
import type { ConfirmationRequest } from '../tools/framework/tool';
import type { StoredMessage } from '../data/chatRepository';
import * as Clipboard from 'expo-clipboard';
import { shareTextFile } from '../attachments/shareFile';
import { displayPartial } from '../chat/replyFormat';
import { ImageViewer, type BubbleAttachment } from './AttachmentViews';
import { FileViewer, type BubbleFile } from './FileCards';
import { InFlightBubble, InputRow, MessageBubble } from './ChatComponents';
import { ConversationBanner, ConversationButton } from './ConversationViews';
import { RecordingRow, VoiceNoticeBar } from './VoiceViews';
import { useTheme } from './theme';
import { useNow } from './useNow';

/** Each conversation keeps its own unsent draft while the app runs. */
const drafts = new Store<ReadonlyMap<number, string>>(new Map());

type Item =
  | { key: string; kind: 'message'; message: StoredMessage }
  | { key: string; kind: 'inflight'; text: string; label: string; canStop: boolean; confirmation?: ConfirmationRequest };

export function ChatScreen() {
  const theme = useTheme();
  const router = useRouter();
  const { repo, engine, current, speech, tools, attachments: store, voice, conversation } = useServices();
  const talk = useStore(conversation.state);
  const talkNotice = useStore(conversation.notice);
  const voiceState = useStore(voice.state);
  const voiceNotice = useStore(voice.notice);
  const conversationId = useStore(current.id);
  const engineState = useStore(engine.state);
  const speaking = useStore(speech.speakingMessageId);

  const messages = useRepoQuery(repo, () => (conversationId === null ? Promise.resolve([]) : repo.messages(conversationId)), [conversationId], [] as StoredMessage[]);
  const conversations = useRepoQuery(repo, () => repo.conversations(), [], []);
  const stored = useRepoQuery(repo, () => (conversationId === null ? Promise.resolve([]) : repo.attachments(conversationId)), [conversationId], []);
  useStore(store.version); // redraw when a sent picture's bytes arrive
  // After a restart the pictures are on disk: load the ones this chat shows.
  useEffect(() => {
    void store.hydrate([...new Set(stored.filter((a) => !a.removedAt).map((a) => a.resendOf ?? a.id))]);
  }, [stored, store]);
  const [pending, setPending] = useState<PendingAttachment[]>([]);
  const [viewing, setViewing] = useState<string | null>(null);
  const [viewingFile, setViewingFile] = useState<BubbleFile | null>(null);
  const title = conversations.find((c) => c.id === conversationId)?.title || 'New chat';

  const allDrafts = useStore(drafts);
  const draft = conversationId === null ? '' : (allDrafts.get(conversationId) ?? '');
  const changeDraft = (t: string) => {
    if (conversationId !== null) drafts.set(new Map(allDrafts).set(conversationId, t));
  };

  // The reply being written, here only if it belongs to this conversation.
  const workingHere = engineState.kind === 'working' && engineState.conversationId === conversationId;
  const busyElsewhere = engineState.kind === 'working' && engineState.conversationId !== conversationId;

  // Bridge: the engine goes idle a moment before the stored reply reaches the list; keep the text until it does.
  const startMaxId = useRef(0);
  const lastPartial = useRef('');
  const wasWorking = useRef(false);
  const [linger, setLinger] = useState<{ text: string; afterId: number } | null>(null);
  useEffect(() => {
    if (workingHere) {
      if (!wasWorking.current) startMaxId.current = messages.reduce((m, x) => Math.max(m, x.id), 0);
      wasWorking.current = true;
      lastPartial.current = engineState.kind === 'working' ? engineState.partialText : '';
    } else if (wasWorking.current) {
      wasWorking.current = false;
      if (lastPartial.current) {
        setLinger({ text: lastPartial.current, afterId: startMaxId.current });
        const t = setTimeout(() => setLinger(null), 2000);
        return () => clearTimeout(t);
      }
    }
  }, [workingHere, engineState, messages]);
  const lingering = !workingHere && linger !== null && !messages.some((m) => m.role === 'assistant' && m.id > linger.afterId);

  const now = useNow();
  const latestAssistantId = useMemo(() => [...messages].reverse().find((m) => m.role === 'assistant')?.id, [messages]);
  const idle = engineState.kind !== 'working' && voiceState.kind === 'idle' && talk.kind === 'off';

  // Leaving the app mid-recording releases the microphone and drops the memo (C12 As built).
  useEffect(() => {
    const sub = AppState.addEventListener('change', (s) => {
      if (s !== 'active') {
        void voice.leftTheApp();
        void conversation.leftTheApp(); // no background audio in Expo Go: the mode ends (C57 §3)
      }
    });
    return () => sub.remove();
  }, [voice, conversation]);
  // The microphone is only live while the banner shows: leaving this tab ends the mode.
  useFocusEffect(
    useCallback(() => () => void conversation.stop('Conversation stopped when you left the chat.'), [conversation]),
  );

  const items: Item[] = [];
  if (workingHere && engineState.kind === 'working') {
    items.push({ key: 'inflight', kind: 'inflight', text: displayPartial(engineState.partialText), label: engineState.label, canStop: true, confirmation: engineState.confirmation });
  } else if (lingering && linger) {
    items.push({ key: 'inflight', kind: 'inflight', text: displayPartial(linger.text), label: '', canStop: false });
  }
  for (let i = messages.length - 1; i >= 0; i--) items.push({ key: `m${messages[i].id}`, kind: 'message', message: messages[i] });

  const canSend = conversationId !== null && idle && (draft.trim().length > 0 || pending.length > 0);
  const hint = busyElsewhere ? 'Edith is replying in another chat' : null;

  const send = () => {
    if (!canSend || conversationId === null) return;
    const text = draft.trim();
    const photos = pending;
    speech.stop(); // silence when the owner sends a message
    changeDraft('');
    setPending([]);
    void engine.send(conversationId, text, 'typed', photos);
  };
  const attach = async () => {
    try {
      const picked = await pickPhotos(MAX_ATTACHMENTS - pending.length);
      const prepared: PendingAttachment[] = [];
      for (const p of picked) prepared.push(await prepareImage(p, expoImageProcessor, pending.length + prepared.length + 1));
      if (prepared.length > 0) setPending((cur) => [...cur, ...prepared].slice(0, MAX_ATTACHMENTS));
    } catch {
      Alert.alert("Couldn't attach that", "That photo couldn't be read. Try another.");
    }
  };
  const bubbleAttachments = (messageId: number): BubbleAttachment[] =>
    stored
      .filter((a) => a.messageId === messageId && a.kind === 'image')
      // A resend shows the original's picture (C56): gone when that is.
      .map((a) => ({ id: a.id, fileName: a.fileName, base64: a.removedAt ? null : store.image(a.resendOf ?? a.id) }));
  const fileCards = (messageId: number): BubbleFile[] =>
    stored.filter((a) => a.messageId === messageId && a.kind === 'file').map((a) => ({ id: a.id, name: a.fileName, text: a.removedAt ? null : store.fileText(a.id) }));
  const shareFile = (f: BubbleFile) => {
    if (f.text !== null) void shareTextFile(f.name, f.text).catch(() => Alert.alert("Couldn't share that", 'Try again.'));
  };
  const copyFile = (f: BubbleFile) => {
    if (f.text !== null) void Clipboard.setStringAsync(f.text);
  };
  const regenerate = () => {
    if (conversationId !== null) void engine.regenerateLast(conversationId);
  };
  const rename = () => {
    if (conversationId === null) return;
    Alert.prompt('Rename chat', undefined, (t) => void (t?.trim() && repo.rename(conversationId, t)), 'plain-text', title === 'New chat' ? '' : title);
  };

  return (
    <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.background }]}>
      <View style={[styles.header, { borderBottomColor: theme.border }]}>
        <Pressable onPress={() => router.push('/conversations')} accessibilityRole="button" accessibilityLabel="Conversations" hitSlop={8}>
          <Ionicons name="list" size={24} color={theme.text} />
        </Pressable>
        <Pressable onPress={rename} style={styles.titleWrap} accessibilityRole="button" accessibilityLabel="Rename chat">
          <Text numberOfLines={1} style={[styles.title, { color: theme.text }]}>
            {title}
          </Text>
        </Pressable>
        <ConversationButton
          on={talk.kind !== 'off'}
          disabled={conversationId === null || (talk.kind === 'off' && (engineState.kind === 'working' || voiceState.kind !== 'idle'))}
          onPress={() => (talk.kind === 'off' ? void conversation.start(conversationId ?? 0) : void conversation.stop())}
          theme={theme}
        />
        <Pressable onPress={() => void current.newChat()} accessibilityRole="button" accessibilityLabel="New chat" hitSlop={8}>
          <Ionicons name="create-outline" size={24} color={theme.text} />
        </Pressable>
      </View>

      <KeyboardAvoidingView style={styles.flex} behavior="padding">
        <FlatList
          style={styles.flex}
          data={items}
          inverted
          keyExtractor={(i) => i.key}
          contentContainerStyle={styles.list}
          ItemSeparatorComponent={() => <View style={{ height: 8 }} />}
          keyboardShouldPersistTaps="handled"
          keyboardDismissMode="interactive"
          ListEmptyComponent={
            <View style={styles.empty}>
              <Text style={[styles.emptyText, { color: theme.textMuted }]}>
                Ask Edith anything — your schedule, the weather, or what&apos;s on your mind.
              </Text>
            </View>
          }
          renderItem={({ item }) =>
            item.kind === 'inflight' ? (
              <InFlightBubble
                text={item.text}
                label={item.label}
                canStop={item.canStop}
                onStop={() => engine.cancel()}
                theme={theme}
                confirmation={item.confirmation}
                onAnswer={(approved) => item.confirmation && engine.resolveConfirmation(item.confirmation.id, approved)}
              />
            ) : (
              <MessageBubble
                message={item.message}
                theme={theme}
                now={now}
                onRetry={regenerate}
                toolNames={item.message.toolsUsed.map((n) => tools.displayName(n))}
                attachments={bubbleAttachments(item.message.id)}
                onOpenImage={setViewing}
                files={fileCards(item.message.id)}
                onViewFile={setViewingFile}
                onCopyFile={copyFile}
                onShareFile={shareFile}
                actions={
                  item.message.role === 'assistant' && item.message.status !== 'failed'
                    ? {
                        speaking: speaking === item.message.id,
                        canRegenerate: idle && item.message.id === latestAssistantId,
                        onPlay: () => speech.play(item.message.id, item.message.content),
                        onStop: () => speech.stop(),
                        onRegenerate: regenerate,
                      }
                    : null
                }
              />
            )
          }
        />
        {voiceNotice && voiceState.kind === 'idle' ? <VoiceNoticeBar notice={voiceNotice} onDismiss={() => voice.dismiss()} theme={theme} /> : null}
        {talkNotice ? <VoiceNoticeBar notice={talkNotice} onDismiss={() => conversation.dismiss()} theme={theme} /> : null}
        {talk.kind !== 'off' ? (
          <ConversationBanner kind={talk.kind} level={() => conversation.level()} onEnd={() => void conversation.stop()} onStopSpeaking={() => speech.stop()} theme={theme} />
        ) : voiceState.kind !== 'idle' ? (
          <RecordingRow state={voiceState} level={() => voice.level()} onCancel={() => void voice.cancel()} onStop={() => void voice.stop()} theme={theme} />
        ) : (
          <InputRow
            value={draft}
            onChange={changeDraft}
            canSend={canSend}
            hint={hint}
            onSend={send}
            theme={theme}
            pending={pending}
            onAttach={pending.length < MAX_ATTACHMENTS ? () => void attach() : undefined}
            onRemove={(id) => setPending((cur) => cur.filter((p) => p.localId !== id))}
            onRecord={idle && conversationId !== null ? () => void voice.start(conversationId) : undefined}
          />
        )}
        <ImageViewer base64={viewing} onClose={() => setViewing(null)} />
        <FileViewer file={viewingFile} theme={theme} onClose={() => setViewingFile(null)} />
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  flex: { flex: 1 },
  header: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 10, gap: 12, borderBottomWidth: StyleSheet.hairlineWidth },
  titleWrap: { flex: 1, alignItems: 'center' },
  title: { fontSize: 17, fontWeight: '600' },
  list: { padding: 12 },
  empty: { padding: 32, transform: [{ scaleY: -1 }] },
  emptyText: { fontSize: 16, textAlign: 'center' },
});

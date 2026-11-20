import Slider from '@react-native-community/slider';
import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, Switch, Text, View } from 'react-native';
import { useServices, useStore } from '../core/services';
import type { Settings } from '../settings/settings';
import { VoiceNoticeBar } from './VoiceViews';
import type { Theme } from './theme';

/**
 * Settings → Conversation (C57 §4): speak during a conversation, the follow-up window, sensitivity, pause length, and a Test that shows the live
 * microphone level and a check when speech is heard. The detector is a heuristic: if it cuts you off or waits too long, move these.
 */
export function ConversationSettings({ theme }: { theme: Theme }) {
  const { settings, conversation } = useServices();
  const [saved, setSaved] = useState<Settings>(settings.snapshot());
  useEffect(() => settings.subscribe(setSaved), [settings]);
  const testing = useStore(conversation.testing);
  const notice = useStore(conversation.notice);
  const [follow, setFollow] = useState(saved.followUpSeconds);
  const [sensitivity, setSensitivity] = useState(saved.conversationSensitivity);
  const [pause, setPause] = useState(saved.conversationPauseMs / 1000);
  // A test ends when this screen does.
  useEffect(() => () => void conversation.stopTest(), [conversation]);

  return (
    <View style={styles.box}>
      <View style={styles.switchRow}>
        <View style={styles.flex}>
          <Text style={{ color: theme.text, fontSize: 16 }}>Speak replies during conversation</Text>
          <Text style={[styles.note, { color: theme.textMuted }]}>If speaking is off, it is turned on while a conversation lasts</Text>
        </View>
        <Switch value={saved.speakDuringConversation} onValueChange={(v) => void settings.update((s) => ({ ...s, speakDuringConversation: v }))} accessibilityLabel="Speak replies during conversation" />
      </View>

      <Text style={{ color: theme.text }}>Follow-up window: {follow === 0 ? 'none (ends after each reply)' : `${follow} s`}</Text>
      <Slider
        minimumValue={0}
        maximumValue={15}
        step={1}
        value={follow}
        onValueChange={setFollow}
        onSlidingComplete={(v) => void settings.update((s) => ({ ...s, followUpSeconds: Math.round(v) }))}
        accessibilityLabel="Follow-up window"
      />

      <Text style={{ color: theme.text }}>Sensitivity: {Math.round(sensitivity * 100)}%</Text>
      <Slider
        minimumValue={0}
        maximumValue={1}
        step={0.05}
        value={sensitivity}
        onValueChange={setSensitivity}
        onSlidingComplete={(v) => void settings.update((s) => ({ ...s, conversationSensitivity: Math.round(v * 100) / 100 }))}
        accessibilityLabel="Sensitivity"
      />
      <Text style={[styles.note, { color: theme.textMuted }]}>Higher hears quieter speech, and also more of the room&apos;s noise.</Text>

      <Text style={{ color: theme.text }}>Pause length: {pause.toFixed(1)} s</Text>
      <Slider
        minimumValue={0.7}
        maximumValue={2}
        step={0.1}
        value={pause}
        onValueChange={setPause}
        onSlidingComplete={(v) => void settings.update((s) => ({ ...s, conversationPauseMs: Math.round(v * 10) * 100 }))}
        accessibilityLabel="Pause length"
      />
      <Text style={[styles.note, { color: theme.textMuted }]}>How long you stay quiet before Edith decides you are done.</Text>

      {testing ? (
        <View style={styles.testRow}>
          <View style={[styles.meterTrack, { backgroundColor: theme.background }]} accessibilityLabel="Microphone level">
            <View style={[styles.meterFill, { backgroundColor: testing.heard ? theme.primary : theme.textMuted, width: `${Math.round(testing.level * 100)}%` }]} />
          </View>
          <Text style={{ color: theme.text }} accessibilityLabel={testing.heard ? 'Speech heard' : 'No speech yet'}>
            {testing.heard ? '✓ Heard you' : 'Say something…'}
          </Text>
        </View>
      ) : null}
      <Pressable
        onPress={() => void (testing ? conversation.stopTest() : conversation.startTest())}
        accessibilityRole="button"
        style={[styles.button, { backgroundColor: theme.primary }]}
      >
        <Text style={{ color: theme.onPrimary, fontWeight: '600' }}>{testing ? 'Stop test' : 'Test microphone'}</Text>
      </Pressable>
      {notice && !testing ? <VoiceNoticeBar notice={notice} onDismiss={() => conversation.dismiss()} theme={theme} /> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  box: { gap: 10 },
  flex: { flex: 1 },
  switchRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  note: { fontSize: 13 },
  testRow: { gap: 6 },
  meterTrack: { height: 8, borderRadius: 4, overflow: 'hidden' },
  meterFill: { height: 8, borderRadius: 4 },
  button: { paddingHorizontal: 16, paddingVertical: 10, borderRadius: 10, alignSelf: 'flex-start' },
});

import { useEffect, useState } from 'react';
import { Alert, Pressable, StyleSheet, Text, View } from 'react-native';
import { MAX_KEEP, applyRetention, beyondKeep, deleteAllAttachments, usageText } from '../attachments/retention';
import { useServices, useStore } from '../core/services';
import { useRepoQuery } from '../core/useRepoQuery';
import type { Settings, SettingsRepository } from '../settings/settings';
import type { Theme } from './theme';

/** Settings → Attachments (C16 §2): how many of your photos stay on the phone, what they use, and delete all. */
export function AttachmentsSettings({ theme }: { theme: Theme }) {
  const { settings, repo, retention, attachments } = useServices();
  useStore(attachments.version);
  const keep = useStoreSettings(settings.snapshot(), settings);
  const kept = useRepoQuery(repo, () => repo.keptAttachments('from_user'), [attachments.version.get()], []);
  const bytes = kept.reduce((n, a) => n + a.sizeBytes, 0);

  const change = async (next: number) => {
    const n = Math.min(MAX_KEEP, Math.max(0, next));
    if (n === keep) return;
    const doomed = n < keep ? (await beyondKeep(repo, n)).length : 0;
    const go = async () => {
      await settings.update((s) => ({ ...s, keepRecentAttachments: n }));
      await applyRetention(retention, n);
    };
    if (doomed > 0 && n > 0) {
      Alert.alert(`This will delete ${doomed} older attachment${doomed === 1 ? '' : 's'}.`, undefined, [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Delete', style: 'destructive', onPress: () => void go() },
      ]);
    } else {
      await go();
    }
  };
  const deleteAll = () =>
    Alert.alert('Delete all attachments now?', 'The photos you sent are removed from this phone. The chats keep their text.', [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: () => void deleteAllAttachments(retention) },
    ]);

  return (
    <View style={{ gap: 10 }}>
      <Text style={[styles.note, { color: theme.textMuted }]}>
        Keeps the most recent photos you sent on this phone only; older ones are deleted. 0 keeps none after the app closes.
      </Text>
      <View style={styles.row}>
        <Text style={{ color: theme.text, fontSize: 16, flex: 1 }}>Keep the newest {keep}</Text>
        <Pressable onPress={() => void change(keep - 1)} accessibilityRole="button" accessibilityLabel="Keep fewer" style={[styles.step, { borderColor: theme.border }]}>
          <Text style={{ color: theme.text, fontSize: 18 }}>−</Text>
        </Pressable>
        <Pressable onPress={() => void change(keep + 1)} accessibilityRole="button" accessibilityLabel="Keep more" style={[styles.step, { borderColor: theme.border }]}>
          <Text style={{ color: theme.text, fontSize: 18 }}>+</Text>
        </Pressable>
      </View>
      <Text style={{ color: theme.textMuted }}>{usageText(kept.length, bytes)}</Text>
      <Pressable onPress={deleteAll} accessibilityRole="button" accessibilityLabel="Delete all attachments now" disabled={kept.length === 0} style={kept.length === 0 && { opacity: 0.4 }}>
        <Text style={{ color: theme.error, fontWeight: '600' }}>Delete all attachments now</Text>
      </Pressable>
    </View>
  );
}

function useStoreSettings(initial: Settings, repo: SettingsRepository): number {
  const [s, setS] = useState(initial);
  useEffect(() => repo.subscribe(setS), [repo]);
  return s.keepRecentAttachments;
}

const styles = StyleSheet.create({
  note: { fontSize: 13 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  step: { width: 40, height: 40, borderRadius: 20, borderWidth: 1, alignItems: 'center', justifyContent: 'center' },
});

import { useEffect, useState } from 'react';
import { ActivityIndicator, Modal, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { MacroList, type MacrosUi, type PresetsUi } from '../../printer/feed/cards';
import { ConsoleHistory } from '../../printer/feed/consoleHistory';
import * as input from '../../printer/feed/input';
import { Risks, type MacroInfo, type Preset } from '../../printer/feed/wire';
import { CmdButton, Refusal, WhyOff, type ControlsCtx } from './CardControls';
import { disabledReason } from './ControlViews';

// ---- Macros ----------------------------------------------------------------------------------------------------------------

/**
 * Macros (C39 §8.4): the list Jarvis sends, in Jarvis's groups and order, with a filter. A macro with parameters opens a small form (the default is the hint) and a Run
 * button; a macro without them is one button. Every run goes through the confirm sheet, which shows Jarvis's own question. Edith never decides what is hidden or denied:
 * what Jarvis leaves out is not here, and what the owner hid is listed at the bottom with Show.
 */
export function MacrosCard({ macros, ctx, onQuery, onHidden }: { macros: MacrosUi; ctx: ControlsCtx; onQuery: (q: string) => void; onHidden: (hidden: string[]) => void }) {
  const t = ctx.theme;
  const [folded, setFolded] = useState<Record<string, boolean>>({});
  const answer = macros.answer;
  if (!answer) {
    return (
      <View style={{ gap: 8 }} testID="macros-card">
        {macros.loading ? <ActivityIndicator color={t.textMuted} /> : null}
        {macros.error ? <Text style={{ color: t.error, fontSize: 13 }}>{macros.error}</Text> : null}
      </View>
    );
  }
  const sections = MacroList.sections(answer, macros.query);
  return (
    <View style={{ gap: 8 }} testID="macros-card">
      <TextInput value={macros.query} onChangeText={onQuery} placeholder="Filter" placeholderTextColor={t.textMuted} accessibilityLabel="Filter macros" autoCapitalize="none" autoCorrect={false} testID="macro-filter" style={[styles.input, { color: t.text, borderColor: t.border }]} />
      <WhyOff ctx={ctx} />
      {sections.length === 0 ? <Text style={{ color: t.textMuted, fontSize: 13 }}>No macros match.</Text> : null}
      {sections.map((section) => {
        // While the filter has text every group is open, so nothing that matches is hidden behind a fold.
        const open = macros.query.trim() !== '' || !folded[section.id];
        return (
          <View key={section.id} style={{ gap: 6 }}>
            <Pressable onPress={() => setFolded((f) => ({ ...f, [section.id]: open }))} accessibilityRole="button" accessibilityLabel={`${open ? 'Fold' : 'Open'} ${section.title}`} testID={`macro-group-${section.id}`} style={styles.group}>
              <Text style={{ color: t.text, fontWeight: '600', flex: 1 }}>{`${section.title} (${section.macros.length})`}</Text>
              <Text style={{ color: t.textMuted }}>{open ? '⌃' : '⌄'}</Text>
            </Pressable>
            {open ? section.macros.map((m) => <MacroItem key={m.name} m={m} ctx={ctx} onHide={() => onHidden([...new Set([...answer.hidden, m.name])])} />) : null}
          </View>
        );
      })}
      {macros.error ? <Text style={{ color: t.error, fontSize: 13 }}>{macros.error}</Text> : null}
      {answer.hidden.length > 0 ? (
        <View style={{ gap: 4, marginTop: 8 }}>
          <Text accessibilityRole="header" style={{ color: t.text, fontWeight: '600' }}>{`Hidden (${answer.hidden.length})`}</Text>
          {answer.hidden.map((name) => (
            <View key={name} style={styles.hiddenRow}>
              <Text style={{ color: t.text, flex: 1 }}>{name}</Text>
              <Pressable onPress={() => onHidden(answer.hidden.filter((h) => h !== name))} accessibilityRole="button" accessibilityLabel={`Show ${name}`} testID={`show-${name}`}>
                <Text style={{ color: t.primary, fontWeight: '600' }}>Show</Text>
              </Pressable>
            </View>
          ))}
        </View>
      ) : null}
    </View>
  );
}

function MacroItem({ m, ctx, onHide }: { m: MacroInfo; ctx: ControlsCtx; onHide: () => void }) {
  const t = ctx.theme;
  const [typed, setTyped] = useState<Record<string, string>>({});
  const [open, setOpen] = useState(false);
  const danger = m.risk === Risks.danger;
  return (
    <View style={{ gap: 4, paddingLeft: 8 }} testID={`macro-${m.name}`}>
      <View style={styles.row}>
        {m.params.length === 0 ? (
          <CmdButton text={m.name} cmd={{ kind: 'macro', name: m.name }} ctx={ctx} danger={danger} tag={`run-${m.name}`} />
        ) : (
          <Pressable onPress={() => setOpen(!open)} accessibilityRole="button" accessibilityLabel={open ? `Close ${m.name}` : m.name} accessibilityState={{ expanded: open }} testID={`open-${m.name}`} style={[styles.openButton, { borderColor: danger ? t.error : t.border }]}>
            <Text style={{ color: danger ? t.error : t.text, fontWeight: '600' }}>{open ? `Close ${m.name}` : m.name}</Text>
          </Pressable>
        )}
        <Pressable onPress={onHide} accessibilityRole="button" accessibilityLabel={`Hide ${m.name}`} testID={`hide-${m.name}`}>
          <Text style={{ color: t.primary, fontWeight: '600' }}>Hide</Text>
        </Pressable>
      </View>
      {m.description && m.description.trim() ? <Text style={{ color: t.textMuted, fontSize: 13 }}>{m.description}</Text> : null}
      {m.params.length > 0 && open ? (
        <>
          {m.params.map((p) => (
            <TextInput
              key={p.name}
              value={typed[p.name] ?? ''}
              onChangeText={(v) => setTyped((x) => ({ ...x, [p.name]: v }))}
              placeholder={p.default ?? p.name}
              placeholderTextColor={t.textMuted}
              accessibilityLabel={`${m.name} ${p.name}`}
              autoCapitalize="none"
              autoCorrect={false}
              testID={`param-${m.name}-${p.name}`}
              style={[styles.input, { color: t.text, borderColor: t.border }]}
            />
          ))}
          <View style={{ alignSelf: 'flex-start' }}>
            <CmdButton text={`Run ${m.name}`} cmd={{ kind: 'macro', name: m.name, params: MacroList.params(typed) }} ctx={ctx} danger={danger} tag={`run-${m.name}`} />
          </View>
        </>
      ) : null}
      <Refusal ctx={ctx} keys={[`macro:${m.name}`]} />
    </View>
  );
}

// ---- The console's input ---------------------------------------------------------------------------------------------------

/**
 * The console's one-line input (C39 §8.4): at most 200 characters, never a line break, Enter or Send asks Jarvis (the sheet then shows the exact line and its risk), and the
 * arrows walk through the last 50 lines sent. Jarvis's refusal ("Use the System card or the printer's screen for this") shows under the box.
 */
export function ConsoleInput({ history, ctx, onRemember }: { history: string[]; ctx: ControlsCtx; onRemember: (line: string) => void }) {
  const t = ctx.theme;
  const [text, setText] = useState('');
  const [index, setIndex] = useState(-1);
  const cmd = input.consoleLine(text);
  const clear = () => {
    setText('');
    setIndex(-1);
  };
  const sendNow = () => {
    if (!cmd) return;
    ctx.send(cmd);
    onRemember(cmd.script);
    clear();
  };
  const enabled = cmd !== null && disabledReason(ctx.ui, ctx.controls) === null;
  return (
    <View style={{ gap: 4 }} testID="console-input">
      <View style={styles.row}>
        <TextInput
          value={text}
          onChangeText={(typed) => {
            setText(typed.replace(/[\r\n]/g, '').slice(0, input.MAX_LINE));
            setIndex(-1);
          }}
          placeholder="Send a line to the printer"
          placeholderTextColor={t.textMuted}
          accessibilityLabel="Send a line to the printer"
          returnKeyType="send"
          onSubmitEditing={() => enabled && sendNow()}
          autoCapitalize="none"
          autoCorrect={false}
          testID="console-line"
          style={[styles.input, { color: t.text, borderColor: t.border, flex: 1 }]}
        />
        <Pressable
          onPress={() => {
            const next = ConsoleHistory.older(history, index);
            setIndex(next);
            setText(ConsoleHistory.at(history, next));
          }}
          disabled={history.length === 0}
          accessibilityRole="button"
          accessibilityLabel="Older line"
          accessibilityState={{ disabled: history.length === 0 }}
          testID="console-older"
          style={[styles.arrow, history.length === 0 && { opacity: 0.4 }]}
        >
          <Text style={{ color: t.text, fontSize: 18 }}>↑</Text>
        </Pressable>
        <Pressable
          onPress={() => {
            const next = ConsoleHistory.newer(index);
            setIndex(next);
            setText(ConsoleHistory.at(history, next));
          }}
          disabled={index < 0}
          accessibilityRole="button"
          accessibilityLabel="Newer line"
          accessibilityState={{ disabled: index < 0 }}
          testID="console-newer"
          style={[styles.arrow, index < 0 && { opacity: 0.4 }]}
        >
          <Text style={{ color: t.text, fontSize: 18 }}>↓</Text>
        </Pressable>
      </View>
      <View style={styles.row}>
        <CmdButton text="Send" cmd={cmd} ctx={ctx} tag="console-send" after={() => {
            if (cmd) onRemember(cmd.script);
            clear();
          }} />
        <Text style={{ color: t.textMuted, fontSize: 12 }}>{`${text.length}/${input.MAX_LINE}`}</Text>
      </View>
      <Refusal ctx={ctx} keys={['gcode']} />
      <WhyOff ctx={ctx} />
    </View>
  );
}

// ---- The presets editor ----------------------------------------------------------------------------------------------------

/**
 * The temperature presets (C39 §16): the names and the nozzle and bed temperatures Jarvis offers this phone, up to 10. Jarvis keeps them, so its own page shows the same
 * ones; it needs Controls on and checks them again. With none, Jarvis uses its own defaults.
 */
export function PresetsDialog({ presets, ctx, editor, onSave, onClose }: { presets: Preset[]; ctx: ControlsCtx; editor: PresetsUi; onSave: (p: Preset[]) => void; onClose: () => void }) {
  const t = ctx.theme;
  const [rows, setRows] = useState<input.PresetRow[]>(() => presets.map((p) => ({ name: p.name, nozzle: String(p.extruder), bed: String(p.bed) })));
  const [savedSeen] = useState(editor.saved);
  useEffect(() => {
    if (editor.saved > savedSeen) onClose();
  }, [editor.saved, savedSeen, onClose]);
  const problem = input.presetsProblem(rows);
  const why = disabledReason(ctx.ui, ctx.controls);
  const set = (n: number, patch: Partial<input.PresetRow>) => setRows((r) => r.map((x, i) => (i === n ? { ...x, ...patch } : x)));
  const canSave = problem === null && why === null && !editor.saving;
  return (
    <Modal visible animationType="slide" presentationStyle="pageSheet" onRequestClose={onClose}>
      <View style={[styles.dialog, { backgroundColor: t.background }]} testID="presets-dialog">
        <Text accessibilityRole="header" style={{ color: t.text, fontSize: 20, fontWeight: '700' }}>Temperature presets</Text>
        <ScrollView contentContainerStyle={{ gap: 8 }} keyboardShouldPersistTaps="handled">
          {rows.map((r, n) => (
            <View key={n} style={{ gap: 4 }}>
              <View style={styles.row}>
                <TextInput value={r.name} onChangeText={(v) => set(n, { name: v })} placeholder="Name" placeholderTextColor={t.textMuted} accessibilityLabel={`Preset ${n + 1} name`} testID={`preset-name-${n}`} style={[styles.input, { color: t.text, borderColor: t.border, flex: 1.3 }]} />
                <TextInput value={r.nozzle} onChangeText={(v) => set(n, { nozzle: v })} placeholder="Nozzle" placeholderTextColor={t.textMuted} keyboardType="decimal-pad" accessibilityLabel={`Preset ${n + 1} nozzle`} testID={`preset-nozzle-${n}`} style={[styles.input, { color: t.text, borderColor: t.border, flex: 1 }]} />
                <TextInput value={r.bed} onChangeText={(v) => set(n, { bed: v })} placeholder="Bed" placeholderTextColor={t.textMuted} keyboardType="decimal-pad" accessibilityLabel={`Preset ${n + 1} bed`} testID={`preset-bed-${n}`} style={[styles.input, { color: t.text, borderColor: t.border, flex: 0.9 }]} />
              </View>
              <Pressable onPress={() => setRows((x) => x.filter((_, i) => i !== n))} accessibilityRole="button" accessibilityLabel={`Remove ${r.name.trim() || 'this one'}`} testID={`preset-remove-${n}`}>
                <Text style={{ color: t.error, fontWeight: '600' }}>{`Remove ${r.name.trim() || 'this one'}`}</Text>
              </Pressable>
            </View>
          ))}
          <Pressable onPress={() => setRows((x) => [...x, { name: '', nozzle: '', bed: '' }])} disabled={rows.length >= input.MAX_PRESETS} accessibilityRole="button" accessibilityLabel="Add a preset" accessibilityState={{ disabled: rows.length >= input.MAX_PRESETS }} testID="preset-add" style={[styles.openButton, { borderColor: t.border, alignSelf: 'flex-start', opacity: rows.length >= input.MAX_PRESETS ? 0.5 : 1 }]}>
            <Text style={{ color: t.text, fontWeight: '600' }}>Add a preset</Text>
          </Pressable>
          {rows.length === 0 ? <Text style={{ color: t.textMuted, fontSize: 13 }}>With none, Jarvis uses its own presets.</Text> : null}
          {problem ?? editor.error ?? why ? (
            <Text testID="presets-problem" style={{ color: t.error, fontSize: 13 }}>
              {(problem ?? editor.error ?? why) as string}
            </Text>
          ) : null}
        </ScrollView>
        <View style={styles.row}>
          <Pressable onPress={() => { const p = input.presets(rows); if (p) onSave(p); }} disabled={!canSave} accessibilityRole="button" accessibilityLabel="Save presets" accessibilityState={{ disabled: !canSave, busy: editor.saving }} testID="presets-save" style={[styles.saveButton, { backgroundColor: t.primary, opacity: canSave ? 1 : 0.5 }]}>
            {editor.saving ? <ActivityIndicator size="small" color={t.onPrimary} /> : null}
            <Text style={{ color: t.onPrimary, fontWeight: '700' }}>Save</Text>
          </Pressable>
          <Pressable onPress={onClose} accessibilityRole="button" accessibilityLabel="Cancel" testID="presets-cancel" style={[styles.openButton, { borderColor: t.border }]}>
            <Text style={{ color: t.text, fontWeight: '600' }}>Cancel</Text>
          </Pressable>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 8 },
  input: { borderWidth: 1, borderRadius: 8, paddingHorizontal: 10, paddingVertical: 8, minWidth: 70 },
  group: { flexDirection: 'row', alignItems: 'center', paddingVertical: 4 },
  hiddenRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  openButton: { borderWidth: 1, borderRadius: 10, paddingHorizontal: 12, paddingVertical: 8 },
  arrow: { paddingHorizontal: 10, paddingVertical: 6 },
  dialog: { flex: 1, padding: 20, gap: 12 },
  saveButton: { flexDirection: 'row', alignItems: 'center', gap: 6, borderRadius: 10, paddingHorizontal: 18, paddingVertical: 10 },
});

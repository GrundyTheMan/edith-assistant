import { useState } from 'react';
import { ActivityIndicator, Image, Pressable, ScrollView, StyleSheet, Switch, Text, TextInput, View, type LayoutChangeEvent } from 'react-native';
import { bytesToBase64 } from '../../core/bytes';
import { ChartMath, ConsoleView, FileList, MeshColors, type CameraUi, type CardsUi, type ChartLine } from '../../printer/feed/cards';
import * as format from '../../printer/feed/format';
import type { BedMesh, FilamentSlot, Preset, PrinterState, PrintObject } from '../../printer/feed/wire';
import { Connections } from '../../printer/feed/wire';
import type { Theme } from '../theme';
import { ConsoleInput } from './MacrosAndConsole';
import { CameraControls, FanControls, FileControls, LimitControls, ObjectControls, QueueControls, SensorControls, SystemControls, TemperatureControls, ToolheadControls, type ControlsCtx } from './CardControls';

/** What the cards can ask for (the page passes the card data's methods; a test passes fakes). */
export interface CardActions {
  toggleSeries(id: string): void;
  chooseCamera(id: string): void;
  moreHistory(): void;
  setHideValues(on: boolean): void;
  clearConsole(): void;
  loadFiles(fresh: boolean): void;
  setFileQuery(q: string): void;
  setPrintedOnly(on: boolean): void;
  openFile(path: string): void;
  closeFile(): void;
  setMacroQuery(q: string): void;
  setHiddenMacros(hidden: string[]): void;
  rememberConsoleLine(line: string): void;
  savePresets(presets: Preset[]): void;
  clearPresetsError(): void;
}

const lineColors = (t: Theme) => [t.primary, '#C77700', t.error, '#1B7F5C', t.text, t.textMuted];

/** A label and its value on one line. */
export function LabelValue({ label, value, theme }: { label: string; value: string; theme: Theme }) {
  return (
    <View style={styles.lv} accessible accessibilityLabel={`${label}: ${value}`}>
      <Text style={{ color: theme.textMuted, flex: 1 }}>{label}</Text>
      <Text style={{ color: theme.text }}>{value}</Text>
    </View>
  );
}

const Small = ({ children, theme, error, testID }: { children: string; theme: Theme; error?: boolean; testID?: string }) => (
  <Text testID={testID} style={{ color: error ? theme.error : theme.textMuted, fontSize: 13 }}>
    {children}
  </Text>
);

/** A straight line between two points, drawn as a rotated bar (no drawing library is needed for these few shapes). */
export function Seg({ x1, y1, x2, y2, color, thickness = 2, dashed = false }: { x1: number; y1: number; x2: number; y2: number; color: string; thickness?: number; dashed?: boolean }) {
  const len = Math.hypot(x2 - x1, y2 - y1);
  if (len < 0.5) return null;
  const angle = (Math.atan2(y2 - y1, x2 - x1) * 180) / Math.PI;
  const style = dashed
    ? { borderTopWidth: thickness, borderColor: color, borderStyle: 'dashed' as const, height: 0 }
    : { backgroundColor: color, height: thickness };
  return <View pointerEvents="none" style={[{ position: 'absolute', left: (x1 + x2) / 2 - len / 2, top: (y1 + y2) / 2 - thickness / 2, width: len, transform: [{ rotate: `${angle}deg` }] }, style]} />;
}

// ---- Camera ------------------------------------------------------------------------------------------------------------

export function CameraCard({ state, camera, actions, theme, ctx }: { state: PrinterState; camera: CameraUi; actions: CardActions; theme: Theme; ctx?: ControlsCtx }) {
  const cameras = state.cameras;
  const chosen = cameras.find((c) => c.id === camera.cameraId) ?? cameras[0];
  if (!chosen) return <Text style={{ color: theme.text }}>This printer has no camera.</Text>;
  const turned = chosen.rotation === 90 || chosen.rotation === 270;
  const aspect = Math.min(3, Math.max(0.3, turned ? 1 / chosen.aspect : chosen.aspect));
  const uri = camera.frame ? `data:image/jpeg;base64,${bytesToBase64(camera.frame)}` : null;
  return (
    <View style={{ gap: 8 }} testID="camera-card">
      {cameras.length > 1 ? (
        <View style={styles.chips}>
          {cameras.map((c) => (
            <Pressable key={c.id} onPress={() => actions.chooseCamera(c.id)} accessibilityRole="button" accessibilityState={{ selected: c.id === chosen.id }} accessibilityLabel={c.name} style={[styles.chip, { borderColor: c.id === chosen.id ? theme.primary : theme.border }]}>
              <Text style={{ color: theme.text }}>{c.name}</Text>
            </Pressable>
          ))}
        </View>
      ) : null}
      <View style={{ width: '100%', aspectRatio: aspect, alignItems: 'center', justifyContent: 'center', backgroundColor: theme.border, overflow: 'hidden', borderRadius: 8 }}>
        {uri ? (
          <Image
            key={camera.number}
            source={{ uri }}
            accessibilityLabel={`${chosen.name} picture`}
            resizeMode="contain"
            style={{ width: '100%', height: '100%', transform: [{ rotate: `${chosen.rotation}deg` }, { scaleX: chosen.flipH ? -1 : 1 }, { scaleY: chosen.flipV ? -1 : 1 }] }}
          />
        ) : null}
        {camera.offline ? (
          <Text testID="camera-offline" style={{ position: 'absolute', backgroundColor: theme.surface, color: theme.error, padding: 8, borderRadius: 6 }}>
            Camera offline
          </Text>
        ) : !uri ? (
          <Text testID="camera-connecting" style={{ color: theme.text }}>
            Connecting…
          </Text>
        ) : null}
      </View>
      {camera.offline && ctx ? <CameraControls ctx={ctx} /> : null}
    </View>
  );
}

// ---- Temperatures --------------------------------------------------------------------------------------------------------

const PLOT_HEIGHT = 160;
const AXIS = 34;
const MAX_DRAWN = 120;

/** Time runs left to right; the y axis goes from 0 to the highest value seen plus a tenth, with a line every 50 °C. A dashed line is the target. */
export function TempChart({ lines, hidden, theme }: { lines: ChartLine[]; hidden: ReadonlySet<string>; theme: Theme }) {
  const [width, setWidth] = useState(0);
  const colors = lineColors(theme);
  const yMax = ChartMath.yMax(lines, hidden);
  const plotW = Math.max(0, width - AXIS);
  const y = (v: number) => PLOT_HEIGHT - ChartMath.fraction(v, yMax) * PLOT_HEIGHT;
  const points = Math.max(0, ...lines.map((l) => l.values.length));
  return (
    <View onLayout={(e: LayoutChangeEvent) => setWidth(e.nativeEvent.layout.width)} style={{ height: PLOT_HEIGHT + 18 }} testID="temp-chart" accessibilityLabel="Temperature chart of the last 20 minutes" accessibilityRole="image">
      {ChartMath.gridlines(yMax).map((g) => (
        <View key={g} pointerEvents="none">
          <Seg x1={AXIS} y1={y(g)} x2={width} y2={y(g)} color={theme.border} thickness={1} />
          <Text style={{ position: 'absolute', left: 0, top: y(g) - 7, fontSize: 10, color: theme.textMuted }}>{g}</Text>
        </View>
      ))}
      {plotW > 0
        ? lines.map((line, idx) => {
            if (hidden.has(line.id) || line.values.length < 2) return null;
            const color = colors[idx % colors.length];
            const draw = (values: number[], dashed: boolean) => {
              const v = ChartMath.thin(values, MAX_DRAWN);
              return v.slice(1).map((val, i) => {
                const x = (k: number) => AXIS + plotW * (k / Math.max(1, v.length - 1));
                return <Seg key={`${line.id}-${dashed ? 't' : 'v'}-${i}`} x1={x(i)} y1={y(v[i])} x2={x(i + 1)} y2={y(val)} color={color} thickness={dashed ? 1.5 : 2.5} dashed={dashed} />;
              });
            };
            const targets = line.targets && line.targets.length === line.values.length && line.targets.some((t) => t > 0) ? line.targets : null;
            return (
              <View key={line.id} pointerEvents="none" style={StyleSheet.absoluteFill}>
                {draw(line.values, false)}
                {targets ? draw(targets, true) : null}
              </View>
            );
          })
        : null}
      {points >= 2 ? (
        <>
          <Text style={[styles.axisLabel, { left: AXIS, color: theme.textMuted }]}>{ChartMath.minutesAgo(0, points)}</Text>
          <Text style={[styles.axisLabel, { right: 0, color: theme.textMuted }]}>now</Text>
        </>
      ) : null}
      {lines.every((l) => hidden.has(l.id)) && lines.length ? <Text style={{ position: 'absolute', left: AXIS + 8, top: 8, color: theme.textMuted }}>Every line is hidden</Text> : null}
    </View>
  );
}

export function TemperatureCard({ state, cards, actions, theme, ctx }: { state: PrinterState; cards: CardsUi; actions: CardActions; theme: Theme; ctx?: ControlsCtx }) {
  const colors = lineColors(theme);
  const lines = cards.chart;
  return (
    <View style={{ gap: 10 }} testID="temperature-card">
      {state.heaters.map((h) => (
        <View key={h.id} style={{ gap: 2 }} accessible accessibilityLabel={`${h.label}: ${format.temperature(h.temperature)}, target ${format.target(h.target)}`}>
          <View style={styles.heaterRow}>
            <Text style={{ color: theme.text, flex: 1, fontWeight: '600' }}>{h.label}</Text>
            <Text style={{ color: theme.text, fontSize: 20 }}>{format.temperature(h.temperature)}</Text>
            <Text style={{ color: theme.textMuted }}>→ {format.target(h.target)}</Text>
          </View>
          <View style={[styles.power, { backgroundColor: theme.border }]}>
            <View style={{ height: 4, borderRadius: 2, backgroundColor: theme.primary, width: `${Math.round(Math.min(1, Math.max(0, h.power)) * 100)}%` }} />
          </View>
        </View>
      ))}
      {lines.some((l) => l.values.length >= 2) ? (
        <>
          <TempChart lines={lines} hidden={cards.chartHidden} theme={theme} />
          <View style={styles.chips}>
            {lines.map((l, i) => (
              <Pressable key={l.id} onPress={() => actions.toggleSeries(l.id)} accessibilityRole="button" accessibilityLabel={`${l.label} line`} accessibilityState={{ selected: !cards.chartHidden.has(l.id) }} style={[styles.chip, { borderColor: cards.chartHidden.has(l.id) ? theme.border : colors[i % colors.length] }]}>
                <View style={{ width: 10, height: 10, borderRadius: 5, backgroundColor: colors[i % colors.length], opacity: cards.chartHidden.has(l.id) ? 0.3 : 1 }} />
                <Text style={{ color: theme.text }}>{l.label}</Text>
              </Pressable>
            ))}
          </View>
        </>
      ) : (
        <Small theme={theme}>The chart fills in as the printer reports.</Small>
      )}
      {ctx ? <TemperatureControls state={state} presets={ctx.ui.presets ?? []} ctx={ctx} editor={cards.presets} onSavePresets={actions.savePresets} onClearPresetsError={actions.clearPresetsError} /> : null}
    </View>
  );
}

// ---- Values --------------------------------------------------------------------------------------------------------------

export function ToolheadCard({ state, theme, ctx }: { state: PrinterState; theme: Theme; ctx?: ControlsCtx }) {
  const t = state.toolhead;
  return (
    <View style={{ gap: 6 }} testID="toolhead-card">
      <View style={{ flexDirection: 'row', gap: 20 }}>
        {['X', 'Y', 'Z'].map((axis, i) => (
          <View key={axis} accessible accessibilityLabel={`${axis} ${format.position(t.position[i] ?? 0)}${t.homed.includes(axis.toLowerCase()) ? '' : ', not homed'}`}>
            <Text style={{ color: t.homed.includes(axis.toLowerCase()) ? theme.text : theme.textMuted, fontSize: 12 }}>{axis}</Text>
            <Text style={{ color: theme.text, fontSize: 16 }}>{format.position(t.position[i] ?? 0)}</Text>
          </View>
        ))}
      </View>
      <Small theme={theme}>{t.homed ? `Homed: ${t.homed.toUpperCase().split('').join(' ')}` : 'Not homed'}</Small>
      {!t.motorsOn ? <Small theme={theme}>Motors are off</Small> : null}
      {ctx ? <ToolheadControls state={state} ctx={ctx} /> : null}
    </View>
  );
}

export function FansCard({ state, theme, ctx }: { state: PrinterState; theme: Theme; ctx?: ControlsCtx }) {
  return (
    <View style={{ gap: 6 }} testID="fans-card">
      {state.fans.map((f) => (
        <LabelValue key={f.id} label={f.label} value={format.percent(f.speed) + (f.settable ? '' : ' · automatic')} theme={theme} />
      ))}
      <LabelValue label="Light" value={state.light === null ? 'No light' : state.light <= 0 ? 'Off' : format.percent(state.light)} theme={theme} />
      {state.fans.length === 0 && state.light === null ? <Small theme={theme}>This printer reports no fans.</Small> : null}
      {ctx ? <FanControls state={state} ctx={ctx} /> : null}
    </View>
  );
}

export function LimitsCard({ state, theme, ctx }: { state: PrinterState; theme: Theme; ctx?: ControlsCtx }) {
  const t = state.toolhead;
  const r = state.retraction;
  return (
    <View style={{ gap: 6 }} testID="limits-card">
      <LabelValue label="Speed" value={format.factor(t.speedFactor)} theme={theme} />
      <LabelValue label="Flow" value={format.factor(t.extrudeFactor)} theme={theme} />
      <LabelValue label="Velocity" value={`${Math.round(t.maxVelocity)} mm/s`} theme={theme} />
      <LabelValue label="Acceleration" value={`${Math.round(t.maxAccel)} mm/s²`} theme={theme} />
      <LabelValue label="Square corner velocity" value={`${format.position(t.squareCornerVelocity)} mm/s`} theme={theme} />
      <LabelValue label="Minimum cruise ratio" value={format.position(t.minimumCruiseRatio)} theme={theme} />
      {r ? (
        <>
          <Text style={{ color: theme.text, fontWeight: '600', marginTop: 6 }} accessibilityRole="header">
            Retraction
          </Text>
          <LabelValue label="Length" value={`${format.position(r.length)} mm`} theme={theme} />
          <LabelValue label="Speed" value={`${format.position(r.speed)} mm/s`} theme={theme} />
          <LabelValue label="Extra length" value={`${format.position(r.extraLength)} mm`} theme={theme} />
          <LabelValue label="Unretract speed" value={`${format.position(r.unretractSpeed)} mm/s`} theme={theme} />
        </>
      ) : null}
      {ctx ? <LimitControls state={state} ctx={ctx} /> : null}
    </View>
  );
}

export function SensorsCard({ state, theme, ctx }: { state: PrinterState; theme: Theme; ctx?: ControlsCtx }) {
  const temps = state.sensors.filter((s) => s.unit);
  return (
    <View style={{ gap: 6 }} testID="sensors-card">
      {temps.map((s) => (
        <LabelValue key={s.id} label={s.label} value={format.temperature(s.value)} theme={theme} />
      ))}
      {state.filamentSensors.map((f) => (
        <LabelValue key={f.id} label={f.label} value={`${f.detected ? 'Filament detected' : 'No filament'} · ${f.enabled ? 'enabled' : 'disabled'}`} theme={theme} />
      ))}
      {temps.length === 0 && state.filamentSensors.length === 0 ? <Small theme={theme}>This printer reports no sensors.</Small> : null}
      {ctx ? <SensorControls state={state} ctx={ctx} /> : null}
    </View>
  );
}

/** The swatch colour: Jarvis's hex, or grey when it is not a colour. */
export const swatchColor = (s: FilamentSlot): string => {
  const hex = s.hex.replace(/^#/, '');
  return /^[0-9a-fA-F]{6}$/.test(hex) ? `#${hex}` : '#808080';
};

export function SlotsCard({ state, theme }: { state: PrinterState; theme: Theme }) {
  return (
    <View style={{ gap: 8 }} testID="slots-card">
      {state.slots.map((s) => {
        const name = [s.material, s.color].filter(Boolean).join(' · ');
        return (
          <View key={s.id} style={styles.slot} accessible accessibilityLabel={`${name}${state.slotsLive ? (s.loaded ? ', loaded' : ', empty') : ''}`}>
            <View style={{ width: 22, height: 22, borderRadius: 11, backgroundColor: swatchColor(s), borderWidth: 1, borderColor: theme.border }} />
            <Text style={{ color: theme.text, flex: 1 }}>{name}</Text>
            {state.slotsLive ? <Text style={{ color: theme.textMuted }}>{s.loaded ? 'Loaded' : 'Empty'}</Text> : null}
          </View>
        );
      })}
      {!state.slotsLive ? <Small theme={theme}>The printer isn&apos;t reporting which slots have filament right now. Its screen shows them.</Small> : null}
      {state.slots.length === 0 ? <Small theme={theme}>This printer reports no filament slots.</Small> : null}
    </View>
  );
}

export function SystemCard({ state, theme, ctx }: { state: PrinterState; theme: Theme; ctx?: ControlsCtx }) {
  return (
    <View style={{ gap: 6 }} testID="system-card">
      {state.message && state.message.trim() && state.connection !== Connections.ready ? <Text style={{ color: theme.text }}>{state.message}</Text> : null}
      {state.hostname ? <LabelValue label="Host name" value={state.hostname} theme={theme} /> : null}
      {state.versions?.moonraker ? <LabelValue label="Moonraker" value={state.versions.moonraker} theme={theme} /> : null}
      {state.versions?.mcu ? <LabelValue label="MCU" value={state.versions.mcu} theme={theme} /> : null}
      {state.host ? (
        <>
          <LabelValue label="Load" value={state.host.load.toFixed(2)} theme={theme} />
          <LabelValue label="Memory free" value={`${Math.trunc(state.host.memAvailableKb / 1024)} MB`} theme={theme} />
        </>
      ) : null}
      {ctx ? <SystemControls ctx={ctx} /> : null}
    </View>
  );
}

export function QueueCard({ state, now, theme, ctx }: { state: PrinterState; now: number; theme: Theme; ctx?: ControlsCtx }) {
  const q = state.queue;
  if (!q) return null;
  return (
    <View style={{ gap: 6 }} testID="queue-card">
      <Text style={{ color: theme.text }}>{q.state === 'ready' ? 'The next job starts as soon as the printer is free' : q.state === 'paused' ? 'The queue is paused' : 'Starting the next job…'}</Text>
      {q.jobs.length === 0 ? <Small theme={theme}>No jobs are waiting.</Small> : null}
      {q.jobs.map((j) => (
        <LabelValue key={j.id} label={j.title.trim() ? j.title : j.filename} value={format.age(j.added, now)} theme={theme} />
      ))}
      <Small theme={theme}>Starting prints from here needs the printer in Local Only mode.</Small>
      {ctx ? <QueueControls state={state} ctx={ctx} /> : null}
    </View>
  );
}

// ---- Objects and the bed mesh ----------------------------------------------------------------------------------------------

const objectNote = (o: PrintObject) => (o.excluded ? 'excluded' : o.current ? 'printing now' : '');

/** The parts of this print on a drawing of the bed: the one being printed in the accent colour, excluded ones crossed out. The back of the bed is the top. */
export function ObjectsCard({ state, theme, ctx }: { state: PrinterState; theme: Theme; ctx?: ControlsCtx }) {
  const [width, setWidth] = useState(0);
  const maxX = state.toolhead.max[0] > 0 ? state.toolhead.max[0] : 220;
  const maxY = state.toolhead.max[1] > 0 ? state.toolhead.max[1] : 220;
  const height = width * (maxY / maxX);
  const px = (x: number) => (x / maxX) * width;
  const py = (y: number) => height - (y / maxY) * height;
  return (
    <View style={{ gap: 8 }} testID="objects-card">
      <View onLayout={(e) => setWidth(e.nativeEvent.layout.width)} style={{ width: '100%', height, backgroundColor: theme.border, overflow: 'hidden' }} accessibilityRole="image" accessibilityLabel={`The bed with ${state.objects.length} objects`}>
        {width > 0
          ? state.objects.map((o) => {
              const poly = o.polygon && o.polygon.length >= 3 ? o.polygon : null;
              if (!poly) return null;
              const color = o.current && !o.excluded ? theme.primary : theme.textMuted;
              const edges = poly.map((p, i) => {
                const n = poly[(i + 1) % poly.length];
                return <Seg key={`${o.name}-${i}`} x1={px(p[0])} y1={py(p[1])} x2={px(n[0])} y2={py(n[1])} color={color} thickness={o.current ? 3 : 2} />;
              });
              const xs = poly.map((p) => p[0]);
              const ys = poly.map((p) => p[1]);
              return (
                <View key={o.name} pointerEvents="none" style={[StyleSheet.absoluteFill, o.excluded && { opacity: 0.5 }]}>
                  {edges}
                  {o.excluded ? <Seg x1={px(Math.min(...xs))} y1={py(Math.min(...ys))} x2={px(Math.max(...xs))} y2={py(Math.max(...ys))} color={color} thickness={2} /> : null}
                </View>
              );
            })
          : null}
      </View>
      {state.objects.map((o) => (
        <LabelValue key={o.name} label={o.name} value={objectNote(o)} theme={theme} />
      ))}
      {state.objects.length === 0 ? <Small theme={theme}>No objects in this print.</Small> : null}
      {ctx ? <ObjectControls state={state} ctx={ctx} /> : null}
    </View>
  );
}

export function MeshCard({ mesh, theme }: { mesh: BedMesh; theme: Theme }) {
  const all = mesh.points.flat();
  const lo = all.length ? Math.min(...all) : 0;
  const hi = all.length ? Math.max(...all) : 0;
  const e = MeshColors.extremes(mesh.points);
  return (
    <View style={{ gap: 6 }} testID="mesh-card">
      {!mesh.active ? <Text style={{ color: theme.text, fontWeight: '600' }}>Saved profile (not in use now)</Text> : null}
      <Small theme={theme}>{`Profile: ${mesh.profile}`}</Small>
      <View style={{ gap: 2 }}>
        {[...mesh.points].reverse().map((row, ri) => (
          <View key={ri} style={{ flexDirection: 'row', gap: 2 }}>
            {row.map((v, ci) => {
              const [r, g, b] = MeshColors.rgb(v, lo, hi);
              return (
                <View key={ci} style={{ flex: 1, height: 28, alignItems: 'center', justifyContent: 'center', backgroundColor: `rgb(${r},${g},${b})` }} accessible accessibilityLabel={`${v.toFixed(2)} millimetres`}>
                  <Text style={{ color: '#000', fontSize: 10 }}>{v.toFixed(2)}</Text>
                </View>
              );
            })}
          </View>
        ))}
      </View>
      {e ? (
        <>
          <LabelValue label="Spread" value={`${e.spread.toFixed(3)} mm`} theme={theme} />
          <LabelValue label="Lowest" value={`${e.lowest.value.toFixed(3)} mm`} theme={theme} />
          <LabelValue label="Highest" value={`${e.highest.value.toFixed(3)} mm`} theme={theme} />
        </>
      ) : null}
    </View>
  );
}

// ---- History, console and files -----------------------------------------------------------------------------------------------

export function HistoryCard({ cards, now, actions, theme }: { cards: CardsUi; now: number; actions: CardActions; theme: Theme }) {
  const h = cards.history;
  return (
    <View style={{ gap: 6 }} testID="history-card">
      {h.totals ? (
        <>
          <LabelValue label="Jobs" value={`${h.totals.jobs}`} theme={theme} />
          <LabelValue label="Print time" value={format.duration(h.totals.printTime)} theme={theme} />
          <LabelValue label="Filament" value={format.filament(h.totals.filamentUsed)} theme={theme} />
          <LabelValue label="Longest print" value={format.duration(h.totals.longestPrint)} theme={theme} />
          <View style={{ height: StyleSheet.hairlineWidth, backgroundColor: theme.border, marginVertical: 4 }} />
        </>
      ) : null}
      {h.jobs.map((j) => (
        <View key={j.id} accessible>
          <Text style={{ color: theme.text, fontSize: 16 }}>{j.title.trim() ? j.title : j.filename}</Text>
          <Small theme={theme}>{`${j.status.replace(/_/g, ' ')} · ${format.age(j.start, now)} · ${format.duration(j.printDuration)} · ${format.filament(j.filamentUsed)}`}</Small>
        </View>
      ))}
      {h.loading ? <ActivityIndicator color={theme.textMuted} /> : null}
      {h.error ? <Small theme={theme} error>{h.error}</Small> : null}
      {h.more && !h.loading ? (
        <Pressable onPress={actions.moreHistory} accessibilityRole="button" accessibilityLabel="More jobs" style={[styles.button, { borderColor: theme.border }]}>
          <Text style={{ color: theme.primary, fontWeight: '600' }}>More</Text>
        </Pressable>
      ) : null}
      {h.jobs.length === 0 && !h.loading && !h.error ? <Small theme={theme}>No jobs yet.</Small> : null}
    </View>
  );
}

export function ConsoleCard({ cards, actions, theme, ctx }: { cards: CardsUi; actions: CardActions; theme: Theme; ctx?: ControlsCtx }) {
  const c = cards.terminal;
  const lines = ConsoleView.visible(c.lines, c.hideValues).slice(-120);
  return (
    <View style={{ gap: 6 }} testID="console-card">
      <View style={styles.heaterRow}>
        <Switch value={c.hideValues} onValueChange={actions.setHideValues} accessibilityLabel="Hide value lines" />
        <Text style={{ color: theme.text, flex: 1 }}>Hide &quot;// value&quot; lines</Text>
        <Pressable onPress={actions.clearConsole} accessibilityRole="button" accessibilityLabel="Clear console" hitSlop={8}>
          <Text style={{ color: theme.primary, fontWeight: '600' }}>Clear</Text>
        </Pressable>
      </View>
      <ScrollView style={{ maxHeight: 280 }} nestedScrollEnabled>
        {lines.map((l) => (
          <Text key={l.seq} selectable style={{ fontSize: 12, fontFamily: 'Courier', color: l.kind === 'error' ? theme.error : l.kind === 'command' ? theme.primary : theme.text }}>
            {l.text}
          </Text>
        ))}
        {lines.length === 0 ? <Small theme={theme}>Nothing yet.</Small> : null}
      </ScrollView>
      {c.error ? <Small theme={theme} error>{c.error}</Small> : null}
      {ctx ? <ConsoleInput history={c.history} ctx={ctx} onRemember={actions.rememberConsoleLine} /> : null}
    </View>
  );
}

const LISTING_NOTE = 'Listing the files keeps the printer busy for several seconds, and its buttons wait.';
const NOT_WHILE_PRINTING = 'Not while printing: the printer would stop answering for several seconds.';

/** Files: nothing is asked of Jarvis until Load files is pressed. Then a search, newest first, and the details of a file. */
export function FilesCard({ state, cards, now, actions, theme, ctx }: { state: PrinterState; cards: CardsUi; now: number; actions: CardActions; theme: Theme; ctx?: ControlsCtx }) {
  const f = cards.files;
  const printing = state.print.state === 'printing' || state.print.state === 'paused';
  const list = f.files;
  const detailTitle = list?.find((x) => x.path === f.detailOf)?.title ?? f.detailOf;
  const d = f.detail;
  return (
    <View style={{ gap: 8 }} testID="files-card">
      {list === null ? (
        <>
          <Text style={{ color: theme.text }}>{LISTING_NOTE}</Text>
          {printing ? <Small theme={theme}>{NOT_WHILE_PRINTING}</Small> : null}
          {f.loading ? (
            <ActivityIndicator color={theme.textMuted} />
          ) : (
            <Pressable onPress={() => actions.loadFiles(false)} disabled={printing} accessibilityRole="button" accessibilityLabel="Load files" accessibilityState={{ disabled: printing }} style={[styles.button, { backgroundColor: printing ? theme.border : theme.primary }]}>
              <Text style={{ color: theme.onPrimary, fontWeight: '600' }}>Load files</Text>
            </Pressable>
          )}
          {f.error ? <Small theme={theme} error>{f.error}</Small> : null}
        </>
      ) : (
        <>
          <TextInput value={f.query} onChangeText={actions.setFileQuery} placeholder="Search" placeholderTextColor={theme.textMuted} accessibilityLabel="Search files" autoCapitalize="none" autoCorrect={false} style={[styles.input, { color: theme.text, borderColor: theme.border }]} />
          <View style={styles.heaterRow}>
            <Switch value={f.printedOnly} onValueChange={actions.setPrintedOnly} accessibilityLabel="Printed before only" />
            <Text style={{ color: theme.text, flex: 1 }}>{f.printedOnly ? 'Printed before' : 'All'}</Text>
            <Small theme={theme}>{`Listed ${format.age(f.cachedAt, now)}`}</Small>
          </View>
          {(() => {
            const shown = FileList.shown(list, f.query, f.printedOnly);
            return (
              <>
                {shown.length === 0 ? <Small theme={theme}>No files match.</Small> : null}
                {shown.map((file) => (
                  <Pressable key={file.path} onPress={() => actions.openFile(file.path)} accessibilityRole="button" accessibilityLabel={`${file.title}, details`} style={[styles.fileRow, { borderBottomColor: theme.border }]}>
                    <Text style={{ color: theme.text, fontSize: 16 }}>{file.title}</Text>
                    <Small theme={theme}>{[FileList.size(file.size), format.age(file.modified, now), file.printedBefore ? 'printed before' : null].filter(Boolean).join(' · ')}</Small>
                  </Pressable>
                ))}
              </>
            );
          })()}
          <Pressable onPress={() => actions.loadFiles(true)} disabled={printing || f.loading} accessibilityRole="button" accessibilityLabel="Refresh files" accessibilityState={{ disabled: printing || f.loading }} style={[styles.button, { borderColor: theme.border, opacity: printing || f.loading ? 0.5 : 1 }]}>
            <Text style={{ color: theme.primary, fontWeight: '600' }}>Refresh</Text>
          </Pressable>
          <Small theme={theme}>{printing ? NOT_WHILE_PRINTING : LISTING_NOTE}</Small>
          {f.error ? <Small theme={theme} error>{f.error}</Small> : null}
        </>
      )}
      {f.detailOf ? (
        <View style={[styles.detail, { backgroundColor: theme.surface, borderColor: theme.border }]} testID="file-detail">
          <Text style={{ color: theme.text, fontWeight: '600', fontSize: 16 }} accessibilityRole="header">
            {detailTitle}
          </Text>
          {f.detailLoading ? <ActivityIndicator color={theme.textMuted} /> : null}
          {f.detailError ? <Small theme={theme} error>{f.detailError}</Small> : null}
          {d ? (
            <>
              {d.thumbnail ? <Image source={{ uri: d.thumbnail.startsWith('data:') ? d.thumbnail : `data:image/png;base64,${d.thumbnail}` }} accessibilityLabel="Preview of the print" style={{ width: '100%', height: 160 }} resizeMode="contain" /> : null}
              {d.estimatedTime !== null ? <LabelValue label="Estimated time" value={format.duration(d.estimatedTime)} theme={theme} /> : null}
              {d.filamentTotal !== null ? <LabelValue label="Filament" value={format.filament(d.filamentTotal)} theme={theme} /> : null}
              {d.layerCount !== null ? <LabelValue label="Layers" value={`${d.layerCount}`} theme={theme} /> : null}
              {d.estimatedTime === null && d.filamentTotal === null && d.layerCount === null && !d.thumbnail ? <Text style={{ color: theme.text }}>No details on the printer for this file</Text> : null}
            </>
          ) : null}
          {ctx && f.detailOf ? <FileControls path={f.detailOf} ctx={ctx} /> : null}
          <Pressable onPress={actions.closeFile} accessibilityRole="button" accessibilityLabel="Close details" style={[styles.button, { borderColor: theme.border }]}>
            <Text style={{ color: theme.primary, fontWeight: '600' }}>Close</Text>
          </Pressable>
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  lv: { flexDirection: 'row', gap: 8 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  chip: { flexDirection: 'row', alignItems: 'center', gap: 6, borderWidth: 1, borderRadius: 16, paddingHorizontal: 10, paddingVertical: 6 },
  heaterRow: { flexDirection: 'row', alignItems: 'flex-end', gap: 10 },
  power: { height: 4, borderRadius: 2, overflow: 'hidden' },
  axisLabel: { position: 'absolute', bottom: 0, fontSize: 10 },
  slot: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  button: { alignSelf: 'flex-start', borderWidth: 1, borderColor: 'transparent', borderRadius: 10, paddingHorizontal: 16, paddingVertical: 10 },
  input: { borderWidth: 1, borderRadius: 8, paddingHorizontal: 10, paddingVertical: 8 },
  fileRow: { paddingVertical: 8, borderBottomWidth: StyleSheet.hairlineWidth },
  detail: { borderWidth: 1, borderRadius: 10, padding: 12, gap: 8 },
});

import Slider from '@react-native-community/slider';
import { useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Switch, Text, TextInput, View } from 'react-native';
import { AirMode, Axis, HomeAxes, type PrinterCommand } from '../../printer/feed/commands';
import { controlKey, type ControlUi } from '../../printer/feed/control';
import * as format from '../../printer/feed/format';
import * as input from '../../printer/feed/input';
import type { PrinterUi } from '../../printer/feed/page';
import type { Preset, PrinterState } from '../../printer/feed/wire';
import type { Theme } from '../theme';
import { disabledReason } from './ControlViews';

/**
 * What a card needs to draw controls (C39 stage 2): the page's state, Controls, and how to send. A card draws controls only when `mayControl` (Jarvis lets this phone
 * control the printer); otherwise it stays look-only and draws nothing extra.
 */
export interface ControlsCtx {
  ui: PrinterUi;
  controls: ControlUi;
  send: (command: PrinterCommand) => void;
  theme: Theme;
}

/** A button that sends `cmd`: disabled with the reason when it cannot be used, a spinner while its change is awaited. `cmd` null means the boxes are not valid yet. */
export function CmdButton({ text, cmd, ctx, quickKey = null, danger = false, filled = false, tag, after }: { text: string; cmd: PrinterCommand | null; ctx: ControlsCtx; quickKey?: string | null; danger?: boolean; filled?: boolean; tag?: string; after?: () => void }) {
  const pending = cmd !== null && ctx.controls.pending.has(controlKey(cmd));
  const enabled = cmd !== null && disabledReason(ctx.ui, ctx.controls, quickKey) === null && !pending;
  const t = ctx.theme;
  const color = danger ? t.error : t.primary;
  return (
    <Pressable
      onPress={() => {
        if (!cmd) return;
        ctx.send(cmd);
        after?.();
      }}
      disabled={!enabled}
      accessibilityRole="button"
      accessibilityLabel={text}
      accessibilityState={{ disabled: !enabled, busy: pending }}
      testID={tag ?? `cmd:${text}`}
      style={[styles.cmd, filled ? { backgroundColor: color, borderColor: color } : { borderColor: danger ? t.error : t.border }, !enabled && { opacity: 0.5 }]}
    >
      {pending ? <ActivityIndicator size="small" color={filled ? t.onPrimary : t.textMuted} /> : null}
      <Text style={{ color: filled ? (danger ? '#ffffff' : t.onPrimary) : danger ? t.error : t.text, fontWeight: '600' }}>{text}</Text>
    </Pressable>
  );
}

/** One line saying why this group of controls cannot be used (Controls off, or Jarvis away); nothing when they can. */
export function WhyOff({ ctx, quickKey = null }: { ctx: ControlsCtx; quickKey?: string | null }) {
  const why = disabledReason(ctx.ui, ctx.controls, quickKey);
  return why ? (
    <Text testID="why-off" style={{ color: ctx.theme.textMuted, fontSize: 13 }}>
      {why}
    </Text>
  ) : null;
}

/** Jarvis's refusal of a command, shown under the control that sent it ("Home the printer first."). */
export function Refusal({ ctx, keys }: { ctx: ControlsCtx; keys: string[] }) {
  const why = keys.map((k) => ctx.controls.refused[k]).find((r) => r);
  return why ? (
    <Text testID="refusal" accessibilityRole="alert" style={{ color: ctx.theme.error, fontSize: 13 }}>
      {why}
    </Text>
  ) : null;
}

function NumberBox({ label, value, onChange, theme, tag, style }: { label: string; value: string; onChange: (v: string) => void; theme: Theme; tag: string; style?: object }) {
  return (
    <TextInput
      value={value}
      onChangeText={onChange}
      placeholder={label}
      placeholderTextColor={theme.textMuted}
      accessibilityLabel={label}
      testID={tag}
      keyboardType="decimal-pad"
      style={[styles.box, { color: theme.text, borderColor: theme.border }, style]}
    />
  );
}

/** A slider that sends only when the finger is lifted (never a command for each pixel). */
function ReleaseSlider({ value, min, max, disabled, label, tag, onRelease }: { value: number; min: number; max: number; disabled: boolean; label: string; tag: string; onRelease: (v: number) => void }) {
  const [v, setV] = useState(Math.min(max, Math.max(min, value)));
  const [seen, setSeen] = useState(value);
  if (seen !== value) {
    setSeen(value);
    setV(Math.min(max, Math.max(min, value)));
  }
  return <Slider value={v} minimumValue={min} maximumValue={max} step={1} onValueChange={setV} onSlidingComplete={onRelease} disabled={disabled} accessibilityLabel={label} testID={tag} />;
}

const Label = ({ children, ctx }: { children: string; ctx: ControlsCtx }) => (
  <Text accessibilityRole="header" style={{ color: ctx.theme.text, fontWeight: '600' }}>
    {children}
  </Text>
);
const Row = ({ children }: { children: React.ReactNode }) => <View style={styles.row}>{children}</View>;

// --- Temperatures ---

/** A target for each heater (Set, Off), the presets from Jarvis, and All off. (Editing the presets comes with the settings route.) */
export function TemperatureControls({ state, presets, ctx }: { state: PrinterState; presets: Preset[]; ctx: ControlsCtx }) {
  const [texts, setTexts] = useState<Record<string, string>>({});
  return (
    <View style={{ gap: 8 }} testID="temperature-controls">
      {state.heaters.map((h) => (
        <View key={h.id} style={{ gap: 4 }}>
          <Row>
            <NumberBox label={`${h.label} °C`} value={texts[h.id] ?? ''} onChange={(v) => setTexts((t) => ({ ...t, [h.id]: v }))} theme={ctx.theme} tag={`target-${h.id}`} style={{ flex: 1 }} />
            <CmdButton text={`Set ${h.label}`} cmd={input.setTemp(h.id, texts[h.id] ?? '', h.maxTemp)} ctx={ctx} tag={`set-${h.id}`} />
            <CmdButton text={`${h.label} off`} cmd={{ kind: 'setTemp', heater: h.id, target: 0 }} ctx={ctx} tag={`off-${h.id}`} />
          </Row>
          <Refusal ctx={ctx} keys={[`setTemp:${h.id}`]} />
        </View>
      ))}
      {presets.length > 0 ? (
        <Row>
          {presets.map((p) => (
            <CmdButton key={p.name} text={p.name} cmd={{ kind: 'preset', name: p.name }} ctx={ctx} />
          ))}
        </Row>
      ) : null}
      <Refusal ctx={ctx} keys={presets.map((p) => `preset:${p.name}`)} />
      <View style={{ alignSelf: 'flex-start' }}>
        <CmdButton text="All off" cmd={{ kind: 'heatersOff' }} ctx={ctx} quickKey="heatersOff" />
      </View>
      <WhyOff ctx={ctx} />
    </View>
  );
}

// --- Toolhead ---

/** Home, a jog cross with its step chips, the Z offset, extrude and retract, and motors off. Nothing repeats while held. */
export function ToolheadControls({ state, ctx }: { state: PrinterState; ctx: ControlsCtx }) {
  const [step, setStep] = useState(1);
  const [length, setLength] = useState('10');
  const [speed, setSpeed] = useState('5');
  // The label says Z+ and Z-, never "bed up" or "bed down": which way the bed moves depends on the printer.
  const d = (axis: string) => (axis === Axis.z && step > 50 ? 50 : step);
  const t = ctx.theme;
  return (
    <View style={{ gap: 8 }} testID="toolhead-controls">
      <Label ctx={ctx}>Home</Label>
      <Row>
        <CmdButton text="Home all" cmd={{ kind: 'home', axes: HomeAxes.all }} ctx={ctx} quickKey="home:xyz" />
        <CmdButton text="Home XY" cmd={{ kind: 'home', axes: HomeAxes.xy }} ctx={ctx} quickKey="home:xy" />
        <CmdButton text="Home Z" cmd={{ kind: 'home', axes: HomeAxes.z }} ctx={ctx} quickKey="home:z" />
      </Row>
      <Label ctx={ctx}>Move</Label>
      <Row>
        {input.STEPS.map((s) => (
          <Pressable key={s} onPress={() => setStep(s)} accessibilityRole="button" accessibilityLabel={`Step ${input.stepLabel(s)} millimetres`} accessibilityState={{ selected: step === s }} testID={`step-${input.stepLabel(s)}`} style={[styles.chip, { borderColor: step === s ? t.primary : t.border }]}>
            <Text style={{ color: t.text }}>{input.stepLabel(s)}</Text>
          </Pressable>
        ))}
      </Row>
      <Row>
        <CmdButton text="X−" cmd={{ kind: 'jog', axis: Axis.x, distance: -d(Axis.x) }} ctx={ctx} />
        <CmdButton text="X+" cmd={{ kind: 'jog', axis: Axis.x, distance: d(Axis.x) }} ctx={ctx} />
        <CmdButton text="Y−" cmd={{ kind: 'jog', axis: Axis.y, distance: -d(Axis.y) }} ctx={ctx} />
        <CmdButton text="Y+" cmd={{ kind: 'jog', axis: Axis.y, distance: d(Axis.y) }} ctx={ctx} />
      </Row>
      <Row>
        <CmdButton text="Z−" cmd={{ kind: 'jog', axis: Axis.z, distance: -d(Axis.z) }} ctx={ctx} />
        <CmdButton text="Z+" cmd={{ kind: 'jog', axis: Axis.z, distance: d(Axis.z) }} ctx={ctx} />
      </Row>
      <Refusal ctx={ctx} keys={['jog']} />
      <Label ctx={ctx}>{`Z offset ${format.position(state.toolhead.zOffset)} mm`}</Label>
      <Row>
        {input.Z_OFFSET_STEPS.map((z) => (
          <CmdButton key={`-${z}`} text={`−${input.stepLabel(z)}`} cmd={{ kind: 'zOffset', delta: -z }} ctx={ctx} tag={`zoffset:-${z}`} />
        ))}
        {input.Z_OFFSET_STEPS.map((z) => (
          <CmdButton key={`+${z}`} text={`+${input.stepLabel(z)}`} cmd={{ kind: 'zOffset', delta: z }} ctx={ctx} tag={`zoffset:+${z}`} />
        ))}
      </Row>
      <Label ctx={ctx}>Filament</Label>
      <Row>
        <NumberBox label="Length mm" value={length} onChange={setLength} theme={t} tag="extrude-length" style={{ flex: 1 }} />
        <NumberBox label="Speed mm/s" value={speed} onChange={setSpeed} theme={t} tag="extrude-speed" style={{ flex: 1 }} />
      </Row>
      <Row>
        <CmdButton text="Extrude" cmd={input.extrude(length, speed, false)} ctx={ctx} />
        <CmdButton text="Retract" cmd={input.extrude(length, speed, true)} ctx={ctx} />
      </Row>
      <Refusal ctx={ctx} keys={['extrude']} />
      <View style={{ alignSelf: 'flex-start' }}>
        <CmdButton text="Motors off" cmd={{ kind: 'motorsOff' }} ctx={ctx} quickKey="motorsOff" />
      </View>
      <WhyOff ctx={ctx} />
    </View>
  );
}

// --- Fans, light, air ---

/** Settable fans as sliders (sent on release), the light, and the air circulation. */
export function FanControls({ state, ctx }: { state: PrinterState; ctx: ControlsCtx }) {
  const off = disabledReason(ctx.ui, ctx.controls) !== null;
  return (
    <View style={{ gap: 4 }} testID="fan-controls">
      {state.fans
        .filter((f) => f.settable)
        .map((f) => (
          <View key={f.id}>
            <Label ctx={ctx}>{`${f.label} ${format.percent(f.speed)}`}</Label>
            <ReleaseSlider value={Math.round(f.speed * 100)} min={0} max={100} disabled={off} label={`${f.label} speed`} tag={`fan-${f.id}`} onRelease={(v) => ctx.send({ kind: 'fan', fan: f.id, speed: input.level(v) })} />
          </View>
        ))}
      {state.light !== null ? (
        <View style={{ gap: 4 }}>
          <Label ctx={ctx}>{`Light ${format.percent(state.light)}`}</Label>
          <ReleaseSlider value={Math.round(state.light * 100)} min={0} max={100} disabled={off} label="Light level" tag="light-slider" onRelease={(v) => ctx.send({ kind: 'light', level: input.level(v) })} />
          <Row>
            <CmdButton text="Light on" cmd={{ kind: 'light', level: 1 }} ctx={ctx} />
            <CmdButton text="Light off" cmd={{ kind: 'light', level: 0 }} ctx={ctx} />
          </Row>
        </View>
      ) : null}
      <Label ctx={ctx}>Air circulation</Label>
      <Row>
        <CmdButton text="Internal" cmd={{ kind: 'airCirculation', mode: AirMode.internal }} ctx={ctx} quickKey="air:internal" />
        <CmdButton text="External" cmd={{ kind: 'airCirculation', mode: AirMode.external }} ctx={ctx} quickKey="air:external" />
        <CmdButton text="Stop air" cmd={{ kind: 'airCirculation', mode: AirMode.stop }} ctx={ctx} quickKey="air:stop" />
      </Row>
      <WhyOff ctx={ctx} />
    </View>
  );
}

// --- Limits and retraction ---

/** Speed and flow sliders (on release), the speed limits and the retraction with Apply (Jarvis asks before it changes them). */
export function LimitControls({ state, ctx }: { state: PrinterState; ctx: ControlsCtx }) {
  const tl = state.toolhead;
  const off = disabledReason(ctx.ui, ctx.controls) !== null;
  const [f, setF] = useState({ velocity: '', accel: '', scv: '', cruise: '', rLength: '', rSpeed: '', rExtra: '', rUnretract: '' });
  const set = (k: keyof typeof f) => (v: string) => setF((x) => ({ ...x, [k]: v }));
  const t = ctx.theme;
  const half = { width: '48%' } as const;
  return (
    <View style={{ gap: 8 }} testID="limit-controls">
      <Label ctx={ctx}>{`Speed ${format.factor(tl.speedFactor)}`}</Label>
      <ReleaseSlider value={Math.round(tl.speedFactor * 100)} min={10} max={300} disabled={off} label="Speed factor" tag="speed-slider" onRelease={(v) => ctx.send({ kind: 'speedFactor', percent: input.percent(v) })} />
      <Label ctx={ctx}>{`Flow ${format.factor(tl.extrudeFactor)}`}</Label>
      <ReleaseSlider value={Math.round(tl.extrudeFactor * 100)} min={50} max={150} disabled={off} label="Flow factor" tag="flow-slider" onRelease={(v) => ctx.send({ kind: 'flowFactor', percent: input.percent(v) })} />
      <Label ctx={ctx}>Speed limits (leave a box empty to leave it as it is)</Label>
      <Row>
        <NumberBox label="Velocity mm/s" value={f.velocity} onChange={set('velocity')} theme={t} tag="limit-velocity" style={half} />
        <NumberBox label="Accel mm/s²" value={f.accel} onChange={set('accel')} theme={t} tag="limit-accel" style={half} />
        <NumberBox label="Corner velocity" value={f.scv} onChange={set('scv')} theme={t} tag="limit-scv" style={half} />
        <NumberBox label="Cruise ratio" value={f.cruise} onChange={set('cruise')} theme={t} tag="limit-cruise" style={half} />
      </Row>
      <View style={{ alignSelf: 'flex-start' }}>
        <CmdButton text="Apply limits" cmd={input.limits(f.velocity, f.accel, f.scv, f.cruise)} ctx={ctx} />
      </View>
      <Refusal ctx={ctx} keys={['limits']} />
      <Text style={{ color: t.textMuted, fontSize: 13 }}>Back to the printer&apos;s settings after a restart.</Text>
      {state.retraction ? (
        <>
          <Label ctx={ctx}>Retraction</Label>
          <Row>
            <NumberBox label="Length mm" value={f.rLength} onChange={set('rLength')} theme={t} tag="retraction-length" style={half} />
            <NumberBox label="Speed mm/s" value={f.rSpeed} onChange={set('rSpeed')} theme={t} tag="retraction-speed" style={half} />
            <NumberBox label="Extra length mm" value={f.rExtra} onChange={set('rExtra')} theme={t} tag="retraction-extra" style={half} />
            <NumberBox label="Unretract mm/s" value={f.rUnretract} onChange={set('rUnretract')} theme={t} tag="retraction-unretract" style={half} />
          </Row>
          <View style={{ alignSelf: 'flex-start' }}>
            <CmdButton text="Apply retraction" cmd={input.retraction(f.rLength, f.rSpeed, f.rExtra, f.rUnretract)} ctx={ctx} />
          </View>
          <Refusal ctx={ctx} keys={['retraction']} />
        </>
      ) : null}
      <WhyOff ctx={ctx} />
    </View>
  );
}

// --- Sensors ---

/** The filament sensor's Enabled switch (Jarvis asks first, and calls it danger when turning it off during a print). */
export function SensorControls({ state, ctx }: { state: PrinterState; ctx: ControlsCtx }) {
  if (state.filamentSensors.length === 0) return null;
  const off = disabledReason(ctx.ui, ctx.controls) !== null;
  return (
    <View style={{ gap: 4 }} testID="sensor-controls">
      {state.filamentSensors.map((fs) => (
        <View key={fs.id} style={[styles.row, { justifyContent: 'space-between' }]}>
          <Text style={{ color: ctx.theme.text, flex: 1 }}>{`${fs.label} enabled`}</Text>
          <Switch value={fs.enabled} onValueChange={(on) => ctx.send({ kind: 'filamentSensor', sensor: fs.id, enabled: on })} disabled={off} accessibilityLabel={`${fs.label} enabled`} testID={`filament-${fs.id}`} />
        </View>
      ))}
      <Refusal ctx={ctx} keys={['filamentSensor']} />
      <WhyOff ctx={ctx} />
    </View>
  );
}

// --- Objects, queue, system, camera ---

/** Exclude an object from this print (Jarvis asks, and says it cannot be undone). */
export function ObjectControls({ state, ctx }: { state: PrinterState; ctx: ControlsCtx }) {
  return (
    <View style={{ gap: 6 }} testID="object-controls">
      {state.objects
        .filter((o) => !o.excluded)
        .map((o) => (
          <View key={o.name} style={{ alignSelf: 'flex-start' }}>
            <CmdButton text={`Exclude ${o.name}`} cmd={{ kind: 'excludeObject', name: o.name }} ctx={ctx} danger />
          </View>
        ))}
      <Refusal ctx={ctx} keys={['excludeObject']} />
      <WhyOff ctx={ctx} />
    </View>
  );
}

/** Remove a waiting job, start the queue, pause the queue. */
export function QueueControls({ state, ctx }: { state: PrinterState; ctx: ControlsCtx }) {
  const q = state.queue;
  if (!q) return null;
  return (
    <View style={{ gap: 6 }} testID="queue-controls">
      {q.jobs.map((j) => (
        <View key={j.id} style={{ alignSelf: 'flex-start' }}>
          <CmdButton text={`Remove ${j.title.trim() ? j.title : j.filename}`} cmd={{ kind: 'queueRemove', jobId: j.id }} ctx={ctx} tag={`queue-remove:${j.id}`} />
        </View>
      ))}
      <Row>
        <CmdButton text="Start queue" cmd={{ kind: 'queueStart' }} ctx={ctx} quickKey="queueStart" />
        <CmdButton text="Pause queue" cmd={{ kind: 'queuePause' }} ctx={ctx} quickKey="queuePause" />
      </Row>
      <WhyOff ctx={ctx} />
    </View>
  );
}

/** Restart Klipper and Firmware restart (both asked first, as dangerous). */
export function SystemControls({ ctx }: { ctx: ControlsCtx }) {
  return (
    <View style={{ gap: 6 }} testID="system-controls">
      <Row>
        <CmdButton text="Restart Klipper" cmd={{ kind: 'restart' }} ctx={ctx} quickKey="restart" danger />
        <CmdButton text="Firmware restart" cmd={{ kind: 'firmwareRestart' }} ctx={ctx} quickKey="firmwareRestart" danger />
      </Row>
      <WhyOff ctx={ctx} />
    </View>
  );
}

/** The camera's own switch, offered when the picture says it is offline. */
export function CameraControls({ ctx }: { ctx: ControlsCtx }) {
  return (
    <View style={{ alignSelf: 'flex-start' }}>
      <CmdButton text="Turn camera on" cmd={{ kind: 'camera', on: true }} ctx={ctx} quickKey="camera:on" />
    </View>
  );
}

/** What the Files details sheet offers: Print, Add to queue and Delete, each a command Jarvis checks; the sheet closes only when Jarvis accepted it. */
export function FileControls({ path, ctx }: { path: string; ctx: ControlsCtx }) {
  return (
    <View style={{ gap: 6 }} testID="file-controls">
      <Row>
        <CmdButton text="Print" cmd={{ kind: 'start', path }} ctx={ctx} filled tag="file-print" />
        <CmdButton text="Add to queue" cmd={{ kind: 'queueAdd', path }} ctx={ctx} tag="file-queue" />
        <CmdButton text="Delete" cmd={{ kind: 'deleteFile', path }} ctx={ctx} danger tag="file-delete" />
      </Row>
      <WhyOff ctx={ctx} />
      <Refusal ctx={ctx} keys={['start', 'queueAdd', 'deleteFile']} />
    </View>
  );
}

/** Firmware restart in the banner when Klipper has stopped: the way out (Controls may be switched on in that state). */
export function FirmwareRestartButton({ ctx }: { ctx: ControlsCtx }) {
  return <CmdButton text="Firmware restart" cmd={{ kind: 'firmwareRestart' }} ctx={ctx} quickKey="firmwareRestart" danger />;
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 8 },
  cmd: { flexDirection: 'row', alignItems: 'center', gap: 6, borderWidth: 1, borderRadius: 10, paddingHorizontal: 12, paddingVertical: 8 },
  chip: { borderWidth: 1, borderRadius: 16, paddingHorizontal: 12, paddingVertical: 6 },
  box: { borderWidth: 1, borderRadius: 8, paddingHorizontal: 10, paddingVertical: 8, minWidth: 90 },
});

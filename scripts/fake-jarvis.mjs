// A pretend Jarvis "Edith feed" for trying Edith's work account on the emulator (C38), with no PC, Outlook or Jarvis.
// Speaks the contract in docs/design/jarvis-feed-spec.md over HTTPS with a throwaway self-signed certificate.
//
//   node scripts/fake-jarvis.mjs            # listens on 0.0.0.0:47821; the emulator reaches it at 10.0.2.2:47821
//
// It prints the certificate fingerprint (what Edith shows for the owner to compare) and the pairing code, which is
// always ABCDE-FGHJK. The mail, events and tasks are made-up samples. Only drafts are "written": into drafts.json here.
//
// The 3D printer (C39, contract: docs/design/jarvis-printer-feed-spec.md) is a small simulated AD5X in memory, under /v1/printer/.
//   FAKE_PRINTER=off          the printer is not shared (no `printer` entry in /v1/shared; its routes answer 403 PRINTER_NOT_SHARED)
//   FAKE_PRINTER_CONTROL=off  the printer is shared look-only (control: false)
// Every request is printed (method, path, query): that log is how to check that the camera stops polling and that Files makes no request until asked.
// Everything starts in standby. `start` begins a print (+1 % every 3 s), heaters move 2 °C a second toward their targets, `home` takes 3 s,
// `cancel` ends 3 s later, a finished print raises a `finished` event 20 s later. Camera pictures are PNGs with the time drawn on them (real
// Jarvis sends JPEGs; Edith decodes either). Recordings and the live camera stream (stage 4) are not simulated yet.
import { createHash, createHmac, randomBytes } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:https'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { deflateSync } from 'node:zlib'

const PORT = Number(process.env.PORT ?? 47821)
const CODE = 'ABCDE-FGHJK'
const dir = join(tmpdir(), 'fake-jarvis')
mkdirSync(dir, { recursive: true })
const keyFile = join(dir, 'key.pem')
const certFile = join(dir, 'cert.pem')
if (!existsSync(certFile)) {
  execFileSync('openssl', ['req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1', '-nodes', '-keyout', keyFile, '-out', certFile, '-days', '30', '-subj', '/CN=fake-jarvis'], { stdio: 'ignore' })
}
const cert = readFileSync(certFile, 'utf8')
const der = Buffer.from(cert.replace(/-----[^-]+-----/g, '').replace(/\s+/g, ''), 'base64')
const fingerprint = createHash('sha256').update(der).digest('hex')
// Paired phones are kept in a file beside the certificate, so restarting this pretend does not make a paired emulator pair again.
const tokenFile = join(dir, "tokens.json")
const tokens = new Set(existsSync(tokenFile) ? JSON.parse(readFileSync(tokenFile, "utf8")) : [])
const drafts = []
const now = Date.now()
const hour = 3_600_000
const day = 24 * hour
const midnight = new Date(); midnight.setHours(0, 0, 0, 0)

const mail = [
  { id: 'work:1:101', account: 'work', from: { name: 'Dana Whitfield', address: 'dana@corp.example' }, to: ['me@corp.example'], cc: [], subject: 'Q4 budget review: numbers attached', date: now - 2 * hour, unread: true, flagged: true, answered: false, toMe: 'to', preview: 'Hi, the Q4 numbers are in. Can you check the travel line before Thursday?', score: 8 },
  { id: 'work:1:102', account: 'work', from: { name: 'IT Service Desk', address: 'it@corp.example' }, to: ['all@corp.example'], cc: [], subject: 'Password expiry reminder', date: now - 20 * hour, unread: false, flagged: false, answered: false, toMe: 'other', preview: 'Your password expires in 7 days.', score: 3 },
  { id: 'work:1:103', account: 'work', from: { name: 'Priya Natarajan', address: 'priya@corp.example' }, to: ['me@corp.example'], cc: ['dana@corp.example'], subject: 'Lunch Friday?', date: now - 30 * hour, unread: true, flagged: false, answered: false, toMe: 'to', preview: 'Are you free for lunch on Friday to talk about the roadmap?', score: 5 },
]
const bodies = {
  'work:1:101': 'Hi,\n\nThe Q4 numbers are attached. Please check the travel line before Thursday: it looks high to me.\n\nThanks,\nDana',
  'work:1:102': 'Your password expires in 7 days. Change it at the usual place.',
  'work:1:103': 'Are you free for lunch on Friday to talk about the roadmap?\n\nPriya',
}
const events = [
  { id: 'ev1', calendar: 'cal:1', title: 'Budget review', start: midnight.getTime() + 14 * hour, end: midnight.getTime() + 15 * hour, allDay: false, location: 'Room 4B', recurring: false, joinUrl: 'https://teams.example/join/abc', organizer: 'Dana Whitfield', myResponse: 'accepted', showAs: 'busy' },
  { id: 'ev2', calendar: 'cal:1', title: 'Company holiday', start: midnight.getTime() + 2 * day, end: midnight.getTime() + 3 * day, allDay: true, recurring: false, showAs: 'free' },
  { id: 'ev3', calendar: 'cal:1', title: 'Roadmap lunch', start: midnight.getTime() + 4 * day + 12 * hour, end: midnight.getTime() + 4 * day + 13 * hour, allDay: false, recurring: false, showAs: 'busy' },
]
const tasks = [
  { id: 't1', title: 'Submit expense report', due: new Date(now + 2 * day).toISOString().slice(0, 10), done: false, importance: 'high' },
  { id: 't2', title: 'Review Priya’s roadmap draft', notes: 'Focus on the Q1 section', done: false, importance: 'normal' },
  { id: 't3', title: 'Book travel for the conference', done: true, completed: now - day, importance: 'normal' },
]

// ---------------------------------------------------------------------------------------------------------------------------------------
// The simulated printer (C39). Follows the contract and nothing else.
// ---------------------------------------------------------------------------------------------------------------------------------------
const PRINTER_ON = process.env.FAKE_PRINTER !== 'off'
const PRINTER_CONTROL = process.env.FAKE_PRINTER_CONTROL !== 'off'
const ARM_MINUTES = 1
const EPOCH = Math.floor(Date.now() / 1000)
const presets = [{ name: 'PLA', extruder: 220, bed: 60 }, { name: 'PETG', extruder: 240, bed: 80 }]
const hiddenMacros = []
const macroList = [
  { name: 'LOAD_FILAMENT', description: 'Load filament', params: [], risk: 'confirm', group: 'filament' },
  { name: 'UNLOAD_FILAMENT', description: 'Unload filament', params: [], risk: 'confirm', group: 'filament' },
  { name: 'CLEAN_NOZZLE', params: [], risk: 'confirm', group: 'print' },
  { name: 'BED_MESH_CALIBRATE', description: 'Probe the bed', params: [{ name: 'PROFILE', default: 'default' }], risk: 'danger', group: 'calibration' },
  { name: 'SET_SCREEN_BRIGHTNESS', params: [{ name: 'LEVEL', default: '50' }], risk: 'confirm', group: 'screen' },
  { name: 'SAVE_CONFIG', description: 'Save and restart', params: [], risk: 'danger', group: 'system' },
]
const macroGroups = [['filament', 'Filament and nozzle'], ['print', 'Printing'], ['calibration', 'Bed and calibration'], ['screen', 'Camera, screen and sound'], ['system', 'System'], ['other', 'Other']]
const fileDefs = [
  { path: 'benchy.gcode', size: 2_400_000, modified: now - 3 * day, title: 'Benchy', kind: 'gcode', printedBefore: true },
  { path: '3mf/model/Chimney/Metadata/plate_1.gcode', size: 5_100_000, modified: now - 2 * hour, title: 'Chimney and Platforms (plate 1)', kind: 'gcode', printedBefore: false },
  { path: 'holder.3mf', size: 900_000, modified: now - 9 * day, title: 'Holder', kind: '3mf', printedBefore: false },
]
const P = {
  rev: 1, consoleSeq: 0, waiters: [], eventWaiters: [], armedUntil: 0, lastListing: 0, filesCache: null, answers: new Map(),
  state: 'standby', filename: null, title: null, progress: 0, printStart: 0, pausedAt: 0, completeAt: 0, cancelAt: 0,
  homed: 'xyz', homeAt: 0, pos: [110, 110, 5], motors: true, light: 0, fans: { fanM106: 0 }, air: 'stop', camera: true,
  speed: 1, flow: 1, zOffset: 0, limits: { velocity: 300, accel: 3000, scv: 5, cruiseRatio: 0.5 }, retraction: { length: 0.8, speed: 35, extraLength: 0, unretractSpeed: 35 },
  nozzle: 24, nozzleTarget: 0, bed: 23, bedTarget: 0, excluded: new Set(), filamentSensor: true,
  queue: { state: 'ready', jobs: [] }, events: [], eventSeq: EPOCH, console: [], series: { extruder: [], heater_bed: [], head: [] },
}
const consoleAdd = (text, kind = 'response') => { P.console.push({ seq: ++P.consoleSeq, time: Date.now(), text, kind }); if (P.console.length > 500) P.console.shift() }
for (const [text, kind] of [['// Klipper state: Ready', 'response'], ['G28', 'command'], ['ok', 'response'], ['M105', 'command'], ['ok T:24.0 /0.0 B:23.0 /0.0', 'response'], ['!! Filament runout (an old message)', 'error']]) consoleAdd(text, kind)
const bump = () => { P.rev++; const w = P.waiters; P.waiters = []; for (const f of w) f() }
const printing = () => P.state === 'printing' || P.state === 'paused'
const activeObjects = () => [{ name: 'Chimney', center: [100, 100], polygon: [[90, 90], [110, 90], [110, 110], [90, 110]], excluded: P.excluded.has('Chimney'), current: true }, { name: 'Platform', center: [150, 120], polygon: [[140, 110], [160, 110], [160, 130], [140, 130]], excluded: P.excluded.has('Platform'), current: false }]
const layer = () => ({ current: Math.floor(P.progress * 240), total: 240 })
const timeLeft = () => (P.progress > 0.01 ? Math.round(((1 - P.progress) / P.progress) * (Date.now() - P.printStart) / 1000) : null)
const printerState = () => ({
  connection: 'ready', hostname: 'fake-ad5x',
  print: { state: P.state, ...(P.filename ? { filename: P.filename, title: P.title } : {}), progress: P.progress, printDuration: P.printStart ? Math.round((Date.now() - P.printStart) / 1000) : 0, totalDuration: P.printStart ? Math.round((Date.now() - P.printStart) / 1000) : 0, timeLeft: printing() ? timeLeft() : null, timeLeftFrom: 'progress', filamentUsed: Math.round(P.progress * 12000), ...(printing() ? { layer: layer() } : {}), paused: P.state === 'paused', busy: printing() || P.homeAt > Date.now() },
  heaters: [{ id: 'extruder', label: 'Nozzle', temperature: Math.round(P.nozzle * 10) / 10, target: P.nozzleTarget, power: P.nozzleTarget > P.nozzle ? 0.8 : 0.1, maxTemp: 300 }, { id: 'heater_bed', label: 'Bed', temperature: Math.round(P.bed * 10) / 10, target: P.bedTarget, power: P.bedTarget > P.bed ? 0.6 : 0.05, maxTemp: 120 }],
  sensors: [{ id: 'temperature_sensor head', label: 'Head', value: 25.5, unit: '°C' }, { id: 'temperature_sensor weightValue', label: 'Weight value', value: 0, unit: '' }],
  fans: [{ id: 'fan_generic fanM106', label: 'Part cooling', speed: P.fans.fanM106, settable: true }, { id: 'heater_fan heat_fan', label: 'Hotend', speed: P.nozzle > 60 ? 1 : 0, settable: false }],
  light: P.light,
  toolhead: { position: P.pos.map((v) => Math.round(v * 100) / 100), homed: P.homeAt > Date.now() ? '' : P.homed, min: [0, 0, 0], max: [220, 220, 220], liveVelocity: P.state === 'printing' ? 85 : 0, maxVelocity: P.limits.velocity, maxAccel: P.limits.accel, squareCornerVelocity: P.limits.scv, minimumCruiseRatio: P.limits.cruiseRatio, speedFactor: P.speed, extrudeFactor: P.flow, zOffset: P.zOffset, motorsOn: P.motors },
  extruder: { canExtrude: P.nozzle >= 170, minExtrudeTemp: 0, nozzleDiameter: 0.4 },
  retraction: P.retraction,
  filamentSensors: [{ id: 'filament_switch_sensor head_switch_sensor', label: 'Head switch sensor', detected: true, enabled: P.filamentSensor }],
  objects: printing() ? activeObjects() : [],
  slots: [['1', 'PLA', 'red', 'F72224', true], ['2', 'PETG', 'blue', '2255F7', true], ['3', 'ABS', 'black', '222222', false], ['4', 'PLA', 'white', 'F2F2F2', true]].map(([id, material, color, hex, loaded]) => ({ id, material, color, hex, loaded })),
  slotsLive: true,
  ...(P.alert ? { alert: P.alert } : {}),
  host: { load: 0.4, memAvailableKb: 120_000 }, versions: { moonraker: 'v0.8.0-fake', mcu: 'v0.12.0-fake' },
  cameras: [{ id: 'video', name: 'Camera', aspect: 1.3333, rotation: 0, flipH: false, flipV: false }, { id: 'screen', name: 'Printer screen', aspect: 1.6667, rotation: 0, flipH: false, flipV: false }],
  mesh: { profile: 'default', active: true, min: [20, 20], max: [200, 200], points: [[0.05, 0.02, -0.01, -0.03, -0.05], [0.04, 0.01, -0.02, -0.04, -0.06], [0.02, 0, -0.01, -0.03, -0.05], [0.01, -0.01, -0.02, -0.02, -0.04], [0, -0.02, -0.03, -0.03, -0.03]] },
  queue: P.queue,
})
// What the check says about a command now: refusal (reason), risk and the owner's question.
const AXIS_WORDS = { xyz: 'all axes', xy: 'X and Y', z: 'Z' }
function check(c) {
  const no = (reason) => ({ allowed: false, risk: 'none', reason })
  const ask = (risk, question, confirm) => ({ allowed: true, risk, question, confirm })
  const ok = { allowed: true, risk: 'none' }
  const moving = () => (P.homeAt > Date.now() ? 'The printer is homing. Wait a moment.' : P.homed !== 'xyz' ? 'Home the printer first.' : null)
  switch (c.kind) {
    case 'pause': return P.state === 'printing' ? ok : no('Nothing is printing.')
    case 'resume': return P.state === 'paused' ? ok : no('The print isn’t paused.')
    case 'cancel': return printing() ? ask('danger', 'Cancel the print? It can’t be continued.', 'Cancel print') : no('Nothing is printing.')
    case 'start': return printing() ? no('Not while printing.') : c.path.endsWith('.3mf') ? no('Start .3mf files from the printer’s screen.') : ask('confirm', `Start printing ${c.path}?`, 'Print')
    case 'estop': return ask('danger', 'Emergency stop? The printer stops at once and must be restarted.', 'Emergency stop')
    case 'restart': return ask('danger', 'Restart Klipper?', 'Restart')
    case 'firmwareRestart': return ask('danger', 'Restart the printer’s firmware?', 'Restart')
    case 'setTemp': return c.target < 0 || c.target > 300 ? no('That temperature is too high.') : ask('confirm', c.target === 0 ? `Turn the ${c.heater} off?` : `Heat the ${c.heater} to ${c.target} °C?`, 'Heat')
    case 'preset': { const p = presets.find((x) => x.name === c.name); return p ? ask('confirm', `Heat the nozzle to ${p.extruder} °C and the bed to ${p.bed} °C?`, 'Heat') : no('There is no such preset.') }
    case 'heatersOff': return ask('confirm', 'Turn both heaters off?', 'Heaters off')
    case 'home': return printing() ? no('Not while printing.') : ask('confirm', `Home ${AXIS_WORDS[c.axes]}?`, 'Home')
    case 'jog': return printing() ? no('Not while printing.') : moving() ? no(moving()) : ok
    case 'zOffset': return ok
    case 'extrude': return printing() ? no('Not while printing.') : P.nozzle < 170 ? no('Heat the nozzle to at least 170 °C first.') : ask('confirm', `${c.length < 0 ? 'Retract' : 'Extrude'} ${Math.abs(c.length)} mm?`, c.length < 0 ? 'Retract' : 'Extrude')
    case 'motorsOff': return printing() ? no('Not while printing.') : ask('confirm', 'Turn the motors off? The printer will need homing again.', 'Motors off')
    case 'speedFactor': case 'flowFactor': return c.percent < 10 || c.percent > 300 ? no('That is outside what the printer allows.') : ok
    case 'limits': return ask('confirm', 'Change the printer’s speed limits until it restarts?', 'Change')
    case 'retraction': return ask('confirm', 'Change the retraction settings?', 'Change')
    case 'fan': case 'light': case 'camera': case 'airCirculation': case 'queueRemove': case 'queuePause': return ok
    case 'filamentSensor': return ask(printing() && !c.enabled ? 'danger' : 'confirm', c.enabled ? 'Turn the filament sensor on?' : 'Turn the filament sensor off?', c.enabled ? 'Turn on' : 'Turn off')
    case 'macro': { const m = macroList.find((x) => x.name === c.name); return m ? ask(m.risk, `Run ${c.name}?`, 'Run') : no('That macro isn’t known.') }
    case 'excludeObject': return printing() ? ask('danger', `Stop printing ${c.name}? This can’t be undone for this print.`, 'Exclude') : no('Nothing is printing.')
    case 'gcode': return /^(M112|FIRMWARE_RESTART|RESTART)\b/i.test(c.script) ? no('Use the System card or the printer’s screen for this.') : ask('confirm', `Send ${c.script}?`, 'Send')
    case 'deleteFile': return ask('danger', `Delete ${c.path}? It can’t be brought back.`, 'Delete')
    case 'screenTap': return ok
    case 'queueAdd': return ask('confirm', `Add ${c.path} to the queue?`, 'Add')
    case 'queueStart': return ask('confirm', 'Start the queue?', 'Start')
    default: return no('That command isn’t known.')
  }
}
const quickCommands = { pause: { kind: 'pause' }, resume: { kind: 'resume' }, cancel: { kind: 'cancel' }, estop: { kind: 'estop' }, 'home:xyz': { kind: 'home', axes: 'xyz' }, 'home:xy': { kind: 'home', axes: 'xy' }, 'home:z': { kind: 'home', axes: 'z' }, motorsOff: { kind: 'motorsOff' }, heatersOff: { kind: 'heatersOff' }, restart: { kind: 'restart' }, firmwareRestart: { kind: 'firmwareRestart' }, queueStart: { kind: 'queueStart' }, queuePause: { kind: 'queuePause' }, 'camera:on': { kind: 'camera', on: true }, 'camera:off': { kind: 'camera', on: false } }
const quick = () => Object.fromEntries(Object.entries(quickCommands).map(([k, c]) => { const r = check(c); return [k, r.allowed ? { allowed: true } : { allowed: false, reason: r.reason }] }))
const arm = () => (P.armedUntil > Date.now() ? { armed: true, until: P.armedUntil } : { armed: false })
const stateAnswer = () => ({ rev: P.rev, epoch: EPOCH, state: printerState(), arm: arm(), quick: quick(), consoleSeq: P.consoleSeq, serverTime: Date.now() })
// What each command does to the pretend printer; returns the line Jarvis would say it sent.
function apply(c) {
  consoleAdd(c.kind === 'gcode' ? c.script : c.kind, 'command')
  switch (c.kind) {
    case 'pause': P.state = 'paused'; P.pausedAt = Date.now(); return 'PAUSE'
    case 'resume': P.state = 'printing'; return 'RESUME'
    case 'cancel': P.cancelAt = Date.now() + 3000; return 'CANCEL_PRINT'
    case 'start': P.state = 'printing'; P.filename = c.path; P.title = fileDefs.find((f) => f.path === c.path)?.title ?? c.path; P.progress = 0; P.printStart = Date.now(); P.excluded.clear(); P.nozzleTarget = 220; P.bedTarget = 60; return `SDCARD_PRINT_FILE FILENAME=${c.path}`
    case 'estop': P.state = 'error'; P.nozzleTarget = 0; P.bedTarget = 0; P.alert = { text: 'Emergency stop', time: Date.now() }; return 'M112'
    case 'restart': case 'firmwareRestart': return c.kind === 'restart' ? 'RESTART' : 'FIRMWARE_RESTART'
    case 'setTemp': if (c.heater === 'extruder') P.nozzleTarget = c.target; else P.bedTarget = c.target; return `SET_HEATER_TEMPERATURE HEATER=${c.heater} TARGET=${c.target}`
    case 'preset': { const p = presets.find((x) => x.name === c.name); P.nozzleTarget = p.extruder; P.bedTarget = p.bed; return `PRESET ${c.name}` }
    case 'heatersOff': P.nozzleTarget = 0; P.bedTarget = 0; return 'TURN_OFF_HEATERS'
    case 'home': P.homeAt = Date.now() + 3000; P.homed = c.axes === 'xyz' ? 'xyz' : P.homed; return 'G28'
    case 'jog': { const i = 'xyz'.indexOf(c.axis); P.pos[i] = Math.max(0, Math.min(220, P.pos[i] + c.distance)); return `G1 ${c.axis.toUpperCase()}${c.distance}` }
    case 'zOffset': P.zOffset = Math.round((P.zOffset + c.delta) * 1000) / 1000; return `SET_GCODE_OFFSET Z_ADJUST=${c.delta}`
    case 'extrude': return `G1 E${c.length} F${c.speed * 60}`
    case 'motorsOff': P.motors = false; P.homed = ''; return 'M84'
    case 'speedFactor': P.speed = c.percent / 100; return `M220 S${c.percent}`
    case 'flowFactor': P.flow = c.percent / 100; return `M221 S${c.percent}`
    case 'limits': for (const [k, v] of Object.entries({ velocity: c.velocity, accel: c.accel, scv: c.scv, cruiseRatio: c.cruiseRatio })) if (v !== undefined) P.limits[k] = v; return 'SET_VELOCITY_LIMIT'
    case 'retraction': for (const k of ['length', 'speed', 'extraLength', 'unretractSpeed']) if (c[k] !== undefined) P.retraction[k] = c[k]; return 'SET_RETRACTION'
    case 'fan': P.fans.fanM106 = c.speed; return `SET_FAN_SPEED FAN=fanM106 SPEED=${c.speed}`
    case 'light': P.light = c.level; return `SET_LIGHT LEVEL=${c.level}`
    case 'airCirculation': P.air = c.mode; return `AIR_CIRCULATION MODE=${c.mode}`
    case 'filamentSensor': P.filamentSensor = c.enabled; return 'SET_FILAMENT_SENSOR'
    case 'camera': P.camera = c.on; return c.on ? 'CAMERA_ON' : 'CAMERA_OFF'
    case 'macro': return `${c.name}${Object.entries(c.params ?? {}).map(([k, v]) => ` ${k}=${v}`).join('')}`
    case 'excludeObject': P.excluded.add(c.name); return `EXCLUDE_OBJECT NAME=${c.name}`
    case 'gcode': return c.script
    case 'deleteFile': { const i = fileDefs.findIndex((f) => f.path === c.path); if (i >= 0) fileDefs.splice(i, 1); P.filesCache = null; return 'DELETE_FILE' }
    case 'screenTap': return `SCREEN_TAP ${c.x} ${c.y}`
    case 'queueAdd': P.queue.jobs.push({ id: String(P.queue.jobs.length + 1).padStart(6, '0'), filename: c.path, title: fileDefs.find((f) => f.path === c.path)?.title ?? c.path, added: Date.now() }); return 'QUEUE_ADD'
    case 'queueRemove': P.queue.jobs = P.queue.jobs.filter((j) => j.id !== c.jobId); return 'QUEUE_REMOVE'
    case 'queueStart': P.queue.state = 'ready'; return 'QUEUE_START'
    case 'queuePause': P.queue.state = 'paused'; return 'QUEUE_PAUSE'
  }
  return c.kind
}
const KINDS = ['pause', 'resume', 'cancel', 'start', 'estop', 'restart', 'firmwareRestart', 'setTemp', 'preset', 'heatersOff', 'home', 'jog', 'zOffset', 'extrude', 'motorsOff', 'speedFactor', 'flowFactor', 'limits', 'retraction', 'fan', 'light', 'airCirculation', 'filamentSensor', 'camera', 'macro', 'excludeObject', 'gcode', 'deleteFile', 'screenTap', 'queueAdd', 'queueRemove', 'queueStart', 'queuePause']
// The simulation: heaters move toward their targets, a print advances, a finished print raises an event.
const pushEvent = (kind, title, text) => { P.events.push({ seq: ++P.eventSeq, time: Date.now(), kind, title, text }); if (P.events.length > 50) P.events.shift(); const w = P.eventWaiters; P.eventWaiters = []; for (const f of w) f() }
let lastTick = Date.now()
let lastBump = 0
setInterval(() => {
  const t = Date.now(); const dt = (t - lastTick) / 1000; lastTick = t
  const step = (v, target, rate) => (v < target ? Math.min(target, v + rate * dt) : Math.max(target, v - rate * dt * 0.3, target > 24 ? target : 24))
  P.nozzle = step(P.nozzle, P.nozzleTarget, 2); P.bed = step(P.bed, P.bedTarget, 1)
  let meaningful = false
  if (P.state === 'printing') {
    if (P.nozzle >= P.nozzleTarget - 5) P.progress = Math.min(1, P.progress + dt / 300)
    if (P.progress >= 1) { P.state = 'complete'; P.completeAt = t; P.nozzleTarget = 0; P.bedTarget = 0; meaningful = true; consoleAdd('// Print complete') }
  }
  if (P.cancelAt && t >= P.cancelAt) { P.cancelAt = 0; P.state = 'standby'; P.progress = 0; P.filename = null; P.title = null; P.nozzleTarget = 0; P.bedTarget = 0; meaningful = true }
  if (P.completeAt && t - P.completeAt >= 20_000) { P.completeAt = 0; pushEvent('finished', 'Print finished', `${P.title ?? 'The print'} is done.`) }
  if (P.homeAt && t >= P.homeAt) { P.homeAt = 0; meaningful = true }
  if (P.armedUntil && t >= P.armedUntil) { P.armedUntil = 0; meaningful = true }
  for (const [id, v] of [['extruder', P.nozzle], ['heater_bed', P.bed], ['head', 25.5]]) { P.series[id].push(Math.round(v * 10) / 10); if (P.series[id].length > 1200) P.series[id].shift() }
  // Live readings alone are announced at most every 2 s, like Jarvis.
  if (meaningful || (t - lastBump >= 2000 && (printing() || Math.abs(P.nozzle - P.nozzleTarget) > 0.5 || Math.abs(P.bed - P.bedTarget) > 0.5))) { lastBump = t; bump() }
}, 500).unref()
// A small PNG with the time drawn on it (3x5 digits), so a new picture is visibly new.
const DIGITS = { 0: '111101101101111', 1: '010110010010111', 2: '111001111100111', 3: '111001111001111', 4: '101101111001001', 5: '111100111001111', 6: '111100111101111', 7: '111001001001001', 8: '111101111101111', 9: '111101111001111', ':': '000010000010000' }
function picture(label) {
  const W = 200, H = 120, S = 6; const raw = Buffer.alloc((W * 3 + 1) * H)
  for (let y = 0; y < H; y++) { raw[y * (W * 3 + 1)] = 0; for (let x = 0; x < W; x++) { const o = y * (W * 3 + 1) + 1 + x * 3; raw[o] = 30 + y; raw[o + 1] = 60 + (x >> 1); raw[o + 2] = 110 } }
  let cx = 8
  for (const ch of label) { const g = DIGITS[ch]; if (g) for (let gy = 0; gy < 5; gy++) for (let gx = 0; gx < 3; gx++) if (g[gy * 3 + gx] === '1') for (let dy = 0; dy < S; dy++) for (let dx = 0; dx < S; dx++) { const o = (50 + gy * S + dy) * (W * 3 + 1) + 1 + (cx + gx * S + dx) * 3; raw[o] = raw[o + 1] = raw[o + 2] = 255 } cx += 4 * S - 2 }
  const crcTable = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0 })
  const crc = (b) => { let c = 0xffffffff; for (const x of b) c = crcTable[(c ^ x) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0 }
  const chunk = (type, data) => { const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([len, td, c]) }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(W, 0); ihdr.writeUInt32BE(H, 4); ihdr[8] = 8; ihdr[9] = 2
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))])
}
const printerError = (res, status, code, message, extra = {}) => send(res, status, { error: { code, message }, ...extra })
// Answers a printer route; returns false when the path isn't one. `token` is the phone's token (its arming is per phone, here one).
function printerRoute(req, res, path, url, body, token) {
  if (!path.startsWith('/v1/printer/')) return false
  if (!PRINTER_ON) { printerError(res, 403, 'PRINTER_NOT_SHARED', 'Jarvis isn’t sharing the printer.'); return true }
  const route = path.slice('/v1/printer/'.length); const q = url.searchParams; const method = req.method
  const hold = (waiters, wait, answer, same) => { // long poll: answered when something changes or when the wait ends
    const w = Math.min(25, Math.max(0, Number(q.get('wait') ?? 0)))
    let done = false
    const finish = () => { if (!done) { done = true; clearTimeout(timer); send(res, 200, answer()) } }
    const timer = setTimeout(finish, w * 1000)
    waiters.push(finish); res.on('close', () => { done = true; clearTimeout(timer) })
  }
  if (method === 'GET' && route === 'state') {
    const after = q.get('after'); const epoch = q.get('epoch')
    if (after === null || Number(after) !== P.rev || (epoch !== null && Number(epoch) !== EPOCH) || !(Number(q.get('wait')) > 0)) return send(res, 200, stateAnswer()), true
    hold(P.waiters, q.get('wait'), stateAnswer); return true
  }
  if (method === 'GET' && route === 'temperatures') return send(res, 200, { series: [{ id: 'extruder', label: 'Nozzle', temperatures: P.series.extruder, targets: P.series.extruder.map(() => P.nozzleTarget) }, { id: 'heater_bed', label: 'Bed', temperatures: P.series.heater_bed, targets: P.series.heater_bed.map(() => P.bedTarget) }, { id: 'temperature_sensor head', label: 'Head', temperatures: P.series.head }] }), true
  if (method === 'GET' && route === 'macros') {
    const visible = macroList.filter((m) => !hiddenMacros.includes(m.name))
    return send(res, 200, { macros: visible, groups: macroGroups.filter(([id]) => visible.some((m) => m.group === id)).map(([id, title]) => ({ id, title })), hidden: hiddenMacros }), true
  }
  if (method === 'GET' && route === 'console') {
    const after = q.get('after'); const count = Number(q.get('count') ?? 200)
    const lines = (after === null ? P.console : P.console.filter((l) => l.seq > Number(after))).slice(-count)
    return send(res, 200, { lines, seq: P.consoleSeq }), true
  }
  if (method === 'GET' && route === 'files') {
    if (!P.filesCache || q.get('fresh') === '1') {
      if (printing()) return printerError(res, 422, 'REFUSED', 'Not while printing: the printer would stop answering for several seconds.'), true
      if (Date.now() - P.lastListing < 60_000 && P.lastListing) return printerError(res, 429, 'TOO_MANY', 'The files were listed a moment ago. Wait a minute before listing them again.'), true
      P.lastListing = Date.now(); P.filesCache = { files: fileDefs.map((f) => ({ ...f })), cachedAt: Date.now() }
    }
    return send(res, 200, { files: P.filesCache.files, cachedAt: P.filesCache.cachedAt }), true
  }
  if (method === 'GET' && route === 'files/detail') {
    const path2 = q.get('path') ?? ''
    if (!P.filesCache?.files.some((f) => f.path === path2)) return printerError(res, 404, 'NOT_FOUND', 'That file isn’t in the list. Load the files first.'), true
    return send(res, 200, path2.endsWith('.3mf') ? { path: path2 } : { path: path2, estimatedTime: 5400, filamentTotal: 12000, layerCount: 240 }), true
  }
  if (method === 'GET' && route === 'history') {
    const start = Number(q.get('start') ?? 0)
    const jobs = Array.from({ length: 5 }, (_, i) => ({ id: String(123 - i - start).padStart(6, '0'), filename: 'benchy.gcode', title: 'Benchy', status: i === 2 ? 'cancelled' : 'completed', start: now - (i + 1) * day, end: now - (i + 1) * day + hour, printDuration: 3600 - i * 100, filamentUsed: 4500 }))
    return send(res, 200, { jobs: start >= 5 ? [] : jobs, totals: { jobs: 966, printTime: 3_600_000, filamentUsed: 9_000_000, longestPrint: 50_000 } }), true
  }
  if (method === 'GET' && /^camera\/[^/]+\/snapshot$/.test(route)) {
    if (!P.camera && route.includes('video')) return printerError(res, 502, 'PRINTER_UNREACHABLE', 'The camera isn’t answering.'), true
    const d = new Date(); const label = [d.getHours(), d.getMinutes(), d.getSeconds()].map((n) => String(n).padStart(2, '0')).join(':')
    res.writeHead(200, { 'Content-Type': 'image/png', 'Cache-Control': 'no-store' }); res.end(picture(label)); return true
  }
  if (method === 'POST' && route === 'controls') {
    if (!PRINTER_CONTROL) return printerError(res, 403, 'CONTROL_OFF', 'Jarvis lets this phone look, not control.'), true
    const on = JSON.parse(body || '{}').on === true
    P.armedUntil = on ? Date.now() + ARM_MINUTES * 60_000 : 0; bump()
    return send(res, 200, { arm: arm() }), true
  }
  if (method === 'POST' && route === 'settings') {
    if (!PRINTER_CONTROL) return printerError(res, 403, 'CONTROL_OFF', 'Jarvis lets this phone look, not control.'), true
    if (!arm().armed) return printerError(res, 409, 'CONTROLS_OFF', 'Turn on Controls first.'), true
    const b = JSON.parse(body || '{}')
    if (b.presets) presets.splice(0, presets.length, ...(b.presets.length ? b.presets : [{ name: 'PLA', extruder: 220, bed: 60 }]))
    if (b.hiddenMacros) hiddenMacros.splice(0, hiddenMacros.length, ...b.hiddenMacros.map((n) => String(n).toUpperCase()))
    bump(); return send(res, 200, { presets, hiddenMacros }), true
  }
  if (method === 'POST' && route === 'command') {
    let b; try { b = JSON.parse(body || '{}') } catch { return printerError(res, 400, 'BAD_REQUEST', 'The command wasn’t understood.'), true }
    const dry = b.dryRun === true; const via = b.via === 'chat' ? 'chat' : 'phone'
    if (!KINDS.includes(b.command?.kind)) return printerError(res, 400, 'BAD_REQUEST', 'That command isn’t known.'), true
    const c = b.command
    if (c.kind !== 'estop' && !PRINTER_CONTROL) return printerError(res, 403, 'CONTROL_OFF', 'Jarvis lets this phone look, not control.'), true
    if (via === 'chat' && !['pause', 'resume', 'cancel', 'light'].includes(c.kind)) return printerError(res, 422, 'REFUSED', 'Edith’s chat can’t do that.'), true
    if (!dry && !(typeof b.requestId === 'string' && b.requestId)) return printerError(res, 400, 'BAD_REQUEST', 'requestId is needed (a new one for each command, the same when it is sent again).'), true
    const checked = check(c)
    if (dry) return send(res, 200, checked), true
    const earlier = P.answers.get(b.requestId); if (earlier) return send(res, earlier.status, earlier.body), true
    if (via === 'phone' && c.kind !== 'estop' && !arm().armed) return printerError(res, 409, 'CONTROLS_OFF', 'Turn on Controls first.'), true
    if (!checked.allowed) return printerError(res, 422, 'REFUSED', checked.reason), true
    if ((checked.risk === 'confirm' || checked.risk === 'danger') && b.confirmed !== true) return printerError(res, 409, 'CONFIRM_REQUIRED', 'Ask the owner to confirm this first.', { check: checked }), true
    if (P.armedUntil) P.armedUntil = Date.now() + ARM_MINUTES * 60_000
    const sent = apply(c); bump()
    const answer = { sent, risk: checked.risk }; P.answers.set(b.requestId, { status: 200, body: answer })
    return send(res, 200, answer), true
  }
  if (method === 'GET' && route === 'events') {
    const after = q.get('after'); const answer = () => ({ seq: P.eventSeq, events: after === null ? P.events : P.events.filter((e) => e.seq > Number(after)) })
    if (after === null || Number(after) < P.eventSeq || !(Number(q.get('wait')) > 0)) return send(res, 200, answer()), true
    hold(P.eventWaiters, q.get('wait'), answer); return true
  }
  if (method === 'POST' && route === 'files/upload') {
    if (!PRINTER_CONTROL) return printerError(res, 403, 'CONTROL_OFF', 'Jarvis lets this phone look, not control.'), true
    if (!arm().armed) return printerError(res, 409, 'CONTROLS_OFF', 'Turn on Controls first.'), true
    const name = q.get('name') ?? ''
    if (!/^[A-Za-z0-9 _.()+-]{1,100}\.(gcode|3mf)$/.test(name)) return printerError(res, 400, 'BAD_REQUEST', 'That file name isn’t allowed.'), true
    if (body.length > 64 * 1024 * 1024) return printerError(res, 413, 'TOO_LARGE', 'That file is over 64 MB.'), true
    fileDefs.push({ path: name, size: body.length, modified: Date.now(), title: name.replace(/\.(gcode|3mf)$/, ''), kind: name.endsWith('.3mf') ? '3mf' : 'gcode', printedBefore: false }); P.filesCache = null
    return send(res, 200, { path: name }), true
  }
  printerError(res, 404, 'NOT_FOUND', 'The pretend printer has no such route yet.'); return true
}

const send = (res, status, body) => { res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(body)) }
const err = (res, status, code, message) => send(res, status, { error: { code, message } })

createServer({ key: readFileSync(keyFile), cert }, (req, res) => {
  const url = new URL(req.url, 'https://x')
  const path = url.pathname.replace(/\/+$/, '')
  let body = ''
  req.on('data', (c) => (body += c))
  req.on('end', () => {
    console.log(req.method, path, url.search)
    if (path === '/v1/hello') return send(res, 200, { app: 'jarvis-feed', version: 1 })
    if (path === '/v1/pair' && req.method === 'POST') {
      const want = createHmac('sha256', CODE.replace('-', '')).update(fingerprint).digest('hex')
      if (JSON.parse(body || '{}').proof !== want) return err(res, 403, 'PAIRING_REFUSED', 'That pairing code wasn’t accepted.')
      const token = randomBytes(32).toString('base64url')
      tokens.add(token)
      writeFileSync(tokenFile, JSON.stringify([...tokens]))
      return send(res, 200, { token, deviceId: 'fake-device', pcName: 'FAKE-PC', version: 1 })
    }
    const auth = (req.headers.authorization ?? '').replace(/^Bearer /, '')
    if (!tokens.has(auth)) return err(res, 401, 'UNAUTHORIZED', 'This phone isn’t paired with Jarvis.')
    if (path === '/v1/shared') {
      const printer = PRINTER_ON
        ? { name: 'FAKE-AD5X', control: PRINTER_CONTROL, alerts: true, armMinutes: ARM_MINUTES, presets, cameras: [{ id: 'video', name: 'Camera' }, { id: 'screen', name: 'Printer screen' }], features: ['state', 'temperatures', 'macros', 'console', 'files', 'history', 'camera', 'controls', 'events', 'upload'], schema: 1 }
        : undefined
      return send(res, 200, { pcName: 'FAKE-PC', mail: [{ id: 'work', label: 'Work', email: 'me@corp.example' }], calendars: [{ id: 'cal:1', label: 'Work calendar', color: null }], tasks: [{ id: 'outlook', label: 'Outlook tasks' }], ...(printer ? { printer } : {}) })
    }
    if (printerRoute(req, res, path, url, body, auth)) return
    if (path === '/v1/mail/messages') {
      const text = (url.searchParams.get('text') ?? '').toLowerCase()
      const unread = url.searchParams.get('unreadOnly') === '1'
      const items = mail.filter((m) => (!unread || m.unread) && (!text || `${m.subject} ${m.from.name} ${m.preview}`.toLowerCase().includes(text)))
      return send(res, 200, { items, more: false })
    }
    if (path.startsWith('/v1/mail/messages/')) {
      const id = decodeURIComponent(path.slice('/v1/mail/messages/'.length))
      const message = mail.find((m) => m.id === id)
      return message ? send(res, 200, { message, text: bodies[id], truncated: false, attachments: [] }) : err(res, 404, 'NOT_SHARED', 'No such message.')
    }
    if (path === '/v1/mail/drafts' && req.method === 'POST') {
      drafts.push(JSON.parse(body))
      writeFileSync('drafts.json', JSON.stringify(drafts, null, 2))
      return send(res, 200, { saved: true, note: 'Draft saved in Outlook (pretend).' })
    }
    if (path === '/v1/calendar/events') {
      const from = Number(url.searchParams.get('from')); const to = Number(url.searchParams.get('to'))
      return send(res, 200, { items: events.filter((e) => e.end > from && e.start < to), more: false })
    }
    if (path === '/v1/tasks/lists') return send(res, 200, { items: [{ id: 'outlook:tasks', title: 'Tasks' }], more: false })
    if (path === '/v1/tasks') return send(res, 200, { items: tasks.filter((t) => url.searchParams.get('completed') === '1' || !t.done), more: false })
    return err(res, 404, 'NOT_SHARED', 'That isn’t shared with Edith.')
  })
}).listen(PORT, '0.0.0.0', () => {
  console.log(`Pretend Jarvis on https://0.0.0.0:${PORT}  (emulator: 10.0.2.2:${PORT})`)
  console.log(`Fingerprint: ${fingerprint.slice(0, 24).match(/.{4}/g).join(' ')}   Pairing code: ${CODE}`)
})

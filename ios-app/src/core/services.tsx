import { createContext, useContext, useEffect, useState, useSyncExternalStore, type ReactNode } from 'react';
import { AttachmentStore } from '../attachments/attachments';
import { expoAttachmentFiles } from '../attachments/expoAttachmentFiles';
import { applyRetention, removeOrphans, type RetentionDeps } from '../attachments/retention';
import { expoNotifier } from '../alerts/expoNotifier';
import { AlertLoop } from '../alerts/loop';
import type { Notifier } from '../alerts/notifications';
import { AlertPrefs } from '../alerts/prefs';
import { AlertWatcher } from '../alerts/watcher';
import { DemoBackend } from '../home/demoBackend';
import { Home } from '../home/home';
import { cameraSnapshotTool, homeControlTool, homeDevicesTool, recentEventsTool } from '../home/homeTools';
import { RingApi } from '../ring/api';
import { RingService } from '../ring/service';
import { JarvisApi } from '../jarvis/api';
import { PrinterFeed } from '../printer/feed/feed';
import { PrinterCardData } from '../printer/feed/cards';
import { PrinterControl } from '../printer/feed/control';
import { FeedPrinterRead } from '../printer/feed/toHome';
import { PrinterPrefs } from '../printer/feed/prefs';
import { PrinterSession } from '../printer/feed/session';
import { JarvisService } from '../jarvis/service';
import { BlinkApi } from '../blink/api';
import { BlinkService } from '../blink/service';
import { PrinterService } from '../printer/service';
import { printerStatusTool } from '../printer/tools';
import { ExpoRecorder, expoAudioFiles } from '../voice/expoRecorder';
import { Conversation } from '../voice/conversation';
import { OllamaTranscriber, VoiceMemos } from '../voice/voiceMemos';
import { activateKeepAwakeAsync, deactivateKeepAwake } from 'expo-keep-awake';
import { ChatEngine } from '../chat/engine';
import { CurrentConversation } from '../chat/currentConversation';
import { PromptBuilder, attachmentsSection, dateTimeSection } from '../chat/promptBuilder';
import { edithFilesSection } from '../chat/replyFormat';
import { ConversationTitler } from '../chat/titler';
import { ChatRepository } from '../data/chatRepository';
import { openDatabase } from '../data/expoDb';
import { ModelCatalog } from '../ollama/catalog';
import { currentTimeTool } from '../tools/context/currentTimeTool';
import { ToolRegistry, toolGuidanceSection } from '../tools/framework/toolRegistry';
import { deviceLocation } from '../tools/location/deviceLocation';
import { OpenMeteoClient } from '../tools/weather/openMeteo';
import { weatherTool } from '../tools/weather/weatherTool';
import { DuckDuckGoSearch } from '../tools/web/duckDuckGo';
import { PageReader } from '../tools/web/pageReader';
import { readWebPageTool, searchWebTool } from '../tools/web/webTools';
import { ModelSelection } from '../settings/modelSelection';
import { SettingsRepository } from '../settings/settings';
import { SpeechOutput } from '../speech/speechOutput';
import { expoTts } from '../speech/expoTts';
import { systemClock } from './clock';
import { kvStore } from './kv';
import { keychainSecrets, type SecretStore } from './secrets';
import * as Network from 'expo-network';
import * as Crypto from 'expo-crypto';
import { MerossService } from '../meross/service';
import { AlexaApi } from '../alexa/api';
import { AlexaService } from '../alexa/service';
import { secureFetch } from './secureFetch';
import { httpFetch, ollamaClient } from './network';
import type { Store } from './store';

export interface Services {
  settings: SettingsRepository;
  repo: ChatRepository;
  catalog: ModelCatalog;
  selection: ModelSelection;
  engine: ChatEngine;
  current: CurrentConversation;
  speech: SpeechOutput;
  tools: ToolRegistry;
  attachments: AttachmentStore;
  /** What retention needs (C16): the Settings screen applies it when the number changes. */
  retention: RetentionDeps;
  voice: VoiceMemos;
  conversation: Conversation;
  /** The Printer page through Jarvis (C67): what it shows, and what the owner folded. */
  printerSession: PrinterSession;
  printerPrefs: PrinterPrefs;
  printerCards: PrinterCardData;
  /** Controls, the confirm flow and the pending buttons of the Printer page (C67 E7). */
  printerControl: PrinterControl;
  /** What Jarvis shares of the printer (Settings reads it for its status line). */
  printerFeed: PrinterFeed;
  /** Camera alerts while the app is open, and the catch-up when the owner returns (C72). */
  alerts: { prefs: AlertPrefs; loop: AlertLoop; notifier: Notifier; demoRing: () => void };
  home: Home;
  /** Tokens and keys (the Keychain); services take it as a dependency (C48). */
  secrets: SecretStore;
}

/** Wires the app together once, at startup: the database, settings, engine, titler and speech (C06, C06b, C13). */
export async function createServices(): Promise<Services> {
  const db = await openDatabase();
  const settings = new SettingsRepository(kvStore);
  await settings.current();
  const repo = new ChatRepository(db, systemClock);
  const catalog = new ModelCatalog(ollamaClient);
  const selection = new ModelSelection(settings, catalog);
  const home = new Home({ store: kvStore });
  await home.load();
  const secrets = keychainSecrets;
  // Real services register here as their chunks land: `home.registerService(...)`.
  // The 3D printer talks plain HTTP to its LAN address (Moonraker has no TLS): `httpFetch`, deliberately (AGENTS.md).
  // When it does not answer directly it asks Jarvis once ("via Jarvis"), and never when the direct read works (C67 E6).
  const jarvis = new JarvisService({ secrets, api: new JarvisApi({ fetch: secureFetch(httpFetch, { allowHosts: ['ts.net'] }) }) });
  const printerFeed = new PrinterFeed(jarvis);
  home.registerService(new PrinterService({ secrets, fetch: httpFetch, jarvis: new FeedPrinterRead(printerFeed) }));
  // Alexa: Amazon's own sign-in page in a WebView, then HTTPS to Amazon only (the api checks every address, and follows redirects itself to keep cookies).
  home.registerService(new AlexaService({ secrets, api: new AlexaApi({ fetch: httpFetch, randomBytes: (n) => Crypto.getRandomBytes(n) }) }));
  // Meross: HTTPS to Meross's cloud only; plain HTTP to the doors on the LAN (they have no TLS), deliberately.
  home.registerService(
    new MerossService({
      secrets,
      store: kvStore,
      cloudFetch: secureFetch(httpFetch, { allowHosts: ['meross.com'] }),
      lanFetch: httpFetch,
      phoneIp: async () => {
        try {
          return await Network.getIpAddressAsync();
        } catch {
          return null;
        }
      },
      uuid: () => Crypto.randomUUID(),
    }),
  );
  // Ring: HTTPS to ring.com only. The sign-in rotates its refresh token, so each new one is saved to the Keychain before it is used.
  home.registerService(new RingService({ secrets, api: new RingApi({ fetch: secureFetch(httpFetch, { allowHosts: ['ring.com'] }) }), uuid: () => Crypto.randomUUID() }));
  // Blink: HTTPS to blink.com and immedia-semi.com only (the api checks every address itself: it follows redirects by hand to keep the sign-in's cookies).
  home.registerService(new BlinkService({ secrets, api: new BlinkApi({ fetch: httpFetch, randomBytes: (n) => Crypto.getRandomBytes(n) }), uuid: () => Crypto.randomUUID() }));
  // Jarvis (C66a, D39): ordinary HTTPS to the PC's Tailscale name only (a publicly trusted certificate on Jarvis's second listener).
  home.registerService(jarvis);
  // The Printer page through Jarvis (C67): the session asks only while the page is on screen.
  const printerPrefs = new PrinterPrefs(kvStore);
  await printerPrefs.load();
  const printerSession = new PrinterSession({ feed: printerFeed });
  const printerCards = new PrinterCardData({ session: printerSession });
  const printerControl = new PrinterControl({ session: printerSession, uuid: () => Crypto.randomUUID() });
  printerControl.onSent = (c) => printerCards.commandSent(c);
  // Each is restored at startup; one failing never stops the app.
  await home.restoreServices();
  // The Demo home is for trying things with no service; development builds only (Expo Go is one).
  let demo: DemoBackend | null = null;
  const applyBackends = () => home.setBackends(__DEV__ && home.demoOn ? [(demo = new DemoBackend())] : []);
  applyBackends();
  let demoWas = home.demoOn;
  home.version.subscribe(() => {
    if (home.demoOn !== demoWas) {
      demoWas = home.demoOn;
      applyBackends();
    }
  });
  const tools = new ToolRegistry([currentTimeTool, homeDevicesTool(home), homeControlTool(home), cameraSnapshotTool(home), recentEventsTool(home), printerStatusTool(home), weatherTool({ client: new OpenMeteoClient({ fetch: httpFetch }), location: deviceLocation }),
    searchWebTool(new DuckDuckGoSearch(httpFetch)),
    readWebPageTool(new PageReader(httpFetch)),
  ]);
  const prompts = new PromptBuilder(systemClock, () => [dateTimeSection, toolGuidanceSection(tools), attachmentsSection, edithFilesSection]);
  const titler = new ConversationTitler(repo, ollamaClient, settings);
  let openId: number | null = null;
  const speech = new SpeechOutput(expoTts, settings, (id) => id === openId);
  const attachments = new AttachmentStore(expoAttachmentFiles);
  const retention: RetentionDeps = { repo, store: attachments, clock: systemClock };
  const engine = new ChatEngine({
    repo,
    settings,
    selection,
    client: ollamaClient,
    prompts,
    listeners: [speech, titler],
    tools,
    attachments,
    afterAttachmentsSent: async () => void (await applyRetention(retention, settings.snapshot().keepRecentAttachments)),
  });
  // One microphone: a memo and a conversation never run together (each refuses while the other is on).
  const gate = { conversationOn: () => false };
  const recorder = new ExpoRecorder();
  const voice = new VoiceMemos({
    recorder,
    blocked: () => gate.conversationOn(),
    files: expoAudioFiles,
    transcriber: new OllamaTranscriber(ollamaClient, settings, selection),
    engine,
    stopSpeaking: () => speech.stop(),
  });
  const conversation = new Conversation({
    recorder,
    files: expoAudioFiles,
    transcriber: new OllamaTranscriber(ollamaClient, settings, selection),
    engine,
    speech,
    settings,
    memoBusy: () => voice.busy,
    keepAwake: { on: () => void activateKeepAwakeAsync('conversation'), off: () => deactivateKeepAwake('conversation') },
  });
  gate.conversationOn = () => conversation.on;
  const current = new CurrentConversation(repo, settings, engine, (ids) => attachments.forget(ids));
  current.id.subscribe(() => {
    openId = current.id.get();
  });
  await current.resolve();
  // Startup housekeeping (C16 §2): keep only the newest N pictures, and delete files nothing points to.
  void (async () => {
    await applyRetention(retention, settings.snapshot().keepRecentAttachments, true);
    await removeOrphans(retention);
  })().catch(() => {});
  // Turning "Speak Edith's replies" off also stops what's being spoken (C13 As built).
  let speakWas = settings.snapshot().speakReplies;
  settings.subscribe((s) => {
    if (speakWas && !s.speakReplies) speech.stop();
    speakWas = s.speakReplies;
  });
  // Camera alerts (C72): looked for only while Edith is open; nothing runs in the background (iOS has no foreground services).
  const alertPrefs = new AlertPrefs(kvStore);
  await alertPrefs.load();
  const alertLoop = new AlertLoop({
    watcher: new AlertWatcher(home),
    choices: alertPrefs.choices,
    notifier: expoNotifier,
    photo: async (alert) => {
      const camera = await home.alertCamera(alert.backend, alert.device);
      return camera ? (await home.snapshot(camera)).jpegBase64 : null;
    },
  });
  alertLoop.start();
  const alerts = { prefs: alertPrefs, loop: alertLoop, notifier: expoNotifier, demoRing: () => demo?.ring() };
  return { settings, repo, catalog, selection, engine, current, speech, tools, attachments, retention, voice, conversation, alerts, printerSession, printerPrefs, printerCards, printerControl, printerFeed, home, secrets };
}

const ServicesContext = createContext<Services | null>(null);

export function ServicesProvider({ children, fallback }: { children: ReactNode; fallback: (error: string | null) => ReactNode }) {
  const [services, setServices] = useState<Services | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    createServices().then(setServices, (e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  }, []);
  if (!services) return <>{fallback(error)}</>;
  return <ServicesContext.Provider value={services}>{children}</ServicesContext.Provider>;
}

export function useServices(): Services {
  const s = useContext(ServicesContext);
  if (!s) throw new Error('useServices outside ServicesProvider');
  return s;
}

export function useStore<T>(store: Store<T>): T {
  return useSyncExternalStore(store.subscribe, store.get, store.get);
}

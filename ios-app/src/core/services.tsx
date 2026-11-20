import { createContext, useContext, useEffect, useState, useSyncExternalStore, type ReactNode } from 'react';
import { AttachmentStore } from '../attachments/attachments';
import { ExpoRecorder, expoAudioFiles } from '../voice/expoRecorder';
import { OllamaTranscriber, VoiceMemos } from '../voice/voiceMemos';
import { ChatEngine } from '../chat/engine';
import { CurrentConversation } from '../chat/currentConversation';
import { PromptBuilder, attachmentsSection, dateTimeSection } from '../chat/promptBuilder';
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
  voice: VoiceMemos;
}

/** Wires the app together once, at startup: the database, settings, engine, titler and speech (C06, C06b, C13). */
export async function createServices(): Promise<Services> {
  const db = await openDatabase();
  const settings = new SettingsRepository(kvStore);
  await settings.current();
  const repo = new ChatRepository(db, systemClock);
  const catalog = new ModelCatalog(ollamaClient);
  const selection = new ModelSelection(settings, catalog);
  const tools = new ToolRegistry([currentTimeTool, weatherTool({ client: new OpenMeteoClient({ fetch: httpFetch }), location: deviceLocation }),
    searchWebTool(new DuckDuckGoSearch(httpFetch)),
    readWebPageTool(new PageReader(httpFetch)),
  ]);
  const prompts = new PromptBuilder(systemClock, () => [dateTimeSection, toolGuidanceSection(tools), attachmentsSection]);
  const titler = new ConversationTitler(repo, ollamaClient, settings);
  let openId: number | null = null;
  const speech = new SpeechOutput(expoTts, settings, (id) => id === openId);
  const attachments = new AttachmentStore();
  const engine = new ChatEngine({
    repo,
    settings,
    selection,
    client: ollamaClient,
    prompts,
    listeners: [speech, titler],
    tools,
    attachments,
  });
  const voice = new VoiceMemos({
    recorder: new ExpoRecorder(),
    files: expoAudioFiles,
    transcriber: new OllamaTranscriber(ollamaClient, settings, selection),
    engine,
    stopSpeaking: () => speech.stop(),
  });
  const current = new CurrentConversation(repo, settings, engine);
  current.id.subscribe(() => {
    openId = current.id.get();
  });
  await current.resolve();
  // Turning "Speak Edith's replies" off also stops what's being spoken (C13 As built).
  let speakWas = settings.snapshot().speakReplies;
  settings.subscribe((s) => {
    if (speakWas && !s.speakReplies) speech.stop();
    speakWas = s.speakReplies;
  });
  return { settings, repo, catalog, selection, engine, current, speech, tools, attachments, voice };
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

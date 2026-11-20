import { fetch as expoFetch } from 'expo/fetch';
import { OllamaClient, type HttpFetch } from '../ollama/client';

/**
 * Plain HTTP to Ollama on the LAN is the one exception to HTTPS-only (C37; Ollama has no TLS). `expo/fetch` streams
 * the response body, which React Native's own fetch can't.
 */
export const httpFetch: HttpFetch = (url, init) => expoFetch(url, init) as unknown as ReturnType<HttpFetch>;

export const ollamaClient = new OllamaClient({ fetch: httpFetch });

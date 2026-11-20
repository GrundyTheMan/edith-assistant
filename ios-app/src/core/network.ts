import { fetch as expoFetch } from 'expo/fetch';
import { OllamaClient, type HttpFetch } from '../ollama/client';
import { secureFetch } from './secureFetch';

/**
 * Plain HTTP to Ollama on the LAN is the one exception to HTTPS-only (C37; Ollama has no TLS). `expo/fetch` streams
 * the response body, which React Native's own fetch can't.
 */
export const httpFetch: HttpFetch = (url, init) => expoFetch(url, init) as unknown as ReturnType<HttpFetch>;

export const ollamaClient = new OllamaClient({ fetch: httpFetch });

/**
 * Every other service uses this (C37, C48): HTTPS only, and a redirect can't carry a credential to another host. Give each
 * service its own `secureFetch(httpFetch, { allowHosts: ['meross.com'] })`; `secureHttp` alone allows only the starting host.
 * Plain `httpFetch` is for Ollama, the 3D printer, Meross on the LAN and the web-page reader, and a PR that uses it says why.
 */
export const secureHttp: HttpFetch = secureFetch(httpFetch);

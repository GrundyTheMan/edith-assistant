import { log } from '../../core/log';
import type { HttpFetch } from '../../ollama/client';
import type { Forecast, Place, WeatherUnits } from './weather';

/** Thrown for any failure of the weather service; the tool turns it into one plain sentence. */
export class WeatherServiceError extends Error {}

interface GeoResponse {
  results?: {
    name: string;
    latitude: number;
    longitude: number;
    country?: string;
    country_code?: string;
    admin1?: string;
    population?: number;
  }[];
}
interface ForecastResponse {
  current?: {
    temperature_2m: number;
    apparent_temperature: number;
    relative_humidity_2m: number;
    weather_code: number;
    wind_speed_10m: number;
  };
  daily?: {
    time: string[];
    weather_code: number[];
    temperature_2m_max: number[];
    temperature_2m_min: number[];
    precipitation_probability_max?: (number | null)[];
  };
}

export interface OpenMeteoOptions {
  fetch: HttpFetch;
  geocodingBase?: string;
  forecastBase?: string;
  timeoutMs?: number;
}

/** Open-Meteo: free, no key, HTTPS (C09). Parsed only from responses captured live (L5). */
export class OpenMeteoClient {
  private readonly geo: string;
  private readonly forecastBase: string;
  private readonly timeoutMs: number;

  constructor(private readonly options: OpenMeteoOptions) {
    this.geo = options.geocodingBase ?? 'https://geocoding-api.open-meteo.com';
    this.forecastBase = options.forecastBase ?? 'https://api.open-meteo.com';
    this.timeoutMs = options.timeoutMs ?? 10_000;
  }

  private async getJson<T>(url: string, signal?: AbortSignal): Promise<T> {
    const timeout = new AbortController();
    const timer = setTimeout(() => timeout.abort(), this.timeoutMs);
    const onAbort = () => timeout.abort();
    signal?.addEventListener('abort', onAbort);
    try {
      const res = await this.options.fetch(url, { method: 'GET', signal: timeout.signal });
      if (!res.ok) throw new WeatherServiceError(`HTTP ${res.status}`);
      return JSON.parse(await res.text()) as T;
    } catch (e) {
      if (signal?.aborted) throw e;
      log.warn('Weather', 'The weather service failed');
      throw e instanceof WeatherServiceError ? e : new WeatherServiceError('unreachable');
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', onAbort);
    }
  }

  /** Only the name before the first comma is sent. Open-Meteo leaves out `results` when nothing matches. */
  async geocode(name: string, signal?: AbortSignal): Promise<Place[]> {
    const url = `${this.geo}/v1/search?name=${encodeURIComponent(name)}&count=10&language=en&format=json`;
    const body = await this.getJson<GeoResponse>(url, signal);
    return (body.results ?? []).map((r) => ({
      name: r.name,
      latitude: r.latitude,
      longitude: r.longitude,
      country: r.country,
      countryCode: r.country_code,
      admin1: r.admin1,
      population: r.population,
    }));
  }

  async forecast(latitude: number, longitude: number, units: WeatherUnits, signal?: AbortSignal): Promise<Forecast> {
    const url =
      `${this.forecastBase}/v1/forecast?latitude=${latitude}&longitude=${longitude}` +
      '&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,precipitation' +
      '&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max' +
      `&temperature_unit=${units.temperature}&wind_speed_unit=${units.wind}&timezone=auto&forecast_days=4`;
    const body = await this.getJson<ForecastResponse>(url, signal);
    const c = body.current;
    const d = body.daily;
    if (!c || !d || !Array.isArray(d.time)) throw new WeatherServiceError('garbled reply');
    return {
      now: {
        temperature: c.temperature_2m,
        feelsLike: c.apparent_temperature,
        humidity: c.relative_humidity_2m,
        code: c.weather_code,
        wind: c.wind_speed_10m,
      },
      days: d.time.map((date, i) => ({
        date,
        code: d.weather_code[i],
        high: d.temperature_2m_max[i],
        low: d.temperature_2m_min[i],
        rainChance: d.precipitation_probability_max?.[i] ?? null,
      })),
    };
  }
}

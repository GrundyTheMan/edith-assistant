/** Weather types and the pure parts of C09: WMO codes in words, units, place resolution. No regexes (L25). */

export interface Place {
  name: string;
  latitude: number;
  longitude: number;
  country?: string;
  countryCode?: string;
  admin1?: string;
  population?: number;
}

export interface WeatherUnits {
  temperature: 'fahrenheit' | 'celsius';
  wind: 'mph' | 'kmh';
}
export const IMPERIAL: WeatherUnits = { temperature: 'fahrenheit', wind: 'mph' };
export const METRIC: WeatherUnits = { temperature: 'celsius', wind: 'kmh' };

/** D7: automatic from the phone's country; the US, Liberia and Myanmar use °F and mph. */
export function unitsForCountry(countryCode: string | null | undefined): WeatherUnits {
  const c = (countryCode ?? '').toUpperCase();
  return c === 'US' || c === 'LR' || c === 'MM' ? IMPERIAL : METRIC;
}

export interface Forecast {
  now: { temperature: number; feelsLike: number; humidity: number; code: number; wind: number };
  days: { date: string; code: number; high: number; low: number; rainChance: number | null }[];
}

const WEATHER_CODES: Record<number, string> = {
  0: 'clear',
  1: 'mainly clear',
  2: 'partly cloudy',
  3: 'overcast',
  45: 'fog',
  48: 'freezing fog',
  51: 'light drizzle',
  53: 'drizzle',
  55: 'heavy drizzle',
  56: 'light freezing drizzle',
  57: 'freezing drizzle',
  61: 'light rain',
  63: 'rain',
  65: 'heavy rain',
  66: 'light freezing rain',
  67: 'freezing rain',
  71: 'light snow',
  73: 'snow',
  75: 'heavy snow',
  77: 'snow grains',
  80: 'light showers',
  81: 'showers',
  82: 'heavy showers',
  85: 'light snow showers',
  86: 'heavy snow showers',
  95: 'thunderstorm',
  96: 'thunderstorm with hail',
  99: 'thunderstorm with heavy hail',
};
export const describeWeatherCode = (code: number): string => WEATHER_CODES[code] ?? 'unsettled';

const US_STATES: Record<string, string> = {
  AL: 'Alabama', AK: 'Alaska', AZ: 'Arizona', AR: 'Arkansas', CA: 'California', CO: 'Colorado', CT: 'Connecticut',
  DE: 'Delaware', FL: 'Florida', GA: 'Georgia', HI: 'Hawaii', ID: 'Idaho', IL: 'Illinois', IN: 'Indiana', IA: 'Iowa',
  KS: 'Kansas', KY: 'Kentucky', LA: 'Louisiana', ME: 'Maine', MD: 'Maryland', MA: 'Massachusetts', MI: 'Michigan',
  MN: 'Minnesota', MS: 'Mississippi', MO: 'Missouri', MT: 'Montana', NE: 'Nebraska', NV: 'Nevada', NH: 'New Hampshire',
  NJ: 'New Jersey', NM: 'New Mexico', NY: 'New York', NC: 'North Carolina', ND: 'North Dakota', OH: 'Ohio',
  OK: 'Oklahoma', OR: 'Oregon', PA: 'Pennsylvania', RI: 'Rhode Island', SC: 'South Carolina', SD: 'South Dakota',
  TN: 'Tennessee', TX: 'Texas', UT: 'Utah', VT: 'Vermont', VA: 'Virginia', WA: 'Washington', WV: 'West Virginia',
  WI: 'Wisconsin', WY: 'Wyoming', DC: 'District of Columbia',
};

export interface Resolved {
  place: Place;
  /** Several equally plausible places share the name and none matched a hint. */
  ambiguous: boolean;
}

/** "Fowlerville, MI" → the name ("Fowlerville") and a hint ("MI") matched against state, country and country code. */
export function splitPlaceQuery(query: string): { name: string; hint: string } {
  const comma = query.indexOf(',');
  return comma < 0 ? { name: query.trim(), hint: '' } : { name: query.slice(0, comma).trim(), hint: query.slice(comma + 1).trim() };
}

export function resolvePlace(places: Place[], hint: string): Resolved | null {
  if (places.length === 0) return null;
  const h = hint.trim().toLowerCase();
  const byPopulation = (list: Place[]) => [...list].sort((a, b) => (b.population ?? 0) - (a.population ?? 0))[0];
  if (h) {
    const state = US_STATES[hint.trim().toUpperCase()]?.toLowerCase();
    const matches = places.filter(
      (p) =>
        p.admin1?.toLowerCase() === h ||
        (state !== undefined && p.admin1?.toLowerCase() === state && p.countryCode === 'US') ||
        p.country?.toLowerCase() === h ||
        p.countryCode?.toLowerCase() === h,
    );
    if (matches.length > 0) return { place: byPopulation(matches), ambiguous: false };
  }
  return { place: byPopulation(places), ambiguous: !h && places.length > 1 };
}

export function placeLabel(p: Place): string {
  const parts = [p.name];
  if (p.admin1 && p.admin1 !== p.name) parts.push(p.admin1);
  return parts.join(', ') + (p.countryCode ? ` (${p.countryCode})` : '');
}

const DAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
const weekday = (isoDate: string) => DAYS[new Date(`${isoDate}T12:00:00Z`).getUTCDay()];

/** The tool's text, at most 400 characters: now, plus the daily forecast when asked for. */
export function weatherText(label: string, f: Forecast, units: WeatherUnits, days: number, ambiguous: boolean): string {
  const t = units.temperature === 'fahrenheit' ? '°F' : '°C';
  const w = units.wind === 'mph' ? 'mph' : 'km/h';
  const n = f.now;
  let text =
    `${label} — now ${Math.round(n.temperature)}${t} (feels like ${Math.round(n.feelsLike)}${t}), ${describeWeatherCode(n.code)}, ` +
    `wind ${Math.round(n.wind)} ${w}, humidity ${Math.round(n.humidity)}%.`;
  // days[0] is today; the forecast starts tomorrow.
  f.days.slice(1, 1 + days).forEach((d, i) => {
    const when = i === 0 ? 'Tomorrow' : weekday(d.date);
    const rain = d.rainChance !== null && d.rainChance > 0 ? `, ${Math.round(d.rainChance)}% chance of rain` : '';
    text += ` ${when}: ${describeWeatherCode(d.code)}, high ${Math.round(d.high)}${t}, low ${Math.round(d.low)}${t}${rain}.`;
  });
  if (ambiguous) text += ' (Other places share this name — say a state or country if you meant another.)';
  return text.length > 400 ? `${text.slice(0, 399)}…` : text;
}

import type { LocationProvider } from '../location/deviceLocation';
import { failed, ok, type Tool } from '../framework/tool';
import type { OpenMeteoClient } from './openMeteo';
import { WeatherServiceError } from './openMeteo';
import { placeLabel, resolvePlace, splitPlaceQuery, unitsForCountry, weatherText, type WeatherUnits } from './weather';

const HERE = ['here', 'my location', 'current location', 'near me', 'nearby', 'my area', 'where i am'];

/** Region of the phone's locale ("en-US" → "US"): D7, units follow the phone's country. */
export function deviceUnits(): WeatherUnits {
  const locale = Intl.DateTimeFormat().resolvedOptions().locale || '';
  const region = locale.split('-').pop() ?? '';
  return unitsForCountry(region.length === 2 ? region : null);
}

/** `get_weather(location, days)`: Open-Meteo, "here" through the phone's location (C09, C11). */
export function weatherTool(deps: { client: OpenMeteoClient; location: LocationProvider; units?: () => WeatherUnits }): Tool {
  const unitsOf = deps.units ?? deviceUnits;
  return {
    spec: {
      name: 'get_weather',
      description: 'current weather and a short forecast for a place; use "here" for the phone\'s location',
      parameters: [
        {
          name: 'location',
          type: 'string',
          description: 'A city, like "Fowlerville, MI" or "Paris, France", or "here" for where the owner is now',
          aliases: ['place', 'city'],
        },
        { name: 'days', type: 'integer', description: 'Forecast days after today: 0 = current weather only, 1 to 3 adds the daily forecast' },
      ],
    },
    statusLabel: 'Checking the weather…',
    displayName: 'weather',
    async execute(args, ctx) {
      const query = (args.string('location') ?? '').trim();
      const days = Math.min(3, Math.max(0, args.int('days') ?? 0));
      const units = unitsOf();
      try {
        if (query === '' || HERE.includes(query.toLowerCase())) {
          const here = await deps.location.current(ctx.signal);
          if (here.kind === 'denied') return failed("The user hasn't shared their location — ask for a city.");
          if (here.kind === 'unavailable') return failed("I couldn't get the phone's location right now — ask for a city.");
          const forecast = await deps.client.forecast(here.latitude, here.longitude, units, ctx.signal);
          return ok(weatherText('Your current location', forecast, units, days, false));
        }
        const { name, hint } = splitPlaceQuery(query);
        const resolved = resolvePlace(await deps.client.geocode(name, ctx.signal), hint);
        if (!resolved) return failed(`Couldn't find a place called ${query}.`);
        const { place, ambiguous } = resolved;
        const forecast = await deps.client.forecast(place.latitude, place.longitude, units, ctx.signal);
        return ok(weatherText(placeLabel(place), forecast, units, days, ambiguous));
      } catch (e) {
        if (ctx.signal?.aborted) throw e;
        if (e instanceof WeatherServiceError || e instanceof Error) return failed("The weather service didn't respond — try again shortly.");
        throw e;
      }
    },
  };
}

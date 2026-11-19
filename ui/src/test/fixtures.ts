// Spec-shaped sample bodies for tests (openapi-v1.json examples).
import type {
  CurrentWeather,
  Favourite,
  FavouriteForecast,
  FavouriteWeather,
  Forecast,
  ForecastDay,
  Location,
  Problem,
  ProblemCode,
  Units,
} from '../api/types';

export const BASE_URL = 'http://localhost:8080';
export const API = `${BASE_URL}/api/v1`;
export const CLIENT_ID = '0f8b5a52-0c1e-4b44-9d7b-2a8c2f7e9a11';

export const LONDON: Location = { name: 'London', country: 'GB', latitude: 51.5074, longitude: -0.1278 };
export const PARIS: Location = { name: 'Paris', country: 'FR', latitude: 48.8566, longitude: 2.3522 };
export const TOKYO: Location = { name: 'Tokyo', country: 'JP', latitude: 35.6762, longitude: 139.6503 };

export function currentWeather(location: Location = LONDON, overrides: Partial<CurrentWeather> = {}): CurrentWeather {
  return {
    location,
    observedAt: '2026-11-15T10:00:00Z',
    units: 'metric',
    temperature: 11.2,
    feelsLike: 9.8,
    humidity: 81,
    windSpeed: 4.1,
    windDirection: 230,
    condition: { code: 'CLOUDY', description: 'Overcast clouds' },
    ...overrides,
  };
}

export function forecastDays(count: number, start = '2026-11-15'): ForecastDay[] {
  const [y, m, d] = start.split('-').map(Number) as [number, number, number];
  return Array.from({ length: count }, (_, i) => ({
    date: new Date(Date.UTC(y, m - 1, d + i)).toISOString().slice(0, 10),
    minTemperature: 6 + i,
    maxTemperature: 12.5 + i,
    precipitationProbability: 40,
    condition: { code: 'RAIN', description: 'Light rain' },
  }));
}

export function forecast(location: Location = LONDON, days = 5, units: Units = 'metric'): Forecast {
  return { location, units, days: forecastDays(days) };
}

export function favourite(id: string, city: string, location: Location, label?: string): Favourite {
  return {
    id,
    city,
    ...(label === undefined ? {} : { label }),
    location,
    createdAt: '2026-11-15T10:00:00Z',
    updatedAt: '2026-11-15T10:00:00Z',
  };
}

export const FAV_LONDON = favourite('11111111-1111-4111-8111-111111111111', 'London', LONDON, 'Home');
export const FAV_PARIS = favourite('22222222-2222-4222-8222-222222222222', 'Paris', PARIS);
export const FAV_TOKYO = favourite('33333333-3333-4333-8333-333333333333', 'Tokyo', TOKYO);

export function favouriteWeather(fav: Favourite, weather?: CurrentWeather, error?: FavouriteWeather['error']): FavouriteWeather {
  const { id, city, label, location } = fav;
  return {
    id,
    city,
    ...(label === undefined ? {} : { label }),
    location,
    ...(weather ? { weather } : {}),
    ...(error ? { error } : {}),
  };
}

export function favouriteForecast(fav: Favourite, fc?: Forecast, error?: FavouriteForecast['error']): FavouriteForecast {
  const { id, city, label, location } = fav;
  return {
    id,
    city,
    ...(label === undefined ? {} : { label }),
    location,
    ...(fc ? { forecast: fc } : {}),
    ...(error ? { error } : {}),
  };
}

export function problem(status: number, code: ProblemCode | string, overrides: Partial<Problem> = {}): Problem {
  return {
    type: `https://weather.example.com/problems/${String(code).toLowerCase()}`,
    title: String(code),
    status,
    detail: 'Problem detail text.',
    instance: '/api/v1/weather/current',
    code: code as ProblemCode,
    requestId: '3f2b1c9e-8d7a-4f3e-9b1a-2c4d6e8f0a1b',
    ...overrides,
  };
}

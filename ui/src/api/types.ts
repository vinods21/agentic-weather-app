// Hand-written /api/v1 types, mirroring src/main/resources/openapi/openapi-v1.json (ADR 0018).
// Keep them in step with ./contract.ts: ContractChecks below fails `npm run typecheck` when the
// property sets differ, and contract.test.ts fails `npm test` when the manifest differs from
// the spec.
import type {
  CONDITION_CODES,
  FAVOURITE_ERROR_CODES,
  PROBLEM_CODES,
  SCHEMA_CONTRACT,
  UNITS,
} from './contract';

export type Units = (typeof UNITS)[number];
export type ConditionCode = (typeof CONDITION_CODES)[number];
export type ProblemCode = (typeof PROBLEM_CODES)[number];
export type FavouriteErrorCode = (typeof FAVOURITE_ERROR_CODES)[number];

export interface Location {
  name: string;
  country: string;
  latitude: number;
  longitude: number;
}

export interface Condition {
  code: ConditionCode;
  description: string;
}

export interface CurrentWeather {
  location: Location;
  observedAt: string;
  units: Units;
  temperature: number;
  feelsLike: number;
  humidity: number;
  windSpeed: number;
  windDirection: number;
  condition: Condition;
}

export interface ForecastDay {
  date: string;
  minTemperature: number;
  maxTemperature: number;
  precipitationProbability: number;
  condition: Condition;
}

export interface Forecast {
  location: Location;
  units: Units;
  days: ForecastDay[];
}

export interface Favourite {
  id: string;
  city: string;
  label?: string;
  location: Location;
  createdAt: string;
  updatedAt: string;
}

export interface FavouriteList {
  items: Favourite[];
}

export interface FavouriteWeatherError {
  code: FavouriteErrorCode;
  detail: string;
}

export interface FavouriteWeather {
  id: string;
  city: string;
  label?: string;
  location: Location;
  weather?: CurrentWeather;
  error?: FavouriteWeatherError;
}

export interface FavouriteWeatherList {
  items: FavouriteWeather[];
}

export interface FavouriteForecast {
  id: string;
  city: string;
  label?: string;
  location: Location;
  forecast?: Forecast;
  error?: FavouriteWeatherError;
}

export interface FavouriteForecastList {
  items: FavouriteForecast[];
}

export interface CreateFavouriteRequest {
  city: string;
  label?: string | null;
}

export interface UpdateFavouriteRequest {
  label: string | null;
}

export interface ReorderFavouritesRequest {
  ids: string[];
}

export interface FieldError {
  field: string;
  message: string;
}

export interface Problem {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance: string;
  code: ProblemCode;
  requestId: string;
  errors?: FieldError[];
}

// ---------------------------------------------------------------------------------------------
// Compile-time tie between the interfaces above and SCHEMA_CONTRACT.
// ---------------------------------------------------------------------------------------------
type RequiredKeys<T> = { [K in keyof T]-?: {} extends Pick<T, K> ? never : K }[keyof T];
type OptionalKeys<T> = { [K in keyof T]-?: {} extends Pick<T, K> ? K : never }[keyof T];
type NullableKeys<T> = { [K in keyof T]-?: null extends T[K] ? K : never }[keyof T];
type Equals<A, B> =
  (<G>() => G extends A ? 1 : 2) extends <G>() => G extends B ? 1 : 2 ? true : false;
type Assert<T extends true> = T;

type Manifest = typeof SCHEMA_CONTRACT;
/** true when the interface's required, optional and nullable key sets equal the manifest's. */
type Check<T, S extends keyof Manifest> = Equals<
  [RequiredKeys<T>, OptionalKeys<T>, NullableKeys<T>],
  [Manifest[S]['required'][number], Manifest[S]['optional'][number], Manifest[S]['nullable'][number]]
>;

/** Exported only so that `noUnusedLocals` keeps it; it has no runtime meaning. */
export type ContractChecks = [
  Assert<Check<Location, 'Location'>>,
  Assert<Check<Condition, 'Condition'>>,
  Assert<Check<CurrentWeather, 'CurrentWeather'>>,
  Assert<Check<ForecastDay, 'ForecastDay'>>,
  Assert<Check<Forecast, 'Forecast'>>,
  Assert<Check<Favourite, 'Favourite'>>,
  Assert<Check<FavouriteList, 'FavouriteList'>>,
  Assert<Check<FavouriteWeather, 'FavouriteWeather'>>,
  Assert<Check<FavouriteWeatherList, 'FavouriteWeatherList'>>,
  Assert<Check<FavouriteForecast, 'FavouriteForecast'>>,
  Assert<Check<FavouriteForecastList, 'FavouriteForecastList'>>,
  Assert<Check<FavouriteWeatherError, 'FavouriteWeatherError'>>,
  Assert<Check<CreateFavouriteRequest, 'CreateFavouriteRequest'>>,
  Assert<Check<UpdateFavouriteRequest, 'UpdateFavouriteRequest'>>,
  Assert<Check<ReorderFavouritesRequest, 'ReorderFavouritesRequest'>>,
  Assert<Check<Problem, 'Problem'>>,
  Assert<Check<FieldError, 'FieldError'>>,
];

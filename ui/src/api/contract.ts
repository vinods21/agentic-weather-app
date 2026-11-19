// Contract manifest for the /api/v1 schemas the UI uses (ADR 0018 section 4).
// It is tied to ./types.ts at compile time (ContractChecks) and to
// src/main/resources/openapi/openapi-v1.json at test time (contract.test.ts).
// Enum values exist only here; the TypeScript unions are derived from these arrays.

export const UNITS = ['metric', 'imperial'] as const;

export const CONDITION_CODES = [
  'CLEAR',
  'PARTLY_CLOUDY',
  'CLOUDY',
  'FOG',
  'DRIZZLE',
  'RAIN',
  'SNOW',
  'THUNDERSTORM',
] as const;

export const PROBLEM_CODES = [
  'VALIDATION_FAILED',
  'LOCATION_NOT_FOUND',
  'NOT_FOUND',
  'METHOD_NOT_ALLOWED',
  'UPSTREAM_UNAVAILABLE',
  'INTERNAL_ERROR',
  'FAVOURITE_ALREADY_EXISTS',
  'FAVOURITES_LIMIT_REACHED',
] as const;

export const FAVOURITE_ERROR_CODES = ['UPSTREAM_UNAVAILABLE', 'LOCATION_NOT_FOUND'] as const;

/**
 * Per schema: required and optional property names, nullable property names, and the `$ref`
 * target of each object property or array-items property.
 */
export const SCHEMA_CONTRACT = {
  Location: {
    required: ['name', 'country', 'latitude', 'longitude'],
    optional: [],
    nullable: [],
    refs: {},
  },
  Condition: {
    required: ['code', 'description'],
    optional: [],
    nullable: [],
    refs: {},
  },
  CurrentWeather: {
    required: [
      'location',
      'observedAt',
      'units',
      'temperature',
      'feelsLike',
      'humidity',
      'windSpeed',
      'windDirection',
      'condition',
    ],
    optional: [],
    nullable: [],
    refs: { location: 'Location', condition: 'Condition' },
  },
  ForecastDay: {
    required: ['date', 'minTemperature', 'maxTemperature', 'precipitationProbability', 'condition'],
    optional: [],
    nullable: [],
    refs: { condition: 'Condition' },
  },
  Forecast: {
    required: ['location', 'units', 'days'],
    optional: [],
    nullable: [],
    refs: { location: 'Location', days: 'ForecastDay' },
  },
  Favourite: {
    required: ['id', 'city', 'location', 'createdAt', 'updatedAt'],
    optional: ['label'],
    nullable: [],
    refs: { location: 'Location' },
  },
  FavouriteList: {
    required: ['items'],
    optional: [],
    nullable: [],
    refs: { items: 'Favourite' },
  },
  FavouriteWeather: {
    required: ['id', 'city', 'location'],
    optional: ['label', 'weather', 'error'],
    nullable: [],
    refs: { location: 'Location', weather: 'CurrentWeather', error: 'FavouriteWeatherError' },
  },
  FavouriteWeatherList: {
    required: ['items'],
    optional: [],
    nullable: [],
    refs: { items: 'FavouriteWeather' },
  },
  FavouriteForecast: {
    required: ['id', 'city', 'location'],
    optional: ['label', 'forecast', 'error'],
    nullable: [],
    refs: { location: 'Location', forecast: 'Forecast', error: 'FavouriteWeatherError' },
  },
  FavouriteForecastList: {
    required: ['items'],
    optional: [],
    nullable: [],
    refs: { items: 'FavouriteForecast' },
  },
  FavouriteWeatherError: {
    required: ['code', 'detail'],
    optional: [],
    nullable: [],
    refs: {},
  },
  CreateFavouriteRequest: {
    required: ['city'],
    optional: ['label'],
    nullable: ['label'],
    refs: {},
  },
  UpdateFavouriteRequest: {
    required: ['label'],
    optional: [],
    nullable: ['label'],
    refs: {},
  },
  ReorderFavouritesRequest: {
    required: ['ids'],
    optional: [],
    nullable: [],
    refs: {},
  },
  Problem: {
    required: ['type', 'title', 'status', 'detail', 'instance', 'code', 'requestId'],
    optional: ['errors'],
    nullable: [],
    refs: { errors: 'FieldError' },
  },
  FieldError: {
    required: ['field', 'message'],
    optional: [],
    nullable: [],
    refs: {},
  },
} as const;

/** Enums the UI uses; `at` is a path below `components` in the spec. */
export const ENUM_CONTRACT = [
  { at: 'schemas.Condition.code', values: CONDITION_CODES },
  { at: 'schemas.CurrentWeather.units', values: UNITS },
  { at: 'schemas.Forecast.units', values: UNITS },
  { at: 'schemas.Problem.code', values: PROBLEM_CODES },
  { at: 'schemas.FavouriteWeatherError.code', values: FAVOURITE_ERROR_CODES },
  { at: 'parameters.Units', values: UNITS },
] as const;

// User-facing texts. Tables are typed over the contract enums, so a missing code is a type
// error, and messages.test.ts checks them against the spec (AC-20).
import type { FavouriteErrorCode, ProblemCode } from './types';

export const GENERIC_MESSAGE = 'Something went wrong. Please try again.';
export const NO_DATA_MESSAGE = 'No data available.';
export const REORDER_CONFLICT_MESSAGE = 'Your favourites changed elsewhere. The list has been refreshed.';
export const STORAGE_UNAVAILABLE_NOTICE =
  'Your browser storage is unavailable, so this browser will forget your favourites when the tab closes.';

export function networkMessage(baseUrl: string): string {
  return `Can't reach the weather service at ${baseUrl}.`;
}

export interface ProblemMessage {
  /** `{city}` is replaced with the submitted city. */
  text: string;
  retryable: boolean;
}

export const PROBLEM_MESSAGES: Record<ProblemCode, ProblemMessage> = {
  VALIDATION_FAILED: { text: 'Please check the highlighted values.', retryable: false },
  LOCATION_NOT_FOUND: { text: 'No weather data found for "{city}".', retryable: false },
  NOT_FOUND: { text: 'This favourite no longer exists.', retryable: false },
  METHOD_NOT_ALLOWED: { text: GENERIC_MESSAGE, retryable: true },
  UPSTREAM_UNAVAILABLE: {
    text: 'The weather service is temporarily unavailable. Please try again.',
    retryable: true,
  },
  INTERNAL_ERROR: { text: GENERIC_MESSAGE, retryable: true },
  FAVOURITE_ALREADY_EXISTS: { text: '{city} is already in your favourites.', retryable: false },
  FAVOURITES_LIMIT_REACHED: {
    text: "You've reached the maximum number of favourites. Remove one to add another.",
    retryable: false,
  },
};

/** Validation messages: all errors inline, or some listed in the general alert. */
export const VALIDATION_INLINE_MESSAGE = PROBLEM_MESSAGES.VALIDATION_FAILED.text;
export const VALIDATION_LIST_MESSAGE = 'Some values were not accepted:';

/** Per-item dashboard errors (AC-29); unknown codes fall back to NO_DATA_MESSAGE. */
export const ITEM_ERROR_MESSAGES: Record<FavouriteErrorCode, string> = {
  UPSTREAM_UNAVAILABLE: 'Weather temporarily unavailable.',
  LOCATION_NOT_FOUND: 'No weather data for this location.',
};

export function itemErrorMessage(code: string | undefined): string {
  if (code !== undefined && Object.hasOwn(ITEM_ERROR_MESSAGES, code)) {
    return ITEM_ERROR_MESSAGES[code as FavouriteErrorCode];
  }
  return NO_DATA_MESSAGE;
}

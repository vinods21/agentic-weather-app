// Client-side validation, mirroring the API rules (stripped, city 1..100, label 1..50).

export const CITY_MAX = 100;
export const LABEL_MAX = 50;

export const CITY_REQUIRED_MESSAGE = 'Enter a city name.';
export const CITY_TOO_LONG_MESSAGE = `City name must be at most ${CITY_MAX} characters.`;
export const LABEL_TOO_LONG_MESSAGE = `Label must be at most ${LABEL_MAX} characters.`;

export type Validation<T> = { ok: true; value: T } | { ok: false; message: string };

/** Trims; empty is an error; more than 100 UTF-16 code units is an error. */
export function validateCity(raw: string): Validation<string> {
  const value = raw.trim();
  if (value === '') return { ok: false, message: CITY_REQUIRED_MESSAGE };
  if (value.length > CITY_MAX) return { ok: false, message: CITY_TOO_LONG_MESSAGE };
  return { ok: true, value };
}

/** Trims; empty means "no label" (null); more than 50 UTF-16 code units is an error. */
export function validateLabel(raw: string): Validation<string | null> {
  const value = raw.trim();
  if (value === '') return { ok: true, value: null };
  if (value.length > LABEL_MAX) return { ok: false, message: LABEL_TOO_LONG_MESSAGE };
  return { ok: true, value };
}

import { UNITS } from '../api/contract';
import type { Units } from '../api/types';

export const UNITS_KEY = 'weather.units';
export const DEFAULT_UNITS: Units = 'metric';

function isUnits(value: unknown): value is Units {
  return typeof value === 'string' && (UNITS as readonly string[]).includes(value);
}

/** The stored units, or metric when missing, invalid or storage is unavailable. Never throws. */
export function loadUnits(getStorage: () => Storage): Units {
  try {
    const stored = getStorage().getItem(UNITS_KEY);
    return isUnits(stored) ? stored : DEFAULT_UNITS;
  } catch {
    return DEFAULT_UNITS;
  }
}

/** Stores the units; storage failures are ignored (the choice then lasts for the session). */
export function saveUnits(getStorage: () => Storage, units: Units): void {
  try {
    getStorage().setItem(UNITS_KEY, units);
  } catch {
    // Storage unavailable: keep the in-memory choice only.
  }
}

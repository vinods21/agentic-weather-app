import { describe, expect, it } from 'vitest';
import { enumAt } from '../test/spec';
import { ITEM_ERROR_MESSAGES, PROBLEM_MESSAGES, itemErrorMessage } from './messages';

describe('user-facing messages cover every code in the spec', () => {
  it('AC-20: every Problem.code value has a message', () => {
    const codes = enumAt('schemas.Problem.code');
    expect(codes?.length).toBeGreaterThan(0);
    for (const code of codes ?? []) {
      expect(Object.hasOwn(PROBLEM_MESSAGES, code), `no message for Problem code ${code}`).toBe(true);
      expect(PROBLEM_MESSAGES[code as keyof typeof PROBLEM_MESSAGES].text.trim()).not.toBe('');
    }
  });

  it('AC-20: every FavouriteWeatherError.code value has a message', () => {
    const codes = enumAt('schemas.FavouriteWeatherError.code');
    expect(codes?.length).toBeGreaterThan(0);
    for (const code of codes ?? []) {
      expect(Object.hasOwn(ITEM_ERROR_MESSAGES, code), `no message for item error code ${code}`).toBe(true);
      expect(ITEM_ERROR_MESSAGES[code as keyof typeof ITEM_ERROR_MESSAGES].trim()).not.toBe('');
    }
  });

  it('AC-29: per-item messages, with a fallback for unknown codes', () => {
    expect(itemErrorMessage('UPSTREAM_UNAVAILABLE')).toBe('Weather temporarily unavailable.');
    expect(itemErrorMessage('LOCATION_NOT_FOUND')).toBe('No weather data for this location.');
    expect(itemErrorMessage('SOMETHING_NEW')).toBe('No data available.');
    expect(itemErrorMessage('toString')).toBe('No data available.');
    expect(itemErrorMessage(undefined)).toBe('No data available.');
  });
});

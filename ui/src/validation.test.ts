import { describe, expect, it } from 'vitest';
import { validateCity, validateLabel } from './validation';

describe('validateCity', () => {
  it('AC-10: empty or whitespace-only is rejected', () => {
    expect(validateCity('')).toEqual({ ok: false, message: 'Enter a city name.' });
    expect(validateCity(' \t ')).toEqual({ ok: false, message: 'Enter a city name.' });
  });

  it('AC-10: the trimmed value is returned', () => {
    expect(validateCity('  São Paulo ')).toEqual({ ok: true, value: 'São Paulo' });
  });

  it('AC-10: 100 characters are accepted, 101 are rejected (after trimming)', () => {
    expect(validateCity(` ${'a'.repeat(100)} `)).toEqual({ ok: true, value: 'a'.repeat(100) });
    expect(validateCity('a'.repeat(101))).toEqual({
      ok: false,
      message: 'City name must be at most 100 characters.',
    });
  });
});

describe('validateLabel', () => {
  it('AC-22: empty or whitespace-only means no label', () => {
    expect(validateLabel('')).toEqual({ ok: true, value: null });
    expect(validateLabel('   ')).toEqual({ ok: true, value: null });
  });

  it('AC-22: 50 characters are accepted, 51 are rejected (after trimming)', () => {
    expect(validateLabel(` ${'b'.repeat(50)} `)).toEqual({ ok: true, value: 'b'.repeat(50) });
    expect(validateLabel('b'.repeat(51))).toEqual({ ok: false, message: 'Label must be at most 50 characters.' });
  });
});

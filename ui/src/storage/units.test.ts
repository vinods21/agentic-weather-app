import { describe, expect, it } from 'vitest';
import { fakeStorage } from '../test/fakeStorage';
import { UNITS_KEY, loadUnits, saveUnits } from './units';

describe('units storage', () => {
  it('AC-11: defaults to metric when nothing is stored', () => {
    expect(loadUnits(() => fakeStorage())).toBe('metric');
  });

  it('AC-11: reads a stored valid value', () => {
    expect(loadUnits(() => fakeStorage({ [UNITS_KEY]: 'imperial' }))).toBe('imperial');
  });

  it('AC-11: an invalid stored value gives metric', () => {
    expect(loadUnits(() => fakeStorage({ [UNITS_KEY]: 'Imperial' }))).toBe('metric');
  });

  it('AC-11: saves under weather.units and survives a reload', () => {
    const storage = fakeStorage();
    saveUnits(() => storage, 'imperial');
    expect(storage.getItem(UNITS_KEY)).toBe('imperial');
    expect(loadUnits(() => storage)).toBe('imperial');
  });

  it('never throws when storage is unavailable', () => {
    const broken = () => {
      throw new DOMException('denied', 'SecurityError');
    };
    expect(loadUnits(broken)).toBe('metric');
    expect(() => saveUnits(broken, 'imperial')).not.toThrow();
    expect(() => saveUnits(() => fakeStorage({}, ['setItem']), 'imperial')).not.toThrow();
  });
});

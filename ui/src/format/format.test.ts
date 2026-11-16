import { describe, expect, it } from 'vitest';
import {
  formatDirection,
  formatForecastDate,
  formatObservedAt,
  formatPercent,
  formatTemperature,
  formatWind,
} from './format';

describe('format', () => {
  it('AC-9: temperatures are whole degrees with the unit symbol', () => {
    expect(formatTemperature(11.2, 'metric')).toBe('11 °C');
    expect(formatTemperature(11.5, 'metric')).toBe('12 °C');
    expect(formatTemperature(-0.4, 'metric')).toBe('0 °C');
    expect(formatTemperature(-3.6, 'metric')).toBe('-4 °C');
    expect(formatTemperature(52.2, 'imperial')).toBe('52 °F');
  });

  it('AC-9, AC-11: wind has one decimal place and the unit', () => {
    expect(formatWind(4.1, 'metric')).toBe('4.1 m/s');
    expect(formatWind(4, 'metric')).toBe('4.0 m/s');
    expect(formatWind(4.05, 'metric')).toBe('4.1 m/s');
    expect(formatWind(0, 'metric')).toBe('0.0 m/s');
    expect(formatWind(9.2, 'imperial')).toBe('9.2 mph');
  });

  it('AC-9: direction in degrees and percentages', () => {
    expect(formatDirection(230)).toBe('230°');
    expect(formatPercent(81)).toBe('81%');
  });

  it('AC-12: forecast dates have no timezone shift (TZ is America/Los_Angeles)', () => {
    expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe('America/Los_Angeles');
    const text = formatForecastDate('2026-11-15', 'en-US');
    expect(text).toContain('15');
    expect(text).toContain('Nov');
    expect(text).not.toContain('14');
    expect(formatForecastDate('2026-11-15', 'en-GB')).toBe('Sun 15 Nov');
  });

  it('AC-12: a malformed date is shown as is', () => {
    expect(formatForecastDate('not-a-date', 'en-US')).toBe('not-a-date');
  });

  it('AC-9: observedAt is shown in local time', () => {
    // 10:00Z is 02:00 in Los Angeles (PST, UTC-8) on 15 Nov.
    const text = formatObservedAt('2026-11-15T10:00:00Z', 'en-US');
    expect(text).toContain('Nov 15, 2026');
    expect(text).toMatch(/2:00\sAM/);
    expect(formatObservedAt('garbage', 'en-US')).toBe('garbage');
  });
});

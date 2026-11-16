// Display formatting (requirements A-15). Plain arithmetic and Intl only.
import type { Units } from '../api/types';

function defaultLocale(): string | undefined {
  return typeof navigator !== 'undefined' ? navigator.language : undefined;
}

export function temperatureSymbol(units: Units): string {
  return units === 'imperial' ? '°F' : '°C';
}

export function windUnit(units: Units): string {
  return units === 'imperial' ? 'mph' : 'm/s';
}

/** Whole degrees with the unit symbol: 11.2 -> "11 °C". Never shows "-0". */
export function formatTemperature(value: number, units: Units): string {
  const rounded = Math.round(value);
  return `${rounded === 0 ? 0 : rounded} ${temperatureSymbol(units)}`;
}

/** One decimal place with the unit: 4.1 -> "4.1 m/s". Rounds half away from zero. */
export function formatWind(speed: number, units: Units): string {
  const tenths = Math.round(Math.abs(speed) * 10 + Number.EPSILON * 10) * Math.sign(speed);
  return `${(tenths === 0 ? 0 : tenths / 10).toFixed(1)} ${windUnit(units)}`;
}

export function formatDirection(degrees: number): string {
  return `${degrees}°`;
}

export function formatPercent(value: number): string {
  return `${value}%`;
}

const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * A `YYYY-MM-DD` forecast date as short weekday, day and month, without a timezone shift:
 * the date is built in UTC and formatted in UTC (AC-12).
 */
export function formatForecastDate(isoDate: string, locale: string | undefined = defaultLocale()): string {
  const match = DATE_RE.exec(isoDate);
  if (!match) return isoDate;
  const [, y, m, d] = match;
  const date = new Date(Date.UTC(Number(y), Number(m) - 1, Number(d)));
  if (Number.isNaN(date.getTime())) return isoDate;
  return new Intl.DateTimeFormat(locale, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    timeZone: 'UTC',
  }).format(date);
}

/** The observation timestamp in the browser's local time. */
export function formatObservedAt(isoDateTime: string, locale: string | undefined = defaultLocale()): string {
  const date = new Date(isoDateTime);
  if (Number.isNaN(date.getTime())) return isoDateTime;
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

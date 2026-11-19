import type { ForecastDay, Units } from '../api/types';
import { formatForecastDate, formatPercent, formatTemperature } from '../format/format';

export interface ForecastListProps {
  days: ForecastDay[];
  units: Units;
  /** Compact rows (dashboard): date, min/max and condition only. */
  compact?: boolean;
}

/** One row per returned day (AC-9, AC-12, AC-28). */
export function ForecastList({ days, units, compact = false }: ForecastListProps) {
  return (
    <ul className="forecast-list">
      {days.map((day) => (
        <li key={day.date}>
          <span className="forecast-date">{formatForecastDate(day.date)}</span>
          <span>
            {formatTemperature(day.minTemperature, units)} / {formatTemperature(day.maxTemperature, units)}
          </span>
          {!compact && <span>Rain {formatPercent(day.precipitationProbability)}</span>}
          <span>{day.condition.description}</span>
        </li>
      ))}
    </ul>
  );
}

import type { CurrentWeather } from '../api/types';
import { formatDirection, formatObservedAt, formatPercent, formatTemperature, formatWind } from '../format/format';

export function CurrentWeatherDetails({ weather }: { weather: CurrentWeather }) {
  const { units } = weather;
  return (
    <dl>
      <dt>Temperature</dt>
      <dd>{formatTemperature(weather.temperature, units)}</dd>
      <dt>Feels like</dt>
      <dd>{formatTemperature(weather.feelsLike, units)}</dd>
      <dt>Condition</dt>
      <dd>{weather.condition.description}</dd>
      <dt>Humidity</dt>
      <dd>{formatPercent(weather.humidity)}</dd>
      <dt>Wind</dt>
      <dd>
        {formatWind(weather.windSpeed, units)}, {formatDirection(weather.windDirection)}
      </dd>
    </dl>
  );
}

/** Current weather for a searched city (AC-9). */
export function CurrentWeatherPanel({ weather }: { weather: CurrentWeather }) {
  return (
    <section className="panel" aria-labelledby="current-heading">
      <h2 id="current-heading">
        {weather.location.name}, {weather.location.country}
      </h2>
      <CurrentWeatherDetails weather={weather} />
      <p className="muted">Observed {formatObservedAt(weather.observedAt)}</p>
    </section>
  );
}

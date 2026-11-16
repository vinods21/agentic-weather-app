import { itemErrorMessage } from '../api/messages';
import type { FavouriteForecast, FavouriteWeather } from '../api/types';
import { CurrentWeatherDetails } from '../components/CurrentWeatherPanel';
import { ForecastList } from '../components/ForecastList';

export interface DashboardCardProps {
  item: FavouriteWeather;
  /** false while the forecast aggregate is loading or failed: the section is left out. */
  forecastAvailable: boolean;
  forecastItem: FavouriteForecast | undefined;
}

/** One favourite on the dashboard; each section shows its data or its own per-item message (AC-29). */
export function DashboardCard({ item, forecastAvailable, forecastItem }: DashboardCardProps) {
  const headingId = `card-${item.id}`;
  return (
    <li>
      <article className="card" aria-labelledby={headingId}>
        <h2 id={headingId}>{item.label ?? item.city}</h2>
        <p className="muted">
          {item.location.name}, {item.location.country}
        </p>
        <section aria-label="Current weather">
          {item.weather ? (
            <CurrentWeatherDetails weather={item.weather} compact />
          ) : (
            <p>{itemErrorMessage(item.error?.code)}</p>
          )}
        </section>
        {forecastAvailable && (
          <section aria-label="Forecast">
            {forecastItem?.forecast ? (
              <ForecastList days={forecastItem.forecast.days} units={forecastItem.forecast.units} compact />
            ) : (
              <p>{itemErrorMessage(forecastItem?.error?.code)}</p>
            )}
          </section>
        )}
      </article>
    </li>
  );
}

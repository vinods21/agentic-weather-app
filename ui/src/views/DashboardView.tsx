import { useCallback, useEffect, useMemo } from 'react';
import { describeApiError } from '../api/errors';
import type { FavouriteForecast, FavouriteForecastList, FavouriteWeatherList, Units } from '../api/types';
import { ErrorMessage } from '../components/ErrorMessage';
import { Loading } from '../components/Loading';
import { ViewHeading } from '../components/ViewHeading';
import { ROUTE_HASH } from '../hooks/useHashRoute';
import { useRequestSlot } from '../hooks/useRequestSlot';
import { useApp } from '../state/AppContext';
import { DashboardCard } from './DashboardCard';

/** Fixed short forecast for the compact cards (requirements A-5). */
export const DASHBOARD_DAYS = 3;

export function DashboardView({ focusHeading }: { focusHeading: boolean }) {
  const { api, units } = useApp();
  const weather = useRequestSlot<FavouriteWeatherList>();
  const forecast = useRequestSlot<FavouriteForecastList>();
  const runWeather = weather.run;
  const runForecast = forecast.run;

  const loadWeather = useCallback(
    (u: Units) => runWeather((signal) => api.listFavouritesWeather(u, signal)),
    [api, runWeather],
  );
  const loadForecast = useCallback(
    (u: Units) => runForecast((signal) => api.listFavouritesForecast(u, DASHBOARD_DAYS, signal)),
    [api, runForecast],
  );
  // Both aggregates start in the same tick; neither awaits the other (AC-28).
  const loadBoth = useCallback(
    (u: Units) => {
      void loadWeather(u);
      void loadForecast(u);
    },
    [loadWeather, loadForecast],
  );

  // On mount and on every units change (AC-31). No polling.
  useEffect(() => {
    loadBoth(units);
  }, [units, loadBoth]);

  const retryFailed = () => {
    if (weather.state.status === 'error') void loadWeather(units);
    if (forecast.state.status === 'error') void loadForecast(units);
  };

  const forecastById = useMemo(() => {
    const map = new Map<string, FavouriteForecast>();
    if (forecast.state.status === 'success') {
      for (const item of forecast.state.data.items) map.set(item.id, item);
    }
    return map;
  }, [forecast.state]);

  const loading = weather.state.status === 'loading' || forecast.state.status === 'loading';
  const weatherError = weather.state.status === 'error' ? describeApiError(weather.state.error) : null;
  const forecastError = forecast.state.status === 'error' ? describeApiError(forecast.state.error) : null;

  return (
    <>
      <ViewHeading focusOnMount={focusHeading}>Dashboard</ViewHeading>
      <div className="toolbar">
        <button type="button" className="secondary" onClick={() => loadBoth(units)}>
          Refresh
        </button>
        {(weatherError || forecastError) && (
          <button type="button" onClick={retryFailed}>
            Retry
          </button>
        )}
      </div>

      {loading && <Loading text="Loading dashboard…" />}
      {weatherError && <ErrorMessage error={weatherError} />}
      {/* Both failing the same way (e.g. backend down) shows one message. */}
      {forecastError && (!weatherError || forecastError.message !== weatherError.message) && (
        <ErrorMessage error={forecastError} />
      )}

      {weather.state.status === 'success' && weather.state.data.items.length === 0 && (
        <p>
          No favourites yet. <a href={ROUTE_HASH.favourites}>Go to Favourites</a>
        </p>
      )}

      {weather.state.status === 'success' && weather.state.data.items.length > 0 && (
        <ul className="cards" aria-label="Favourite cards">
          {weather.state.data.items.map((item) => (
            <DashboardCard
              key={item.id}
              item={item}
              forecastAvailable={forecast.state.status === 'success'}
              forecastItem={forecastById.get(item.id)}
            />
          ))}
        </ul>
      )}
    </>
  );
}

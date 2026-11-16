import { useEffect, useRef, useState, type FormEvent } from 'react';
import { describeApiError, type ErrorView } from '../api/errors';
import type { CurrentWeather, Forecast, Units } from '../api/types';
import { CurrentWeatherPanel } from '../components/CurrentWeatherPanel';
import { ErrorMessage } from '../components/ErrorMessage';
import { ForecastList } from '../components/ForecastList';
import { Loading } from '../components/Loading';
import { ViewHeading } from '../components/ViewHeading';
import { useRequestSlot } from '../hooks/useRequestSlot';
import { useApp } from '../state/AppContext';
import { validateCity } from '../validation';

const DAY_OPTIONS = [1, 2, 3, 4, 5, 6, 7];
const DEFAULT_DAYS = 5;
const SEARCH_FIELDS = ['city'] as const;

type AddState =
  | { status: 'idle' }
  | { status: 'pending' }
  | { status: 'saved' }
  | { status: 'error'; error: ErrorView };

export function SearchView({ focusHeading }: { focusHeading: boolean }) {
  const { api, units } = useApp();
  const current = useRequestSlot<CurrentWeather>();
  const forecast = useRequestSlot<Forecast>();

  const [cityInput, setCityInput] = useState('');
  const [clientError, setClientError] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState<{ city: string } | null>(null);
  const [days, setDays] = useState(DEFAULT_DAYS);
  const [addState, setAddState] = useState<AddState>({ status: 'idle' });

  const runCurrent = (city: string, u: Units) => current.run((signal) => api.getCurrentWeather(city, u, signal));
  const runForecast = (city: string, u: Units, d: number) =>
    forecast.run((signal) => api.getForecast(city, u, d, signal));
  const runBoth = (city: string, u: Units, d: number) => {
    void runCurrent(city, u);
    void runForecast(city, u, d);
  };

  // Units changed elsewhere (header control): re-run both for the submitted city (AC-11).
  const previousUnits = useRef(units);
  useEffect(() => {
    if (previousUnits.current === units) return;
    previousUnits.current = units;
    if (submitted) runBoth(submitted.city, units, days);
    // Only a real units change triggers a re-run.
  }, [units]);

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    const result = validateCity(cityInput);
    if (!result.ok) {
      setClientError(result.message);
      return;
    }
    setClientError(null);
    setSubmitted({ city: result.value });
    setAddState({ status: 'idle' });
    runBoth(result.value, units, days);
  };

  const onDaysChange = (value: number) => {
    setDays(value);
    if (submitted) void runForecast(submitted.city, units, value);
  };

  const onAdd = async () => {
    if (!submitted) return;
    setAddState({ status: 'pending' });
    try {
      await api.createFavourite({ city: submitted.city });
      setAddState({ status: 'saved' });
    } catch (error) {
      setAddState({ status: 'error', error: describeApiError(error, { city: submitted.city, knownFields: [] }) });
    }
  };

  const ctx = { city: submitted?.city, knownFields: SEARCH_FIELDS };
  const currentError = current.state.status === 'error' ? describeApiError(current.state.error, ctx) : null;
  const forecastError = forecast.state.status === 'error' ? describeApiError(forecast.state.error, ctx) : null;
  const cityError =
    clientError ?? currentError?.inlineFieldErrors.city ?? forecastError?.inlineFieldErrors.city ?? null;
  const loading = current.state.status === 'loading' || forecast.state.status === 'loading';

  return (
    <>
      <ViewHeading focusOnMount={focusHeading}>Search</ViewHeading>
      <form aria-label="City search" onSubmit={onSubmit} noValidate>
        <div className="field">
          <label htmlFor="search-city">City</label>
          <input
            id="search-city"
            type="text"
            value={cityInput}
            onChange={(e) => setCityInput(e.target.value)}
            aria-invalid={cityError ? true : undefined}
            aria-describedby={cityError ? 'search-city-error' : undefined}
            autoComplete="off"
          />
        </div>
        <div className="field-inline">
          <label htmlFor="search-days">Days</label>
          <select id="search-days" value={days} onChange={(e) => onDaysChange(Number(e.target.value))}>
            {DAY_OPTIONS.map((d) => (
              <option key={d} value={d}>
                {d}
              </option>
            ))}
          </select>
        </div>
        <button type="submit">Search</button>
      </form>
      {cityError && (
        <p id="search-city-error" className="field-error" role="alert">
          {cityError}
        </p>
      )}

      {loading && <Loading text="Loading weather…" />}

      {currentError && submitted && (
        <ErrorMessage error={currentError} onRetry={() => runBoth(submitted.city, units, days)} />
      )}

      {current.state.status === 'success' && (
        <>
          <CurrentWeatherPanel weather={current.state.data} />
          <div className="actions">
            {addState.status === 'saved' ? (
              <button type="button" disabled>
                Saved
              </button>
            ) : (
              <button
                type="button"
                className="secondary"
                onClick={() => void onAdd()}
                disabled={addState.status === 'pending'}
              >
                Add to favourites
              </button>
            )}
          </div>
          {addState.status === 'error' && <ErrorMessage error={addState.error} />}
        </>
      )}

      {current.state.status !== 'error' && forecast.state.status !== 'idle' && (
        <section aria-labelledby="forecast-heading">
          <h2 id="forecast-heading">Forecast</h2>
          {forecast.state.status === 'success' && (
            <ForecastList days={forecast.state.data.days} units={forecast.state.data.units} />
          )}
          {forecastError && submitted && (
            <ErrorMessage
              error={forecastError}
              onRetry={() => void runForecast(submitted.city, units, days)}
              retryLabel="Retry forecast"
            />
          )}
        </section>
      )}
    </>
  );
}

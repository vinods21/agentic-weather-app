// Tester: AC-mapped component tests for the Dashboard that DashboardView.test.tsx does not
// already cover (AC-28 card content, AC-29 all-error / not-found, AC-30 whole-request failures,
// AC-31 loading and no polling).
import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { deferred, json, mockFetch } from '../test/fetchMock';
import {
  API,
  BASE_URL,
  CLIENT_ID,
  FAV_LONDON,
  FAV_PARIS,
  FAV_TOKYO,
  PARIS,
  TOKYO,
  currentWeather,
  favouriteForecast,
  favouriteWeather,
  forecast,
  problem,
} from '../test/fixtures';
import { renderApp } from '../test/renderApp';

const weatherUrl = (units = 'metric') => `${API}/favourites/weather?units=${units}`;
const forecastUrl = (units = 'metric') => `${API}/favourites/forecast?units=${units}&days=3`;
const networkDown = () => Promise.reject(new TypeError('Failed to fetch'));

function card(name: string) {
  return screen.getByRole('article', { name });
}

function section(cardName: string, name: 'Current weather' | 'Forecast') {
  return within(card(cardName)).getByRole('region', { name });
}

describe('DashboardView (tester AC coverage)', () => {
  it('AC-28: each card shows heading (label or city), location, weather details and 3 compact forecast rows', async () => {
    const mock = mockFetch()
      .on('GET', weatherUrl(), () =>
        json(200, {
          items: [favouriteWeather(FAV_LONDON, currentWeather()), favouriteWeather(FAV_PARIS, currentWeather(PARIS))],
        }),
      )
      .on('GET', forecastUrl(), () =>
        json(200, {
          items: [favouriteForecast(FAV_LONDON, forecast(undefined, 3)), favouriteForecast(FAV_PARIS, forecast(PARIS, 3))],
        }),
      );
    renderApp({ hash: '#/dashboard' });
    await screen.findByRole('article', { name: 'Home' });
    // Paris has no label: the heading is its city.
    expect(within(card('Paris')).getByRole('heading', { name: 'Paris' })).toBeInTheDocument();
    expect(card('Paris')).toHaveTextContent('Paris, FR');
    const weather = section('Home', 'Current weather');
    expect(weather).toHaveTextContent('11 °C');
    expect(weather).toHaveTextContent('Overcast clouds');
    expect(weather).toHaveTextContent('81%');
    expect(weather).toHaveTextContent('4.1 m/s, 230°');
    const rows = within(section('Home', 'Forecast')).getAllByRole('listitem');
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent(/Nov/);
    expect(rows[0]).toHaveTextContent('6 °C / 13 °C');
    expect(rows[0]).toHaveTextContent('Light rain');
    expect(rows[0]).not.toHaveTextContent('Rain 40%'); // compact rows: date, min/max, condition
    expect(mock.calls).toHaveLength(2);
    expect(mock.calls.every((c) => c.headers['x-client-id'] === CLIENT_ID)).toBe(true);
    expect(mock.calls.some((c) => c.url.includes('/weather/current') || c.url.includes('/weather/forecast'))).toBe(false);
  });

  it('AC-28, AC-11: with imperial units both aggregate requests use units=imperial', async () => {
    const mock = mockFetch()
      .on('GET', weatherUrl('imperial'), () =>
        json(200, { items: [favouriteWeather(FAV_LONDON, currentWeather(undefined, { units: 'imperial', temperature: 52.3 }))] }),
      )
      .on('GET', forecastUrl('imperial'), () =>
        json(200, { items: [favouriteForecast(FAV_LONDON, forecast(undefined, 3, 'imperial'))] }),
      );
    renderApp({ hash: '#/dashboard', units: 'imperial' });
    await screen.findByRole('article', { name: 'Home' });
    expect(section('Home', 'Current weather')).toHaveTextContent('52 °F');
    expect(section('Home', 'Current weather')).toHaveTextContent('mph');
    expect(mock.calls.map((c) => c.url)).toEqual([weatherUrl('imperial'), forecastUrl('imperial')]);
  });

  it('AC-29: LOCATION_NOT_FOUND per item; every item failing with HTTP 200 still renders all cards', async () => {
    mockFetch()
      .on('GET', weatherUrl(), () =>
        json(200, {
          items: [
            favouriteWeather(FAV_LONDON, undefined, { code: 'LOCATION_NOT_FOUND', detail: 'gone' }),
            favouriteWeather(FAV_PARIS, undefined, { code: 'UPSTREAM_UNAVAILABLE', detail: 'down' }),
            favouriteWeather(FAV_TOKYO, undefined, { code: 'SOMETHING_NEW' as 'LOCATION_NOT_FOUND', detail: '?' }),
          ],
        }),
      )
      .on('GET', forecastUrl(), () =>
        json(200, {
          items: [
            favouriteForecast(FAV_LONDON, undefined, { code: 'LOCATION_NOT_FOUND', detail: 'gone' }),
            favouriteForecast(FAV_PARIS, undefined, { code: 'UPSTREAM_UNAVAILABLE', detail: 'down' }),
            favouriteForecast(FAV_TOKYO, undefined, { code: 'SOMETHING_NEW' as 'LOCATION_NOT_FOUND', detail: '?' }),
          ],
        }),
      );
    renderApp({ hash: '#/dashboard' });
    await screen.findByRole('article', { name: 'Home' });
    expect(section('Home', 'Current weather')).toHaveTextContent('No weather data for this location.');
    expect(section('Home', 'Forecast')).toHaveTextContent('No weather data for this location.');
    expect(section('Paris', 'Current weather')).toHaveTextContent('Weather temporarily unavailable.');
    expect(section('Paris', 'Forecast')).toHaveTextContent('Weather temporarily unavailable.');
    expect(section('Tokyo', 'Current weather')).toHaveTextContent('No data available.');
    expect(section('Tokyo', 'Forecast')).toHaveTextContent('No data available.');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText('gone')).not.toBeInTheDocument(); // per-item detail is not shown
  });

  it('AC-29: a card whose forecast error is set still shows its weather, and other cards are unaffected', async () => {
    mockFetch()
      .on('GET', weatherUrl(), () =>
        json(200, {
          items: [favouriteWeather(FAV_PARIS, currentWeather(PARIS)), favouriteWeather(FAV_TOKYO, currentWeather(TOKYO))],
        }),
      )
      .on('GET', forecastUrl(), () =>
        json(200, {
          items: [
            favouriteForecast(FAV_PARIS, undefined, { code: 'UPSTREAM_UNAVAILABLE', detail: 'down' }),
            favouriteForecast(FAV_TOKYO, forecast(TOKYO, 3)),
          ],
        }),
      );
    renderApp({ hash: '#/dashboard' });
    await screen.findByRole('article', { name: 'Paris' });
    expect(section('Paris', 'Current weather')).toHaveTextContent('11 °C');
    expect(section('Paris', 'Forecast')).toHaveTextContent('Weather temporarily unavailable.');
    expect(within(section('Tokyo', 'Forecast')).getAllByRole('listitem')).toHaveLength(3);
  });

  it('AC-30: the weather aggregate failing on the network shows the AC-19 text; Retry re-sends only it', async () => {
    const mock = mockFetch()
      .on('GET', weatherUrl(), networkDown, { times: 1 })
      .on('GET', weatherUrl(), () => json(200, { items: [favouriteWeather(FAV_LONDON, currentWeather())] }))
      .on('GET', forecastUrl(), () => json(200, { items: [favouriteForecast(FAV_LONDON, forecast(undefined, 3))] }));
    const { user } = renderApp({ hash: '#/dashboard' });
    expect(await screen.findByRole('alert')).toHaveTextContent(`Can't reach the weather service at ${BASE_URL}.`);
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    await screen.findByRole('article', { name: 'Home' });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
    expect(mock.calls.map((c) => c.url)).toEqual([weatherUrl(), forecastUrl(), weatherUrl()]);
  });

  it('AC-30: both aggregates down shows the message once; Retry re-sends both', async () => {
    const mock = mockFetch()
      .on('GET', weatherUrl(), networkDown, { times: 1 })
      .on('GET', forecastUrl(), networkDown, { times: 1 })
      .on('GET', weatherUrl(), () => json(200, { items: [] }))
      .on('GET', forecastUrl(), () => json(200, { items: [] }));
    const { user } = renderApp({ hash: '#/dashboard' });
    await waitFor(() => expect(screen.getAllByRole('alert')).toHaveLength(1));
    expect(screen.getByRole('alert')).toHaveTextContent(`Can't reach the weather service at ${BASE_URL}.`);
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    await screen.findByText(/No favourites yet\./);
    expect(mock.calls).toHaveLength(4);
  });

  it('AC-30, AC-18: a 503 on the weather aggregate and a 500 on the forecast show both messages with references', async () => {
    mockFetch()
      .on('GET', weatherUrl(), () => json(503, problem(503, 'UPSTREAM_UNAVAILABLE')))
      .on('GET', forecastUrl(), () => json(500, problem(500, 'INTERNAL_ERROR', { requestId: 'agg-500' })));
    renderApp({ hash: '#/dashboard' });
    await waitFor(() => expect(screen.getAllByRole('alert')).toHaveLength(2));
    const texts = screen.getAllByRole('alert').map((a) => a.textContent ?? '');
    expect(texts.some((t) => t.includes('The weather service is temporarily unavailable. Please try again.'))).toBe(true);
    expect(texts.some((t) => t.includes('Something went wrong. Please try again.') && t.includes('Reference: agg-500'))).toBe(
      true,
    );
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
  });

  it('AC-31: Refresh shows the loading indicator while both requests are in flight', async () => {
    const w = deferred();
    const f = deferred();
    mockFetch()
      .on('GET', weatherUrl(), () => json(200, { items: [] }), { times: 1 })
      .on('GET', forecastUrl(), () => json(200, { items: [] }), { times: 1 })
      .on('GET', weatherUrl(), () => w.promise)
      .on('GET', forecastUrl(), () => f.promise);
    const { user } = renderApp({ hash: '#/dashboard' });
    await screen.findByText(/No favourites yet\./);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(screen.getByRole('status')).toHaveTextContent('Loading dashboard');
    w.resolve(json(200, { items: [] }));
    f.resolve(json(200, { items: [] }));
    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument());
  });

  it('AC-31: the dashboard never polls (no interval, no extra requests after 10 minutes)', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    try {
      const mock = mockFetch()
        .on('GET', weatherUrl(), () => json(200, { items: [favouriteWeather(FAV_LONDON, currentWeather())] }))
        .on('GET', forecastUrl(), () => json(200, { items: [favouriteForecast(FAV_LONDON, forecast(undefined, 3))] }));
      renderApp({ hash: '#/dashboard' });
      await screen.findByRole('article', { name: 'Home' });
      expect(vi.getTimerCount()).toBe(0);
      vi.advanceTimersByTime(10 * 60_000);
      await new Promise((r) => setTimeout(r, 20));
      expect(mock.calls).toHaveLength(2);
    } finally {
      vi.useRealTimers();
    }
  });
});

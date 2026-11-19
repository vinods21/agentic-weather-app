import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { deferred, json, mockFetch } from '../test/fetchMock';
import {
  API,
  FAV_LONDON,
  FAV_PARIS,
  FAV_TOKYO,
  currentWeather,
  favouriteForecast,
  favouriteWeather,
  forecast,
  problem,
  PARIS,
} from '../test/fixtures';
import { renderApp } from '../test/renderApp';

const weatherUrl = (units = 'metric') => `${API}/favourites/weather?units=${units}`;
const forecastUrl = (units = 'metric') => `${API}/favourites/forecast?units=${units}&days=3`;

function card(name: string) {
  return screen.getByRole('article', { name });
}

describe('DashboardView', () => {
  it('AC-28: sends both aggregates in parallel and renders one card per item', async () => {
    const w = deferred();
    const f = deferred();
    const mock = mockFetch()
      .on('GET', weatherUrl(), () => w.promise)
      .on('GET', forecastUrl(), () => f.promise);
    renderApp({ hash: '#/dashboard' });
    await waitFor(() => expect(mock.calls).toHaveLength(2));
    expect(screen.getByRole('status')).toHaveTextContent('Loading dashboard');

    w.resolve(
      json(200, {
        items: [favouriteWeather(FAV_LONDON, currentWeather()), favouriteWeather(FAV_PARIS, currentWeather(PARIS))],
      }),
    );
    f.resolve(
      json(200, {
        items: [favouriteForecast(FAV_PARIS, forecast(PARIS, 3)), favouriteForecast(FAV_LONDON, forecast(undefined, 3))],
      }),
    );
    await screen.findByRole('article', { name: 'Home' });
    const cards = within(screen.getByRole('list', { name: 'Favourite cards' })).getAllByRole('article');
    expect(cards.map((c) => within(c).getByRole('heading').textContent)).toEqual(['Home', 'Paris']);
    expect(card('Home')).toHaveTextContent('London, GB');
    expect(card('Home')).toHaveTextContent('11 °C');
    expect(within(within(card('Home')).getByRole('region', { name: 'Forecast' })).getAllByRole('listitem')).toHaveLength(3);
    expect(mock.calls.map((c) => c.url)).toEqual([weatherUrl(), forecastUrl()]);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('AC-29: per-item errors stay in their section; unknown codes and orphans are handled', async () => {
    mockFetch()
      .on('GET', weatherUrl(), () =>
        json(200, {
          items: [
            favouriteWeather(FAV_LONDON, undefined, { code: 'UPSTREAM_UNAVAILABLE', detail: 'x' }),
            favouriteWeather(FAV_PARIS, currentWeather(PARIS)),
          ],
        }),
      )
      .on('GET', forecastUrl(), () =>
        json(200, {
          items: [
            favouriteForecast(FAV_LONDON, forecast(undefined, 3)),
            favouriteForecast(FAV_PARIS, undefined, { code: 'SOMETHING_NEW' as 'LOCATION_NOT_FOUND', detail: 'y' }),
            favouriteForecast(FAV_TOKYO, forecast(undefined, 3)),
          ],
        }),
      );
    renderApp({ hash: '#/dashboard' });
    await screen.findByRole('article', { name: 'Home' });
    expect(within(card('Home')).getByRole('region', { name: 'Current weather' })).toHaveTextContent(
      'Weather temporarily unavailable.',
    );
    expect(within(card('Paris')).getByRole('region', { name: 'Forecast' })).toHaveTextContent('No data available.');
    expect(screen.queryByRole('article', { name: 'Tokyo' })).not.toBeInTheDocument();
  });

  it('AC-30: empty list links to Favourites', async () => {
    mockFetch()
      .on('GET', weatherUrl(), () => json(200, { items: [] }))
      .on('GET', forecastUrl(), () => json(200, { items: [] }));
    renderApp({ hash: '#/dashboard' });
    expect(await screen.findByText(/No favourites yet\./)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to Favourites' })).toHaveAttribute('href', '#/favourites');
  });

  it('AC-30: only the forecast failing keeps the weather; Retry re-sends only the forecast', async () => {
    const mock = mockFetch()
      .on('GET', weatherUrl(), () => json(200, { items: [favouriteWeather(FAV_LONDON, currentWeather())] }))
      .on('GET', forecastUrl(), () => json(500, problem(500, 'INTERNAL_ERROR')), { times: 1 })
      .on('GET', forecastUrl(), () => json(200, { items: [favouriteForecast(FAV_LONDON, forecast(undefined, 3))] }));
    const { user } = renderApp({ hash: '#/dashboard' });
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong. Please try again.');
    expect(card('Home')).toHaveTextContent('11 °C');
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(within(card('Home')).getByRole('region', { name: 'Forecast' })).toBeInTheDocument());
    expect(mock.calls.map((c) => c.url)).toEqual([weatherUrl(), forecastUrl(), forecastUrl()]);
  });

  it('AC-31: Refresh and a units change re-send both requests', async () => {
    const mock = mockFetch()
      .on('GET', /\/favourites\/weather\?/, () => json(200, { items: [] }))
      .on('GET', /\/favourites\/forecast\?/, () => json(200, { items: [] }));
    const { user } = renderApp({ hash: '#/dashboard' });
    await screen.findByText(/No favourites yet\./);
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    await user.selectOptions(screen.getByLabelText('Units'), 'imperial');
    await waitFor(() => expect(mock.calls).toHaveLength(6));
    expect(mock.calls.map((c) => c.url)).toEqual([
      weatherUrl(),
      forecastUrl(),
      weatherUrl(),
      forecastUrl(),
      weatherUrl('imperial'),
      forecastUrl('imperial'),
    ]);
    await screen.findByText(/No favourites yet\./);
  });
});

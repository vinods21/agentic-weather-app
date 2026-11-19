import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { deferred, json, mockFetch } from '../test/fetchMock';
import { API, FAV_LONDON, PARIS, TOKYO, currentWeather, forecast, problem } from '../test/fixtures';
import { renderApp } from '../test/renderApp';

const currentUrl = (city: string, units = 'metric') => `${API}/weather/current?city=${city}&units=${units}`;
const forecastUrl = (city: string, units = 'metric', days = 5) =>
  `${API}/weather/forecast?city=${city}&units=${units}&days=${days}`;

async function search(user: ReturnType<typeof renderApp>['user'], city: string) {
  const input = screen.getByLabelText('City');
  await user.clear(input);
  await user.type(input, `${city}{Enter}`);
}

describe('SearchView', () => {
  it('AC-9: shows current weather and one forecast row per day', async () => {
    const mock = mockFetch()
      .on('GET', currentUrl('London'), () => json(200, currentWeather()))
      .on('GET', forecastUrl('London'), () => json(200, forecast()));
    const { user } = renderApp();
    await search(user, 'London');

    expect(await screen.findByRole('heading', { name: 'London, GB' })).toBeInTheDocument();
    expect(screen.getByText('11 °C')).toBeInTheDocument();
    expect(screen.getByText('10 °C')).toBeInTheDocument();
    expect(screen.getByText('81%')).toBeInTheDocument();
    expect(screen.getByText('4.1 m/s, 230°')).toBeInTheDocument();
    expect(screen.getByText('Overcast clouds')).toBeInTheDocument();
    const rows = within(screen.getByRole('region', { name: 'Forecast' })).getAllByRole('listitem');
    expect(rows).toHaveLength(5);
    expect(mock.calls.map((c) => c.url)).toEqual([currentUrl('London'), forecastUrl('London')]);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('AC-10: an empty city sends nothing and shows a linked inline error', async () => {
    const { user } = renderApp();
    await user.type(screen.getByLabelText('City'), '   {Enter}');
    const error = screen.getByRole('alert');
    expect(error).toHaveTextContent('Enter a city name.');
    expect(screen.getByLabelText('City')).toHaveAttribute('aria-describedby', error.id);
    expect(screen.getByLabelText('City')).toHaveAttribute('aria-invalid', 'true');
  });

  it('AC-16: a forecast failure keeps the current weather and offers a forecast-only retry', async () => {
    const mock = mockFetch()
      .on('GET', currentUrl('London'), () => json(200, currentWeather()))
      .on('GET', forecastUrl('London'), () => json(503, problem(503, 'UPSTREAM_UNAVAILABLE')), { times: 1 })
      .on('GET', forecastUrl('London'), () => json(200, forecast()));
    const { user } = renderApp();
    await search(user, 'London');

    const forecastRegion = await screen.findByRole('region', { name: 'Forecast' });
    expect(await within(forecastRegion).findByRole('alert')).toHaveTextContent(
      'The weather service is temporarily unavailable. Please try again.',
    );
    expect(screen.getByRole('heading', { name: 'London, GB' })).toBeInTheDocument();
    await user.click(within(forecastRegion).getByRole('button', { name: 'Retry forecast' }));
    await waitFor(() => expect(within(forecastRegion).getAllByRole('listitem')).toHaveLength(5));
    expect(mock.calls.map((c) => c.url)).toEqual([currentUrl('London'), forecastUrl('London'), forecastUrl('London')]);
  });

  it('AC-17: only the latest search is displayed when an earlier one responds last', async () => {
    const parisCurrent = deferred();
    const parisForecast = deferred();
    mockFetch()
      .on('GET', currentUrl('Paris'), () => parisCurrent.promise)
      .on('GET', forecastUrl('Paris'), () => parisForecast.promise)
      .on('GET', currentUrl('Tokyo'), () => json(200, currentWeather(TOKYO)))
      .on('GET', forecastUrl('Tokyo'), () => json(200, forecast(TOKYO)));
    const { user } = renderApp();
    await search(user, 'Paris');
    expect(screen.getByRole('status')).toHaveTextContent('Loading weather');
    expect(screen.getByRole('button', { name: 'Search' })).toBeEnabled();
    await search(user, 'Tokyo');
    expect(await screen.findByRole('heading', { name: 'Tokyo, JP' })).toBeInTheDocument();
    parisCurrent.resolve(json(200, currentWeather(PARIS)));
    parisForecast.resolve(json(200, forecast(PARIS)));
    await new Promise((r) => setTimeout(r, 0));
    expect(screen.queryByRole('heading', { name: 'Paris, FR' })).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Tokyo, JP' })).toBeInTheDocument();
  });

  it('AC-11, AC-12: units change re-sends both, days change re-sends the forecast only', async () => {
    const mock = mockFetch()
      .on('GET', /\/weather\/current\?/, (req) =>
        json(200, currentWeather(undefined, { units: req.url.includes('imperial') ? 'imperial' : 'metric' })),
      )
      .on('GET', /\/weather\/forecast\?/, (req) => {
        const days = Number(new URL(req.url).searchParams.get('days'));
        return json(200, forecast(undefined, days, req.url.includes('imperial') ? 'imperial' : 'metric'));
      });
    const { user } = renderApp();
    await search(user, 'London');
    await screen.findByRole('heading', { name: 'London, GB' });

    await user.selectOptions(screen.getByLabelText('Units'), 'imperial');
    await screen.findByText('4.1 mph, 230°');
    await user.selectOptions(screen.getByLabelText('Days'), '3');
    await waitFor(() =>
      expect(within(screen.getByRole('region', { name: 'Forecast' })).getAllByRole('listitem')).toHaveLength(3),
    );
    expect(mock.calls.map((c) => c.url)).toEqual([
      currentUrl('London'),
      forecastUrl('London'),
      currentUrl('London', 'imperial'),
      forecastUrl('London', 'imperial'),
      forecastUrl('London', 'imperial', 3),
    ]);
  });

  it('AC-23: Add to favourites posts the typed city and shows Saved', async () => {
    const mock = mockFetch()
      .on('GET', currentUrl('London'), () => json(200, currentWeather()))
      .on('GET', forecastUrl('London'), () => json(200, forecast()))
      .on('POST', `${API}/favourites`, () => json(201, FAV_LONDON));
    const { user } = renderApp();
    await search(user, ' London ');
    await user.click(await screen.findByRole('button', { name: 'Add to favourites' }));
    expect(await screen.findByRole('button', { name: 'Saved' })).toBeDisabled();
    expect(mock.calls[2]?.body).toBe('{"city":"London"}');
  });
});

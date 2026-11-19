// Tester: AC-mapped component tests for the Search view that the developer's SearchView.test.tsx
// does not already cover (AC-9..AC-19, AC-23, AC-34 keyboard, request budget).
import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { deferred, json, mockFetch } from '../test/fetchMock';
import { API, BASE_URL, FAV_LONDON, PARIS, currentWeather, forecast, problem } from '../test/fixtures';
import { renderApp } from '../test/renderApp';
import { formatObservedAt } from '../format/format';
import { loadUnits } from '../storage/units';

const currentUrl = (city: string, units = 'metric') => `${API}/weather/current?city=${city}&units=${units}`;
const forecastUrl = (city: string, units = 'metric', days = 5) =>
  `${API}/weather/forecast?city=${city}&units=${units}&days=${days}`;

type User = ReturnType<typeof renderApp>['user'];

async function search(user: User, city: string) {
  const input = screen.getByLabelText('City');
  await user.clear(input);
  await user.type(input, `${city}{Enter}`);
}

const networkDown = () => Promise.reject(new TypeError('Failed to fetch'));

/** Answers both weather endpoints for any city with London-shaped data in the requested units/days. */
function mockAnyCity() {
  return mockFetch()
    .on('GET', /\/weather\/current\?/, (req) =>
      json(200, currentWeather(undefined, { units: req.url.includes('imperial') ? 'imperial' : 'metric' })),
    )
    .on('GET', /\/weather\/forecast\?/, (req) => {
      const days = Number(new URL(req.url).searchParams.get('days'));
      return json(200, forecast(undefined, days, req.url.includes('imperial') ? 'imperial' : 'metric'));
    });
}

describe('SearchView (tester AC coverage)', () => {
  it('AC-9: activating the Search button sends both requests and shows every field of the result', async () => {
    const mock = mockFetch()
      .on('GET', currentUrl('London'), () => json(200, currentWeather()))
      .on('GET', forecastUrl('London'), () => json(200, forecast()));
    const { user } = renderApp();
    await user.type(screen.getByLabelText('City'), 'London');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    const panel = await screen.findByRole('region', { name: 'London, GB' });
    expect(within(panel).getByText('11 °C')).toBeInTheDocument(); // temperature 11.2
    expect(within(panel).getByText('10 °C')).toBeInTheDocument(); // feels like 9.8
    expect(within(panel).getByText('81%')).toBeInTheDocument();
    expect(within(panel).getByText('4.1 m/s, 230°')).toBeInTheDocument();
    expect(within(panel).getByText('Overcast clouds')).toBeInTheDocument();
    // observedAt 10:00Z is shown in browser local time (TZ America/Los_Angeles = 02:00).
    const observed = within(panel).getByText(`Observed ${formatObservedAt('2026-11-15T10:00:00Z')}`);
    expect(observed.textContent).toMatch(/\b2:00\b/);
    expect(observed.textContent).not.toMatch(/10:00/);

    const rows = within(screen.getByRole('region', { name: 'Forecast' })).getAllByRole('listitem');
    expect(rows).toHaveLength(5);
    // date, min/max (6 / 12.5 -> 13), rain probability, condition
    expect(rows[0]).toHaveTextContent(/Nov/);
    expect(rows[0]).toHaveTextContent('15');
    expect(rows[0]).toHaveTextContent('6 °C / 13 °C');
    expect(rows[0]).toHaveTextContent('Rain 40%');
    expect(rows[0]).toHaveTextContent('Light rain');
    expect(mock.calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      `GET ${currentUrl('London')}`,
      `GET ${forecastUrl('London')}`,
    ]);
  });

  it('AC-10: a city longer than 100 characters sends nothing and shows the length error', async () => {
    const mock = mockFetch();
    const { user } = renderApp();
    await user.click(screen.getByLabelText('City'));
    await user.paste(`  ${'a'.repeat(101)}  `);
    await user.click(screen.getByRole('button', { name: 'Search' }));
    const error = screen.getByRole('alert');
    expect(error).toHaveTextContent('City name must be at most 100 characters.');
    expect(screen.getByLabelText('City')).toHaveAttribute('aria-describedby', error.id);
    expect(mock.calls).toHaveLength(0);
  });

  it('AC-10: exactly 100 characters (after trimming) are sent', async () => {
    const city = 'b'.repeat(100);
    const mock = mockFetch()
      .on('GET', currentUrl(city), () => json(200, currentWeather()))
      .on('GET', forecastUrl(city), () => json(200, forecast()));
    const { user } = renderApp();
    await user.click(screen.getByLabelText('City'));
    await user.paste(`   ${city}   `);
    await user.keyboard('{Enter}');
    await screen.findByRole('heading', { name: 'London, GB' });
    expect(mock.calls).toHaveLength(2);
  });

  it('AC-10: the trimmed value is URL-encoded (São Paulo -> S%C3%A3o%20Paulo)', async () => {
    const mock = mockFetch()
      .on('GET', currentUrl('S%C3%A3o%20Paulo'), () => json(200, currentWeather()))
      .on('GET', forecastUrl('S%C3%A3o%20Paulo'), () => json(200, forecast()));
    const { user } = renderApp();
    await search(user, '  São Paulo  ');
    await screen.findByRole('heading', { name: 'London, GB' });
    expect(mock.calls.map((c) => c.url)).toEqual([currentUrl('S%C3%A3o%20Paulo'), forecastUrl('S%C3%A3o%20Paulo')]);
  });

  it('AC-10: a corrected input clears the inline error', async () => {
    mockAnyCity();
    const { user } = renderApp();
    await user.type(screen.getByLabelText('City'), '{Enter}');
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a city name.');
    await search(user, 'London');
    await screen.findByRole('heading', { name: 'London, GB' });
    expect(screen.queryByText('Enter a city name.')).not.toBeInTheDocument();
    expect(screen.getByLabelText('City')).not.toHaveAttribute('aria-invalid');
  });

  it('AC-11: imperial shows °F and mph; the choice is reused after a reload', async () => {
    const mock = mockAnyCity();
    const first = renderApp();
    await search(first.user, 'London');
    await screen.findByText('11 °C');
    await first.user.selectOptions(screen.getByLabelText('Units'), 'imperial');
    expect(await screen.findByText('4.1 mph, 230°')).toBeInTheDocument();
    expect(screen.getByText('11 °F')).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Forecast' })).getAllByRole('listitem')[0]).toHaveTextContent(
      '6 °F / 13 °F',
    );
    first.unmount();

    // "Reload": main.tsx reads the stored units the same way.
    const second = renderApp({ units: loadUnits(() => window.localStorage) });
    expect(screen.getByLabelText('Units')).toHaveValue('imperial');
    await search(second.user, 'London');
    await screen.findByText('11 °F');
    expect(mock.calls.slice(-2).map((c) => c.url)).toEqual([
      currentUrl('London', 'imperial'),
      forecastUrl('London', 'imperial'),
    ]);
  });

  it('AC-12: Days defaults to 5, offers 1..7, and dates are not shifted by the timezone', async () => {
    expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe('America/Los_Angeles');
    mockAnyCity();
    const { user } = renderApp();
    const days = screen.getByLabelText('Days');
    expect(days).toHaveValue('5');
    expect(within(days).getAllByRole('option').map((o) => o.textContent)).toEqual(['1', '2', '3', '4', '5', '6', '7']);
    await search(user, 'London');
    const region = await screen.findByRole('region', { name: 'Forecast' });
    await waitFor(() => expect(within(region).getAllByRole('listitem')).toHaveLength(5));
    const first = within(region).getAllByRole('listitem')[0] as HTMLElement;
    const date = within(first).getByText(/Nov/).textContent ?? '';
    expect(date).toMatch(/\b15\b/);
    expect(date).toMatch(/Nov/);
    expect(date).not.toMatch(/\b14\b/);
  });

  it('AC-13: LOCATION_NOT_FOUND shows the not-found text and no previous result stays', async () => {
    mockFetch()
      .on('GET', currentUrl('London'), () => json(200, currentWeather()))
      .on('GET', forecastUrl('London'), () => json(200, forecast()))
      .on('GET', currentUrl('Atlantis'), () => json(404, problem(404, 'LOCATION_NOT_FOUND')))
      .on('GET', forecastUrl('Atlantis'), () => json(404, problem(404, 'LOCATION_NOT_FOUND')));
    const { user } = renderApp();
    await search(user, 'London');
    await screen.findByRole('heading', { name: 'London, GB' });
    await search(user, 'Atlantis');
    expect(await screen.findByRole('alert')).toHaveTextContent('No weather data found for "Atlantis".');
    expect(screen.queryByRole('heading', { name: 'London, GB' })).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Forecast' })).not.toBeInTheDocument();
    expect(screen.queryByText('Overcast clouds')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add to favourites' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });

  it('AC-14: UPSTREAM_UNAVAILABLE shows the message with Retry, and Retry sends the same requests', async () => {
    const mock = mockFetch()
      .on('GET', currentUrl('London', 'metric'), () => json(503, problem(503, 'UPSTREAM_UNAVAILABLE')), { times: 1 })
      .on('GET', forecastUrl('London', 'metric', 3), () => json(503, problem(503, 'UPSTREAM_UNAVAILABLE')), { times: 1 })
      .on('GET', currentUrl('London', 'metric'), () => json(200, currentWeather()))
      .on('GET', forecastUrl('London', 'metric', 3), () => json(200, forecast(undefined, 3)));
    const { user } = renderApp();
    await user.selectOptions(screen.getByLabelText('Days'), '3');
    await search(user, 'London');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The weather service is temporarily unavailable. Please try again.');
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    await screen.findByRole('heading', { name: 'London, GB' });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(mock.calls.map((c) => c.url)).toEqual([
      currentUrl('London'),
      forecastUrl('London', 'metric', 3),
      currentUrl('London'),
      forecastUrl('London', 'metric', 3),
    ]);
  });

  it('AC-15: a server city error is shown inline; fields without a control are listed', async () => {
    const body = problem(400, 'VALIDATION_FAILED', {
      errors: [
        { field: 'city', message: 'must not be blank' },
        { field: 'units', message: 'must be metric or imperial' },
        { field: 'days', message: 'must be between 1 and 7' },
        { field: 'X-Client-Id', message: 'is invalid' },
        { field: 'body', message: 'is malformed' },
      ],
    });
    mockFetch()
      .on('GET', currentUrl('London'), () => json(400, body))
      .on('GET', forecastUrl('London'), () => json(400, body));
    const { user } = renderApp();
    await search(user, 'London');
    const inline = await screen.findByText('must not be blank');
    expect(inline).toHaveAttribute('role', 'alert');
    expect(screen.getByLabelText('City')).toHaveAttribute('aria-describedby', inline.id);
    expect(screen.getByLabelText('City')).toHaveAttribute('aria-invalid', 'true');
    const general = screen
      .getAllByRole('alert')
      .find((a) => a.textContent?.includes('Some values were not accepted:')) as HTMLElement;
    expect(general).toBeDefined();
    const listed = within(general)
      .getAllByRole('listitem')
      .map((li) => li.textContent);
    expect(listed).toEqual([
      'units: must be metric or imperial',
      'days: must be between 1 and 7',
      'X-Client-Id: is invalid',
      'body: is malformed',
    ]);
    expect(general).not.toHaveTextContent('city:');
  });

  it('AC-15: only a city error gives the inline error and the "check the highlighted values" message', async () => {
    const body = problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'city', message: 'size must be at most 100' }] });
    mockFetch()
      .on('GET', currentUrl('London'), () => json(400, body))
      .on('GET', forecastUrl('London'), () => json(400, body));
    const { user } = renderApp();
    await search(user, 'London');
    expect(await screen.findByText('size must be at most 100')).toBeInTheDocument();
    expect(screen.getByText('Please check the highlighted values.')).toBeInTheDocument();
  });

  it('AC-17: the loading indicator shows while in flight and disappears when the requests settle', async () => {
    const c = deferred();
    const f = deferred();
    mockFetch()
      .on('GET', currentUrl('London'), () => c.promise)
      .on('GET', forecastUrl('London'), () => f.promise);
    const { user } = renderApp();
    await search(user, 'London');
    expect(screen.getByRole('status')).toHaveTextContent('Loading weather');
    expect(screen.getByRole('button', { name: 'Search' })).toBeEnabled();
    c.resolve(json(200, currentWeather()));
    await screen.findByRole('heading', { name: 'London, GB' });
    expect(screen.getByRole('status')).toBeInTheDocument(); // forecast still in flight
    f.resolve(json(200, forecast()));
    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument());
  });

  it('AC-17: a units change mid-flight shows only the latest (imperial) result', async () => {
    const metricCurrent = deferred();
    const metricForecast = deferred();
    mockFetch()
      .on('GET', currentUrl('Paris'), () => metricCurrent.promise)
      .on('GET', forecastUrl('Paris'), () => metricForecast.promise)
      .on('GET', currentUrl('Paris', 'imperial'), () =>
        json(200, currentWeather(PARIS, { units: 'imperial', temperature: 52.2 })),
      )
      .on('GET', forecastUrl('Paris', 'imperial'), () => json(200, forecast(PARIS, 5, 'imperial')));
    const { user } = renderApp();
    await search(user, 'Paris');
    await user.selectOptions(screen.getByLabelText('Units'), 'imperial');
    expect(await screen.findByText('52 °F')).toBeInTheDocument();
    metricCurrent.resolve(json(200, currentWeather(PARIS, { temperature: 11.2 })));
    metricForecast.resolve(json(200, forecast(PARIS)));
    await new Promise((r) => setTimeout(r, 0));
    expect(screen.queryByText('11 °C')).not.toBeInTheDocument();
    expect(screen.getByText('52 °F')).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('AC-18: a 500 INTERNAL_ERROR shows the generic text with the body requestId as Reference', async () => {
    const body = problem(500, 'INTERNAL_ERROR', { requestId: 'req-body-1', detail: 'java.lang.NullPointerException at X' });
    mockFetch()
      .on('GET', currentUrl('London'), () => json(500, body, { 'X-Request-Id': 'req-header-1' }))
      .on('GET', forecastUrl('London'), () => json(500, body));
    const { user } = renderApp();
    await search(user, 'London');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong. Please try again.');
    expect(alert).toHaveTextContent('Reference: req-body-1');
    expect(alert).not.toHaveTextContent('req-header-1');
    expect(alert).not.toHaveTextContent('NullPointerException');
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('AC-18: an HTML proxy page is not rendered; the reference comes from X-Request-Id', async () => {
    const html = () =>
      new Response('<html><body><h1>Bad Gateway</h1><p>nginx stack trace</p></body></html>', {
        status: 502,
        headers: { 'Content-Type': 'text/html', 'X-Request-Id': 'proxy-123' },
      });
    mockFetch().on('GET', currentUrl('London'), html).on('GET', forecastUrl('London'), html);
    const { user } = renderApp();
    await search(user, 'London');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong. Please try again.');
    expect(alert).toHaveTextContent('Reference: proxy-123');
    expect(screen.queryByText(/Bad Gateway/)).not.toBeInTheDocument();
    expect(screen.queryByText(/stack trace/)).not.toBeInTheDocument();
  });

  it('AC-18: a 415 JSON body without code gives the generic text', async () => {
    const springDefault = () =>
      json(415, { timestamp: '2026-11-15T10:00:00Z', status: 415, error: 'Unsupported Media Type', path: '/x' });
    mockFetch().on('GET', currentUrl('London'), springDefault).on('GET', forecastUrl('London'), springDefault);
    const { user } = renderApp();
    await search(user, 'London');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong. Please try again.');
    expect(alert).not.toHaveTextContent('Unsupported Media Type');
  });

  it('AC-18: an unknown 4xx code shows the Problem detail as plain text (markup is not interpreted)', async () => {
    const detail = '<b>bold</b> <img src=x onerror=alert(1)> too many requests';
    const body = problem(429, 'RATE_LIMITED', { detail, requestId: 'r-429' });
    mockFetch()
      .on('GET', currentUrl('London'), () => json(429, body))
      .on('GET', forecastUrl('London'), () => json(429, body));
    const { user } = renderApp();
    await search(user, 'London');
    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText(detail)).toBeInTheDocument();
    expect(screen.queryByText('bold')).not.toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
    expect(alert).toHaveTextContent('Reference: r-429');
    expect(within(alert).queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });

  it('AC-19: a rejected fetch shows the base URL message with Retry, and Retry recovers', async () => {
    const mock = mockFetch()
      .on('GET', currentUrl('London'), networkDown, { times: 1 })
      .on('GET', forecastUrl('London'), networkDown, { times: 1 })
      .on('GET', currentUrl('London'), () => json(200, currentWeather()))
      .on('GET', forecastUrl('London'), () => json(200, forecast()));
    const { user } = renderApp();
    await search(user, 'London');
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(`Can't reach the weather service at ${BASE_URL}.`);
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    await screen.findByRole('heading', { name: 'London, GB' });
    expect(mock.calls).toHaveLength(4);
  });

  it('AC-19: the message names a custom base URL without /api/v1', async () => {
    mockFetch()
      .on('GET', 'https://api.example.test/api/v1/weather/current?city=London&units=metric', networkDown)
      .on('GET', 'https://api.example.test/api/v1/weather/forecast?city=London&units=metric&days=5', networkDown);
    const { user } = renderApp({ baseUrl: 'https://api.example.test' });
    await search(user, 'London');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      "Can't reach the weather service at https://api.example.test.",
    );
  });

  it('AC-23, AC-24: Add to favourites errors appear next to the button with the AC-24 texts', async () => {
    const mock = mockFetch()
      .on('GET', currentUrl('London'), () => json(200, currentWeather()))
      .on('GET', forecastUrl('London'), () => json(200, forecast()))
      .on('POST', `${API}/favourites`, () => json(409, problem(409, 'FAVOURITE_ALREADY_EXISTS')), { times: 1 })
      .on('POST', `${API}/favourites`, () => json(409, problem(409, 'FAVOURITES_LIMIT_REACHED')), { times: 1 });
    const { user } = renderApp();
    await search(user, 'London');
    await user.click(await screen.findByRole('button', { name: 'Add to favourites' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('London is already in your favourites.');
    await user.click(screen.getByRole('button', { name: 'Add to favourites' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      "You've reached the maximum number of favourites. Remove one to add another.",
    );
    expect(mock.calls.filter((c) => c.method === 'POST').map((c) => c.headers['content-type'])).toEqual([
      'application/json',
      'application/json',
    ]);
  });

  it('AC-23: Saved stays after a units change and resets on a new city search', async () => {
    mockAnyCity().on('POST', `${API}/favourites`, () => json(201, FAV_LONDON));
    const { user } = renderApp();
    await search(user, 'London');
    await user.click(await screen.findByRole('button', { name: 'Add to favourites' }));
    expect(await screen.findByRole('button', { name: 'Saved' })).toBeDisabled();
    await user.selectOptions(screen.getByLabelText('Units'), 'imperial');
    await screen.findByText('11 °F');
    expect(screen.getByRole('button', { name: 'Saved' })).toBeDisabled();
    await search(user, 'Paris');
    expect(await screen.findByRole('button', { name: 'Add to favourites' })).toBeEnabled();
  });

  it('AC-34: the Search view is operable by keyboard (Tab, type, Enter; Tab to Search, Space)', async () => {
    const mock = mockAnyCity();
    const { user } = renderApp();
    const city = screen.getByLabelText('City');
    for (let i = 0; i < 10 && document.activeElement !== city; i += 1) await user.tab();
    expect(city).toHaveFocus();
    await user.keyboard('London{Enter}');
    await screen.findByRole('heading', { name: 'London, GB' });
    expect(mock.calls).toHaveLength(2);

    await user.tab();
    expect(screen.getByLabelText('Days')).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Search' })).toHaveFocus();
    await user.keyboard(' ');
    await waitFor(() => expect(mock.calls).toHaveLength(4));
    await screen.findByRole('heading', { name: 'London, GB' });
  });

  it('NFR request budget: typing and changing Days before a search send nothing; a search sends 2', async () => {
    const mock = mockAnyCity();
    const { user } = renderApp();
    await user.type(screen.getByLabelText('City'), 'Lond');
    await user.selectOptions(screen.getByLabelText('Days'), '7');
    expect(mock.calls).toHaveLength(0);
    await user.type(screen.getByLabelText('City'), 'on{Enter}');
    await screen.findByRole('heading', { name: 'London, GB' });
    await waitFor(() => expect(within(screen.getByRole('region', { name: 'Forecast' })).getAllByRole('listitem')).toHaveLength(7));
    expect(mock.calls.map((c) => c.url)).toEqual([currentUrl('London'), forecastUrl('London', 'metric', 7)]);
  });
});

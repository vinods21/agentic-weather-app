// Tester: shell-level AC coverage not in App.test.tsx (AC-32 back/forward via hashchange,
// AC-34 labels and Tab reachability on every view).
import { act, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { json, mockFetch } from './test/fetchMock';
import { API, FAV_LONDON, FAV_PARIS, currentWeather, favouriteForecast, favouriteWeather, forecast } from './test/fixtures';
import { renderApp } from './test/renderApp';

function mockViewLoads() {
  return mockFetch()
    .on('GET', `${API}/favourites`, () => json(200, { items: [FAV_LONDON, FAV_PARIS] }))
    .on('GET', /\/favourites\/weather\?/, () =>
      json(200, { items: [favouriteWeather(FAV_LONDON, currentWeather())] }),
    )
    .on('GET', /\/favourites\/forecast\?/, () =>
      json(200, { items: [favouriteForecast(FAV_LONDON, forecast(undefined, 3))] }),
    );
}

const VIEWS: Array<{ hash: string; heading: string; ready: () => Promise<unknown> }> = [
  { hash: '#/search', heading: 'Search', ready: async () => undefined },
  {
    hash: '#/favourites',
    heading: 'Favourites',
    ready: () => waitFor(() => expect(screen.getAllByRole('button', { name: 'Delete' })).toHaveLength(2)),
  },
  { hash: '#/dashboard', heading: 'Dashboard', ready: () => screen.findByRole('article', { name: 'Home' }) },
];

function interactive(): HTMLElement[] {
  return [
    ...screen.getAllByRole('link'),
    ...screen.getAllByRole('button'),
    ...screen.queryAllByRole('textbox'),
    ...screen.queryAllByRole('combobox'),
  ];
}

describe('App shell (tester AC coverage)', () => {
  for (const { hash, heading, ready } of VIEWS) {
    it(`AC-34: on ${heading}, every control has an accessible name and every enabled control is reachable with Tab`, async () => {
      mockViewLoads();
      const { user } = renderApp({ hash });
      await ready();
      for (const control of [...screen.queryAllByRole('textbox'), ...screen.queryAllByRole('combobox')]) {
        expect(control).toHaveAccessibleName();
        expect(control.id).not.toBe('');
      }
      for (const control of interactive()) expect(control).toHaveAccessibleName();

      const expected = interactive().filter((el) => !(el as HTMLButtonElement).disabled);
      const visited = new Set<Element>();
      for (let i = 0; i < expected.length + 5; i += 1) {
        await user.tab();
        if (document.activeElement) visited.add(document.activeElement);
      }
      const missed = expected.filter((el) => !visited.has(el)).map((el) => el.outerHTML.slice(0, 80));
      expect(missed).toEqual([]);
    });
  }

  it('AC-32: browser back/forward (hashchange) switches the view', async () => {
    mockViewLoads();
    renderApp({ hash: '#/dashboard' });
    await screen.findByRole('article', { name: 'Home' });
    await act(async () => {
      window.location.hash = '#/favourites';
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    });
    expect(await screen.findByRole('heading', { level: 1, name: 'Favourites' })).toBeInTheDocument();
    expect(within(screen.getByRole('navigation', { name: 'Main' })).getByRole('link', { name: 'Favourites' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    await act(async () => {
      window.location.hash = '';
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    });
    expect(await screen.findByRole('heading', { level: 1, name: 'Search' })).toBeInTheDocument();
  });
});

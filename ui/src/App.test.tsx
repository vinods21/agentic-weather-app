import { act, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { json, mockFetch } from './test/fetchMock';
import { API } from './test/fixtures';
import { renderApp } from './test/renderApp';

/** Views that load on open get empty answers, so shell tests make no unmocked calls. */
function mockViewLoads() {
  return mockFetch()
    .on('GET', `${API}/favourites`, () => json(200, { items: [] }))
    .on('GET', /\/favourites\/weather\?/, () => json(200, { items: [] }))
    .on('GET', /\/favourites\/forecast\?/, () => json(200, { items: [] }));
}

const VIEWS = [
  { hash: '#/search', heading: 'Search' },
  { hash: '#/favourites', heading: 'Favourites' },
  { hash: '#/dashboard', heading: 'Dashboard' },
];

describe('App shell', () => {
  it('AC-32: a nav landmark offers Search, Favourites and Dashboard', () => {
    renderApp();
    const nav = screen.getByRole('navigation', { name: 'Main' });
    expect(within(nav).getByRole('link', { name: 'Search' })).toHaveAttribute('href', '#/search');
    expect(within(nav).getByRole('link', { name: 'Favourites' })).toHaveAttribute('href', '#/favourites');
    expect(within(nav).getByRole('link', { name: 'Dashboard' })).toHaveAttribute('href', '#/dashboard');
  });

  it.each([[''], ['#/nowhere']])('AC-32: the default view is Search (hash %j)', (hash) => {
    renderApp({ hash });
    expect(screen.getByRole('heading', { level: 1, name: 'Search' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Search' })).toHaveAttribute('aria-current', 'page');
  });

  for (const { hash, heading } of VIEWS) {
    it(`AC-32, AC-33: loading ${hash} shows that view and the attribution footer`, async () => {
      mockViewLoads();
      renderApp({ hash });
      expect(screen.getByRole('heading', { level: 1, name: heading })).toBeInTheDocument();
      expect(screen.getByRole('link', { name: heading })).toHaveAttribute('aria-current', 'page');
      const footer = screen.getByRole('contentinfo');
      expect(within(footer).getByRole('link', { name: 'Open-Meteo.com' })).toHaveAttribute(
        'href',
        'https://open-meteo.com/',
      );
      expect(within(footer).getByRole('link', { name: 'CC BY 4.0' })).toHaveAttribute(
        'href',
        'https://creativecommons.org/licenses/by/4.0/',
      );
      expect(footer).toHaveTextContent('Weather data by Open-Meteo.com');
      await act(async () => {});
    });
  }

  it('AC-32: following a nav link switches the view and focuses its heading', async () => {
    mockViewLoads();
    const { user } = renderApp();
    await user.click(screen.getByRole('link', { name: 'Favourites' }));
    const heading = await screen.findByRole('heading', { level: 1, name: 'Favourites' });
    expect(heading).toHaveFocus();
  });

  it('AC-11: the Units control defaults to metric and stores the choice', async () => {
    const { user } = renderApp();
    const select = screen.getByLabelText('Units');
    expect(select).toHaveValue('metric');
    await user.selectOptions(select, 'imperial');
    expect(select).toHaveValue('imperial');
    expect(window.localStorage.getItem('weather.units')).toBe('imperial');
  });

  it('AC-8: a notice is shown when storage is unavailable, without calling the id an account', () => {
    renderApp({ storagePersistent: false });
    const note = screen.getByRole('note');
    expect(note).toHaveTextContent('will forget your favourites when the tab closes');
    expect(document.body.textContent ?? '').not.toMatch(/account|log ?in/i);
  });

  it('AC-8: no notice when storage works', () => {
    renderApp();
    expect(screen.queryByRole('note')).not.toBeInTheDocument();
  });
});

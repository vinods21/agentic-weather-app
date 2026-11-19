// Tester: AC-mapped component tests for the Favourites view that FavouritesView.test.tsx does not
// already cover (AC-21..AC-27 edge paths, AC-18/AC-19 on this view, AC-34 keyboard).
import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { deferred, json, mockFetch, noContent } from '../test/fetchMock';
import { API, BASE_URL, CLIENT_ID, FAV_LONDON, FAV_PARIS, FAV_TOKYO, LONDON, favourite, problem } from '../test/fixtures';
import { renderApp } from '../test/renderApp';

const LIST = `${API}/favourites`;
const networkDown = () => Promise.reject(new TypeError('Failed to fetch'));

function items() {
  return within(screen.getByRole('list', { name: 'Your favourites' })).getAllByRole('listitem');
}

function item(index: number) {
  return items()[index] as HTMLElement;
}

/** The item's visible text lines (label, city, location); the Move buttons also carry the name, visually hidden. */
function lines(li: HTMLElement) {
  return within(li)
    .getAllByRole('paragraph')
    .map((p) => p.textContent);
}

describe('FavouritesView (tester AC coverage)', () => {
  it('AC-21: an item without a label shows its city and location; a labelled one shows all three', async () => {
    mockFetch().on('GET', LIST, () => json(200, { items: [FAV_PARIS, FAV_LONDON] }));
    renderApp({ hash: '#/favourites' });
    await waitFor(() => expect(items()).toHaveLength(2));
    expect(lines(item(0))).toEqual(['Paris', 'Paris, FR']);
    expect(lines(item(1))).toEqual(['Home', 'London', 'London, GB']);
  });

  it('AC-21, AC-19: a failed list load shows the network message with Retry, and Retry reloads', async () => {
    const mock = mockFetch()
      .on('GET', LIST, networkDown, { times: 1 })
      .on('GET', LIST, () => json(200, { items: [FAV_PARIS] }));
    const { user } = renderApp({ hash: '#/favourites' });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(`Can't reach the weather service at ${BASE_URL}.`);
    await user.click(within(alert).getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(items()).toHaveLength(1));
    expect(mock.calls.every((c) => c.headers['x-client-id'] === CLIENT_ID)).toBe(true);
  });

  it('AC-22: a whitespace-only label is sent as no label, with JSON content type and client id', async () => {
    const mock = mockFetch()
      .on('GET', LIST, () => json(200, { items: [] }))
      .on('POST', LIST, () => json(201, FAV_PARIS));
    const { user } = renderApp({ hash: '#/favourites' });
    await screen.findByText('No favourites yet.');
    await user.type(screen.getByLabelText('City'), '  Paris ');
    await user.type(screen.getByLabelText('Label (optional)'), '   ');
    await user.click(screen.getByRole('button', { name: 'Add favourite' }));
    await waitFor(() => expect(items()).toHaveLength(1));
    const post = mock.calls[1];
    const sent = JSON.parse(post?.body ?? '{}') as Record<string, unknown>;
    expect(sent.city).toBe('Paris');
    expect(sent.label ?? null).toBeNull();
    expect(post?.headers['content-type']).toBe('application/json');
    expect(post?.headers['x-client-id']).toBe(CLIENT_ID);
    expect(screen.queryByText('No favourites yet.')).not.toBeInTheDocument();
  });

  it('AC-22: a label over 50 characters is rejected inline without a request', async () => {
    const mock = mockFetch().on('GET', LIST, () => json(200, { items: [] }));
    const { user } = renderApp({ hash: '#/favourites' });
    await screen.findByText('No favourites yet.');
    await user.type(screen.getByLabelText('City'), 'London');
    await user.click(screen.getByLabelText('Label (optional)'));
    await user.paste(` ${'x'.repeat(51)} `);
    await user.click(screen.getByRole('button', { name: 'Add favourite' }));
    const error = screen.getByText('Label must be at most 50 characters.');
    expect(error).toHaveAttribute('role', 'alert');
    expect(screen.getByLabelText('Label (optional)')).toHaveAttribute('aria-describedby', error.id);
    expect(mock.calls.map((c) => c.method)).toEqual(['GET']);
  });

  it.each([
    ['', 'Enter a city name.'],
    ['c'.repeat(101), 'City name must be at most 100 characters.'],
  ])('AC-22: the add form applies the AC-10 city rules (%#)', async (city, message) => {
    const mock = mockFetch().on('GET', LIST, () => json(200, { items: [] }));
    const { user } = renderApp({ hash: '#/favourites' });
    await screen.findByText('No favourites yet.');
    if (city) {
      await user.click(screen.getByLabelText('City'));
      await user.paste(city);
    }
    await user.click(screen.getByRole('button', { name: 'Add favourite' }));
    const error = screen.getByText(message);
    expect(screen.getByLabelText('City')).toHaveAttribute('aria-describedby', error.id);
    expect(mock.calls.map((c) => c.method)).toEqual(['GET']);
  });

  it.each([
    [409, 'FAVOURITES_LIMIT_REACHED', "You've reached the maximum number of favourites. Remove one to add another."],
    [404, 'LOCATION_NOT_FOUND', 'No weather data found for "Atlantis".'],
    [503, 'UPSTREAM_UNAVAILABLE', 'The weather service is temporarily unavailable. Please try again.'],
  ])('AC-24: %i %s keeps the list and the input and shows the AC text', async (status, code, message) => {
    mockFetch()
      .on('GET', LIST, () => json(200, { items: [FAV_LONDON] }))
      .on('POST', LIST, () => json(status, problem(status, code)));
    const { user } = renderApp({ hash: '#/favourites' });
    await waitFor(() => expect(items()).toHaveLength(1));
    await user.type(screen.getByLabelText('City'), 'Atlantis');
    await user.type(screen.getByLabelText('Label (optional)'), 'Lost');
    await user.click(screen.getByRole('button', { name: 'Add favourite' }));
    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(items()).toHaveLength(1);
    expect(screen.getByLabelText('City')).toHaveValue('Atlantis');
    expect(screen.getByLabelText('Label (optional)')).toHaveValue('Lost');
  });

  it('AC-24: 400 VALIDATION_FAILED puts city/label errors on their inputs and lists the others', async () => {
    mockFetch()
      .on('GET', LIST, () => json(200, { items: [] }))
      .on('POST', LIST, () =>
        json(
          400,
          problem(400, 'VALIDATION_FAILED', {
            errors: [
              { field: 'city', message: 'city rejected by server' },
              { field: 'label', message: 'label rejected by server' },
              { field: 'X-Client-Id', message: 'must match pattern' },
            ],
          }),
        ),
      );
    const { user } = renderApp({ hash: '#/favourites' });
    await screen.findByText('No favourites yet.');
    await user.type(screen.getByLabelText('City'), 'London');
    await user.type(screen.getByLabelText('Label (optional)'), 'Home{Enter}');
    const cityError = await screen.findByText('city rejected by server');
    const labelError = screen.getByText('label rejected by server');
    expect(screen.getByLabelText('City')).toHaveAttribute('aria-describedby', cityError.id);
    expect(screen.getByLabelText('Label (optional)')).toHaveAttribute('aria-describedby', labelError.id);
    expect(screen.getByText('X-Client-Id: must match pattern')).toBeInTheDocument();
    expect(screen.getByLabelText('City')).toHaveValue('London');
    expect(screen.getByText('No favourites yet.')).toBeInTheDocument();
  });

  it('AC-25: Save sends the new label and shows the returned label; focus returns to Rename', async () => {
    const mock = mockFetch()
      .on('GET', LIST, () => json(200, { items: [FAV_PARIS] }))
      .on('PATCH', `${LIST}/${FAV_PARIS.id}`, () =>
        json(200, { ...FAV_PARIS, label: 'Work', updatedAt: '2026-11-16T10:00:00Z' }),
      );
    const { user } = renderApp({ hash: '#/favourites' });
    await waitFor(() => expect(items()).toHaveLength(1));
    await user.click(screen.getByRole('button', { name: 'Rename' }));
    expect(screen.getByLabelText('Label for Paris')).toHaveFocus();
    await user.type(screen.getByLabelText('Label for Paris'), 'Work');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(lines(item(0))).toEqual(['Work', 'Paris', 'Paris, FR']));
    expect(screen.queryByLabelText('Label for Paris')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Rename' })).toHaveFocus();
    const patch = mock.calls[1];
    expect(patch?.body).toBe('{"label":"Work"}');
    expect(patch?.headers['content-type']).toBe('application/json');
    expect(patch?.headers['x-client-id']).toBe(CLIENT_ID);
  });

  it('AC-25: saving an empty label sends {"label":null} and the label disappears', async () => {
    const unlabelled = favourite(FAV_LONDON.id, 'London', LONDON);
    const mock = mockFetch()
      .on('GET', LIST, () => json(200, { items: [FAV_LONDON] }))
      .on('PATCH', `${LIST}/${FAV_LONDON.id}`, () => json(200, unlabelled));
    const { user } = renderApp({ hash: '#/favourites' });
    await waitFor(() => expect(items()).toHaveLength(1));
    expect(lines(item(0))).toEqual(['Home', 'London', 'London, GB']);
    await user.click(screen.getByRole('button', { name: 'Rename' }));
    const input = screen.getByLabelText('Label for Home');
    expect(input).toHaveValue('Home');
    await user.clear(input);
    await user.type(input, '{Enter}');
    await waitFor(() => expect(lines(item(0))).toEqual(['London', 'London, GB']));
    expect(mock.calls[1]?.body).toBe('{"label":null}');
  });

  it('AC-25: Cancel closes the rename without a request; a label over 50 characters is rejected', async () => {
    const mock = mockFetch().on('GET', LIST, () => json(200, { items: [FAV_PARIS] }));
    const { user } = renderApp({ hash: '#/favourites' });
    await waitFor(() => expect(items()).toHaveLength(1));
    await user.click(screen.getByRole('button', { name: 'Rename' }));
    await user.paste('y'.repeat(51));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    const error = screen.getByText('Label must be at most 50 characters.');
    expect(screen.getByLabelText('Label for Paris')).toHaveAttribute('aria-describedby', error.id);
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByLabelText('Label for Paris')).not.toBeInTheDocument();
    expect(mock.calls.map((c) => c.method)).toEqual(['GET']);
  });

  it('AC-26: Move up sends the new order; the server order is shown and focus stays on the moved item', async () => {
    const mock = mockFetch()
      .on('GET', LIST, () => json(200, { items: [FAV_LONDON, FAV_PARIS, FAV_TOKYO] }))
      .on('PUT', `${LIST}/order`, () => json(200, { items: [FAV_TOKYO, FAV_LONDON, FAV_PARIS] }));
    const { user } = renderApp({ hash: '#/favourites' });
    await waitFor(() => expect(items()).toHaveLength(3));
    await user.click(screen.getByRole('button', { name: 'Move Tokyo up' }));
    // The UI computed [London, Tokyo, Paris], but shows what the server returned.
    await waitFor(() => expect(item(0)).toHaveTextContent('Tokyo'));
    expect(items().map((li) => within(li).getByText(/, (GB|FR|JP)$/).textContent)).toEqual([
      'Tokyo, JP',
      'London, GB',
      'Paris, FR',
    ]);
    expect(mock.calls[1]?.body).toBe(JSON.stringify({ ids: [FAV_LONDON.id, FAV_TOKYO.id, FAV_PARIS.id] }));
    expect(mock.calls[1]?.headers['x-client-id']).toBe(CLIENT_ID);
    // Tokyo is now first, so its Move up is disabled and focus goes to its Move down.
    expect(screen.getByRole('button', { name: 'Move Tokyo up' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Move Tokyo down' })).toHaveFocus();
  });

  it('AC-26: move buttons are keyboard-operable and disabled while a reorder is pending', async () => {
    const pending = deferred();
    mockFetch()
      .on('GET', LIST, () => json(200, { items: [FAV_LONDON, FAV_PARIS] }))
      .on('PUT', `${LIST}/order`, () => pending.promise);
    const { user } = renderApp({ hash: '#/favourites' });
    await waitFor(() => expect(items()).toHaveLength(2));
    screen.getByRole('button', { name: 'Move Paris up' }).focus();
    await user.keyboard('{Enter}');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Move Home down' })).toBeDisabled());
    expect(screen.getByRole('button', { name: 'Move Paris up' })).toBeDisabled();
    pending.resolve(json(200, { items: [FAV_PARIS, FAV_LONDON] }));
    await waitFor(() => expect(item(0)).toHaveTextContent('Paris'));
    expect(screen.getByRole('button', { name: 'Move Paris down' })).toBeEnabled();
  });

  it('AC-26: 400 VALIDATION_FAILED on ids shows the changed-elsewhere text and reloads the list', async () => {
    const mock = mockFetch()
      .on('GET', LIST, () => json(200, { items: [FAV_LONDON, FAV_PARIS] }), { times: 1 })
      .on('GET', LIST, () => json(200, { items: [FAV_PARIS, FAV_LONDON, FAV_TOKYO] }))
      .on('PUT', `${LIST}/order`, () =>
        json(400, problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'ids', message: 'must match the stored set' }] })),
      );
    const { user } = renderApp({ hash: '#/favourites' });
    await waitFor(() => expect(items()).toHaveLength(2));
    await user.click(screen.getByRole('button', { name: 'Move Home down' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Your favourites changed elsewhere. The list has been refreshed.',
    );
    await waitFor(() => expect(items()).toHaveLength(3));
    expect(item(0)).toHaveTextContent('Paris');
    expect(mock.calls.map((c) => c.method)).toEqual(['GET', 'PUT', 'GET']);
  });

  it('AC-27: the confirmation is keyboard-operable; Escape cancels it without a request', async () => {
    const mock = mockFetch()
      .on('GET', LIST, () => json(200, { items: [FAV_LONDON, FAV_PARIS] }))
      .on('DELETE', `${LIST}/${FAV_PARIS.id}`, () => noContent());
    const { user } = renderApp({ hash: '#/favourites' });
    await waitFor(() => expect(items()).toHaveLength(2));
    within(item(1)).getByRole('button', { name: 'Delete' }).focus();
    await user.keyboard('{Enter}');
    expect(within(item(1)).getByText('Delete Paris?')).toBeInTheDocument();
    expect(within(item(1)).getByRole('button', { name: 'Cancel' })).toHaveFocus();
    await user.keyboard('{Escape}');
    expect(within(item(1)).queryByText('Delete Paris?')).not.toBeInTheDocument();
    expect(within(item(1)).getByRole('button', { name: 'Delete' })).toHaveFocus();
    expect(mock.calls.map((c) => c.method)).toEqual(['GET']);

    // Confirm with the keyboard: Space opens, Shift+Tab to "Yes, delete", Enter confirms.
    await user.keyboard(' ');
    await user.tab({ shift: true });
    expect(within(item(1)).getByRole('button', { name: 'Yes, delete' })).toHaveFocus();
    await user.keyboard('{Enter}');
    await waitFor(() => expect(items()).toHaveLength(1));
    // The last item was deleted: focus goes to the previous item's Delete.
    expect(within(item(0)).getByRole('button', { name: 'Delete' })).toHaveFocus();
    expect(mock.calls.map((c) => `${c.method} ${c.headers['x-client-id'] ?? ''}`)).toEqual([
      `GET ${CLIENT_ID}`,
      `DELETE ${CLIENT_ID}`,
    ]);
  });

  it('AC-27: 404 on delete removes the item and reloads the list without an error', async () => {
    const mock = mockFetch()
      .on('GET', LIST, () => json(200, { items: [FAV_LONDON, FAV_PARIS] }), { times: 1 })
      .on('GET', LIST, () => json(200, { items: [FAV_PARIS] }))
      .on('DELETE', `${LIST}/${FAV_LONDON.id}`, () => json(404, problem(404, 'NOT_FOUND')));
    const { user } = renderApp({ hash: '#/favourites' });
    await waitFor(() => expect(items()).toHaveLength(2));
    await user.click(within(item(0)).getByRole('button', { name: 'Delete' }));
    await user.click(screen.getByRole('button', { name: 'Yes, delete' }));
    await waitFor(() => expect(mock.calls.map((c) => c.method)).toEqual(['GET', 'DELETE', 'GET']));
    await waitFor(() => expect(items()).toHaveLength(1));
    expect(item(0)).toHaveTextContent('Paris');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('AC-27, AC-18: a 500 on delete keeps the item and shows the generic error with its reference', async () => {
    mockFetch()
      .on('GET', LIST, () => json(200, { items: [FAV_PARIS] }))
      .on('DELETE', `${LIST}/${FAV_PARIS.id}`, () =>
        json(500, problem(500, 'INTERNAL_ERROR', { requestId: 'del-500' })),
      );
    const { user } = renderApp({ hash: '#/favourites' });
    await waitFor(() => expect(items()).toHaveLength(1));
    await user.click(screen.getByRole('button', { name: 'Delete' }));
    await user.click(screen.getByRole('button', { name: 'Yes, delete' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong. Please try again.');
    expect(alert).toHaveTextContent('Reference: del-500');
    expect(items()).toHaveLength(1);
  });

  it('NFR security: API strings are rendered as text, never as HTML', async () => {
    const evil = favourite(FAV_PARIS.id, '<img src=x onerror=alert(1)>', { ...LONDON, name: '<b>Lon</b>' }, '<i>lbl</i>');
    mockFetch().on('GET', LIST, () => json(200, { items: [evil] }));
    renderApp({ hash: '#/favourites' });
    await waitFor(() => expect(items()).toHaveLength(1));
    expect(lines(item(0))).toEqual(['<i>lbl</i>', '<img src=x onerror=alert(1)>', '<b>Lon</b>, GB']);
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
    expect(screen.queryByText('lbl')).not.toBeInTheDocument();
  });
});

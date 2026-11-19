import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { json, mockFetch, noContent } from '../test/fetchMock';
import { API, CLIENT_ID, FAV_LONDON, FAV_PARIS, FAV_TOKYO, problem } from '../test/fixtures';
import { renderApp } from '../test/renderApp';

const LIST = `${API}/favourites`;

function items() {
  return within(screen.getByRole('list', { name: 'Your favourites' })).getAllByRole('listitem');
}

describe('FavouritesView', () => {
  it('AC-21: lists favourites in response order with the client id header', async () => {
    const mock = mockFetch().on('GET', LIST, () => json(200, { items: [FAV_LONDON, FAV_PARIS] }));
    renderApp({ hash: '#/favourites' });
    await waitFor(() => expect(items()).toHaveLength(2));
    expect(items()[0]).toHaveTextContent('Home');
    expect(items()[0]).toHaveTextContent('London, GB');
    expect(items()[1]).toHaveTextContent('Paris, FR');
    expect(mock.calls[0]?.headers['x-client-id']).toBe(CLIENT_ID);
  });

  it('AC-21: an empty list shows the empty message and the add form', async () => {
    mockFetch().on('GET', LIST, () => json(200, { items: [] }));
    renderApp({ hash: '#/favourites' });
    expect(await screen.findByText('No favourites yet.')).toBeInTheDocument();
    expect(screen.getByLabelText('City')).toBeInTheDocument();
    expect(screen.getByLabelText('Label (optional)')).toBeInTheDocument();
  });

  it('AC-22: adding appends the created favourite and clears the form', async () => {
    const mock = mockFetch()
      .on('GET', LIST, () => json(200, { items: [FAV_PARIS] }))
      .on('POST', LIST, () => json(201, FAV_LONDON));
    const { user } = renderApp({ hash: '#/favourites' });
    await waitFor(() => expect(items()).toHaveLength(1));
    await user.type(screen.getByLabelText('City'), 'London');
    await user.type(screen.getByLabelText('Label (optional)'), 'Home');
    await user.click(screen.getByRole('button', { name: 'Add favourite' }));
    await waitFor(() => expect(items()).toHaveLength(2));
    expect(mock.calls[1]?.body).toBe('{"city":"London","label":"Home"}');
    expect(screen.getByLabelText('City')).toHaveValue('');
    expect(screen.getByLabelText('Label (optional)')).toHaveValue('');
  });

  it('AC-24: a duplicate keeps the list and the input', async () => {
    mockFetch()
      .on('GET', LIST, () => json(200, { items: [FAV_LONDON] }))
      .on('POST', LIST, () => json(409, problem(409, 'FAVOURITE_ALREADY_EXISTS')));
    const { user } = renderApp({ hash: '#/favourites' });
    await waitFor(() => expect(items()).toHaveLength(1));
    await user.type(screen.getByLabelText('City'), 'London{Enter}');
    expect(await screen.findByText('London is already in your favourites.')).toBeInTheDocument();
    expect(items()).toHaveLength(1);
    expect(screen.getByLabelText('City')).toHaveValue('London');
  });

  it('AC-26: Move down sends the new order and shows the server order', async () => {
    const mock = mockFetch()
      .on('GET', LIST, () => json(200, { items: [FAV_LONDON, FAV_PARIS, FAV_TOKYO] }))
      .on('PUT', `${LIST}/order`, () => json(200, { items: [FAV_PARIS, FAV_LONDON, FAV_TOKYO] }));
    const { user } = renderApp({ hash: '#/favourites' });
    await waitFor(() => expect(items()).toHaveLength(3));
    expect(screen.getByRole('button', { name: 'Move Home up' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Move Tokyo down' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Move Home down' }));
    await waitFor(() => expect(items()[0]).toHaveTextContent('Paris'));
    expect(mock.calls[1]?.body).toBe(JSON.stringify({ ids: [FAV_PARIS.id, FAV_LONDON.id, FAV_TOKYO.id] }));
  });

  it('AC-25: rename sends the label; 404 shows the message and reloads', async () => {
    const mock = mockFetch()
      .on('GET', LIST, () => json(200, { items: [FAV_PARIS] }), { times: 1 })
      .on('GET', LIST, () => json(200, { items: [] }))
      .on('PATCH', `${LIST}/${FAV_PARIS.id}`, () => json(404, problem(404, 'NOT_FOUND')));
    const { user } = renderApp({ hash: '#/favourites' });
    await waitFor(() => expect(items()).toHaveLength(1));
    await user.click(screen.getByRole('button', { name: 'Rename' }));
    await user.type(screen.getByLabelText('Label for Paris'), 'Work{Enter}');
    expect(await screen.findByText('This favourite no longer exists.')).toBeInTheDocument();
    expect(await screen.findByText('No favourites yet.')).toBeInTheDocument();
    expect(mock.calls.map((c) => `${c.method} ${c.body ?? ''}`)).toEqual(['GET ', 'PATCH {"label":"Work"}', 'GET ']);
  });

  it('AC-25: Escape cancels a rename without a request and returns focus', async () => {
    mockFetch().on('GET', LIST, () => json(200, { items: [FAV_PARIS] }));
    const { user } = renderApp({ hash: '#/favourites' });
    await waitFor(() => expect(items()).toHaveLength(1));
    await user.click(screen.getByRole('button', { name: 'Rename' }));
    await user.keyboard('{Escape}');
    expect(screen.queryByLabelText('Label for Paris')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Rename' })).toHaveFocus();
  });

  it('AC-27: delete after confirm removes the item and focuses the next Delete', async () => {
    const mock = mockFetch()
      .on('GET', LIST, () => json(200, { items: [FAV_LONDON, FAV_PARIS] }))
      .on('DELETE', `${LIST}/${FAV_LONDON.id}`, () => noContent());
    const { user } = renderApp({ hash: '#/favourites' });
    await waitFor(() => expect(items()).toHaveLength(2));
    await user.click(within(items()[0] as HTMLElement).getByRole('button', { name: 'Delete' }));
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
    await user.click(screen.getByRole('button', { name: 'Yes, delete' }));
    await waitFor(() => expect(items()).toHaveLength(1));
    expect(within(items()[0] as HTMLElement).getByRole('button', { name: 'Delete' })).toHaveFocus();
    expect(mock.calls.map((c) => c.method)).toEqual(['GET', 'DELETE']);
  });

  it('AC-27: cancelling sends nothing; deleting the last item focuses the City input', async () => {
    mockFetch()
      .on('GET', LIST, () => json(200, { items: [FAV_PARIS] }))
      .on('DELETE', `${LIST}/${FAV_PARIS.id}`, () => json(404, problem(404, 'NOT_FOUND')), { times: 1 });
    const { user } = renderApp({ hash: '#/favourites' });
    await waitFor(() => expect(items()).toHaveLength(1));
    await user.click(screen.getByRole('button', { name: 'Delete' }));
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByRole('button', { name: 'Delete' })).toHaveFocus();
    await user.click(screen.getByRole('button', { name: 'Delete' }));
    await user.click(screen.getByRole('button', { name: 'Yes, delete' }));
    await waitFor(() => expect(screen.getByLabelText('City')).toHaveFocus());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

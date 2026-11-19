import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError } from '../api/client';
import { describeApiError, type ErrorView } from '../api/errors';
import { REORDER_CONFLICT_MESSAGE } from '../api/messages';
import type { Favourite, FavouriteList } from '../api/types';
import { ErrorMessage } from '../components/ErrorMessage';
import { Loading } from '../components/Loading';
import { ViewHeading } from '../components/ViewHeading';
import { useRequestSlot } from '../hooks/useRequestSlot';
import { useApp } from '../state/AppContext';
import { validateCity, validateLabel } from '../validation';
import { FavouriteItem, type FocusTarget } from './FavouriteItem';

const ADD_FIELDS = ['city', 'label'] as const;

function textError(message: string): ErrorView {
  return { message, inlineFieldErrors: {}, otherFieldErrors: [], retryable: false };
}

function problemOf(error: unknown) {
  return error instanceof ApiError && error.failure.kind === 'http' ? error.failure.problem : undefined;
}

export function FavouritesView({ focusHeading }: { focusHeading: boolean }) {
  const { api } = useApp();
  const list = useRequestSlot<FavouriteList>();
  // Replaced only by server responses (no optimistic updates, requirements A-8).
  const [items, setItems] = useState<Favourite[] | null>(null);
  const [alert, setAlert] = useState<ErrorView | null>(null);
  const [reorderPending, setReorderPending] = useState(false);

  const [cityInput, setCityInput] = useState('');
  const [labelInput, setLabelInput] = useState('');
  const [cityError, setCityError] = useState<string | null>(null);
  const [labelError, setLabelError] = useState<string | null>(null);
  const [formError, setFormError] = useState<ErrorView | null>(null);
  const [adding, setAdding] = useState(false);

  const cityInputRef = useRef<HTMLInputElement>(null);
  const controls = useRef(new Map<string, HTMLButtonElement>());
  const [focusRequest, setFocusRequest] = useState<{ id: string; target: FocusTarget } | 'add-form' | null>(null);

  const registerControl = useCallback((id: string, target: FocusTarget, element: HTMLButtonElement | null) => {
    const key = `${id}:${target}`;
    if (element) controls.current.set(key, element);
    else controls.current.delete(key);
  }, []);

  const load = useCallback(() => list.run((signal) => api.listFavourites(signal)), [api, list.run]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (list.state.status === 'success') setItems(list.state.data.items);
  }, [list.state]);

  // Move focus once the list has re-rendered (AC-27 delete, and after a reorder).
  useEffect(() => {
    if (!focusRequest) return;
    if (focusRequest === 'add-form') {
      cityInputRef.current?.focus();
    } else {
      const { id, target } = focusRequest;
      const preferred = controls.current.get(`${id}:${target}`);
      const fallback = controls.current.get(`${id}:${target === 'up' ? 'down' : target === 'down' ? 'up' : 'delete'}`);
      if (preferred && !preferred.disabled) preferred.focus();
      else if (fallback && !fallback.disabled) fallback.focus();
      else controls.current.get(`${id}:delete`)?.focus();
    }
    setFocusRequest(null);
  }, [focusRequest, items, reorderPending]);

  const onAdd = async (event: FormEvent) => {
    event.preventDefault();
    setFormError(null);
    const city = validateCity(cityInput);
    const label = validateLabel(labelInput);
    setCityError(city.ok ? null : city.message);
    setLabelError(label.ok ? null : label.message);
    if (!city.ok || !label.ok) return;
    setAdding(true);
    try {
      const created = await api.createFavourite(
        label.value === null ? { city: city.value } : { city: city.value, label: label.value },
      );
      setItems((current) => [...(current ?? []), created]);
      setCityInput('');
      setLabelInput('');
    } catch (error) {
      const view = describeApiError(error, { city: city.value, knownFields: ADD_FIELDS });
      setCityError(view.inlineFieldErrors.city ?? null);
      setLabelError(view.inlineFieldErrors.label ?? null);
      setFormError(view);
    } finally {
      setAdding(false);
    }
  };

  const onRename = async (id: string, label: string | null): Promise<boolean> => {
    setAlert(null);
    try {
      const updated = await api.updateFavouriteLabel(id, label);
      setItems((current) => (current ?? []).map((f) => (f.id === id ? updated : f)));
      return true;
    } catch (error) {
      const view = describeApiError(error, { knownFields: [] });
      setAlert(view);
      if (view.code === 'NOT_FOUND') void load();
      return false;
    }
  };

  const onMove = async (id: string, direction: -1 | 1) => {
    if (!items) return;
    const index = items.findIndex((f) => f.id === id);
    const target = index + direction;
    if (index < 0 || target < 0 || target >= items.length) return;
    const ids = items.map((f) => f.id);
    [ids[index], ids[target]] = [ids[target] as string, ids[index] as string];
    setAlert(null);
    setReorderPending(true);
    try {
      const result = await api.reorderFavourites(ids);
      setItems(result.items);
      setFocusRequest({ id, target: direction === -1 ? 'up' : 'down' });
    } catch (error) {
      const problem = problemOf(error);
      if (problem?.code === 'VALIDATION_FAILED' && problem.errors?.some((e) => e.field === 'ids')) {
        setAlert(textError(REORDER_CONFLICT_MESSAGE));
        void load();
      } else {
        setAlert(describeApiError(error, { knownFields: [] }));
        setFocusRequest({ id, target: direction === -1 ? 'up' : 'down' });
      }
    } finally {
      setReorderPending(false);
    }
  };

  const removeAndFocus = (id: string) => {
    const current = items ?? [];
    const index = current.findIndex((f) => f.id === id);
    const remaining = current.filter((f) => f.id !== id);
    const next = current[index + 1] ?? current[index - 1];
    setItems(remaining);
    setFocusRequest(next ? { id: next.id, target: 'delete' } : 'add-form');
  };

  const onDelete = async (id: string) => {
    setAlert(null);
    try {
      await api.deleteFavourite(id);
      removeAndFocus(id);
    } catch (error) {
      if (problemOf(error)?.code === 'NOT_FOUND') {
        // Already gone: the user's intent is satisfied (AC-27).
        removeAndFocus(id);
        void load();
      } else {
        setAlert(describeApiError(error, { knownFields: [] }));
      }
    }
  };

  const listError = list.state.status === 'error' ? describeApiError(list.state.error, { knownFields: [] }) : null;

  return (
    <>
      <ViewHeading focusOnMount={focusHeading}>Favourites</ViewHeading>

      <section aria-labelledby="add-heading">
        <h2 id="add-heading">Add a favourite</h2>
        <form aria-label="Add a favourite" onSubmit={(e) => void onAdd(e)} noValidate>
          <div className="field">
            <label htmlFor="fav-city">City</label>
            <input
              id="fav-city"
              ref={cityInputRef}
              type="text"
              value={cityInput}
              onChange={(e) => setCityInput(e.target.value)}
              aria-invalid={cityError ? true : undefined}
              aria-describedby={cityError ? 'fav-city-error' : undefined}
              autoComplete="off"
            />
          </div>
          <div className="field">
            <label htmlFor="fav-label">Label (optional)</label>
            <input
              id="fav-label"
              type="text"
              value={labelInput}
              onChange={(e) => setLabelInput(e.target.value)}
              aria-invalid={labelError ? true : undefined}
              aria-describedby={labelError ? 'fav-label-error' : undefined}
              autoComplete="off"
            />
          </div>
          <button type="submit" disabled={adding}>
            Add favourite
          </button>
        </form>
        {cityError && (
          <p id="fav-city-error" className="field-error" role="alert">
            {cityError}
          </p>
        )}
        {labelError && (
          <p id="fav-label-error" className="field-error" role="alert">
            {labelError}
          </p>
        )}
        {formError && <ErrorMessage error={formError} />}
      </section>

      <section aria-labelledby="list-heading">
        <h2 id="list-heading">Your favourites</h2>
        {list.state.status === 'loading' && <Loading text="Loading favourites…" />}
        {listError && <ErrorMessage error={listError} onRetry={() => void load()} />}
        {alert && <ErrorMessage error={alert} />}
        {items && items.length === 0 && <p>No favourites yet.</p>}
        {items && items.length > 0 && (
          <ul className="favourites-list" aria-labelledby="list-heading">
            {items.map((favourite, index) => (
              <FavouriteItem
                key={favourite.id}
                favourite={favourite}
                isFirst={index === 0}
                isLast={index === items.length - 1}
                moveDisabled={reorderPending}
                onRename={onRename}
                onMove={(id, direction) => void onMove(id, direction)}
                onDelete={onDelete}
                registerControl={registerControl}
              />
            ))}
          </ul>
        )}
      </section>
    </>
  );
}

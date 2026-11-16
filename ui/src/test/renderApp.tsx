import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App } from '../App';
import { createApiClient } from '../api/client';
import type { Units } from '../api/types';
import { BASE_URL, CLIENT_ID } from './fixtures';

export interface RenderAppOptions {
  /** e.g. '#/favourites'; set before rendering, which simulates a reload on that view. */
  hash?: string;
  baseUrl?: string;
  clientId?: string;
  units?: Units;
  storagePersistent?: boolean;
  getStorage?: () => Storage;
}

/** Renders the whole app with a fixed base URL and client id (no import.meta.env). */
export function renderApp(options: RenderAppOptions = {}) {
  const baseUrl = options.baseUrl ?? BASE_URL;
  if (options.hash !== undefined) window.history.replaceState(null, '', options.hash || window.location.pathname);
  const api = createApiClient({ baseUrl, clientId: options.clientId ?? CLIENT_ID });
  const user = userEvent.setup();
  const result = render(
    <App
      api={api}
      baseUrl={baseUrl}
      initialUnits={options.units ?? 'metric'}
      storagePersistent={options.storagePersistent ?? true}
      getStorage={options.getStorage ?? (() => window.localStorage)}
    />,
  );
  return { ...result, user };
}

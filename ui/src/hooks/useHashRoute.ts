import { useEffect, useState } from 'react';

export type Route = 'search' | 'favourites' | 'dashboard';

export const ROUTE_HASH: Record<Route, string> = {
  search: '#/search',
  favourites: '#/favourites',
  dashboard: '#/dashboard',
};

/** `#/favourites` -> favourites; empty or unknown -> search (the default view). */
export function parseHash(hash: string): Route {
  if (hash === ROUTE_HASH.favourites) return 'favourites';
  if (hash === ROUTE_HASH.dashboard) return 'dashboard';
  return 'search';
}

/**
 * The current view from location.hash. `navigated` becomes true after the first hashchange,
 * so views move focus to their heading only on in-app navigation, not on the initial load.
 */
export function useHashRoute(): { route: Route; navigated: boolean } {
  const [state, setState] = useState(() => ({ route: parseHash(window.location.hash), navigated: false }));

  useEffect(() => {
    const onChange = () => setState({ route: parseHash(window.location.hash), navigated: true });
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);

  return state;
}

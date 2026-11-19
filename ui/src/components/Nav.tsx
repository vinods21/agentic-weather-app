import { ROUTE_HASH, type Route } from '../hooks/useHashRoute';

const ITEMS: ReadonlyArray<{ route: Route; label: string }> = [
  { route: 'search', label: 'Search' },
  { route: 'favourites', label: 'Favourites' },
  { route: 'dashboard', label: 'Dashboard' },
];

export function Nav({ current }: { current: Route }) {
  return (
    <nav aria-label="Main" className="nav">
      <ul>
        {ITEMS.map(({ route, label }) => (
          <li key={route}>
            <a href={ROUTE_HASH[route]} aria-current={route === current ? 'page' : undefined}>
              {label}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
}

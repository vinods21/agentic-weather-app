import type { ApiClient } from './api/client';
import { STORAGE_UNAVAILABLE_NOTICE } from './api/messages';
import type { Units } from './api/types';
import { Footer } from './components/Footer';
import { Nav } from './components/Nav';
import { useHashRoute } from './hooks/useHashRoute';
import { AppProvider, useApp } from './state/AppContext';
import { DashboardView } from './views/DashboardView';
import { FavouritesView } from './views/FavouritesView';
import { SearchView } from './views/SearchView';

export interface AppProps {
  api: ApiClient;
  baseUrl: string;
  initialUnits: Units;
  storagePersistent: boolean;
  getStorage: () => Storage;
}

export function App(props: AppProps) {
  return (
    <AppProvider {...props}>
      <Shell />
    </AppProvider>
  );
}

function UnitsControl() {
  const { units, setUnits } = useApp();
  return (
    <div className="field-inline">
      <label htmlFor="units-select">Units</label>
      <select id="units-select" value={units} onChange={(e) => setUnits(e.target.value as Units)}>
        <option value="metric">Metric (°C, m/s)</option>
        <option value="imperial">Imperial (°F, mph)</option>
      </select>
    </div>
  );
}

function Shell() {
  const { storagePersistent } = useApp();
  const { route, navigated } = useHashRoute();
  return (
    <div className="app">
      <header className="app-header">
        <p className="app-title">Weather</p>
        <UnitsControl />
        {!storagePersistent && (
          <p role="note" className="notice">
            {STORAGE_UNAVAILABLE_NOTICE}
          </p>
        )}
      </header>
      <Nav current={route} />
      <main>
        {route === 'search' && <SearchView key="search" focusHeading={navigated} />}
        {route === 'favourites' && <FavouritesView key="favourites" focusHeading={navigated} />}
        {route === 'dashboard' && <DashboardView key="dashboard" focusHeading={navigated} />}
      </main>
      <Footer />
    </div>
  );
}

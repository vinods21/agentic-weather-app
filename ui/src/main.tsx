import { createRoot } from 'react-dom/client';
import { App } from './App';
import { createApiClient } from './api/client';
import { resolveApiBaseUrl } from './config';
import { loadClientId } from './storage/clientId';
import { loadUnits } from './storage/units';
import './styles.css';

// No <StrictMode>: the dev server must make the same requests as the build (ADR 0017).
const getStorage = () => window.localStorage;
const baseUrl = resolveApiBaseUrl(import.meta.env.VITE_API_BASE_URL);
const clientId = loadClientId(getStorage);
const api = createApiClient({ baseUrl, clientId: clientId.id });

const root = document.getElementById('root');
if (root) {
  createRoot(root).render(
    <App
      api={api}
      baseUrl={baseUrl}
      initialUnits={loadUnits(getStorage)}
      storagePersistent={clientId.persistent}
      getStorage={getStorage}
    />,
  );
}

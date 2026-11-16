import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import type { ApiClient } from '../api/client';
import type { Units } from '../api/types';
import { saveUnits } from '../storage/units';

export interface AppContextValue {
  api: ApiClient;
  /** Resolved backend base URL (without /api/v1), for the network message. */
  baseUrl: string;
  units: Units;
  setUnits: (units: Units) => void;
  /** false when localStorage is unavailable (the client id lives only for the tab). */
  storagePersistent: boolean;
}

const AppContext = createContext<AppContextValue | null>(null);

export interface AppProviderProps {
  api: ApiClient;
  baseUrl: string;
  initialUnits: Units;
  storagePersistent: boolean;
  getStorage: () => Storage;
  children: ReactNode;
}

export function AppProvider({ api, baseUrl, initialUnits, storagePersistent, getStorage, children }: AppProviderProps) {
  const [units, setUnitsState] = useState<Units>(initialUnits);
  const setUnits = useCallback(
    (next: Units) => {
      setUnitsState(next);
      saveUnits(getStorage, next);
    },
    [getStorage],
  );
  const value = useMemo(
    () => ({ api, baseUrl, units, setUnits, storagePersistent }),
    [api, baseUrl, units, setUnits, storagePersistent],
  );
  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

export function useApp(): AppContextValue {
  const value = useContext(AppContext);
  if (!value) throw new Error('useApp must be used inside AppProvider');
  return value;
}

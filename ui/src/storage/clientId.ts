// The opaque X-Client-Id (ADR 0009, ADR 0017 section 6). It is a partition key for
// favourites, not a credential, and is never presented to the user.

export const CLIENT_ID_KEY = 'weather.clientId';
export const CLIENT_ID_PATTERN = /^[A-Za-z0-9._-]{1,64}$/;

type CryptoLike = Pick<Crypto, 'getRandomValues'> & { randomUUID?: () => string };

/** A random UUID v4: crypto.randomUUID when available, else built from getRandomValues. */
export function randomUuid(cryptoImpl: CryptoLike = globalThis.crypto): string {
  if (typeof cryptoImpl.randomUUID === 'function') return cryptoImpl.randomUUID();
  const bytes = cryptoImpl.getRandomValues(new Uint8Array(16));
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40; // version 4
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80; // RFC 4122 variant
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export interface ClientIdResult {
  id: string;
  /** false when storage is unavailable: the id lives only as long as the tab. */
  persistent: boolean;
}

/**
 * Reads the stored client id, or generates and stores a new one when it is missing or invalid.
 * Any storage exception falls back to an in-memory id.
 */
export function loadClientId(getStorage: () => Storage, generate: () => string = randomUuid): ClientIdResult {
  try {
    const storage = getStorage();
    const stored = storage.getItem(CLIENT_ID_KEY);
    if (stored !== null && CLIENT_ID_PATTERN.test(stored)) return { id: stored, persistent: true };
    const id = generate();
    storage.setItem(CLIENT_ID_KEY, id);
    return { id, persistent: true };
  } catch {
    return { id: generate(), persistent: false };
  }
}

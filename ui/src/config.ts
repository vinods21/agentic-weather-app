export const DEFAULT_API_BASE_URL = 'http://localhost:8080';

/**
 * Resolves the backend base URL from VITE_API_BASE_URL (ADR 0017 section 4).
 * Unset or blank gives the default. Otherwise the value is trimmed, trailing slashes are
 * removed, and it must be an absolute http(s) URL, else this throws.
 */
export function resolveApiBaseUrl(raw: string | undefined): string {
  const trimmed = (raw ?? '').trim();
  if (trimmed === '') return DEFAULT_API_BASE_URL;
  const base = trimmed.replace(/\/+$/, '');
  let parsed: URL;
  try {
    parsed = new URL(base);
  } catch {
    throw new Error(`VITE_API_BASE_URL must be an absolute http(s) URL, got "${trimmed}".`);
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error(`VITE_API_BASE_URL must be an absolute http(s) URL, got "${trimmed}".`);
  }
  return base;
}

// Tester: static checks over the production sources (not tests) for the NFRs the design asks
// reviewers to grep for: no HTML injection, no polling, fetch only in the API client, no
// client id wording as "account"/"login", only the two documented localStorage keys.
import { describe, expect, it } from 'vitest';

const all = import.meta.glob<string>('./**/*.{ts,tsx}', { query: '?raw', import: 'default', eager: true });
const sources = Object.entries(all).filter(([path]) => !/\.test\.tsx?$/.test(path) && !path.startsWith('./test/'));

function filesMatching(re: RegExp) {
  return sources.filter(([, text]) => re.test(text)).map(([path]) => path);
}

describe('production source checks (tester)', () => {
  it('finds the production sources', () => {
    const paths = sources.map(([p]) => p);
    expect(paths).toContain('./api/client.ts');
    expect(paths).toContain('./views/SearchView.tsx');
    expect(paths).not.toContain('./test/fetchMock.ts');
  });

  it('NFR security: dangerouslySetInnerHTML and innerHTML are never used', () => {
    expect(filesMatching(/dangerouslySetInnerHTML|\.innerHTML\b|outerHTML/)).toEqual([]);
  });

  it('AC-31 / NFR request budget: no setInterval anywhere, and setTimeout only for the client timeout', () => {
    expect(filesMatching(/setInterval/)).toEqual([]);
    expect(filesMatching(/setTimeout/)).toEqual(['./api/client.ts']);
  });

  it('AC-4 / design: only the API client calls fetch, and it omits credentials', () => {
    expect(filesMatching(/\bfetch\s*\(|globalThis\.fetch|XMLHttpRequest/)).toEqual(['./api/client.ts']);
    const client = all['./api/client.ts'] ?? '';
    expect(client).toMatch(/credentials:\s*'omit'/);
    expect(client).not.toMatch(/'X-Request-Id'\s*\]?\s*=/);
  });

  it('NFR security (ADR 0009): UI text never calls the client id an account or login', () => {
    expect(filesMatching(/\baccount\b|\blog ?in\b|\bsign ?in\b/i)).toEqual([]);
  });

  it('NFR data retention: only weather.clientId and weather.units are used as storage keys', () => {
    const keys = new Set<string>();
    for (const [, text] of sources) for (const m of text.matchAll(/'(weather\.[A-Za-z]+)'/g)) keys.add(m[1] as string);
    expect([...keys].sort()).toEqual(['weather.clientId', 'weather.units']);
  });
});

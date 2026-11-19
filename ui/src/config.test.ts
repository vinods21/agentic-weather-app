import { describe, expect, it } from 'vitest';
import { resolveApiBaseUrl } from './config';

describe('resolveApiBaseUrl', () => {
  it('AC-3: unset or blank gives http://localhost:8080', () => {
    expect(resolveApiBaseUrl(undefined)).toBe('http://localhost:8080');
    expect(resolveApiBaseUrl('')).toBe('http://localhost:8080');
    expect(resolveApiBaseUrl('   ')).toBe('http://localhost:8080');
  });

  it('AC-3: keeps a value without a trailing slash', () => {
    expect(resolveApiBaseUrl('https://api.example.test')).toBe('https://api.example.test');
  });

  it('AC-3: strips one or more trailing slashes and surrounding whitespace', () => {
    expect(resolveApiBaseUrl('https://api.example.test/')).toBe('https://api.example.test');
    expect(resolveApiBaseUrl(' https://api.example.test/// ')).toBe('https://api.example.test');
    expect(resolveApiBaseUrl('https://example.test/weather/')).toBe('https://example.test/weather');
  });

  it('AC-3: rejects values that are not absolute http(s) URLs', () => {
    expect(() => resolveApiBaseUrl('api.example.test')).toThrow(/absolute http\(s\) URL/);
    expect(() => resolveApiBaseUrl('/api')).toThrow(/absolute http\(s\) URL/);
    expect(() => resolveApiBaseUrl('ftp://example.test')).toThrow(/absolute http\(s\) URL/);
  });
});

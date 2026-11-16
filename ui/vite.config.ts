import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// ADR 0017: static build with a relative base, fixed port 5173 (CORS allow-list names that exact origin).
// ADR 0018: Vitest + jsdom, offline, TZ away from UTC.
export default defineConfig({
  plugins: [react()],
  base: './',
  server: { host: 'localhost', port: 5173, strictPort: true },
  preview: { host: 'localhost', port: 5173, strictPort: true },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    env: { TZ: 'America/Los_Angeles' },
    restoreMocks: true,
    unstubGlobals: true,
  },
});

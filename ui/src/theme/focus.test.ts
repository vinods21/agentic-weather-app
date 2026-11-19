import { describe, expect, it } from 'vitest';
import css from '../styles.css?raw';

describe('visible focus', () => {
  it('AC-34: styles.css has a :focus-visible rule with an outline', () => {
    expect(css).toMatch(/:focus-visible\s*\{[^}]*outline:\s*\d+px\s+solid/);
  });

  it('AC-34: styles.css never removes outlines', () => {
    expect(css).not.toMatch(/outline\s*:\s*(none|0)\s*[;}]/);
    expect(css).not.toMatch(/outline-style\s*:\s*none/);
  });
});

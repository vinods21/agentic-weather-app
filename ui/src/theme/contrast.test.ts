import { describe, expect, it } from 'vitest';
import css from '../styles.css?raw';
import { contrastRatio, relativeLuminance } from './contrast';
import { CONTRAST_PAIRS, MIN_RATIO } from './contrastPairs';

function colourTokens(source: string): Map<string, string> {
  const tokens = new Map<string, string>();
  for (const match of source.matchAll(/(--color-[a-z0-9-]+)\s*:\s*(#[0-9a-fA-F]{3,6})\s*;/g)) {
    if (match[1] && match[2]) tokens.set(match[1], match[2]);
  }
  return tokens;
}

describe('colour contrast (WCAG)', () => {
  const tokens = colourTokens(css);

  it('computes known reference values', () => {
    expect(relativeLuminance('#ffffff')).toBeCloseTo(1, 5);
    expect(relativeLuminance('#000')).toBeCloseTo(0, 5);
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 5);
    expect(contrastRatio('#767676', '#ffffff')).toBeCloseTo(4.54, 2);
    expect(contrastRatio('#ffffff', '#767676')).toBeCloseTo(4.54, 2);
  });

  it('AC-35: styles.css declares the colour tokens', () => {
    expect(tokens.size).toBeGreaterThan(0);
  });

  for (const pair of CONTRAST_PAIRS) {
    it(`AC-35: ${pair.fg} on ${pair.bg} meets ${MIN_RATIO[pair.kind]}:1 (${pair.kind})`, () => {
      const fg = tokens.get(pair.fg);
      const bg = tokens.get(pair.bg);
      expect(fg, `${pair.fg} is not declared in styles.css`).toBeDefined();
      expect(bg, `${pair.bg} is not declared in styles.css`).toBeDefined();
      expect(contrastRatio(fg as string, bg as string)).toBeGreaterThanOrEqual(MIN_RATIO[pair.kind]);
    });
  }

  it('AC-35: every colour token appears in at least one pair', () => {
    const used = new Set(CONTRAST_PAIRS.flatMap((p) => [p.fg, p.bg]));
    const unused = [...tokens.keys()].filter((token) => !used.has(token));
    expect(unused).toEqual([]);
  });
});

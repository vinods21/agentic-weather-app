// Foreground/background token pairs used in styles.css (AC-35).
// text: normal text, needs 4.5:1. large-text and ui (component boundaries, focus ring): 3:1.

export type PairKind = 'text' | 'large-text' | 'ui';

export interface ContrastPair {
  fg: string;
  bg: string;
  kind: PairKind;
}

export const MIN_RATIO: Record<PairKind, number> = { text: 4.5, 'large-text': 3, ui: 3 };

export const CONTRAST_PAIRS: readonly ContrastPair[] = [
  { fg: '--color-text', bg: '--color-bg', kind: 'text' },
  { fg: '--color-text', bg: '--color-surface', kind: 'text' },
  { fg: '--color-text-muted', bg: '--color-bg', kind: 'text' },
  { fg: '--color-text-muted', bg: '--color-surface', kind: 'text' },
  { fg: '--color-link', bg: '--color-bg', kind: 'text' },
  { fg: '--color-link', bg: '--color-surface', kind: 'text' },
  { fg: '--color-on-primary', bg: '--color-primary', kind: 'text' },
  { fg: '--color-primary', bg: '--color-bg', kind: 'ui' },
  { fg: '--color-error', bg: '--color-bg', kind: 'text' },
  { fg: '--color-error', bg: '--color-error-bg', kind: 'text' },
  { fg: '--color-disabled-text', bg: '--color-disabled-bg', kind: 'text' },
  { fg: '--color-border', bg: '--color-bg', kind: 'ui' },
  { fg: '--color-border', bg: '--color-surface', kind: 'ui' },
  { fg: '--color-focus', bg: '--color-bg', kind: 'ui' },
  { fg: '--color-focus', bg: '--color-surface', kind: 'ui' },
];

/**
 * Dark theme color tokens of the administrator app, the same values as apps/web (security-design.md "Acessibilidade",
 * NFR11.1). Every text color keeps a contrast of at least 4.5:1 against the backgrounds it is used on (tokens.test.ts).
 * Color is never the only indicator of a state: text or an icon always goes with it.
 */
export const colors = {
  background: '#0f1115',
  surface: '#171a21',
  surfaceRaised: '#1f232c',
  text: '#e8eaed',
  textMuted: '#b3b9c4',
  accent: '#8ab4ff',
  focus: '#ffd166',
  danger: '#ff8a80',
  warning: '#ffcc66',
  unknown: '#c9b8ff',
  border: '#3a404c',
  bannerBackground: '#3d1414',
  /** 261113-trading-real-bot U6 (BR7.1): the amber of the REAL label. */
  real: '#f59e0b',
} as const;

export type ColorToken = keyof typeof colors;

/** Text tokens and the backgrounds they appear on. */
export const textPairs: ReadonlyArray<[ColorToken, ColorToken]> = [
  ['text', 'background'],
  ['text', 'surface'],
  ['text', 'surfaceRaised'],
  ['textMuted', 'background'],
  ['textMuted', 'surface'],
  ['textMuted', 'surfaceRaised'],
  ['accent', 'surface'],
  ['accent', 'background'],
  ['danger', 'surface'],
  ['warning', 'surface'],
  ['unknown', 'surface'],
  ['focus', 'surface'],
  ['text', 'bannerBackground'],
  ['warning', 'bannerBackground'],
  // U6 (U6-NFR6.4): the dark text on the amber fill of the REAL label.
  ['background', 'real'],
];

function channel(value: number): number {
  const c = value / 255;
  return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

/** WCAG 2.1 relative luminance of a #rrggbb color. */
export function luminance(hex: string): number {
  const match = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex);
  if (!match) {
    throw new Error(`Not a #rrggbb color: ${hex}`);
  }
  const [r, g, b] = [match[1], match[2], match[3]].map((h) => channel(parseInt(h as string, 16)));
  return 0.2126 * (r as number) + 0.7152 * (g as number) + 0.0722 * (b as number);
}

/** WCAG 2.1 contrast ratio between two colors. */
export function contrastRatio(foreground: string, background: string): number {
  const [light, dark] = [luminance(foreground), luminance(background)].sort((a, b) => b - a);
  return ((light as number) + 0.05) / ((dark as number) + 0.05);
}

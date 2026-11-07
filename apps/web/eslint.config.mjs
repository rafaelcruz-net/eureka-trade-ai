import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { FlatCompat } from '@eslint/eslintrc';

const compat = new FlatCompat({ baseDirectory: dirname(fileURLToPath(import.meta.url)) });

// U9 (NFR6.2, BR5.1, project.md Corrections): the live channel opens with fetch and the Authorization header, never
// with EventSource (it cannot send the header and would put the token in the URL). In a flat config the most specific
// block REPLACES the options of a rule, so these entries are repeated in every block that defines
// no-restricted-globals or no-restricted-syntax.
const EVENT_SOURCE_MESSAGE =
  'The live channel uses fetch with the Authorization header, never EventSource (project.md, U9 BR5.1).';
const NO_EVENT_SOURCE_GLOBAL = { name: 'EventSource', message: EVENT_SOURCE_MESSAGE };
const NO_EVENT_SOURCE_SYNTAX = [
  { selector: "NewExpression[callee.name='EventSource']", message: EVENT_SOURCE_MESSAGE },
  { selector: "MemberExpression[property.name='EventSource']", message: EVENT_SOURCE_MESSAGE },
];

// U7, U5 and U9 (project.md: no floating point): DecimalString values stay text from the API to the screen.
const NO_PARSE_FLOAT_GLOBAL = {
  name: 'parseFloat',
  message: 'DecimalString values stay text (project.md: no floating point).',
};
const NO_FLOAT_SYNTAX = [
  {
    selector: "CallExpression[callee.name='Number']",
    message: 'DecimalString values stay text (project.md: no floating point).',
  },
  {
    selector: "UnaryExpression[operator='+'][argument.type!='Literal']",
    message: 'Unary plus converts a DecimalString to a number.',
  },
  {
    selector: "CallExpression[callee.property.name='toFixed']",
    message: 'toFixed works on floating point numbers.',
  },
];
const NO_FLOAT_PROPERTIES = [
  { object: 'Number', property: 'parseFloat', message: 'DecimalString values stay text.' },
  {
    object: 'Math',
    property: 'round',
    message: 'Monetary values are never rounded in the screen.',
  },
];

const config = [
  {
    ignores: [
      '.next/**',
      'node_modules/**',
      'coverage/**',
      'test-results/**',
      'playwright-report/**',
      'next-env.d.ts',
      'src/lib/api/schema.d.ts',
    ],
  },
  ...compat.extends('next/core-web-vitals', 'next/typescript'),
  {
    rules: {
      'no-console': ['error', { allow: ['warn', 'error'] }],
      '@typescript-eslint/no-explicit-any': 'error',
    },
  },
  {
    // U9 (NFR6.2): no EventSource anywhere in the app code.
    files: ['src/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-globals': ['error', NO_EVENT_SOURCE_GLOBAL],
      'no-restricted-syntax': ['error', ...NO_EVENT_SOURCE_SYNTAX],
    },
  },
  {
    // U4 (NFR6.6): symbol and name come from untrusted sources; the scanner screens render text only.
    files: ['src/features/scanner/**/*.{ts,tsx}', 'src/app/scanner/**/*.{ts,tsx}'],
    rules: {
      'react/no-danger': 'error',
    },
  },
  {
    // U3 (cicd-pipeline.md): terms, risk notice and account data are plain text, never HTML.
    files: [
      'src/features/auth/**/*.{ts,tsx}',
      'src/features/account/**/*.{ts,tsx}',
      'src/app/convite/**/*.{ts,tsx}',
      'src/app/conta/**/*.{ts,tsx}',
    ],
    rules: {
      'react/no-danger': 'error',
    },
  },
  {
    // U7 (cicd-pipeline.md, project.md): money, quantities, prices and P&L are DecimalString from the API to the screen
    // and are never converted to a JavaScript number (floating point). Calendar parts use parseInt with radix 10.
    // U5 (code-generation plan Step 18): the bot screens follow the same rule for balances, limits and parameters;
    // the only integer is the interval in seconds, read with parseInt and radix 10.
    files: [
      'src/features/pnl/**/*.{ts,tsx}',
      'src/app/pnl/**/*.{ts,tsx}',
      'src/features/bots/**/*.{ts,tsx}',
      'src/app/bots/**/*.{ts,tsx}',
    ],
    rules: {
      'react/no-danger': 'error',
      // U9: the EventSource ban of the src/** block is repeated here, because this block replaces its options.
      'no-restricted-globals': ['error', NO_PARSE_FLOAT_GLOBAL, NO_EVENT_SOURCE_GLOBAL],
      'no-restricted-properties': ['error', ...NO_FLOAT_PROPERTIES],
      'no-restricted-syntax': ['error', ...NO_FLOAT_SYNTAX, ...NO_EVENT_SOURCE_SYNTAX],
    },
  },
  {
    // U9 (NFR4.2, NFR6.5, BR3.5): the dashboard shows the P&L of U7 from its decimal text, never as a JavaScript
    // number (no Number(, parseFloat( or unary plus), never builds markup from received text, and never opens the
    // live channel with EventSource.
    files: ['src/features/dashboard/**/*.{ts,tsx}'],
    rules: {
      'react/no-danger': 'error',
      'no-restricted-globals': ['error', NO_PARSE_FLOAT_GLOBAL, NO_EVENT_SOURCE_GLOBAL],
      'no-restricted-properties': ['error', ...NO_FLOAT_PROPERTIES],
      'no-restricted-syntax': ['error', ...NO_FLOAT_SYNTAX, ...NO_EVENT_SOURCE_SYNTAX],
    },
  },
];

export default config;

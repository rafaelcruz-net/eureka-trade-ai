import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { FlatCompat } from '@eslint/eslintrc';

const compat = new FlatCompat({ baseDirectory: dirname(fileURLToPath(import.meta.url)) });

// 261113-trading-real-bot U6 (U6-NFR1.1b, U6-NFR1.2, U6-NFR1.3, U6-NFR1.9, U6-NFR7.3, U6-NFR8.6): the REAL folders
// never import a transaction library, never send a transaction, never convert money to a JavaScript number, never write
// to the browser storage or the console, and never build markup from received text. The administrator app had no
// float or EventSource ban before; the block only adds the REAL bans to the global rules of the app.
const REAL_FILES = ['src/features/real/**/*.{ts,tsx}', 'src/app/real/**/*.{ts,tsx}'];
const REAL_TESTS = [
  'src/features/real/**/*.test.{ts,tsx}',
  'src/app/real/**/*.test.{ts,tsx}',
  'src/features/real/test-support.tsx',
];
const NO_TRANSACTION_LIBRARIES = {
  paths: ['@solana/web3.js', '@solana/kit', '@solana/spl-token', '@coral-xyz/anchor'].map(
    (name) => ({
      name,
      message: 'The browser never builds nor sends a transaction (ADR-008, U6-NFR1.1b).',
    }),
  ),
  patterns: [
    {
      group: ['@solana/web3.js/*', '@solana/kit/*', '@solana/spl-token/*', '@coral-xyz/anchor/*'],
      message: 'The browser never builds nor sends a transaction (ADR-008, U6-NFR1.1b).',
    },
  ],
};
const REAL_SEND_MESSAGE = 'The browser only signs; the server sends (ADR-008, U6-NFR1.1b).';
const REAL_SYNTAX = [
  { selector: "Identifier[name='sendTransaction']", message: REAL_SEND_MESSAGE },
  { selector: "Identifier[name='sendRawTransaction']", message: REAL_SEND_MESSAGE },
  { selector: "Literal[value='sendTransaction']", message: REAL_SEND_MESSAGE },
  { selector: "Literal[value='sendRawTransaction']", message: REAL_SEND_MESSAGE },
  {
    selector: "CallExpression[callee.name='Number']",
    message: 'Money is bigint in the REAL screens; money.ts is the only conversion (U6-NFR7.3).',
  },
  {
    selector: "UnaryExpression[operator='+'][argument.type!='Literal']",
    message: 'Unary plus converts money to a floating point number (U6-NFR7.3).',
  },
  {
    selector: "CallExpression[callee.property.name='toFixed']",
    message: 'toFixed works on floating point numbers (U6-NFR7.3).',
  },
];
const REAL_GLOBALS = [
  { name: 'parseFloat', message: 'Money is bigint in the REAL screens (U6-NFR7.3).' },
  { name: 'parseInt', message: 'Money is bigint in the REAL screens (U6-NFR7.3).' },
  { name: 'localStorage', message: 'Nothing of the REAL goes to the browser storage (U6-NFR1.2).' },
  {
    name: 'sessionStorage',
    message: 'Nothing of the REAL goes to the browser storage (U6-NFR1.2).',
  },
  { name: 'console', message: 'Nothing of the REAL goes to the console (U6-NFR1.2).' },
];
const REAL_PROPERTIES = [
  ...['parseFloat', 'parseInt'].map((property) => ({
    object: 'Number',
    property,
    message: 'Money is bigint in the REAL screens (U6-NFR7.3).',
  })),
  ...['round', 'floor', 'ceil'].map((property) => ({
    object: 'Math',
    property,
    message: 'Money is bigint and never rounded in the REAL screens (U6-NFR7.3).',
  })),
  ...['window', 'globalThis', 'self'].flatMap((object) =>
    ['localStorage', 'sessionStorage', 'console'].map((property) => ({
      object,
      property,
      message: 'Nothing of the REAL goes to the browser storage or the console (U6-NFR1.2).',
    })),
  ),
  {
    object: 'document',
    property: 'cookie',
    message: 'Nothing of the REAL goes to a cookie (U6-NFR1.2).',
  },
];

const config = [
  {
    ignores: [
      '.next/**',
      'node_modules/**',
      'coverage/**',
      'next-env.d.ts',
      'src/lib/api/schema.d.ts',
    ],
  },
  ...compat.extends('next/core-web-vitals', 'next/typescript'),
  {
    rules: {
      'no-console': ['error', { allow: ['warn', 'error'] }],
      '@typescript-eslint/no-explicit-any': 'error',
      // BR6.6: comments, reasons and names come from people; every screen renders them as text, never as HTML.
      'react/no-danger': 'error',
    },
  },
  {
    // 261113-trading-real-bot U6: the REAL folders (see REAL_FILES above). The tests of these folders are outside the
    // block: they read the storage to prove it stays empty (U6-NFR1.2, U6-NFR1.10).
    files: REAL_FILES,
    ignores: REAL_TESTS,
    rules: {
      'react/no-danger': 'error',
      'no-console': 'error',
      'no-restricted-imports': ['error', NO_TRANSACTION_LIBRARIES],
      'no-restricted-globals': ['error', ...REAL_GLOBALS],
      'no-restricted-properties': ['error', ...REAL_PROPERTIES],
      'no-restricted-syntax': ['error', ...REAL_SYNTAX],
    },
  },
  {
    // U6 (U6-NFR8.6): the named exception, the same as apps/web. money.ts is the single parser and formatter of SOL
    // and token amounts, on bigint only; every other ban of the REAL block still applies to it.
    files: ['src/features/real/money.ts'],
    rules: {
      'no-restricted-syntax': [
        'error',
        ...REAL_SYNTAX.filter((entry) => !entry.selector.includes("callee.name='Number'")),
      ],
    },
  },
];

export default config;

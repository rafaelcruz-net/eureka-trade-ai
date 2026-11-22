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
// 261121-paper-real-unico U1 (BR7.5, ADR-010, C17): the P&L and bot screens never hold a bigint; lamports become SOL
// text only through features/real/money.ts, the single conversion of the REAL money.
const NO_BIGINT_MESSAGE =
  'Lamports are formatted only by features/real/money.ts; bigint lives only there (ADR-010, BR7.5).';
const NO_BIGINT_SYNTAX = [
  { selector: "CallExpression[callee.name='BigInt']", message: NO_BIGINT_MESSAGE },
  { selector: 'Literal[bigint]', message: NO_BIGINT_MESSAGE },
];
const NO_FLOAT_PROPERTIES = [
  { object: 'Number', property: 'parseFloat', message: 'DecimalString values stay text.' },
  {
    object: 'Math',
    property: 'round',
    message: 'Monetary values are never rounded in the screen.',
  },
];

// 261113-trading-real-bot U6 (U6-NFR1.1b, U6-NFR1.2, U6-NFR1.3, U6-NFR1.9, U6-NFR7.3, U6-NFR8.6): the REAL folders
// never import a transaction library, never send a transaction, never convert money to a JavaScript number, never write
// to the browser storage or the console, and never build markup from received text. The block repeats the bans that
// already exist (EventSource, floating point), because a more specific block replaces the options of the previous one.
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
    // 261121-paper-real-unico U1 (BR7.5, ADR-010, C17): the P&L folders also never hold a bigint (the bot screens keep
    // their own exact decimal of the USDC limits). This block replaces the options of the previous one for these
    // files, so it repeats them.
    files: ['src/features/pnl/**/*.{ts,tsx}', 'src/app/pnl/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-syntax': [
        'error',
        ...NO_FLOAT_SYNTAX,
        ...NO_BIGINT_SYNTAX,
        ...NO_EVENT_SOURCE_SYNTAX,
      ],
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
  {
    // 261113-trading-real-bot U6: the REAL folders (see REAL_FILES above). The tests of these folders are outside the
    // block: they read the storage to prove it stays empty (U6-NFR1.2).
    files: REAL_FILES,
    ignores: REAL_TESTS,
    rules: {
      'react/no-danger': 'error',
      'no-console': 'error',
      'no-restricted-imports': ['error', NO_TRANSACTION_LIBRARIES],
      'no-restricted-globals': ['error', ...REAL_GLOBALS, NO_EVENT_SOURCE_GLOBAL],
      'no-restricted-properties': ['error', ...REAL_PROPERTIES],
      'no-restricted-syntax': ['error', ...REAL_SYNTAX, ...NO_EVENT_SOURCE_SYNTAX],
    },
  },
  {
    // U6 (U6-NFR8.6): the named exception. money.ts is the single parser and formatter of SOL and token amounts; it
    // works on bigint only, and may turn the decimals of a mint (a small integer of the contract) into a bigint power.
    // Every other ban of the REAL block still applies to it.
    files: ['src/features/real/money.ts'],
    rules: {
      'no-restricted-syntax': [
        'error',
        ...REAL_SYNTAX.filter((entry) => !entry.selector.includes("callee.name='Number'")),
        ...NO_EVENT_SOURCE_SYNTAX,
      ],
    },
  },
];

export default config;

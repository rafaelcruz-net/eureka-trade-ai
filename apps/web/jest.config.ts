import type { Config } from 'jest';
import nextJest from 'next/jest.js';

const createJestConfig = nextJest({ dir: './' });

const config: Config = {
  testEnvironment: 'jsdom',
  setupFilesAfterEnv: ['<rootDir>/jest.setup.ts'],
  moduleNameMapper: {
    '^@/(.*)$': '<rootDir>/src/$1',
  },
  testPathIgnorePatterns: ['/node_modules/', '/.next/', '/e2e/'],
  collectCoverageFrom: [
    'src/**/*.{ts,tsx}',
    // Fixed exclusions (unit-test-instructions.md); never decided ad hoc.
    '!src/lib/api/schema.d.ts',
    '!src/**/*.config.*',
    '!src/app/layout.tsx',
    '!src/**/*.d.ts',
  ],
  coverageReporters: ['text-summary', 'lcov', 'json-summary'],
  // 80% line floor measured for the frontend alone (team.md, NFR12.1). Never lowered to pass a step.
  coverageThreshold: {
    global: {
      lines: 80,
    },
  },
};

export default createJestConfig(config);

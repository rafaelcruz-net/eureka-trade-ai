import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  // Standalone output: the Docker image ships only the minimal Node server (same pattern as apps/web).
  output: 'standalone',
  poweredByHeader: false,
  reactStrictMode: true,
};

export default nextConfig;

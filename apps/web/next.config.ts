import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  // Standalone output: the Docker image ships only the minimal Node server (infrastructure-specification.md).
  output: 'standalone',
  poweredByHeader: false,
  reactStrictMode: true,
};

export default nextConfig;

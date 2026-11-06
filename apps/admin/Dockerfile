# syntax=docker/dockerfile:1
# Multi-stage image of the administrator app (U8, infrastructure-specification.md Q1): the same pattern as apps/web -
# Next.js "standalone" output on a slim Node LTS, non-root user, base images pinned by digest, install from the lockfile
# only. No NEXT_PUBLIC_* variable and no secret: API_ORIGIN is read by the server at run time.

FROM node:24-alpine@sha256:06d7adf3e0ef61a13f1d28682726366f97a15aa1888c934d3ecec86c33918b53 AS deps
WORKDIR /app
RUN corepack enable && corepack prepare pnpm@10.18.3 --activate
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

FROM deps AS build
ENV NEXT_TELEMETRY_DISABLED=1
COPY . .
RUN pnpm build

FROM node:24-alpine@sha256:06d7adf3e0ef61a13f1d28682726366f97a15aa1888c934d3ecec86c33918b53
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
RUN addgroup -S memetrade && adduser -S -G memetrade -H -s /sbin/nologin memetrade
WORKDIR /app
COPY --from=build --chown=memetrade:memetrade /app/.next/standalone ./
COPY --from=build --chown=memetrade:memetrade /app/.next/static ./.next/static
USER memetrade
EXPOSE 3000
HEALTHCHECK --interval=10s --timeout=3s --retries=3 --start-period=20s \
  CMD ["node", "-e", "fetch('http://127.0.0.1:3000/api/health').then(r=>process.exit(r.status===200?0:1)).catch(()=>process.exit(1))"]
CMD ["node", "server.js"]

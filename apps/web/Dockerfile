# syntax=docker/dockerfile:1
# Multi-stage image of the guest app: Next.js "standalone" output on a slim Node LTS, non-root user, base images
# pinned by digest. Install uses only the lockfile.

FROM node:26-alpine@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5 AS deps
WORKDIR /app
RUN corepack enable && corepack prepare pnpm@10.18.3 --activate
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

FROM deps AS build
ENV NEXT_TELEMETRY_DISABLED=1
COPY . .
RUN pnpm build

FROM node:26-alpine@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
RUN addgroup -S memetrade && adduser -S -G memetrade -H -s /sbin/nologin memetrade
WORKDIR /app
COPY --from=build --chown=memetrade:memetrade /app/.next/standalone ./
COPY --from=build --chown=memetrade:memetrade /app/.next/static ./.next/static
USER memetrade
EXPOSE 3000
HEALTHCHECK --interval=10s --timeout=3s --retries=6 --start-period=20s \
  CMD ["node", "-e", "fetch('http://127.0.0.1:3000/api/health').then(r=>process.exit(r.status===200?0:1)).catch(()=>process.exit(1))"]
CMD ["node", "server.js"]

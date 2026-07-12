# syntax=docker/dockerfile:1.7

FROM node:26-alpine AS toolchain
RUN npm install --global --no-audit --no-fund pnpm@11.7.0
WORKDIR /workspace

FROM toolchain AS deps
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/web/package.json apps/web/package.json
RUN pnpm install --frozen-lockfile --filter @ai-quant/web...

FROM toolchain AS builder
ARG DATA_MODE=MOCK
ENV NEXT_TELEMETRY_DISABLED=1
ENV DATA_MODE=${DATA_MODE}
COPY --from=deps /workspace/node_modules ./node_modules
COPY --from=deps /workspace/apps/web/node_modules ./apps/web/node_modules
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/web ./apps/web
RUN pnpm --filter @ai-quant/web build

FROM node:26-alpine AS runner
WORKDIR /app/apps/web
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV HOSTNAME=0.0.0.0
ENV PORT=3000

RUN addgroup --system --gid 1001 nodejs \
  && adduser --system --uid 1001 nextjs \
  && rm -rf /usr/local/lib/node_modules/npm /usr/local/bin/npm /usr/local/bin/npx

COPY --from=builder --chown=nextjs:nodejs /workspace/apps/web/.next/standalone /app
COPY --from=builder --chown=nextjs:nodejs /workspace/apps/web/public ./public
COPY --from=builder --chown=nextjs:nodejs /workspace/apps/web/.next/static ./.next/static

USER nextjs
EXPOSE 3000

CMD ["node", "server.js"]

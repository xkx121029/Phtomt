# ---------- 构建前端 ----------
FROM node:20-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY vite.config.js index.html ./
COPY src ./src
COPY public ./public
RUN npm run build

# ---------- 运行 ----------
FROM node:20-alpine
ENV NODE_ENV=production \
    PORT=5180 \
    DATA_DIR=/app/server/data

WORKDIR /app

# 只装运行时依赖（express / compression），vite 与 vue 留在构建阶段
COPY package.json package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY server ./server
COPY public ./public
COPY --from=build /app/dist ./dist
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

# 卷挂上去之后镜像里的内容会被遮住，所以先把种子内容另存一份，
# 首次启动时由 entrypoint 铺进空卷。
RUN mkdir -p /opt/seed \
 && cp -r server/data /opt/seed/data

EXPOSE 5180

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
  CMD wget -qO- "http://127.0.0.1:${PORT}/api/health" || exit 1

ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["node", "server/index.js"]

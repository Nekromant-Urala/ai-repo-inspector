# Многостадийная сборка фронтенда AI-Repo Inspector.
# 1. Сборка Angular-приложения в node-образе.
# 2. Раздача статики + прокси /api на бэкенд через nginx.
FROM node:22-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM nginx:alpine
COPY docker/frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/ai-repo-frontend/browser /usr/share/nginx/html
EXPOSE 80

FROM node:22-alpine AS builder

WORKDIR /app

COPY vue/package*.json ./
RUN npm ci

COPY vue/ ./
RUN npm run build

FROM nginx:1.27-alpine

COPY docker/frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

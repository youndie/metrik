# Дашборд — статика в nginx. Отдавать её самим сервером нечем: staticFiles в Kotlin/Native
# недоступен (docs/research/research-architecture.md §1.3), да и незачем.
FROM nginx:1.27-alpine

COPY composeApp/build/dist/wasmJs/productionExecutable/ /usr/share/nginx/html/
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

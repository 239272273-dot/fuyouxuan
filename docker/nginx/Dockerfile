FROM nginx:1.27.3-alpine
COPY docker/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY docker/nginx/proxy_params /etc/nginx/proxy_params
COPY frontend/ /usr/share/nginx/html/
RUN mkdir -p /var/cache/nginx/fuyouxuan_benefits /usr/share/nginx/html/assets/uploads

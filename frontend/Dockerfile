# ---- Stage 1: Build ----
FROM node:20-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# 부스 기간 기능(소견서 → 오늘의 운세 소견서)의 스위치. 평소에는 false 이고, 부스 기간에만
# docker-compose.booth.yml 이 true 를 넘긴다. 빌드 인자는 RUN 에 환경변수로 보이고 vite 가 그 값을 화면 코드에 굳힌다.
# 값이 바뀌면 여기서부터 다시 빌드된다(위의 npm ci 캐시는 그대로 쓴다).
ARG VITE_BOOTH_MODE=false
RUN npm run build

# ---- Stage 2: Serve ----
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]

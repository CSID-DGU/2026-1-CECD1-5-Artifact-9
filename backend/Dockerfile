# =============================================
# 1단계: 빌드 단계 (Gradle로 jar 생성)
# =============================================
FROM gradle:8.5-jdk21 AS builder
WORKDIR /app

# 의존성 캐싱을 위해 build.gradle 먼저 복사
COPY build.gradle settings.gradle ./
COPY gradle ./gradle

# 소스 복사
COPY src ./src

# Gradle 빌드 (테스트는 스킵해서 빌드 시간 단축)
RUN gradle build -x test --no-daemon

# =============================================
# 2단계: 실행 단계 (가벼운 JRE만 사용)
# =============================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 빌드 단계에서 만든 jar 복사
COPY --from=builder /app/build/libs/*.jar app.jar

# 컨테이너 8080 포트 노출
EXPOSE 8080

# 한국 시간대
ENV TZ=Asia/Seoul

# 실행
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
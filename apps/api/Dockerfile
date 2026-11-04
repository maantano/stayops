# syntax=docker/dockerfile:1
# StayOps API 이미지 (P12, 배포 구성만 — 실제 배포는 하지 않음). docs/deploy/DEPLOYMENT.md 참고.
#   docker build -t stayops-api apps/api
# 같은 이미지를 두 용도로 쓴다:
#   - 서버:      SPRING_PROFILES_ACTIVE=aws
#   - migration: SPRING_PROFILES_ACTIVE=aws,migrate  (Flyway 실행 후 종료, 배포 전 한 번)

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
# 의존성 layer를 소스와 분리해 캐시한다
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon --console=plain dependencies > /dev/null
COPY src/main src/main
RUN ./gradlew --no-daemon --console=plain bootJar -x test \
 && java -Djarmode=tools -jar build/libs/*-SNAPSHOT.jar extract --layers --launcher --destination /workspace/extracted

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S stayops && adduser -S -G stayops stayops
WORKDIR /app
# 변경 빈도가 낮은 layer부터 복사한다
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER stayops
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    SPRING_PROFILES_ACTIVE=aws \
    STAYOPS_API_PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

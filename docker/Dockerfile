# ============================================================================
# Stage 1: Build (의존성 layer 캐시 최적화)
# ============================================================================
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace

# 1) Gradle wrapper + 빌드 정의만 먼저 복사 — 코드가 바뀌어도 의존성 캐시 hit
COPY gradlew gradlew.bat settings.gradle build.gradle check.gradle ./
COPY gradle gradle
COPY engine-core/build.gradle engine-core/build.gradle
COPY service-app/build.gradle service-app/build.gradle
# e2e-tests 모듈은 settings.gradle에 include되어 있으므로 디렉토리/build.gradle이 없으면
# Gradle 구성 단계에서 실패한다. bootJar 빌드 자체는 e2e-tests를 컴파일하지 않으므로
# build.gradle만 두어 프로젝트 구조만 만족시킨다.
COPY e2e-tests/build.gradle e2e-tests/build.gradle

# 2) Windows에서 클론한 워킹 트리는 ``gradlew``에 CRLF가 섞일 수 있다.
#    .gitattributes로 LF 고정해도 이미 CRLF로 체크아웃된 사본은 그대로 컨텍스트에 들어가
#    ``/bin/sh: ./gradlew: not found`` (shebang ``#!/usr/bin/env sh\r`` 파싱 실패)로 빌드가 깨진다.
#    호스트 환경과 무관하게 컨테이너 빌드가 성공하도록 LF로 강제 정규화.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

# 3) 의존성 다운로드만 미리 (실패해도 캐시 일부는 생성됨)
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# 4) 소스 복사 후 bootJar (테스트는 CI에서 별도 — 이미지 빌드는 빠르게)
COPY engine-core engine-core
COPY service-app service-app
RUN ./gradlew --no-daemon :service-app:bootJar -x test

# ============================================================================
# Stage 2: Runtime (가벼운 JRE 이미지)
# ============================================================================
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# bootJar 산출물 복사 (이름은 simple-workflow-engine-* 또는 service-app-*)
COPY --from=builder /workspace/service-app/build/libs/*.jar app.jar

EXPOSE 8080

# JVM 옵션은 JAVA_OPTS 환경변수로 외부 주입 가능
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]

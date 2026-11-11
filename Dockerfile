# ============================================================
# 1단계: 빌드 (JDK) - bootJar 생성
# ============================================================
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# 의존성 레이어 캐시를 위해 빌드 스크립트를 먼저 복사
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x ./gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src src
RUN ./gradlew --no-daemon bootJar -x test && \
    mv build/libs/music-*-SNAPSHOT.jar /workspace/app.jar

# ============================================================
# 2단계: 런타임 (JRE + FFmpeg) - 시크릿은 포함하지 않고 실행 시 환경변수로 주입
# ============================================================
FROM eclipse-temurin:21-jre-jammy

# 한글 파일명 처리, 타임존, 기본 프로파일(시크릿 아님)
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=Asia/Seoul \
    SPRING_PROFILES_ACTIVE=prod

# HLS 변환용 FFmpeg 설치 (/usr/bin/ffmpeg)
RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg && \
    rm -rf /var/lib/apt/lists/*

# 비루트 사용자 및 임시 오디오 변환 작업 디렉터리
RUN groupadd --system --gid 1001 app && \
    useradd --system --uid 1001 --gid app --no-create-home app && \
    mkdir -p /tmp/audio-work && chown app:app /tmp/audio-work

WORKDIR /app
COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar

USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

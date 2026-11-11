# [PRD-05] 백엔드 서버 (Backend Cluster) 상세 설계 (v1.4 - 문서 간 정합성 보완안)

> **v1.4 변경 요약 (2026-11-11 PRD 교차 검토 반영):** Spring Boot 4 스타터 명칭 정정, local/prod 프로파일 분리, 쿠키 기반 OAuth2 인가 요청 저장소, `redirect_uri` 화이트리스트 기반 JWT 전달, `playback_logs` 기록, presigned URL 기반 원본 다운로드, 공통 에러 응답, 플랫폼 분리 대비 설정값(`app.media`, `app.cors`) 추가. 상세 결정 이력은 `PRD.md` §7 참조.

## 1. 개요 및 개발 목표
본 문서는 음원 스트리밍 및 원본 아카이빙 서비스 MVP의 핵심 비즈니스 로직과 미디어 전처리를 담당하는 **백엔드 서버 클러스터(`web-server-1`, `web-server-2`)**의 상세 개발 명세를 정의합니다.

전체 아키텍처 문서(`PRD-01`), 웹페이지(`PRD-02`), 음원 서버(`PRD-06`)에서 확정된 원칙에 따라 **도커 리눅스 컨테이너 기반의 초기 개발 인프라 구축**, **대중성과 사용 편의성을 최우선으로 고려한 라이브러리 선정**, **S3 호환 오픈소스 스토리지(`RustFS`) 무수정 연동**, **Google OAuth2 기반 무가입 로그인(프로필 이미지 연동 포함)**, **`application.yml` 및 환경변수 기반 민감 정보 분리 설계**, **표준 레이어드 아키텍처(`Controller ➔ Service ➔ Repository`)**, 그리고 **기능별 Service 계층 단위 테스트(Unit Test) 검증 체계**를 구체화합니다.

## 2. 도커 리눅스 컨테이너 개발 인프라 및 환경 구축

개발 단계에서는 리눅스 컨테이너 내부에 직접 접속하여 빠른 빌드·테스트 피드백 루프를 수행(**방식 1**)하고, 통합 테스트 및 클러스터 구동 시에는 동일한 환경이 코드화된 `Dockerfile`로 다중 인스턴스(`web-server-1`, `web-server-2`, 약칭 `web1`/`web2`)를 복제 구동(**방식 2**)합니다.

### 2.1. 컨테이너 내부 개발 환경 초기 세팅 절차 (방식 1)
Ubuntu 24.04(또는 Debian 12) 기반 리눅스 컨테이너 내부 터미널에서 아래 순서대로 필수 개발 인프라(**Git, OpenJDK 21, FFmpeg, UTF-8 로케일**)를 설치합니다.

```bash
# 1. 패키지 인덱스 업데이트 및 기본 유틸리티·한글/UTF-8 로케일 설치 (한글 파일명 및 ID3 태그 깨짐 방지)
apt-get update && apt-get install -y \
    curl \
    wget \
    unzip \
    locales \
    ca-certificates \
    tzdata

locale-gen ko_KR.UTF-8 en_US.UTF-8
update-locale LANG=ko_KR.UTF-8 LC_ALL=ko_KR.UTF-8
export LANG=ko_KR.UTF-8
export LC_ALL=ko_KR.UTF-8

# 2. Git 설치 및 기본 개행 문자(CRLF -> LF) 설정
apt-get install -y git
git config --global core.autocrlf input
git config --global init.defaultBranch main

# 3. Java 21 (OpenJDK 21) 설치 및 환경변수 검증
apt-get install -y openjdk-21-jdk
java -version
javac -version

# 4. 오디오 변환 및 스트림 검증용 FFmpeg / FFprobe 설치
apt-get install -y ffmpeg
ffmpeg -version
ffprobe -version

# 5. 작업 디렉터리 생성 및 Gradle Wrapper 실행 권한 부여
mkdir -p /workspace/backend
cd /workspace/backend
# (프로젝트 초기화 후) chmod +x ./gradlew
```

### 2.2. 멀티서버(`web-server-1`, `web-server-2`) 클러스터 구동용 `Dockerfile` 명세 (방식 2)
개발 완료된 애플리케이션을 Nginx 로드 밸런서 뒤에 2개의 컨테이너(`web-server-1`, `web-server-2`)로 동일하게 띄우기 위한 표준 `Dockerfile`입니다. 민감 정보는 이미지 내부에 포함하지 않고 컨테이너 실행 시점(Runtime)에 환경변수로 주입합니다.

```dockerfile
FROM eclipse-temurin:21-jdk-jammy

# 한글 파일명 처리 및 FFmpeg 설치
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=Asia/Seoul
RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg git curl && \
    rm -rf /var/lib/apt/lists/*

# 임시 오디오 변환 작업 디렉터리 생성
RUN mkdir -p /tmp/audio-work

WORKDIR /app
COPY build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

## 3. 기술 스택 및 라이브러리 선정 (Kotlin DSL 기준)

### 3.1. 라이브러리 선정 기준 및 검토 결과
추가 라이브러리는 **① 현재 Spring Boot / Java 21 환경 호환성**, **② 충분한 레퍼런스와 검증 여부**, 그리고 **③ 대중성(1순위) > 사용 난이도(2순위) > 성능(3순위)** 기준에 따라 선정했습니다.

> **참고 (`RustFS` 전환에 따른 클라이언트 SDK 호환성):**
> 음원 서버 컨테이너가 `MinIO`에서 1:1 S3 호환 오픈소스인 **`RustFS`(`http://rustfs:9000`)**로 교체되었으나, RustFS는 표준 S3 API를 그대로 구현하므로 자바 진영에서 가장 사용법이 직관적인 **`io.minio:minio` 클라이언트 SDK를 단 한 줄의 코드 변경 없이 그대로 사용**합니다.

| 분류 | 선정 라이브러리 | 대중성 | 사용 난이도 | 성능 | 선정 사유 및 호환성 검토 |
| :--- | :--- | :---: | :---: | :---: | :--- |
| **기반 프레임워크** | **Spring Boot `4.1.2`** (Kotlin DSL, Java 21) | 최상 | 낮음 | 최상 | 프로젝트 표준 베이스 환경 |
| **핵심 스타터** | **Spring Web, Data JPA, Security, Validation** | 최상 | 낮음 | 상 | REST API, ORM 영속성, 보안 필터 체인, 입력값 검증 표준 |
| **OAuth2 인증** | **Spring OAuth2 Client (`spring-boot-starter-oauth2-client`)** | 최상 | **매우 낮음** | 상 | 스프링 공식 스타터. `application.yml`에 구글 클라이언트 ID/Secret만 등록하면 인증 리다이렉트 및 사용자 정보 조회 자동 처리 |
| **JWT 토큰** | **JJWT (`io.jsonwebtoken:jjwt:0.12.6`)** | 최상 | **낮음** | 최상 | 다중 백엔드(`web1`, `web2`) 무상태 인증을 위해 Google OAuth2 로그인 성공 후 자체 Access Token을 발급·검증하는 자바 진영 압도적 1위 라이브러리 |
| **보일러플레이트** | **Lombok** | 최상 | 매우 낮음 | 최상 | Getter, Builder, `@RequiredArgsConstructor`, `@Slf4j` 표준 코드 축약 |
| **DB 드라이버** | **PostgreSQL Driver (`org.postgresql:postgresql`)** | 최상 | 매우 낮음 | 최상 | Spring Boot 기본 관리 버전 사용 (버전 명시 불필요, 자동 호환) |
| **스토리지 연동** | **MinIO S3 Java SDK (`io.minio:minio:8.5.14`)** | 상 | **매우 낮음** | 상 | 표준 S3 호환 클라이언트. `RustFS`(`http://rustfs:9000`)와 100% 호환되며 `endpoint/accessKey/secretKey` 3줄로 직관적 연동 가능 |
| **위변조 검증** | **Apache Tika Core (`org.apache.tika:tika-core:2.9.2`)** | 최상 | **매우 낮음** | 최상 | 자바 진영 파일 Magic Number 검사의 압도적 표준. 무거운 `tika-parsers`를 제외하고 `tika-core`만 사용하여 의존성 충돌 제로 및 `new Tika().detect()` 1줄 호출 |
| **메타데이터/커버** | **JAudioTagger (`net.jthink:jaudiotagger:3.0.1`)** | 상 | **낮음** | 상 | MP3(ID3v1/v2), WAV, FLAC 등에서 제목·가수·재생시간·앨범 커버 바이트(`Artwork`)를 가장 직관적인 API로 추출하는 표준 오픈소스 |
| **단위 테스트** | **`spring-boot-starter-test` (`JUnit 5` + `Mockito` + `AssertJ`)** | 최상 | **매우 낮음** | 최상 | 별도 외부 테스트 라이브러리 추가 없이 스프링 부트 내장 표준 도구만으로 `Service` 계층 모킹(Mocking) 및 가독성 높은 검증 수행 |

### 3.2. `build.gradle.kts` 표준 구성 명세

> **Spring Boot 4 스타터 명칭 기준 (v1.4 보완):** Spring Boot 4부터 스타터가 모듈 단위로 재편되어 `spring-boot-starter-web` ➔ `spring-boot-starter-webmvc`, `spring-boot-starter-oauth2-client` ➔ `spring-boot-starter-security-oauth2-client`로 명칭이 변경되었습니다. 본 명세는 현재 저장소의 `build.gradle.kts`(Spring Initializr 생성본) 명칭을 기준으로 합니다.
>
> **빌드 시 확인 사항:** ① Spring Boot 4는 Jackson 3(`tools.jackson`)을 기본 사용하며, `jjwt-jackson 0.12.6`은 Jackson 2(`com.fasterxml.jackson`)를 전이 의존하므로 두 버전이 공존합니다(패키지가 달라 충돌은 없으나 의존성 트리를 확인). ② `io.minio:minio 8.5.x`는 OkHttp 4.x에 의존하므로 Spring Boot 의존성 관리와 버전이 어긋나지 않는지 `./gradlew dependencies`로 확인합니다.

```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.streaming"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // 1. Base Spring Boot Starters (Spring Boot 4 명칭)
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // 2. Stateless JWT (JJWT)
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // 3. Database (운영: PostgreSQL / 로컬: H2 인메모리)
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.springframework.boot:spring-boot-h2console") // Boot 4부터 H2 웹 콘솔 자동설정이 별도 모듈로 분리됨 (local 프로파일 /h2-console 용)

    // 4. S3 Object Storage Client (RustFS 100% 호환)
    implementation("io.minio:minio:8.5.14")

    // 5. Audio Analysis & Security (Apache Tika Core & JAudioTagger)
    implementation("org.apache.tika:tika-core:2.9.2")
    implementation("net.jthink:jaudiotagger:3.0.1")

    // 6. Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // 7. Unit Testing (JUnit 5, Mockito, AssertJ 내장 / Spring Boot 4 테스트 스타터 명칭)
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-client-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

## 4. `application.yaml` 설정 구조 및 환경변수(민감 정보) 분리 설계

### 4.1. 설정 관리 원칙
1. **모든 설정의 `application.yaml` 일원화:** DB 커넥션, 멀티파트 용량, Google OAuth2, RustFS 스토리지 접속 정보, FFmpeg 변환 옵션 등 애플리케이션 동작에 필요한 모든 설정값은 `src/main/resources/application.yaml`에 정의하고, 자바 코드에서는 `@ConfigurationProperties`로 바인딩하여 사용합니다.
2. **민감 정보(Secret) 환경변수 주입 강제:** DB 패스워드, Google OAuth2 Client ID/Secret, RustFS Secret Key, JWT 서명 키 등 보안상 민감한 정보는 Git 저장소에 노출되지 않도록 `${환경변수명}` 문법으로 OS/컨테이너 환경변수에서 읽어옵니다.
3. **프로파일 분리 (`local` / `prod`):** 로컬 개발(`local`)은 H2 인메모리 DB + `ddl-auto: update`로 외부 DB 없이 기동하고, 통합/운영(`prod`)은 PostgreSQL + `ddl-auto: validate`로 PRD-07 `init.sql` 스키마와 엔티티 매핑 일치를 검증합니다. 기본 활성 프로파일은 `local`이며, 컨테이너 실행 시 `SPRING_PROFILES_ACTIVE=prod`를 주입합니다.
4. **호스트·URL 하드코딩 금지 (플랫폼 분리 대비):** DB, 스토리지, 미디어 공개 URL, CORS 허용 오리진, OAuth2 리다이렉트 허용 목록 등 배포 환경에 따라 달라지는 값은 모두 설정값(환경변수)으로 둡니다. 추후 웹·DB·스토리지가 별도 플랫폼으로 분리되어도 코드 수정 없이 환경변수 변경만으로 대응합니다.

### 4.2. `application.yaml` 표준 명세

```yaml
# ============================================================
# 공통 설정 (모든 프로파일)
# ============================================================
server:
  port: 8080
  forward-headers-strategy: framework # Nginx 리버스 프록시 뒤에서 OAuth2 리다이렉트 URI 정상 인식

spring:
  application:
    name: music-streaming-backend
  profiles:
    default: local # 별도 지정이 없으면 로컬(H2) 프로파일로 기동

  servlet:
    multipart:
      enabled: true
      max-file-size: ${MAX_UPLOAD_SIZE:100MB}
      max-request-size: ${MAX_REQUEST_SIZE:110MB} # 음원 파일(100MB) + 커스텀 앨범자켓 이미지(10MB)

  jpa:
    properties:
      hibernate:
        format_sql: true
        jdbc:
          time_zone: Asia/Seoul
    open-in-view: false # OSIV 비활성화로 DB 커넥션 고갈 방지

  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID}         # 민감 정보: 환경변수 필수 주입
            client-secret: ${GOOGLE_CLIENT_SECRET} # 민감 정보: 환경변수 필수 주입
            scope:
              - openid
              - email
              - profile # 우측 상단 프로필 아바타 이미지(picture 클레임) 표시용

# 커스텀 인프라 및 미디어 설정 (@ConfigurationProperties 바인딩 대상)
app:
  jwt: # JwtProperties
    secret-key: ${JWT_SECRET_KEY} # 민감 정보: 최소 256비트(32자 이상) 환경변수 주입
    access-token-expiration-ms: ${JWT_EXPIRATION_MS:86400000} # 기본 24시간
    default-redirect-uri: ${OAUTH2_DEFAULT_REDIRECT_URI:http://localhost/auth/callback} # redirect_uri 미지정 시 기본값
    allowed-redirect-uris: ${OAUTH2_ALLOWED_REDIRECT_URIS:http://localhost/auth/callback,musicapp://oauth2/callback} # 화이트리스트 (쉼표 구분)

  storage: # StorageProperties
    endpoint: ${STORAGE_ENDPOINT:http://rustfs:9000} # presigned URL 서명 Host 기준. Nginx의 STORAGE_HOST_HEADER와 반드시 일치
    region: ${STORAGE_REGION:us-east-1} # presign 시 GetBucketLocation 추가 호출 생략용 고정 리전
    access-key: ${RUSTFS_ACCESS_KEY} # 민감 정보: 환경변수 필수 주입
    secret-key: ${RUSTFS_SECRET_KEY} # 민감 정보: 환경변수 필수 주입
    bucket-name: ${STORAGE_BUCKET_NAME:audio-storage}
    presigned-expiry-seconds: ${STORAGE_PRESIGNED_EXPIRY_SECONDS:60} # 원본 다운로드용 presigned URL 유효 시간

  audio: # AudioProperties
    work-dir: ${AUDIO_WORK_DIR:/tmp/audio-work}
    ffmpeg-path: ${FFMPEG_PATH:/usr/bin/ffmpeg}
    ffmpeg-timeout-minutes: ${FFMPEG_TIMEOUT_MINUTES:3} # PRD-06 §5: 초과 시 강제 종료 후 FAILED
    hls-segment-seconds: 6
    hls-audio-bitrate: 192k
    max-artwork-size-bytes: 10485760 # 커스텀 앨범자켓 최대 10MB

  media: # MediaProperties
    public-base-url: ${MEDIA_PUBLIC_BASE_URL:} # streamUrl/artworkUrl 접두어. 기본값(빈 값) = 단일 오리진 상대경로("/stream/...")

  cors: # CorsProperties
    allowed-origins: ${CORS_ALLOWED_ORIGINS:} # 기본값(빈 값) = 단일 오리진이므로 CORS 비활성. 웹 분리 배포 시 오리진 지정

---
# ============================================================
# local 프로파일: H2 인메모리 DB (PRD-05 §9)
# ============================================================
spring:
  config:
    activate:
      on-profile: local
  datasource:
    url: jdbc:h2:mem:streaming_db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE
    driver-class-name: org.h2.Driver
    username: sa
    password:
  h2:
    console:
      enabled: true
      path: /h2-console
  jpa:
    hibernate:
      ddl-auto: update

---
# ============================================================
# prod 프로파일: PostgreSQL (PRD-07) — 통합 테스트/클러스터 구동
# ============================================================
spring:
  config:
    activate:
      on-profile: prod
  datasource:
    driver-class-name: org.postgresql.Driver
    url: jdbc:postgresql://${DB_HOST:postgres-db}:${DB_PORT:5432}/${DB_NAME:streaming_db}
    username: ${DB_USERNAME:streaming_user}
    password: ${DB_PASSWORD} # 민감 정보: 환경변수 필수 주입
  jpa:
    hibernate:
      ddl-auto: validate # 스키마는 PRD-07 init.sql이 소유. 엔티티 불일치 시 기동 실패
```

> **local 프로파일 주의:** `local`에서도 `app.jwt.secret-key`, `app.storage.*`, Google OAuth2 값은 바인딩되어야 하므로 `.env` 또는 IDE 실행 설정으로 주입합니다. 로컬에서 RustFS 컨테이너를 사용할 경우 `STORAGE_ENDPOINT=http://localhost:9000`(PRD-06 루프백 바인딩 포트)을 지정하며, 이때 presigned URL 서명 Host도 `localhost:9000`이 되므로 Nginx를 경유한 원본 다운로드 검증은 `prod` 컨테이너 구성에서 수행합니다.

### 4.3. 주입 필수 환경변수 목록 (`.env` / `docker-compose.yml` 연동 대상)

| 환경변수명 | 설명 | 민감도 | 예시 값 (로컬 개발용 `.env.example`) |
| :--- | :--- | :---: | :--- |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | PostgreSQL 접속 호스트·포트·DB명 | 일반 | `postgres-db` / `5432` / `streaming_db` |
| `DB_USERNAME` / **`DB_PASSWORD`** | PostgreSQL 계정 및 **비밀번호** | **민감(Secret)** | `streaming_user` / `local_secret_pw_123!` |
| **`GOOGLE_CLIENT_ID`** | Google Cloud Console 발급 OAuth2 클라이언트 ID | **민감(Secret)** | `123456789-abc.apps.googleusercontent.com` |
| **`GOOGLE_CLIENT_SECRET`** | Google Cloud Console 발급 OAuth2 클라이언트 보안 비밀 | **민감(Secret)** | `GOCSPX-xxxxxxxxxxxxxxxx` |
| `STORAGE_ENDPOINT` / `STORAGE_BUCKET_NAME` | RustFS S3 엔드포인트 및 버킷명 | 일반 | `http://rustfs:9000` / `audio-storage` |
| **`RUSTFS_ACCESS_KEY`** / **`RUSTFS_SECRET_KEY`** | RustFS 스토리지 루트/서비스 접근 키 및 **시크릿 키** | **민감(Secret)** | `rustfsadmin` / `rustfs_secret_key_123!` |
| **`JWT_SECRET_KEY`** | 자체 JWT 토큰 서명용 HMAC-SHA256 비밀키 | **민감(Secret)** | `32자_이상의_랜덤_시크릿_문자열_값` |
| `SPRING_PROFILES_ACTIVE` | 활성 프로파일 (`local` / `prod`) | 일반 | 컨테이너: `prod` / 로컬 IDE: 미지정(`local`) |
| `OAUTH2_DEFAULT_REDIRECT_URI` | `redirect_uri` 미지정 시 로그인 성공 후 이동할 기본 클라이언트 콜백 | 일반 | `http://localhost/auth/callback` |
| `OAUTH2_ALLOWED_REDIRECT_URIS` | 로그인 성공 후 JWT를 전달할 수 있는 클라이언트 콜백 화이트리스트 (쉼표 구분, 완전 일치 비교) | 일반 | `http://localhost/auth/callback,musicapp://oauth2/callback` |
| `STORAGE_REGION` / `STORAGE_PRESIGNED_EXPIRY_SECONDS` | presign 서명 리전 및 원본 다운로드 URL 유효 시간(초) | 일반 | `us-east-1` / `60` |
| `FFMPEG_TIMEOUT_MINUTES` | FFmpeg 변환 프로세스 최대 실행 시간(분) | 일반 | `3` |
| `MEDIA_PUBLIC_BASE_URL` | `streamUrl`/`artworkUrl` 응답 접두어 (빈 값이면 상대경로) | 일반 | (빈 값) / 분리 배포 시 `https://media.example.com` |
| `CORS_ALLOWED_ORIGINS` | 백엔드 API CORS 허용 오리진 (쉼표 구분, 빈 값이면 CORS 비활성) | 일반 | (빈 값) / 분리 배포 시 `https://web.example.com` |

## 5. 레이어드 아키텍처 (Layered Architecture) 및 패키지 구조

표준 **Controller ➔ Service ➔ Repository** 단방향 의존 구조를 채택하며, 외부 인프라(RustFS, FFmpeg) 연동 역시 역할별 인터페이스로 추상화하여 **Service 계층 단위 테스트 시 손쉽게 Mock 객체로 대체**할 수 있도록 설계합니다.

```text
com.streaming.music
├── config/                  # 설정 클래스 (SecurityConfig, AsyncConfig, StorageConfig, CorsConfig)
│   └── properties/          # yaml 바인딩 (@ConfigurationProperties: StorageProperties, AudioProperties, JwtProperties, MediaProperties, CorsProperties)
├── security/                # Google OAuth2 & JWT 무상태 인증 모듈
│   ├── CustomOAuth2UserService.java                       # 구글 최소 정보(sub, email, picture) 추출 및 자동 회원 등록(Upsert)
│   ├── HttpCookieOAuth2AuthorizationRequestRepository.java # OAuth2 인가 요청(state)·redirect_uri를 HttpSession 대신 쿠키에 저장 (멀티노드 무상태)
│   ├── OAuth2AuthenticationSuccessHandler.java            # 로그인 성공 시 JWT 발급 및 화이트리스트 검증된 redirect_uri로 리다이렉트
│   ├── OAuth2AuthenticationFailureHandler.java            # 로그인 실패 시 redirect_uri?error={code}로 리다이렉트
│   ├── JwtTokenProvider.java                              # JWT 생성·파싱·검증
│   └── JwtAuthenticationFilter.java                       # 요청 헤더 Bearer 토큰 검증 필터 (무효 토큰은 익명으로 통과)
├── controller/              # 프레젠테이션 계층 (REST API 요청/응답 매핑)
│   ├── TrackController.java
│   └── AuthController.java                  # 현재 로그인 사용자 정보 및 프로필 이미지 조회 (/api/auth/me)
├── dto/                     # 계층 간 데이터 전송 객체
│   ├── request/             # TrackUploadRequest.java (file, title, artist, album, artworkFile)
│   ├── response/            # TrackResponse, TrackPageResponse, StreamPlayResponse, DownloadInfo, UserResponse, ErrorResponse
│   └── event/               # TranscodeEventDto.java ({ trackId, originalS3Key })
├── entity/                  # 도메인/영속성 엔티티 (PRD-07 DDL과 1:1 매핑)
│   ├── User.java            # (userId, provider, providerId, email, profileImageUrl, role, createdAt, lastLoginAt)
│   ├── Track.java           # (trackId, uploader, title, artist, album, durationSeconds, originalFilename, fileExtension,
│   │                        #  fileSizeBytes, mimeType, originalS3Key, artworkS3Key, hlsPlaylistKey, status, errorMessage, createdAt, updatedAt)
│   ├── TrackStatus.java     # ENUM: PENDING, PROCESSING, READY, FAILED
│   ├── PlaybackLog.java     # (logId, track, user, actionType, createdAt)
│   └── ActionType.java      # ENUM: STREAM, DOWNLOAD
├── repository/              # 데이터 접근 계층 (Spring Data JPA)
│   ├── UserRepository.java
│   ├── TrackRepository.java # 최신순 정렬 및 제목·가수 키워드 검색 쿼리, 상태별 일괄 조회 포함
│   └── PlaybackLogRepository.java
├── service/                 # 핵심 비즈니스 로직 계층 (단위 테스트 핵심 대상)
│   ├── UserService.java                 # OAuth2 정보(sub, email, picture) 기반 회원 조회/자동 생성(Upsert)
│   ├── TrackService.java                # 음원 업로드·키워드 검색 조회·재생 정보·다운로드 경로 제어
│   ├── PlaybackLogService.java          # 재생(STREAM)·다운로드(DOWNLOAD) 이력 기록
│   ├── AudioMetadataValidator.java      # Tika 위변조 검증 및 JAudioTagger 파싱/보정
│   ├── StorageService.java              # [인터페이스] RustFS 버킷 초기화, 원본·앨범아트·HLS 재귀 업로드, presigned URL 생성, 객체 삭제
│   ├── MediaUrlResolver.java            # S3 Key ➔ 클라이언트 공개 URL(streamUrl, artworkUrl) 변환 (app.media.public-base-url 적용)
│   ├── TranscodeTaskDispatcher.java     # [인터페이스] 비동기 변환 이벤트 발행 (결합도 분리)
│   ├── FfmpegCommandRunner.java         # [인터페이스] FFmpeg 프로세스 실행 (타임아웃 포함, 테스트 시 Mock)
│   ├── HlsTranscodeService.java         # @Async FFmpeg 실행 및 상태(READY/FAILED) 갱신
│   └── TranscodeRecoveryRunner.java     # 기동 시 미완료(PENDING/PROCESSING) 트랙 정리 (ApplicationRunner)
└── exception/               # 전역 예외 처리 (@RestControllerAdvice, ErrorCode enum, BusinessException 계층)
```

> **엔티티 기준:** 컬럼명·타입·NULL 허용 여부는 PRD-07 §4·§6 DDL이 단일 기준입니다. `prod` 프로파일은 `ddl-auto: validate`로 불일치를 기동 시점에 검출합니다. 시간 컬럼(`TIMESTAMPTZ`)은 `Instant` 또는 `OffsetDateTime`으로 매핑합니다.

## 6. 핵심 도메인 파이프라인 상세 명세

### 6.1. Google OAuth2 기반 무가입(Zero-Signup) 최소 정보 로그인 파이프라인
별도의 회원가입 폼이나 비밀번호 관리 프로세스를 두지 않고, Google OAuth2 인증만으로 즉시 계정이 생성·로그인되는 구조입니다.

1. **개인정보 최소 수집 원칙 (`scope: openid, email, profile`):**
   * 구글로부터 전달받는 속성 중 **고유 식별자(`sub`)**, **이메일(`email`)**, 그리고 웹페이지(`PRD-02`) 우측 상단 버튼 자리에 표시할 **구글 프로필 이미지 URL(`picture`)** 딱 3가지만 추출하며, 실명, 전화번호, 생년월일 등 불필요한 개인정보는 저장하지 않습니다.
2. **자동 계정 생성 및 프로필 갱신 (`CustomOAuth2UserService` & `UserService`):**
   * 사용자가 구글 로그인을 완료하면 `providerId(sub)` 기준으로 `users` 테이블을 조회합니다.
   * 기존 회원이면 최신 `email`, `profileImageUrl`(`picture`), `lastLoginAt`을 갱신하고, 최초 방문자이면 별도 가입 화면 없이 즉시 `User(provider = GOOGLE, providerId = sub, email = email, profileImageUrl = picture, role = ROLE_USER)` 엔티티를 자동 저장(Upsert)합니다.
3. **멀티노드 무상태 OAuth2 인가 요청 저장 (`HttpCookieOAuth2AuthorizationRequestRepository`):**
   * Spring Security 기본 구현(`HttpSessionOAuth2AuthorizationRequestRepository`)은 구글로 리다이렉트하기 전 인가 요청(`state` 포함)을 `HttpSession`에 저장합니다. Nginx가 콜백(`/login/oauth2/code/google`)을 다른 노드로 분산하면 `authorization_request_not_found` 오류로 로그인이 실패하므로, **인가 요청을 직렬화하여 쿠키에 저장**하는 구현으로 교체합니다.
   * 쿠키 규칙: 이름 `oauth2_auth_request` / `oauth2_redirect_uri`, `HttpOnly`, `Path=/`, `Max-Age=180`(초), `SameSite=Lax`(구글 ➔ 백엔드 top-level GET 리다이렉트에서 전송됨). 로그인 성공·실패 처리 직후 두 쿠키를 즉시 삭제합니다.
4. **클라이언트별 JWT 전달 (`redirect_uri` 화이트리스트):**
   * 클라이언트는 로그인 진입 시 `GET /oauth2/authorization/google?redirect_uri={클라이언트 콜백}`을 호출하고, 백엔드는 `redirect_uri`를 `oauth2_redirect_uri` 쿠키에 보관합니다.
     * 웹(`PRD-02`): `redirect_uri=http://localhost/auth/callback`
     * 모바일 앱(`PRD-03`): `redirect_uri=musicapp://oauth2/callback`
     * 쿼리 값은 URL 인코딩하여 전달합니다. 웹 콜백 경로를 `/oauth2/...`가 아닌 `/auth/callback`으로 두는 이유는 Nginx(PRD-04)가 `/oauth2/*`를 백엔드로 라우팅하므로 SPA가 해당 경로를 받을 수 없기 때문입니다.
   * 로그인 성공 시(`OAuth2AuthenticationSuccessHandler`) 쿠키의 `redirect_uri`를 `app.jwt.allowed-redirect-uris`와 **완전 일치(scheme·host·port·path)**로 대조합니다. 일치하면 `{redirect_uri}?token={jwt}`로, `redirect_uri`가 없으면 `app.jwt.default-redirect-uri?token={jwt}`로 `302` 리다이렉트합니다.
   * 화이트리스트에 없는 `redirect_uri`는 토큰 탈취(Open Redirect) 방지를 위해 **토큰을 붙이지 않고** `app.jwt.default-redirect-uri?error=invalid_redirect_uri`로 리다이렉트합니다. 로그인 실패(`OAuth2AuthenticationFailureHandler`) 시에도 `?error={errorCode}`로 리다이렉트합니다.
5. **무상태(Stateless) JWT 발급 및 사용:**
   * `web-server-1`, `web-server-2` 멀티서버 환경에서 세션을 공유하지 않도록, 로그인 성공 시 `sub = userId`, `role` 클레임을 담은 **JWT Access Token**(HMAC-SHA256, 기본 24시간)을 발급합니다.
   * 클라이언트는 이후 모든 API 요청에 `Authorization: Bearer {jwt}` 헤더를 붙입니다. 로그아웃은 서버 API 없이 **클라이언트가 보관 중인 토큰을 폐기**하는 방식입니다(MVP는 Refresh Token·토큰 블랙리스트 미도입).

### 6.2. 업로드 · 위변조 스캔 · 태그/앨범아트 추출 및 수정 저장 동기 파이프라인 (`TrackService`)
사용자가 웹페이지(`PRD-02`) 업로드 모달에서 음원과 수정된 메타데이터를 전송하면 아래 5단계를 동기(Synchronous)로 빠르게 처리합니다.

1. **로컬 임시 파일 생성:** 업로드된 음원 `MultipartFile`을 `${app.audio.work-dir}/{track_uuid}_raw.{ext}` 경로에 임시 저장합니다.
2. **Apache Tika 위변조 스캔 (`AudioMetadataValidator`):**
   * 파일 확장자(`.mp3`, `.wav` 등)만 바꾼 악성 파일을 차단하기 위해 `tika.detect(tempFile)`로 파일 헤더의 실제 Magic Number 기반 MIME 타입을 추출합니다.
   * 허용된 오디오 MIME 타입(`audio/mpeg`, `audio/wav`, `audio/x-wav`, `audio/vnd.wave`, `audio/flac`, `audio/x-flac`, `audio/mp4`)이 아닐 경우 임시 파일을 즉시 삭제하고 `InvalidAudioFormatException`(`400 Bad Request`)을 발생시킵니다. (`tika-core`는 FLAC을 `audio/x-flac`으로 감지하므로 두 값을 모두 허용합니다.)
   * **확장자 정규화:** 저장 확장자(`file_extension`, S3 Key의 `{ext}`)는 사용자 파일명이 아니라 **Tika 감지 MIME 기준**으로 결정합니다(`audio/mpeg ➔ mp3`, `audio/*wav*·vnd.wave ➔ wav`, `audio/*flac ➔ flac`, `audio/mp4 ➔ m4a`). 원래 파일명은 `original_filename`에 그대로 보존합니다.
3. **JAudioTagger 메타데이터 및 앨범 커버 결정 (옵션 3-B & 3-가 + 사용자 수정 반영):**
   * `AudioFileIO.read(tempFile)`를 통해 오디오 헤더(`AudioHeader`)에서 **재생 시간(`durationSeconds`)**을 필수로 추출합니다. PRD-07의 `CHECK (duration_seconds > 0)` 제약을 위해 1초 미만 음원은 **1초로 보정**하며, 헤더 파싱 자체가 실패하면 `InvalidAudioFormatException`(`400`)으로 거부합니다.
   * **텍스트 태그 우선순위 및 Fallback 보정 (옵션 3-가):**
     * **제목(`title`):** ① 업로드 모달에서 사용자가 확인·수정한 `request.title` ➔ ② ID3 태그 타이틀(`FieldKey.TITLE`) ➔ ③ 둘 다 비어 있으면 **원본 파일명(확장자 제외)**으로 자동 보정합니다.
     * **가수(`artist`):** ① 업로드 모달에서 사용자가 확인·수정한 `request.artist` ➔ ② ID3 태그 아티스트(`FieldKey.ARTIST`) ➔ ③ 둘 다 비어 있으면 **`"Unknown Artist"`**로 자동 보정합니다.
     * **제목 최후 보정:** 제목은 request ➔ 태그 ➔ 원본 파일명(확장자 제외) 순이며, 원본 파일명까지 비어 있는 극단적인 경우에는 DB `NOT NULL` 제약을 지키기 위해 **`"Unknown Title"`**로 보정합니다. 공백 문자열은 모두 "비어 있음"으로 취급합니다.
     * **앨범(`album`):** ① `request.album` ➔ ② ID3 태그 앨범(`FieldKey.ALBUM`) ➔ ③ 없으면 `null`로 저장합니다.
   * **앨범 커버 이미지 결정 및 분리 저장 (옵션 3-B + 커스텀 이미지 지원):**
     * **1순위 (사용자 직접 첨부 이미지):** 업로드 모달에서 사용자가 앨범자켓을 직접 변경하여 `artworkFile`(`MultipartFile`)을 함께 전송한 경우, **Tika로 실제 MIME이 `image/jpeg` 또는 `image/png`인지, 크기가 `app.audio.max-artwork-size-bytes`(10MB) 이하인지 검증**한 뒤(위반 시 `InvalidArtworkException`, `400`) RustFS `audio-storage/artwork/{track_uuid}.jpg`에 저장합니다.
     * **2순위 (음원 내장 이미지):** `artworkFile`이 없고 음원 내부에 `tag.getFirstArtwork()`가 존재하면 내장 이미지 바이트 배열(`getBinaryData()`)을 추출하여 RustFS `audio-storage/artwork/{track_uuid}.jpg`에 저장합니다. (내장 이미지는 사용자 입력이 아니므로 Tika 검증에서 JPEG/PNG가 아니거나 손상된 것으로 판정되면 `InvalidArtworkException`으로 업로드를 거부하지 않고 **내장 커버를 무시(`null`)한 채 업로드를 계속**합니다.)
     * **3순위:** 둘 다 없는 경우 `artworkS3Key = null`로 저장합니다.
     * **키·Content-Type 규칙:** 클라이언트 URL 규칙(`/artwork/{track_uuid}.jpg`)을 고정하기 위해 객체 키 확장자는 항상 `.jpg`로 두되, `Content-Type`은 **실제 감지된 MIME(`image/jpeg` / `image/png`)**으로 지정하여 브라우저가 올바르게 렌더링하도록 합니다(PRD-06 §2.1).
4. **원본 음원 RustFS 업로드 및 DB `PENDING` 저장:**
   * 검증된 원본 임시 파일을 RustFS의 `audio-storage/original/{yyyy}/{mm}/{track_uuid}.{ext}` 경로에 Tika 감지 MIME을 `Content-Type`으로 지정하여 업로드합니다.
   * DB `tracks` 테이블에 상태값 `status = PENDING`으로 엔티티를 저장합니다. 업로더는 JWT 인증 사용자가 있으면 `uploader_id`로 연결하고, 비로그인 업로드면 `NULL`로 둡니다(PRD-07 §3).
   * **보상 처리:** DB 저장이 실패하면 이미 업로드한 원본·앨범아트 객체를 `StorageService.deleteObject()`로 삭제한 뒤 예외를 전파합니다.
   * 성공·실패와 관계없이 `finally` 블록에서 로컬 원본 임시 파일을 삭제합니다.
5. **비동기 HLS 변환 이벤트 발행 (`PRD-01` 결합도 분리 규칙 적용):**
   * 메모리 파일 객체나 로컬 경로를 넘기지 않고, 오직 **`TranscodeEventDto(trackId, originalS3Key)`**만 `TranscodeTaskDispatcher.dispatch(dto)` 인터페이스로 발행한 뒤 클라이언트에게 `202 Accepted` 응답을 즉시 반환합니다.
   * 기본 구현(`ApplicationEventTranscodeTaskDispatcher`)은 `ApplicationEventPublisher.publishEvent(dto)`를 **트랜잭션 내부에서** 호출하고, 수신부는 커밋 이후에 실행되도록 합니다(§6.3-2).

### 6.3. 백엔드 내부 비동기 HLS 청크 변환 파이프라인 (`HlsTranscodeService`)
1. **전용 스레드 풀 격리 (`AsyncConfig`):**
   * `ThreadPoolTaskExecutor`를 `transcodeExecutor` 빈으로 등록하며, FFmpeg가 백엔드 CPU를 독점하지 못하도록 `corePoolSize = 1`, `maxPoolSize = 2`, `queueCapacity = 50`으로 동시 변환 수를 엄격히 제한합니다.
   * **큐 포화 시 처리:** 큐가 가득 차 `TaskRejectedException`이 발생하면 해당 트랙을 `FAILED`(`error_message = "TRANSCODE_QUEUE_FULL"`)로 기록합니다. 업로드 응답(`202`)은 이미 반환되었으므로 클라이언트는 상태 조회로 실패를 확인합니다.
2. **커밋 이후 수신 및 상태 전환:**
   * 수신부는 **`@Async("transcodeExecutor") @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`**로 선언합니다. 일반 `@EventListener`는 업로드 트랜잭션 커밋 전에 실행될 수 있어 비동기 스레드가 아직 저장되지 않은 Track을 조회하지 못하는 경합이 발생하므로 사용하지 않습니다.
   * `TranscodeEventDto`를 수신하면 DB 상태를 `PROCESSING`으로 변경하고, RustFS에서 `originalS3Key` 파일을 `${app.audio.work-dir}/{track_uuid}/input.{ext}`로 내려받습니다.
3. **FFmpeg 6초 단위 단일 음질(AAC 192kbps) 청크 분할 (`ProcessBuilder`):**
   * 출력 폴더 `${app.audio.work-dir}/{track_uuid}/hls/`를 생성하고 아래 표준 FFmpeg 명령어를 실행합니다.
   ```bash
   ffmpeg -y -i /tmp/audio-work/{track_uuid}/input.{ext} \
     -vn -c:a aac -b:a 192k -ar 44100 -ac 2 \
     -f hls -hls_time 6 -hls_list_size 0 \
     -hls_segment_filename "/tmp/audio-work/{track_uuid}/hls/chunk_%03d.ts" \
     "/tmp/audio-work/{track_uuid}/hls/index.m3u8"
   ```
   * **실행 타임아웃 (PRD-06 §5-3):** `FfmpegCommandRunner`는 `process.waitFor(app.audio.ffmpeg-timeout-minutes, TimeUnit.MINUTES)`로 대기하고, 초과 시 `process.destroyForcibly()`로 강제 종료한 뒤 `FAILED`(`error_message = "FFMPEG_TIMEOUT"`)로 처리합니다. 표준 출력/에러 스트림은 버퍼 막힘(Deadlock) 방지를 위해 `redirectErrorStream(true)` 후 별도로 소비하며, 실패 시 마지막 일부 로그를 `error_message`(최대 500자)에 요약 기록합니다.
4. **폴더 전체 재귀 업로드 (다중 음질 확장 대비 설계) 및 클린업:**
   * 변환 프로세스 종료 코드(`exitCode == 0`) 확인 후, 출력 디렉터리 내부의 모든 파일을 `Files.walk()`로 순회하며 RustFS `audio-storage/hls/{track_uuid}/` 하위에 그대로 업로드합니다(`StorageService.uploadDirectoryRecursively()`).
   * 업로드 시 확장자별 `Content-Type`을 지정합니다: `.m3u8 ➔ application/vnd.apple.mpegurl`, `.ts ➔ video/mp2t` (PRD-06 §2.1).
   * 업로드 완료 시 DB 상태를 `READY`로, `hls_playlist_key`를 **S3 객체 키 `hls/{track_uuid}/index.m3u8`**로 갱신합니다. 클라이언트용 `streamUrl`(`{public-base-url}/stream/{track_uuid}/index.m3u8`)은 DB에 저장하지 않고 응답 시점에 `MediaUrlResolver`가 변환합니다. (실패 시 `FAILED` 및 `error_message` 기록)
   * `finally` 블록에서 성공·실패 여부와 관계없이 `${app.audio.work-dir}/{track_uuid}/` 로컬 임시 디렉터리 전체를 즉시 삭제합니다.
5. **기동 시 미완료 트랙 정리 (`TranscodeRecoveryRunner`):**
   * 변환 대기 큐는 인메모리이므로 서버 재시작 시 유실됩니다. 애플리케이션 기동 시 `ApplicationRunner`가 `PENDING`/`PROCESSING` 상태로 남은 트랙을 `FAILED`(`error_message = "INTERRUPTED_BY_RESTART"`)로 일괄 갱신합니다.
   * **다중 노드 주의:** `web-server-1`이 재시작할 때 `web-server-2`에서 정상 변환 중인 트랙까지 `FAILED` 처리하지 않도록, `updated_at`이 `ffmpeg-timeout-minutes × 2` 이상 경과한 트랙만 대상으로 합니다. 자동 재발행(재변환)은 MVP 범위에서 제외합니다.

## 7. REST API 엔드포인트 명세 및 Spring Security 설계

### 7.1. 핵심 API 명세

| 기능 | HTTP Method & URI | 요청 데이터 | 응답 및 동작 방식 |
| :--- | :--- | :--- | :--- |
| **Google OAuth2 로그인 진입** | `GET /oauth2/authorization/google` | Query: `redirect_uri` (선택, 화이트리스트 대상 클라이언트 콜백) | `302 Redirect`<br>구글 로그인 화면으로 이동 ➔ 인증 완료 후 자체 JWT 발급 ➔ `{redirect_uri}?token={jwt}`로 리다이렉트 (실패 시 `?error={code}`, §6.1) |
| **로그인 사용자 정보 조회** | `GET /api/auth/me` | Header: `Authorization: Bearer {jwt}` | `200 OK`<br>`{ userId, email, profileImageUrl, role }` 반환 (`PRD-02` 우측 상단 프로필 아바타 렌더링용)<br>토큰 없음/무효 시 `401 Unauthorized` (`UNAUTHORIZED`) |
| **음원 단건 업로드** | `POST /api/tracks/upload` | `multipart/form-data`<br>- `file` (필수, MP3/WAV/FLAC/M4A)<br>- `title` (선택), `artist` (선택), `album` (선택)<br>- `artworkFile` (선택, JPEG/PNG, 10MB 이하) | `202 Accepted`<br>Tika 검증 + 태그/앨범아트 저장 + 원본 RustFS 저장 후 `TrackResponse`(`status: "PENDING"`) 반환 |
| **음원 목록 및 키워드 검색 조회** | `GET /api/tracks` | Query:<br>- `keyword` (선택: 제목 또는 가수 검색어)<br>- `page`(기본 0), `size`(기본 20, 최대 100), `status`(선택) | `200 OK`<br>**업로드 날짜 내림차순(`createdAt DESC`) 정렬.** `keyword` 입력 시 제목(`title`) 또는 가수(`artist`)에 해당 단어가 포함(대소문자 무시)된 트랙 리스트를 `TrackPageResponse`로 반환 |
| **음원 단건 상세/상태 조회** | `GET /api/tracks/{trackId}` | Path: `trackId` (UUID) | `200 OK` `TrackResponse`<br>업로드 직후 변환 완료(`READY`) 여부 확인(클라이언트 폴링) 및 상세 정보 조회. 없으면 `404` |
| **실시간 스트리밍 진입점 조회** | `GET /api/tracks/{trackId}/play` | Path: `trackId` (UUID) | `200 OK`<br>`status == READY` 검증 후 `{ trackId, streamUrl, title, artist, durationSeconds, artworkUrl }` 반환 + `playback_logs`에 `STREAM` 기록<br>미완료 시 `409 Conflict` (`TRACK_NOT_READY`), 없으면 `404` |
| **원본 파일 다운로드** | `GET /api/tracks/{trackId}/download` | Path: `trackId` (UUID) | `200 OK` (Body 없음, 헤더 반환) + `playback_logs`에 `DOWNLOAD` 기록<br>- `X-Accel-Redirect: /internal-storage/original/{yyyy}/{mm}/{track_uuid}.{ext}?X-Amz-Algorithm=...&X-Amz-Signature=...` (presigned 경로+쿼리)<br>- `Content-Disposition: attachment; filename="{ASCII 대체 파일명}"; filename*=UTF-8''{퍼센트 인코딩 원본파일명}` (RFC 5987/6266)<br>- `Content-Type: {mime_type}`<br>원본은 `PENDING` 상태부터 존재하므로 상태와 무관하게 다운로드 허용. 없으면 `404` |

#### 7.1.1. 응답 DTO 표준

* **`TrackResponse`**: `{ trackId, title, artist, album, durationSeconds, status, artworkUrl, originalFilename, fileExtension, fileSizeBytes, createdAt }`
  * `artworkUrl`: `artwork_s3_key`가 있으면 `{public-base-url}/artwork/{track_uuid}.jpg`, 없으면 `null` (클라이언트 기본 썸네일 표시)
  * `errorMessage`는 내부 진단용이므로 응답에 포함하지 않으며, `status == FAILED`만 노출합니다.
* **`TrackPageResponse`**: `{ content: TrackResponse[], page, size, totalElements, totalPages, hasNext }` (Spring `Page` 객체를 그대로 직렬화하지 않고 고정 스키마로 변환)
* **`StreamPlayResponse`**: `{ trackId, streamUrl, title, artist, durationSeconds, artworkUrl }`
  * `streamUrl`: `{public-base-url}/stream/{track_uuid}/index.m3u8`. `public-base-url`이 빈 값이면 `/stream/...` 상대경로입니다.
* **`UserResponse`**: `{ userId, email, profileImageUrl, role }`

#### 7.1.2. 원본 다운로드 presigned 처리 규칙

1. `StorageService.createPresignedGetUrl(originalS3Key, expirySeconds)`로 RustFS presigned GET URL(`http://rustfs:9000/audio-storage/original/...?X-Amz-...`)을 생성합니다. MinIO SDK는 서명 계산만 수행하며 네트워크 호출이 없도록 `region`을 고정 지정합니다.
2. URL에서 `/{bucket}/` 이후의 경로와 쿼리 문자열을 그대로 잘라 `X-Accel-Redirect: /internal-storage/{경로}?{쿼리}`로 반환합니다. 쿼리 문자열은 재인코딩하지 않습니다(서명 불일치 방지).
3. Nginx(PRD-04)는 `/internal-storage/` ➔ `rustfs:9000/audio-storage/`로 프록시하며 `Host`를 `app.storage.endpoint`의 호스트와 동일하게 설정합니다. RustFS는 서명을 검증한 뒤 원본을 반환합니다(익명 읽기 비허용 경로, PRD-06 §3).

#### 7.1.3. 재생·다운로드 이력 기록 (`PlaybackLogService`)

* `/play` 성공 응답 시 `ActionType.STREAM`, `/download` 성공 응답 시 `ActionType.DOWNLOAD`로 `playback_logs`에 1건을 저장합니다.
* `user_id`는 JWT 인증 사용자가 있으면 설정하고, 비로그인 요청이면 `NULL`입니다(PRD-07 §4.3).
* 이력 저장 실패가 재생·다운로드 응답을 막지 않도록 예외는 로그만 남기고 삼킵니다.

### 7.2. 공통 에러 응답 규격 (`@RestControllerAdvice`)

모든 API 오류는 아래 단일 JSON 스키마로 응답합니다.

```json
{ "code": "TRACK_NOT_READY", "message": "음원 변환이 아직 완료되지 않았습니다.", "timestamp": "2026-11-11T10:00:00+09:00" }
```

| HTTP 상태 | `code` | 발생 조건 |
| :---: | :--- | :--- |
| `400` | `INVALID_AUDIO_FORMAT` | Tika 감지 MIME이 허용 오디오 목록에 없거나 오디오 헤더 파싱 실패 |
| `400` | `INVALID_ARTWORK` | 커스텀 앨범자켓이 JPEG/PNG가 아니거나 10MB 초과 |
| `400` | `INVALID_REQUEST` | 필수 파라미터 누락, `@Valid` 검증 실패, 잘못된 UUID 형식 |
| `401` | `UNAUTHORIZED` | 인증이 필요한 엔드포인트(`/api/auth/me`)에 토큰 없음/무효 |
| `404` | `TRACK_NOT_FOUND` | 존재하지 않는 `trackId` |
| `409` | `TRACK_NOT_READY` | `status != READY`인 트랙의 재생 진입점 조회 |
| `413` | `PAYLOAD_TOO_LARGE` | 멀티파트 용량 초과(`MaxUploadSizeExceededException`) |
| `500` | `INTERNAL_ERROR` | 스토리지 업로드 실패 등 기타 서버 오류 (내부 상세는 응답에 노출하지 않고 로그로만 기록) |

> **표 외 표준 MVC 예외 처리:** `NoResourceFoundException`(없는 경로), `HttpRequestMethodNotSupportedException` 등 위 표에 없는 Spring MVC 표준 예외는 원래 HTTP 상태를 유지하고, 새 에러 코드를 추가하지 않고 4xx는 `INVALID_REQUEST`, 5xx는 `INTERNAL_ERROR` 코드로 응답합니다. 그 외 처리되지 않은 예외는 `500 INTERNAL_ERROR`입니다.

### 7.3. Spring Security 설정 (`MVP permitAll` + Google OAuth2 연동 구조)
초기 MVP에서는 음원 관련 모든 API를 `permitAll()`로 열어두되, Google OAuth2 로그인 파이프라인과 JWT 필터를 함께 구성해 두어 추후 엔드포인트별 권한 제한(`.authenticated()`)을 즉시 켤 수 있도록 설계합니다.

* **JWT 필터 동작:** `Authorization: Bearer` 토큰이 유효하면 `SecurityContext`에 인증 정보를 설정하고, 토큰이 없거나 무효이면 **요청을 차단하지 않고 익명으로 통과**시킵니다(MVP `permitAll`). 인증 필수 여부는 오직 `authorizeHttpRequests` 설정이 결정합니다.
* **`/api/auth/me`:** 사용자 정보가 필요한 유일한 엔드포인트이므로 MVP부터 `.authenticated()`로 두고, 미인증 시 `AuthenticationEntryPoint`가 `401` 공통 에러 응답(§7.2)을 반환합니다(OAuth2 로그인 페이지로 리다이렉트하지 않음).
* **CORS:** `app.cors.allowed-origins`가 비어 있으면(단일 오리진) CORS를 적용하지 않고, 값이 있으면 해당 오리진에 한해 `GET, POST, OPTIONS` 및 `Authorization`, `Content-Type` 헤더를 허용합니다. JWT는 쿠키가 아닌 헤더로 전달하므로 `allowCredentials`는 `false`입니다.

```java
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final HttpCookieOAuth2AuthorizationRequestRepository cookieAuthorizationRequestRepository;
    private final OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    private final OAuth2AuthenticationFailureHandler oAuth2FailureHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint; // 401 공통 에러 JSON 응답
    private final CorsConfigurationSource corsConfigurationSource;           // app.cors 기반

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(restAuthenticationEntryPoint))
            .authorizeHttpRequests(auth -> auth
                // 로그인 사용자 정보는 인증 필수
                .requestMatchers("/api/auth/me").authenticated()
                // [MVP 정책 4] 초기에는 음원 업로드/조회/재생/다운로드 전체 허용 (permitAll)
                // 추후 권한 제어 시 아래 경로를 .authenticated() 또는 .hasRole("USER")로 전환
                .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                .requestMatchers("/api/tracks/*/download").permitAll()
                .requestMatchers("/api/tracks/*/play").permitAll()
                .requestMatchers("/api/tracks/**").permitAll()
                .anyRequest().permitAll()
            )
            .oauth2Login(oauth2 -> oauth2
                // 멀티노드 무상태: 인가 요청(state)을 HttpSession 대신 쿠키에 저장 (§6.1-3)
                .authorizationEndpoint(endpoint -> endpoint
                    .authorizationRequestRepository(cookieAuthorizationRequestRepository))
                .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                .successHandler(oAuth2SuccessHandler)
                .failureHandler(oAuth2FailureHandler)
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
```

> **Google Cloud Console 등록 값:** 승인된 리디렉션 URI는 `http://localhost/login/oauth2/code/google`(Nginx 80 경유)입니다. `forward-headers-strategy: framework`와 Nginx의 `Host`/`X-Forwarded-*` 헤더(PRD-04) 전달로 백엔드가 이 주소를 올바르게 계산합니다.

## 8. Service 계층 기능별 단위 테스트 (Unit Test) 검증 전략

각 기능 개발 직후 **외부 인프라(PostgreSQL, RustFS, 실제 FFmpeg 프로세스, Google 인증 서버)를 띄우지 않고도 1초 이내에 비즈니스 로직의 정합성을 검증**할 수 있도록 `@ExtendWith(MockitoExtension.class)` 기반의 단위 테스트를 필수로 작성합니다.

| 검증 대상 클래스 | 필수 단위 테스트 케이스 (Test Scenarios) | 검증 방법 (`Mockito` & `AssertJ`) |
| :--- | :--- | :--- |
| **`UserServiceTest`** | 1. 최초 Google OAuth2 로그인(`sub`, `email`, `picture`) 시 신규 `User` 엔티티와 `profileImageUrl`이 정상 저장되는지 검증<br>2. 이미 존재하는 `providerId(sub)`로 로그인 시 중복 가입 없이 기존 회원 정보 및 최신 프로필 이미지가 갱신·반환되는지 검증 | `UserRepository`를 `@Mock`으로 주입하여 `findByProviderId()` 유무에 따른 분기 및 저장 동작 검증 |
| **`AudioMetadataValidatorTest`** | 1. 정상 MP3/WAV 바이트 스트림 입력 시 통과 검증<br>2. 확장자만 `.mp3`로 바꾼 텍스트/실행 파일 입력 시 `InvalidAudioFormatException` 발생 검증<br>3. ID3 태그가 있는 MP3에서 제목·가수·재생시간·앨범 커버 바이트 정상 추출 검증<br>4. 태그가 비어 있는 WAV 파일 입력 시 `제목 = 파일명`, `가수 = "Unknown Artist"` 자동 보정(Fallback) 검증 | 테스트 리소스(`src/test/resources/samples/`)의 초소형 샘플 파일(1초 분량 정상 MP3/WAV 및 가짜 텍스트 파일)을 사용해 실제 Tika·JAudioTagger 동작 검증 |
| **`TrackServiceTest`** | 1. 업로드 시 사용자 커스텀 `artworkFile`이 있으면 최우선 저장하고, 없으면 내장 앨범 커버를 저장하며, 둘 다 없으면 `artworkS3Key = null`로 저장하는지 검증<br>2. 목록 조회 시 `keyword`가 없으면 전체 최신순(`createdAt DESC`), `keyword`가 있으면 제목 또는 가수 일치 항목만 최신순으로 반환하는지 검증<br>3. 아직 `PROCESSING` 상태인 트랙에 대해 `getPlayInfo()` 호출 시 예외(`TrackNotReadyException`, `409 TRACK_NOT_READY`) 발생 검증<br>4. `getDownloadInfo()` 호출 시 원본 S3 Key와 매칭되는 정확한 `X-Accel-Redirect` 내부 경로 반환 검증 | `TrackRepository`, `StorageService`, `TranscodeTaskDispatcher`, `AudioMetadataValidator`를 `@Mock`으로 주입하고 `verify()` 및 `assertThat()`으로 검증 |
| **`HlsTranscodeServiceTest`** | 1. 변환 성공 시 임시 폴더 내 생성된 `.m3u8`/`.ts` 파일들이 `StorageService.uploadDirectoryRecursively()`를 통해 업로드되고 트랙 상태가 `READY`, `hlsPlaylistKey = "hls/{uuid}/index.m3u8"`으로 변경되는지 검증<br>2. FFmpeg 실행 중 예외 또는 비정상 종료(`exitCode != 0`) 발생 시 트랙 상태가 `FAILED`로 변경되고, 임시 폴더가 반드시 삭제(Clean-up)되는지 검증<br>3. FFmpeg 타임아웃 발생 시 `FAILED`, `errorMessage = "FFMPEG_TIMEOUT"` 기록 검증 | FFmpeg 실행기(`FfmpegCommandRunner` 인터페이스)와 `StorageService`, `TrackRepository`를 `@Mock` 처리하여 성공/실패 시나리오 상태 전이 및 임시 파일 삭제 여부 검증 |
| **`TrackServiceTest` (추가)** | 5. `getDownloadInfo()`가 presigned URL에서 `/internal-storage/original/...?{서명 쿼리}`를 쿼리 재인코딩 없이 생성하고, `Content-Disposition`에 `filename*=UTF-8''` 인코딩 한글 파일명이 포함되는지 검증<br>6. DB 저장 실패 시 업로드된 원본·앨범아트 객체 삭제(보상 처리) 호출 검증<br>7. `/play`·`/download` 성공 시 `PlaybackLogService`가 `STREAM`/`DOWNLOAD`로 1회 호출되는지 검증 | `StorageService.createPresignedGetUrl()` 반환값을 스텁하고 `verify()`로 호출 검증 |
| **`OAuth2AuthenticationSuccessHandlerTest`** | 1. 화이트리스트에 있는 `redirect_uri`(웹·앱) 쿠키 존재 시 `{redirect_uri}?token={jwt}`로 리다이렉트 검증<br>2. 화이트리스트에 없는 `redirect_uri`는 토큰 없이 `default-redirect-uri?error=invalid_redirect_uri`로 리다이렉트 검증<br>3. 처리 후 인가 요청·redirect 쿠키 삭제 검증 | `MockHttpServletRequest/Response`와 `JwtTokenProvider` `@Mock`으로 `Location` 헤더·쿠키 검증 |
| **`MediaUrlResolverTest`** | 1. `public-base-url`이 빈 값이면 `/stream/{uuid}/index.m3u8`, 값이 있으면 `{base}/stream/...` 반환 검증<br>2. `artworkS3Key == null`이면 `artworkUrl == null` 검증 | 순수 단위 테스트 |

## 9. 로컬 작업 시 DB 구축

로컬 환경에서 작업 시 PostgreSQL 대신 H2 인메모리 DB를 사용한다(`local` 프로파일, §4.2).

* H2는 `MODE=PostgreSQL`로 기동하며, 스키마는 JPA `ddl-auto: update`가 엔티티 기준으로 생성한다.
* 실제 스키마 정합성(컬럼 타입·CHECK 제약 등)은 `prod` 프로파일(PostgreSQL + PRD-07 `init.sql` + `ddl-auto: validate`) 통합 구동에서 검증한다.
* H2 콘솔: `http://localhost:8080/h2-console` (JDBC URL: `jdbc:h2:mem:streaming_db`)
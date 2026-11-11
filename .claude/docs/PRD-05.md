# [PRD-05] 백엔드 서버 (Backend Cluster) 상세 설계 (v1.3 - RustFS 연동 확정안)

## 1. 개요 및 개발 목표
본 문서는 음원 스트리밍 및 원본 아카이빙 서비스 MVP의 핵심 비즈니스 로직과 미디어 전처리를 담당하는 **백엔드 서버 클러스터(`web-server-1`, `web-server-2`)**의 상세 개발 명세를 정의합니다.

전체 아키텍처 문서(`PRD-01`), 웹페이지(`PRD-02`), 음원 서버(`PRD-06`)에서 확정된 원칙에 따라 **도커 리눅스 컨테이너 기반의 초기 개발 인프라 구축**, **대중성과 사용 편의성을 최우선으로 고려한 라이브러리 선정**, **S3 호환 오픈소스 스토리지(`RustFS`) 무수정 연동**, **Google OAuth2 기반 무가입 로그인(프로필 이미지 연동 포함)**, **`application.yml` 및 환경변수 기반 민감 정보 분리 설계**, **표준 레이어드 아키텍처(`Controller ➔ Service ➔ Repository`)**, 그리고 **기능별 Service 계층 단위 테스트(Unit Test) 검증 체계**를 구체화합니다.

## 2. 도커 리눅스 컨테이너 개발 인프라 및 환경 구축

개발 단계에서는 리눅스 컨테이너 내부에 직접 접속하여 빠른 빌드·테스트 피드백 루프를 수행(**방식 1**)하고, 통합 테스트 및 클러스터 구동 시에는 동일한 환경이 코드화된 `Dockerfile`로 다중 인스턴스(`web1`, `web2`)를 복제 구동(**방식 2**)합니다.

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

### 2.2. 멀티서버(`web1`, `web2`) 클러스터 구동용 `Dockerfile` 명세 (방식 2)
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
    // 1. Base Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // 2. Stateless JWT (JJWT)
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // 3. Database (PostgreSQL)
    runtimeOnly("org.postgresql:postgresql")

    // 4. S3 Object Storage Client (RustFS 100% 호환)
    implementation("io.minio:minio:8.5.14")

    // 5. Audio Analysis & Security (Apache Tika Core & JAudioTagger)
    implementation("org.apache.tika:tika-core:2.9.2")
    implementation("net.jthink:jaudiotagger:3.0.1")

    // 6. Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // 7. Unit Testing (JUnit 5, Mockito, AssertJ 내장)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

## 4. `application.yml` 설정 구조 및 환경변수(민감 정보) 분리 설계

### 4.1. 설정 관리 원칙
1. **모든 설정의 `application.yml` 일원화:** DB 커넥션, 멀티파트 용량, Google OAuth2, RustFS 스토리지 접속 정보, FFmpeg 변환 옵션 등 애플리케이션 동작에 필요한 모든 설정값은 `src/main/resources/application.yml`에 정의하고, 자바 코드에서는 `@ConfigurationProperties`(또는 `@Value`)로 바인딩하여 사용합니다.
2. **민감 정보(Secret) 환경변수 주입 강제:** DB 패스워드, Google OAuth2 Client ID/Secret, RustFS Secret Key, JWT 서명 키 등 보안상 민감한 정보는 Git 저장소에 노출되지 않도록 `${환경변수명}` 문법으로 OS/컨테이너 환경변수에서 읽어옵니다.

### 4.2. `application.yml` 표준 명세

```yaml
server:
  port: 8080
  forward-headers-strategy: framework # Nginx 리버스 프록시 뒤에서 OAuth2 리다이렉트 URI 정상 인식

spring:
  application:
    name: music-streaming-backend

  servlet:
    multipart:
      enabled: true
      max-file-size: ${MAX_UPLOAD_SIZE:100MB}
      max-request-size: ${MAX_REQUEST_SIZE:110MB} # 음원 파일(100MB) + 커스텀 앨범자켓 이미지(10MB)

  datasource:
    driver-class-name: org.postgresql.Driver
    url: jdbc:postgresql://${DB_HOST:postgres-db}:${DB_PORT:5432}/${DB_NAME:streaming_db}
    username: ${DB_USERNAME:streaming_user}
    password: ${DB_PASSWORD} # 민감 정보: 환경변수 필수 주입

  jpa:
    hibernate:
      ddl-auto: ${JPA_DDL_AUTO:validate} # 로컬 개발 시 update, 운영/통합 시 validate
    properties:
      hibernate:
        format_sql: true
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
  jwt:
    secret-key: ${JWT_SECRET_KEY} # 민감 정보: 최소 256비트(32자 이상) 환경변수 주입
    access-token-expiration-ms: ${JWT_EXPIRATION_MS:86400000} # 기본 24시간
    oauth2-redirect-uri: ${OAUTH2_SUCCESS_REDIRECT_URI:http://localhost/}

  storage:
    endpoint: ${STORAGE_ENDPOINT:http://rustfs:9000}
    access-key: ${RUSTFS_ACCESS_KEY} # 민감 정보: 환경변수 필수 주입
    secret-key: ${RUSTFS_SECRET_KEY} # 민감 정보: 환경변수 필수 주입
    bucket-name: ${STORAGE_BUCKET_NAME:audio-storage}

  audio:
    work-dir: ${AUDIO_WORK_DIR:/tmp/audio-work}
    ffmpeg-path: ${FFMPEG_PATH:/usr/bin/ffmpeg}
    hls-segment-seconds: 6
    hls-audio-bitrate: 192k
```

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

## 5. 레이어드 아키텍처 (Layered Architecture) 및 패키지 구조

표준 **Controller ➔ Service ➔ Repository** 단방향 의존 구조를 채택하며, 외부 인프라(RustFS, FFmpeg) 연동 역시 역할별 인터페이스로 추상화하여 **Service 계층 단위 테스트 시 손쉽게 Mock 객체로 대체**할 수 있도록 설계합니다.

```text
com.streaming.music
├── config/                  # 설정 클래스 (SecurityConfig, AsyncConfig, StorageConfig)
│   └── properties/          # yaml 바인딩 (@ConfigurationProperties: StorageProperties, AudioProperties, JwtProperties)
├── security/                # Google OAuth2 & JWT 무상태 인증 모듈
│   ├── CustomOAuth2UserService.java         # 구글 최소 정보(sub, email, picture) 추출 및 자동 회원 등록(Upsert)
│   ├── OAuth2AuthenticationSuccessHandler.java # 로그인 성공 시 JWT 발급 및 프론트 리다이렉트
│   └── JwtAuthenticationFilter.java         # 요청 헤더 Bearer 토큰 검증 필터
├── controller/              # 프레젠테이션 계층 (REST API 요청/응답 매핑)
│   ├── TrackController.java
│   └── AuthController.java                  # 현재 로그인 사용자 정보 및 프로필 이미지 조회 (/api/auth/me)
├── dto/                     # 계층 간 데이터 전송 객체
│   ├── request/             # TrackUploadRequest.java (file, title, artist, album, artworkFile)
│   ├── response/            # TrackResponse.java, StreamPlayResponse.java, UserResponse.java
│   └── event/               # TranscodeEventDto.java ({ trackId, originalS3Key })
├── entity/                  # 도메인/영속성 엔티티
│   ├── User.java            # 최소 정보 엔티티 (id, providerId, email, profileImageUrl, role, createdAt)
│   ├── Track.java
│   └── TrackStatus.java     # ENUM: PENDING, PROCESSING, READY, FAILED
├── repository/              # 데이터 접근 계층 (Spring Data JPA)
│   ├── UserRepository.java
│   └── TrackRepository.java # 최신순 정렬 및 제목·가수 키워드 검색 쿼리 포함
├── service/                 # 핵심 비즈니스 로직 계층 (단위 테스트 핵심 대상)
│   ├── UserService.java                 # OAuth2 정보(sub, email, picture) 기반 회원 조회/자동 생성(Upsert)
│   ├── TrackService.java                # 음원 업로드·키워드 검색 조회·다운로드 경로 제어
│   ├── AudioMetadataValidator.java      # Tika 위변조 검증 및 JAudioTagger 파싱/보정
│   ├── StorageService.java              # RustFS 버킷 초기화 및 원본·앨범아트·HLS 재귀 업로드 제어
│   ├── TranscodeTaskDispatcher.java     # 비동기 변환 이벤트 발행 인터페이스 (결합도 분리)
│   └── HlsTranscodeService.java         # @Async FFmpeg 실행 및 상태(READY/FAILED) 갱신
└── exception/               # 전역 예외 처리 (@RestControllerAdvice, CustomException)
```

## 6. 핵심 도메인 파이프라인 상세 명세

### 6.1. Google OAuth2 기반 무가입(Zero-Signup) 최소 정보 로그인 파이프라인
별도의 회원가입 폼이나 비밀번호 관리 프로세스를 두지 않고, Google OAuth2 인증만으로 즉시 계정이 생성·로그인되는 구조입니다.

1. **개인정보 최소 수집 원칙 (`scope: openid, email, profile`):**
   * 구글로부터 전달받는 속성 중 **고유 식별자(`sub`)**, **이메일(`email`)**, 그리고 웹페이지(`PRD-02`) 우측 상단 버튼 자리에 표시할 **구글 프로필 이미지 URL(`picture`)** 딱 3가지만 추출하며, 실명, 전화번호, 생년월일 등 불필요한 개인정보는 저장하지 않습니다.
2. **자동 계정 생성 및 프로필 갱신 (`CustomOAuth2UserService` & `UserService`):**
   * 사용자가 구글 로그인을 완료하면 `providerId(sub)` 기준으로 `users` 테이블을 조회합니다.
   * 기존 회원이면 최신 `profileImageUrl`(`picture`)과 `lastLoginAt`만 갱신하고, 최초 방문자이면 별도 가입 화면 없이 즉시 `User(providerId = sub, email = email, profileImageUrl = picture, role = ROLE_USER)` 엔티티를 자동 저장(Upsert)합니다.
3. **무상태(Stateless) JWT 발급 (`OAuth2AuthenticationSuccessHandler`):**
   * `web1`, `web2` 멀티서버 환경에서 세션을 공유하지 않도록, 로그인 성공 시 `userId`와 `role`이 담긴 **JWT Access Token**을 생성하여 클라이언트 콜백 URL(`app.jwt.oauth2-redirect-uri`)로 전달합니다.

### 6.2. 업로드 · 위변조 스캔 · 태그/앨범아트 추출 및 수정 저장 동기 파이프라인 (`TrackService`)
사용자가 웹페이지(`PRD-02`) 업로드 모달에서 음원과 수정된 메타데이터를 전송하면 아래 5단계를 동기(Synchronous)로 빠르게 처리합니다.

1. **로컬 임시 파일 생성:** 업로드된 음원 `MultipartFile`을 `${app.audio.work-dir}/{track_uuid}_raw.{ext}` 경로에 임시 저장합니다.
2. **Apache Tika 위변조 스캔 (`AudioMetadataValidator`):**
   * 파일 확장자(`.mp3`, `.wav` 등)만 바꾼 악성 파일을 차단하기 위해 `tika.detect(tempFile)`로 파일 헤더의 실제 Magic Number 기반 MIME 타입을 추출합니다.
   * 허용된 오디오 MIME 타입(`audio/mpeg`, `audio/wav`, `audio/x-wav`, `audio/vnd.wave`, `audio/flac`, `audio/mp4`)이 아닐 경우 임시 파일을 즉시 삭제하고 `InvalidAudioFormatException`(`400 Bad Request`)을 발생시킵니다.
3. **JAudioTagger 메타데이터 및 앨범 커버 결정 (옵션 3-B & 3-가 + 사용자 수정 반영):**
   * `AudioFileIO.read(tempFile)`를 통해 오디오 헤더(`AudioHeader`)에서 **재생 시간(`durationSeconds`)**을 필수로 추출합니다.
   * **텍스트 태그 우선순위 및 Fallback 보정 (옵션 3-가):**
     * **제목(`title`):** ① 업로드 모달에서 사용자가 확인·수정한 `request.title` ➔ ② ID3 태그 타이틀(`FieldKey.TITLE`) ➔ ③ 둘 다 비어 있으면 **원본 파일명(확장자 제외)**으로 자동 보정합니다.
     * **가수(`artist`):** ① 업로드 모달에서 사용자가 확인·수정한 `request.artist` ➔ ② ID3 태그 아티스트(`FieldKey.ARTIST`) ➔ ③ 둘 다 비어 있으면 **`"Unknown Artist"`**로 자동 보정합니다.
     * **앨범(`album`):** ① `request.album` ➔ ② ID3 태그 앨범(`FieldKey.ALBUM`) ➔ ③ 없으면 `null`로 저장합니다.
   * **앨범 커버 이미지 결정 및 분리 저장 (옵션 3-B + 커스텀 이미지 지원):**
     * **1순위 (사용자 직접 첨부 이미지):** 업로드 모달에서 사용자가 앨범자켓을 직접 변경하여 `artworkFile`(`MultipartFile`, `image/jpeg` 또는 `image/png`)을 함께 전송한 경우, 해당 이미지를 RustFS `audio-storage/artwork/{track_uuid}.jpg`에 저장합니다.
     * **2순위 (음원 내장 이미지):** `artworkFile`이 없고 음원 내부에 `tag.getFirstArtwork()`가 존재하면 내장 이미지 바이트 배열(`getBinaryData()`)을 추출하여 RustFS `audio-storage/artwork/{track_uuid}.jpg`에 저장합니다.
     * **3순위:** 둘 다 없는 경우 `artworkS3Key = null`로 저장합니다.
4. **원본 음원 RustFS 업로드 및 DB `PENDING` 저장:**
   * 검증된 원본 임시 파일을 RustFS의 `audio-storage/original/{yyyy}/{mm}/{track_uuid}.{ext}` 경로에 업로드합니다.
   * DB `tracks` 테이블에 상태값 `status = PENDING`으로 엔티티를 저장한 뒤, 로컬 원본 임시 파일을 삭제합니다.
5. **비동기 HLS 변환 이벤트 발행 (`PRD-01` 결합도 분리 규칙 적용):**
   * 메모리 파일 객체나 로컬 경로를 넘기지 않고, 오직 **`TranscodeEventDto(trackId, originalS3Key)`**만 `TranscodeTaskDispatcher.dispatch(dto)` 인터페이스로 발행한 뒤 클라이언트에게 `202 Accepted` 응답을 즉시 반환합니다.

### 6.3. 백엔드 내부 비동기 HLS 청크 변환 파이프라인 (`HlsTranscodeService`)
1. **전용 스레드 풀 격리 (`AsyncConfig`):**
   * `ThreadPoolTaskExecutor`를 빈으로 등록하며, FFmpeg가 백엔드 CPU를 독점하지 못하도록 `corePoolSize = 1`, `maxPoolSize = 2`, `queueCapacity = 50`으로 동시 변환 수를 엄격히 제한합니다.
2. **원본 다운로드 및 상태 전환:**
   * `@Async("transcodeExecutor") @EventListener`가 `TranscodeEventDto`를 수신하면 DB 상태를 `PROCESSING`으로 변경하고, RustFS에서 `originalS3Key` 파일을 `${app.audio.work-dir}/{track_uuid}/input.{ext}`로 내려받습니다.
3. **FFmpeg 6초 단위 단일 음질(AAC 192kbps) 청크 분할 (`ProcessBuilder`):**
   * 출력 폴더 `${app.audio.work-dir}/{track_uuid}/hls/`를 생성하고 아래 표준 FFmpeg 명령어를 실행합니다.
   ```bash
   ffmpeg -y -i /tmp/audio-work/{track_uuid}/input.{ext} \
     -vn -c:a aac -b:a 192k -ar 44100 -ac 2 \
     -f hls -hls_time 6 -hls_list_size 0 \
     -hls_segment_filename "/tmp/audio-work/{track_uuid}/hls/chunk_%03d.ts" \
     "/tmp/audio-work/{track_uuid}/hls/index.m3u8"
   ```
4. **폴더 전체 재귀 업로드 (다중 음질 확장 대비 설계) 및 클린업:**
   * 변환 프로세스 종료 코드(`exitCode == 0`) 확인 후, 출력 디렉터리 내부의 모든 파일을 `Files.walk()`로 순회하며 RustFS `audio-storage/hls/{track_uuid}/` 하위에 그대로 업로드합니다.
   * 업로드 완료 시 DB 상태를 `READY`로, 스트리밍 진입점 경로를 `/stream/{track_uuid}/index.m3u8`로 갱신합니다. (실패 시 `FAILED` 기록)
   * `finally` 블록에서 성공·실패 여부와 관계없이 `${app.audio.work-dir}/{track_uuid}/` 로컬 임시 디렉터리 전체를 즉시 삭제합니다.

## 7. REST API 엔드포인트 명세 및 Spring Security 설계

### 7.1. 핵심 API 명세

| 기능 | HTTP Method & URI | 요청 데이터 | 응답 및 동작 방식 |
| :--- | :--- | :--- | :--- |
| **Google OAuth2 로그인 진입** | `GET /oauth2/authorization/google` | 없음 (Spring Security 기본 제공) | `302 Redirect`<br>구글 로그인 화면으로 이동 ➔ 인증 완료 후 자체 JWT 발급 및 클라이언트로 리다이렉트 |
| **로그인 사용자 정보 조회** | `GET /api/auth/me` | Header: `Authorization: Bearer {jwt}` | `200 OK`<br>`{ userId, email, profileImageUrl, role }` 반환 (`PRD-02` 우측 상단 프로필 아바타 렌더링용) |
| **음원 단건 업로드** | `POST /api/tracks/upload` | `multipart/form-data`<br>- `file` (필수, MP3/WAV 등)<br>- `title` (선택), `artist` (선택), `album` (선택)<br>- `artworkFile` (선택, 커스텀 자켓 이미지) | `202 Accepted`<br>Tika 검증 + 태그/앨범아트 저장 + 원본 RustFS 저장 후 `{ trackId, status: "PENDING", title, artist, artworkUrl }` 반환 |
| **음원 목록 및 키워드 검색 조회** | `GET /api/tracks` | Query:<br>- `keyword` (선택: 제목 또는 가수 검색어)<br>- `page`, `size`, `status`(선택) | `200 OK`<br>**업로드 날짜 내림차순(`createdAt DESC`) 정렬.** `keyword` 입력 시 제목(`title`) 또는 가수(`artist`)에 해당 단어가 포함된 트랙 리스트 반환 |
| **음원 단건 상세/상태 조회** | `GET /api/tracks/{trackId}` | Path: `trackId` (UUID) | `200 OK`<br>업로드 직후 변환 완료(`READY`) 여부 확인 및 상세 정보 조회 |
| **실시간 스트리밍 진입점 조회** | `GET /api/tracks/{trackId}/play` | Path: `trackId` (UUID) | `200 OK`<br>`status == READY` 검증 후 `{ streamUrl: "/stream/{track_uuid}/index.m3u8", title, artist, durationSeconds, artworkUrl }` 반환 |
| **원본 파일 다운로드** | `GET /api/tracks/{trackId}/download` | Path: `trackId` (UUID) | `200 OK` (Body 없음, 헤더 반환)<br>- `X-Accel-Redirect: /internal-storage/original/{yyyy}/{mm}/{track_uuid}.{ext}`<br>- `Content-Disposition: attachment; filename="UTF-8인코딩_원본파일명.ext"` |

### 7.2. Spring Security 설정 (`MVP permitAll` + Google OAuth2 연동 구조)
초기 MVP에서는 음원 관련 모든 API를 `permitAll()`로 열어두되, Google OAuth2 로그인 파이프라인과 JWT 필터를 함께 구성해 두어 추후 엔드포인트별 권한 제한(`.authenticated()`)을 즉시 켤 수 있도록 설계합니다.

```java
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // [MVP 정책 4] 초기에는 음원 업로드/조회/재생/다운로드 전체 허용 (permitAll)
                // 추후 권한 제어 시 아래 경로를 .authenticated() 또는 .hasRole("USER")로 전환
                .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                .requestMatchers("/api/tracks/*/download").permitAll()
                .requestMatchers("/api/tracks/*/play").permitAll()
                .requestMatchers("/api/tracks/**").permitAll()
                .anyRequest().permitAll()
            )
            .oauth2Login(oauth2 -> oauth2
                .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                .successHandler(oAuth2SuccessHandler)
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
```

## 8. Service 계층 기능별 단위 테스트 (Unit Test) 검증 전략

각 기능 개발 직후 **외부 인프라(PostgreSQL, RustFS, 실제 FFmpeg 프로세스, Google 인증 서버)를 띄우지 않고도 1초 이내에 비즈니스 로직의 정합성을 검증**할 수 있도록 `@ExtendWith(MockitoExtension.class)` 기반의 단위 테스트를 필수로 작성합니다.

| 검증 대상 클래스 | 필수 단위 테스트 케이스 (Test Scenarios) | 검증 방법 (`Mockito` & `AssertJ`) |
| :--- | :--- | :--- |
| **`UserServiceTest`** | 1. 최초 Google OAuth2 로그인(`sub`, `email`, `picture`) 시 신규 `User` 엔티티와 `profileImageUrl`이 정상 저장되는지 검증<br>2. 이미 존재하는 `providerId(sub)`로 로그인 시 중복 가입 없이 기존 회원 정보 및 최신 프로필 이미지가 갱신·반환되는지 검증 | `UserRepository`를 `@Mock`으로 주입하여 `findByProviderId()` 유무에 따른 분기 및 저장 동작 검증 |
| **`AudioMetadataValidatorTest`** | 1. 정상 MP3/WAV 바이트 스트림 입력 시 통과 검증<br>2. 확장자만 `.mp3`로 바꾼 텍스트/실행 파일 입력 시 `InvalidAudioFormatException` 발생 검증<br>3. ID3 태그가 있는 MP3에서 제목·가수·재생시간·앨범 커버 바이트 정상 추출 검증<br>4. 태그가 비어 있는 WAV 파일 입력 시 `제목 = 파일명`, `가수 = "Unknown Artist"` 자동 보정(Fallback) 검증 | 테스트 리소스(`src/test/resources/samples/`)의 초소형 샘플 파일(1초 분량 정상 MP3/WAV 및 가짜 텍스트 파일)을 사용해 실제 Tika·JAudioTagger 동작 검증 |
| **`TrackServiceTest`** | 1. 업로드 시 사용자 커스텀 `artworkFile`이 있으면 최우선 저장하고, 없으면 내장 앨범 커버를 저장하며, 둘 다 없으면 `artworkKey = null`로 저장하는지 검증<br>2. 목록 조회 시 `keyword`가 없으면 전체 최신순(`createdAt DESC`), `keyword`가 있으면 제목 또는 가수 일치 항목만 최신순으로 반환하는지 검증<br>3. 아직 `PROCESSING` 상태인 트랙에 대해 `getPlayInfo()` 호출 시 예외(`TrackNotReadyException`) 발생 검증<br>4. `getDownloadInfo()` 호출 시 원본 S3 Key와 매칭되는 정확한 `X-Accel-Redirect` 내부 경로 반환 검증 | `TrackRepository`, `StorageService`, `TranscodeTaskDispatcher`, `AudioMetadataValidator`를 `@Mock`으로 주입하고 `verify()` 및 `assertThat()`으로 검증 |
| **`HlsTranscodeServiceTest`** | 1. 변환 성공 시 임시 폴더 내 생성된 `.m3u8`/`.ts` 파일들이 `StorageService.uploadDirectoryRecursively()`를 통해 업로드되고 트랙 상태가 `READY`로 변경되는지 검증<br>2. FFmpeg 실행 중 예외 또는 비정상 종료(`exitCode != 0`) 발생 시 트랙 상태가 `FAILED`로 변경되고, 임시 폴더가 반드시 삭제(Clean-up)되는지 검증 | FFmpeg 실행기(`FfmpegCommandRunner` 인터페이스)와 `StorageService`, `TrackRepository`를 `@Mock` 처리하여 성공/실패 시나리오 상태 전이 및 임시 파일 삭제 여부 검증 |

## 9. 로컬 작업 시 DB 구축

로컬 환경에서 작업 시 PostgreSQL 대신 H2.database를 사용한다.
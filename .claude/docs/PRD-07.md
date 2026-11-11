# [PRD-07] 데이터베이스 (PostgreSQL) 상세 설계 (v1.2 - RustFS 연동 확정안)

## 1. 개요 및 기술 스택
본 문서는 음원 스트리밍 및 원본 아카이빙 서비스 MVP의 영속성 계층을 담당하는 **중앙 데이터베이스(`postgres-db`)**의 스키마, PK 전략, 제약조건(`NULL` 허용 여부), 최소 필수 인덱스 및 도커 인프라 구성을 정의합니다.

전체 아키텍처(`PRD-01`), 웹페이지(`PRD-02`), 백엔드 상세 설계(`PRD-05`), 음원 서버(`PRD-06`)에서 확정된 **Google OAuth2 무가입 로그인(프로필 이미지 연동 포함)**, **노래 제목·가수 키워드 검색 및 업로드 날짜 내림차순 정렬**, **RustFS 오브젝트 스토리지 기반 원본·앨범커버·HLS 분리 저장**, **백엔드 내부 비동기(`PENDING ➔ PROCESSING ➔ READY / FAILED`) 변환 파이프라인**과 100% 정합성을 이루도록 설계되었습니다.

* **DBMS 엔진:** PostgreSQL `16-alpine` (도커 공식 경량 이미지, PostgreSQL 15+ 호환)
* **ORM 연동:** Spring Data JPA / Hibernate (`PRD-05` 기준)
* **타임존 및 인코딩:** `Asia/Seoul (KST)`, `UTF-8` (다국어 음원 ID3 태그 완벽 지원)

## 2. 핵심 설계 원칙 및 PK(기본키) 선정 전략

### 2.1. 엄격한 Anti-BLOB 원칙 (바이너리 DB 저장 금지)
* 오디오 원본 파일(`.mp3`, `.wav`), 분할된 HLS 청크(`.ts`), 추출된 내장 앨범 커버 이미지(`.jpg`) 등 **모든 바이너리 데이터를 DB 테이블 내부(`BYTEA` 컬럼)에 직접 저장하는 행위를 엄격히 금지**합니다.
* **이유:** 수백 KB~수백 MB의 바이너리가 적재되면 PostgreSQL의 공유 버퍼 풀(Shared Buffer Pool) 메모리가 오염되어 일반 메타데이터 조회 성능이 급격히 저하되고 DB 커넥션 풀 고갈을 유발합니다.
* **설계 반영:** 모든 미디어 파일은 `rustfs` S3 호환 오브젝트 스토리지에 저장하고, DB에는 **스토리지 객체 키(`VARCHAR` 상대 경로)**만 텍스트로 기록합니다.

### 2.2. 테이블 특성에 맞춘 하이브리드 PK 전략 (Auto-Increment vs UUID)
모든 테이블에 일괄적으로 Auto-Increment(`1, 2, 3...`)를 적용할 경우 발생하는 **미디어 URL 보안 취약점**과 **파일 선업로드 트랜잭션 문제**를 해결하기 위해 테이블 역할별로 최적의 PK를 분리 적용합니다.

| 테이블명 | 채택 PK 타입 | Auto-Increment 대신(또는 함께) 채택한 기술적 이유 |
| :--- | :--- | :--- |
| **`users`**<br>(회원 마스터) | **`BIGSERIAL`**<br>(Auto-Increment 8Byte 정수) | 외부 정적 파일 URL에 노출되지 않고 내부 외래키(FK) 조인 및 JWT 식별자로만 사용되므로, 인덱스 크기가 가장 작고 B-Tree 검색 속도가 빠른 **Auto-Increment(`BIGSERIAL`)**가 최적입니다. |
| **`tracks`**<br>(음원 메타데이터) | **`UUID`**<br>(128비트 고유 식별자) | 1. **무단 수집(Enumeration Attack) 방어:** MVP 초기에는 스트리밍 경로(`/stream/{track_id}/index.m3u8`)가 전체 개방(`permitAll`)됩니다. 만약 PK가 `1, 2, 3`처럼 순차 증가하면 외부에서 숫자를 대입해 전체 음원을 긁어갈 수 있으나, `UUID`를 사용하면 경로 추측이 불가능합니다.<br>2. **스토리지 선업로드 파이프라인 정합성:** `PRD-05` 구조상 DB `INSERT` 전에 로컬 임시 파일과 RustFS 원본(`original/.../{uuid}.mp3`) 및 앨범 커버(`artwork/{uuid}.jpg`)를 먼저 업로드해야 합니다. 애플리케이션에서 `UUID`를 먼저 발급하면 DB 채번 대기 없이 파일 업로드와 DB 저장을 일관된 식별자로 처리할 수 있습니다. |
| **`playback_logs`**<br>(재생/다운로드 이력) | **`BIGSERIAL`**<br>(Auto-Increment 8Byte 정수) | 대량으로 빠르게 쌓이는 순차 로그성 테이블이므로, B-Tree 인덱스 페이지 분할(Page Split)이 전혀 발생하지 않는 **Auto-Increment(`BIGSERIAL`)**가 쓰기 성능 면에서 가장 유리합니다. |

## 3. 논리적 ERD (Entity-Relationship) 및 테이블 관계

시스템은 핵심 2개 마스터 테이블(`users`, `tracks`)과 1개 이력 테이블(`playback_logs`)로 구성됩니다.

* **`users (1) : (N) tracks`**
  * 한 명의 회원은 여러 개의 음원을 업로드할 수 있습니다.
  * **비로그인 업로드 호환 (`uploader_id NULL` 허용):** `PRD-01` 및 `PRD-05`의 정책 4에 따라 MVP 초기에는 로그인 없이도(`permitAll`) 음원 업로드 테스트가 가능해야 하므로, `tracks.uploader_id` 외래키는 **`NULL`을 허용**하며 회원 탈퇴 시에도 음원이 유실되지 않도록 `ON DELETE SET NULL`을 적용합니다.
* **`tracks (1) : (N) playback_logs` 및 `users (1) : (N) playback_logs`**
  * 음원이 삭제되면 해당 음원의 재생 로그도 함께 정리되도록 `ON DELETE CASCADE`를 적용하고, 비로그인 재생 로그도 기록할 수 있도록 `user_id`는 `NULL`을 허용합니다.

## 4. 테이블별 상세 스키마 및 `NULL` 허용 기준 명세

### 4.1. `users` 테이블 (Google OAuth2 최소 정보 및 프로필 이미지 테이블)
`PRD-02` 및 `PRD-05`의 **자체 회원가입 배제 및 Google OAuth2 최소 정보(`sub`, `email`, `picture`) 수집 원칙**에 따라 비밀번호 해시(`password_hash`), 닉네임, 전화번호 등의 불필요한 컬럼을 제거하고 우측 상단 아바타 표시용 `profile_image_url` 컬럼을 포함한 구조입니다.

| 컬럼명 | 데이터 타입 | 제약 조건 | NULL 허용 | 기본값 (Default) | 설명 및 설계 근거 |
| :--- | :--- | :--- | :---: | :--- | :--- |
| **`user_id`** | `BIGSERIAL` | `PRIMARY KEY` | **NOT NULL** | Auto Increment | 내부 식별용 기본키 (Java `Long`) |
| **`provider`** | `VARCHAR(20)` | - | **NOT NULL** | `'GOOGLE'` | OAuth2 제공자 구분 (기본값 `'GOOGLE'`) |
| **`provider_id`** | `VARCHAR(100)` | `UNIQUE` | **NOT NULL** | - | Google OAuth2 고유 식별자(`sub` 클레임). 중복 가입 방지 및 회원 조회 기준키 |
| **`email`** | `VARCHAR(150)` | `UNIQUE` | **NOT NULL** | - | Google 계정 이메일 주소 |
| **`profile_image_url`** | `VARCHAR(500)` | - | **NULL 허용** | `NULL` | Google OAuth2 프로필 이미지 URL(`picture` 클레임). 웹페이지(`PRD-02`) 우측 상단 프로필 아이콘 표기용 |
| **`role`** | `VARCHAR(30)` | - | **NOT NULL** | `'ROLE_USER'` | Spring Security 권한 (`ROLE_USER`, `ROLE_ADMIN`) |
| **`created_at`** | `TIMESTAMPTZ` | - | **NOT NULL** | `CURRENT_TIMESTAMP` | 최초 Google 로그인(자동 등록) 시각 |
| **`last_login_at`** | `TIMESTAMPTZ` | - | **NOT NULL** | `CURRENT_TIMESTAMP` | 마지막 OAuth2 로그인 시각 |

### 4.2. `tracks` 테이블 (음원 메타데이터, 검색 대상 및 비동기 변환 상태 테이블)
`PRD-02`의 **히어로 섹션 검색창(제목·가수 검색 및 업로드 날짜 내림차순 정렬)**과 `PRD-05`의 **비동기 변환 파이프라인(`PENDING` ➔ `READY`)**을 지원하는 핵심 테이블입니다.

| 컬럼명 | 데이터 타입 | 제약 조건 | NULL 허용 | 기본값 (Default) | 설명 및 `NULL` 여부 판단 근거 |
| :--- | :--- | :--- | :---: | :--- | :--- |
| **`track_id`** | `UUID` | `PRIMARY KEY` | **NOT NULL** | 애플리케이션 생성 | 음원 고유 식별자 및 RustFS 디렉터리/파일 기준 키 |
| **`uploader_id`** | `BIGINT` | `FK (users.user_id)` | **NULL 허용** | `NULL` | 업로더 회원 ID. MVP 초기 `permitAll()` 비로그인 업로드 지원을 위해 `NULL` 허용 |
| **`title`** | `VARCHAR(200)` | - | **NOT NULL** | - | **노래 제목 (`keyword` 검색 대상 1).** 태그 누락 시 원본 파일명으로 자동 보정되므로 항상 값 존재 |
| **`artist`** | `VARCHAR(150)` | - | **NOT NULL** | `'Unknown Artist'` | **가수명 (`keyword` 검색 대상 2).** 태그 누락 시 `'Unknown Artist'`로 자동 보정되므로 `NOT NULL` |
| **`album`** | `VARCHAR(200)` | - | **NULL 허용** | `NULL` | 앨범명. WAV 파일 등 태그가 없는 경우 강제할 필요가 없으므로 `NULL` 허용 |
| **`duration_seconds`** | `INT` | `CHECK (> 0)` | **NOT NULL** | - | 총 재생 시간(초). `jaudiotagger` 오디오 헤더에서 항상 추출 가능하므로 `NOT NULL` |
| **`original_filename`** | `VARCHAR(255)` | - | **NOT NULL** | - | 사용자가 업로드한 원래 파일명 (원본 다운로드 시 `Content-Disposition` 파일명 복원용) |
| **`file_extension`** | `VARCHAR(20)` | - | **NOT NULL** | - | 원본 확장자 (`mp3`, `wav`, `flac` 등 소문자 정규화) |
| **`file_size_bytes`** | `BIGINT` | `CHECK (> 0)` | **NOT NULL** | - | 원본 파일 바이트 크기 |
| **`mime_type`** | `VARCHAR(100)` | - | **NOT NULL** | - | Apache Tika가 검증한 실제 MIME 타입 (`audio/mpeg`, `audio/wav` 등) |
| **`original_s3_key`** | `VARCHAR(500)` | - | **NOT NULL** | - | RustFS 원본 저장 상대 경로 (`original/{yyyy}/{mm}/{track_uuid}.{ext}`). 선업로드 후 INSERT하므로 `NOT NULL` |
| **`artwork_s3_key`** | `VARCHAR(500)` | - | **NULL 허용** | `NULL` | RustFS 앨범 커버 이미지 경로 (`artwork/{track_uuid}.jpg`). 사용자 수정 이미지 또는 내장 이미지 저장 (없을 시 `NULL`) |
| **`hls_playlist_key`** | `VARCHAR(500)` | - | **NULL 허용** | `NULL` | RustFS HLS 매니페스트 경로 (`hls/{track_uuid}/index.m3u8`). **최초 `PENDING` 저장 시점에는 변환 전이므로 `NULL` 허용 필수** |
| **`status`** | `VARCHAR(20)` | `CHECK (IN ...)` | **NOT NULL** | `'PENDING'` | 비동기 변환 상태 (`PENDING`, `PROCESSING`, `READY`, `FAILED`) |
| **`error_message`** | `VARCHAR(500)` | - | **NULL 허용** | `NULL` | FFmpeg 변환 실패(`FAILED`) 시 에러 원인 요약 기록 (정상 처리 시 `NULL`) |
| **`created_at`** | `TIMESTAMPTZ` | - | **NOT NULL** | `CURRENT_TIMESTAMP` | **음원 업로드 시각 (기본 리스트 `DESC` 내림차순 정렬 기준)** |
| **`updated_at`** | `TIMESTAMPTZ` | - | **NOT NULL** | `CURRENT_TIMESTAMP` | 상태(`PROCESSING` ➔ `READY`) 최종 갱신 시각 |

### 4.3. `playback_logs` 테이블 (스트리밍 재생 및 다운로드 이력 테이블)
| 컬럼명 | 데이터 타입 | 제약 조건 | NULL 허용 | 기본값 (Default) | 설명 및 설계 근거 |
| :--- | :--- | :--- | :---: | :--- | :--- |
| **`log_id`** | `BIGSERIAL` | `PRIMARY KEY` | **NOT NULL** | Auto Increment | 로그 고유 식별자 |
| **`track_id`** | `UUID` | `FK (tracks.track_id)` | **NOT NULL** | - | 대상 음원 ID (`ON DELETE CASCADE`) |
| **`user_id`** | `BIGINT` | `FK (users.user_id)` | **NULL 허용** | `NULL` | 요청 회원 ID (비로그인 재생/다운로드 시 `NULL`, 회원 삭제 시 `ON DELETE SET NULL`) |
| **`action_type`** | `VARCHAR(20)` | - | **NOT NULL** | `'STREAM'` | 이벤트 유형 (`STREAM`: 스트리밍 재생 진입, `DOWNLOAD`: 원본 다운로드) |
| **`created_at`** | `TIMESTAMPTZ` | - | **NOT NULL** | `CURRENT_TIMESTAMP` | 이벤트 발생 시각 |

## 5. 최소 필수 인덱스(Index) 및 검색 쿼리 설계

웹페이지(`PRD-02`)의 **업로드 날짜 내림차순(`created_at DESC`) 기본 정렬**과 **제목·가수(`title`, `artist`) 키워드 검색**을 효율적으로 처리하기 위한 인덱스 구성입니다.

| 인덱스명 | 대상 테이블 및 컬럼 | 생성 방식 | 필수 선정 사유 |
| :--- | :--- | :--- | :--- |
| **`uk_users_provider_id`** | `users (provider_id)` | `UNIQUE` 제약조건 자동 생성 | Google OAuth2 로그인 시 `findByProviderId(sub)` 단건 조회를 즉시 처리 |
| **`uk_users_email`** | `users (email)` | `UNIQUE` 제약조건 자동 생성 | 이메일 중복 방지 및 계정 식별 |
| **`idx_tracks_created_at_desc`** | `tracks (created_at DESC)` | 명시적 생성 (`CREATE INDEX`) | 웹페이지(`PRD-02`) 메인 리스트 진입 시 전체(또는 검색된) 음원을 **업로드 날짜 내림차순으로 정렬(Sort 연산 생략)**하여 즉시 반환 |
| **`idx_tracks_status_created_at`** | `tracks (status, created_at DESC)` | 명시적 생성 (`CREATE INDEX`) | 상태별(`READY` 또는 `PENDING`) 필터링 및 최신순 조회 최적화 |
| **`idx_playback_logs_track_id`** | `playback_logs (track_id)` | 명시적 생성 (`CREATE INDEX`) | 음원 삭제(`ON DELETE CASCADE`) 시 `playback_logs` 전체 테이블 스캔 방지 |

## 6. 표준 물리 DDL 스크립트 (`init.sql`)

PostgreSQL 컨테이너 최초 기동 시 `/docker-entrypoint-initdb.d/01-init.sql`에 마운트하여 자동 실행되는 확정 DDL입니다.

```sql
-- 1. 회원 마스터 테이블 (Google OAuth2 최소 정보 + 프로필 이미지 URL)
CREATE TABLE users (
    user_id           BIGSERIAL    PRIMARY KEY,
    provider          VARCHAR(20)  NOT NULL DEFAULT 'GOOGLE',
    provider_id       VARCHAR(100) NOT NULL,
    email             VARCHAR(150) NOT NULL,
    profile_image_url VARCHAR(500) NULL,
    role              VARCHAR(30)  NOT NULL DEFAULT 'ROLE_USER',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_users_provider_id UNIQUE (provider_id),
    CONSTRAINT uk_users_email       UNIQUE (email)
);

-- 2. 음원 메타데이터 및 비동기 변환 상태 테이블
CREATE TABLE tracks (
    track_id          UUID          PRIMARY KEY,
    uploader_id       BIGINT        NULL REFERENCES users(user_id) ON DELETE SET NULL,
    title             VARCHAR(200)  NOT NULL,
    artist            VARCHAR(150)  NOT NULL DEFAULT 'Unknown Artist',
    album             VARCHAR(200)  NULL,
    duration_seconds  INT           NOT NULL CHECK (duration_seconds > 0),
    original_filename VARCHAR(255)  NOT NULL,
    file_extension    VARCHAR(20)   NOT NULL,
    file_size_bytes   BIGINT        NOT NULL CHECK (file_size_bytes > 0),
    mime_type         VARCHAR(100)  NOT NULL,
    original_s3_key   VARCHAR(500)  NOT NULL,
    artwork_s3_key    VARCHAR(500)  NULL,
    hls_playlist_key  VARCHAR(500)  NULL,
    status            VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    error_message     VARCHAR(500)  NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_tracks_status CHECK (status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED'))
);

-- 3. 스트리밍 및 원본 다운로드 이력 테이블
CREATE TABLE playback_logs (
    log_id      BIGSERIAL   PRIMARY KEY,
    track_id    UUID        NOT NULL REFERENCES tracks(track_id) ON DELETE CASCADE,
    user_id     BIGINT      NULL REFERENCES users(user_id) ON DELETE SET NULL,
    action_type VARCHAR(20) NOT NULL DEFAULT 'STREAM',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_playback_logs_action CHECK (action_type IN ('STREAM', 'DOWNLOAD'))
);

-- 4. 최소 필수 인덱스 생성
CREATE INDEX idx_tracks_created_at_desc   ON tracks (created_at DESC);
CREATE INDEX idx_tracks_status_created_at ON tracks (status, created_at DESC);
CREATE INDEX idx_playback_logs_track_id   ON playback_logs (track_id);
```

## 7. 도커 컨테이너(`postgres-db`) 구성 및 데이터 영속성 설계

컨테이너가 재시작되거나 재생성되더라도 DB 데이터가 유실되지 않도록 **Docker Named Volume(`postgres-data`)**을 필수로 마운트하며, 모든 접속 계정 및 비밀번호는 백엔드(`PRD-05`)와 동일한 `.env` 환경변수를 참조합니다.

```yaml
# docker-compose.yml 내 postgres-db 섹션 명세
services:
  postgres-db:
    image: postgres:16-alpine
    container_name: postgres-db
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${DB_NAME:-streaming_db}
      POSTGRES_USER: ${DB_USERNAME:-streaming_user}
      POSTGRES_PASSWORD: ${DB_PASSWORD} # 필수 환경변수 주입
      TZ: Asia/Seoul
    ports:
      - "5432:5432" # 로컬 개발 및 DB 클라이언트 확인용 (운영 시 내부망만 개방 가능)
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ./db/init.sql:/docker-entrypoint-initdb.d/01-init.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USERNAME:-streaming_user} -d ${DB_NAME:-streaming_db}"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  postgres-data:
```

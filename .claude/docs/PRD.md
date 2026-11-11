# PRD 인덱스 (백엔드 개발 관점)

> 최종 갱신: 2026-11-11 — PRD-01~07 교차 검토 및 정합성 보완 반영 (결정 이력: §7)

## 1. 제품 개요

온프레미스 도커 환경에서 동작하는 **음원 스트리밍 및 원본 아카이빙 서비스 MVP**입니다. 웹·모바일 클라이언트가 음원(MP3/WAV/FLAC/M4A)을 업로드하면 **원본**과 **HLS 스트리밍 청크(`.m3u8`/`.ts`)**를 분리 저장하고, 실시간 스트리밍과 원본 다운로드를 제공합니다. 전체 구조는 `PRD-01`을 기준으로 합니다.

* **이 저장소의 범위:** **백엔드 서버(`PRD-05`)만** 구현합니다. 그 외 PRD는 인터페이스 계약 확인용으로만 참조합니다.
* **백엔드의 책임:** 멀티파트 업로드 수신·검증, 메타데이터/앨범아트 추출, RustFS 원본 적재, 비동기 FFmpeg HLS 변환, 메타데이터 CRUD·검색, Google OAuth2 + JWT 무상태 인증, 스트리밍 진입점 조회, presigned 기반 원본 다운로드 지시(`X-Accel-Redirect`), 재생·다운로드 이력 기록.
* **백엔드가 하지 않는 일:** 미디어 바이트(청크·원본) 직접 전송(Nginx ➔ RustFS 전담), 버킷 정책 설정(`storage-init` 컨테이너), DB 스키마 생성(PRD-07 `init.sql`).

## 2. 문서 구성

| 파일 | 범위 | 백엔드 관점 역할 | 버전 | 상태 |
|---|---|---|---|---|
| `PRD-01.md` | 전체 구조 및 아키텍처 | **기준** (항상 참조) | v2.4 | 확정 |
| `PRD-05.md` | 백엔드 서버 상세 설계 | **구현 대상** (단일 기준) | v1.4 | 확정 |
| `PRD-07.md` | 데이터베이스(PostgreSQL) | 계약: 스키마·제약조건 (엔티티 매핑 기준) | v1.3 | 확정 |
| `PRD-06.md` | 음원 서버(RustFS & FFmpeg) | 계약: 버킷 키 구조·Content-Type·접근 정책·FFmpeg 명령 | v1.2 | 확정 |
| `PRD-04.md` | 리버스 프록시(Nginx) | 계약: 라우팅 경로·`X-Accel-Redirect`·전달 헤더 | v1.2 | 확정 |
| `PRD-02.md` | 웹페이지 | 계약: API 호출 방식·로그인 콜백·응답 사용처 | - | 확정 |
| `PRD-03.md` | 모바일 앱(Android) | 계약: API 호출 방식·딥링크 로그인 콜백 | - | 확정 |

## 3. 용어 정의

| 용어 | 정의 |
|---|---|
| **Track** | 업로드된 음원 1건. PK는 애플리케이션이 발급하는 `UUID`(`track_uuid`)이며 RustFS 객체 키의 기준 식별자로도 쓰입니다. |
| **TrackStatus** | 비동기 변환 상태. `PENDING`(원본 저장 완료) ➔ `PROCESSING`(FFmpeg 변환 중) ➔ `READY`(HLS 업로드 완료) / `FAILED`(변환 실패·타임아웃·큐 포화·재시작 중단). |
| **originalS3Key** | 원본 객체 키 `original/{yyyy}/{mm}/{track_uuid}.{ext}`. `{ext}`는 Tika 감지 MIME 기준으로 정규화합니다. |
| **artworkS3Key** | 앨범 커버 객체 키 `artwork/{track_uuid}.jpg`. 키 확장자는 고정이며 Content-Type은 실제 MIME(`image/jpeg`/`image/png`)입니다. 없으면 `null`. |
| **hlsPlaylistKey** | HLS 매니페스트 객체 키 `hls/{track_uuid}/index.m3u8`. DB에는 **S3 키**를 저장합니다. |
| **streamUrl** | 클라이언트 재생 주소 `{public-base-url}/stream/{track_uuid}/index.m3u8`. DB에 저장하지 않고 응답 시 변환합니다. |
| **artworkUrl** | 클라이언트 이미지 주소 `{public-base-url}/artwork/{track_uuid}.jpg` (없으면 `null`). |
| **public-base-url** | 미디어 URL 접두어(`app.media.public-base-url`). 기본값은 빈 값이며, 이 경우 단일 오리진 상대경로입니다. |
| **X-Accel-Redirect** | 백엔드가 Body 없이 응답 헤더로 Nginx에 내부 경로(`/internal-storage/...`) 전송을 지시하는 방식. 백엔드 스레드가 파일 바이트를 처리하지 않습니다. |
| **presigned URL** | 백엔드가 서명한 짧은 유효기간(기본 60초)의 RustFS GET URL. 익명 읽기가 금지된 `original/` 접근에 사용합니다. |
| **TranscodeEventDto** | 업로드 ➔ 변환 모듈로 전달하는 식별자 객체 `{ trackId, originalS3Key }`. 파일 객체·로컬 경로는 전달하지 않습니다. |
| **providerId (sub)** | Google OAuth2 고유 식별자(`sub` 클레임). `users.provider_id`이며 회원 Upsert 기준 키입니다. |
| **PlaybackLog** | 재생(`STREAM`, `/play` 호출 시)·다운로드(`DOWNLOAD`, `/download` 호출 시) 이력 1건. 비로그인이면 `user_id = NULL`. |
| **web-server-1 / web-server-2** | 백엔드 클러스터 노드(컨테이너명). 약칭 `web1`/`web2`. |

## 4. 백엔드 인터페이스 계약 요약

### 4.1. Nginx 경로 계약 (PRD-04)

| 경로 | 처리 주체 | 백엔드 관련 사항 |
|---|---|---|
| `/api/*`, `/oauth2/*`, `/login/oauth2/*` | 백엔드 (`web-server-1/2` 라운드로빈) | 노드 간 상태 공유 없음. `Host`, `X-Forwarded-Host/Proto/For` 헤더 전달됨(`forward-headers-strategy: framework`) |
| `/stream/*` | Nginx ➔ RustFS `hls/` | 백엔드는 `streamUrl`만 응답 |
| `/artwork/*` | Nginx ➔ RustFS `artwork/` | 백엔드는 `artworkUrl`만 응답 |
| `/internal-storage/*` | Nginx 내부 전용 ➔ RustFS 버킷 루트 | 백엔드가 `X-Accel-Redirect: /internal-storage/original/...?X-Amz-...`(presigned 경로+서명 쿼리, 재인코딩 금지)로 지시 |
| `/` (그 외) | Nginx 정적 파일 (SPA) | 웹 로그인 콜백 `/auth/callback`은 SPA 라우트 (`/oauth2/*`는 백엔드 경로라 사용 불가) |

* 업로드 용량: Nginx `client_max_body_size 110M` = Spring `max-request-size 110MB` (음원 100MB + 앨범자켓 10MB).

### 4.2. RustFS 저장소 계약 (PRD-06)

| 키 패턴 | Content-Type (업로드 시 필수 지정) | 접근 방식 |
|---|---|---|
| `original/{yyyy}/{mm}/{uuid}.{ext}` | Tika 감지 MIME | **익명 읽기 금지**, presigned URL로만 접근 |
| `artwork/{uuid}.jpg` | `image/jpeg` / `image/png` | 익명 읽기 허용 (Nginx 프록시) |
| `hls/{uuid}/index.m3u8` | `application/vnd.apple.mpegurl` | 익명 읽기 허용 (Nginx 프록시) |
| `hls/{uuid}/chunk_{NNN}.ts` | `video/mp2t` | 익명 읽기 허용 (Nginx 프록시·캐시) |

* 버킷: `audio-storage` 단일 버킷. 버킷 정책은 `storage-init` 컨테이너가 설정하고, 백엔드는 기동 시 버킷 존재 확인·생성까지만 수행합니다.
* presigned 서명의 `Host`가 맞아야 하므로 백엔드 `STORAGE_ENDPOINT`의 host:port와 Nginx `STORAGE_HOST_HEADER`는 반드시 같아야 합니다.
* HLS 변환: AAC 192kbps / 44.1kHz / Stereo, 6초 세그먼트, VOD(`-hls_list_size 0`), 임시 폴더 전체 재귀 업로드.

### 4.3. DB 계약 (PRD-07)

* **PRD-07 §6 `init.sql`이 스키마의 단일 기준**입니다. JPA 엔티티는 이 DDL에 맞추며, `prod` 프로파일은 `ddl-auto: validate`로 불일치를 기동 시 검출합니다.
* 테이블: `users`(PK `BIGSERIAL`), `tracks`(PK `UUID`), `playback_logs`(PK `BIGSERIAL`).
* 주요 제약: `tracks.uploader_id` NULL 허용(비로그인 업로드), `duration_seconds > 0`(1초 미만은 1초로 보정), `status IN (PENDING, PROCESSING, READY, FAILED)`, `hls_playlist_key`는 `PENDING`일 때 NULL.
* Anti-BLOB: 바이너리는 DB에 저장하지 않고 S3 키만 기록합니다.

### 4.4. 클라이언트 API 계약 (PRD-05 §7, PRD-02/03)

| API | 요약 |
|---|---|
| `GET /oauth2/authorization/google?redirect_uri=...` | 로그인 진입. 성공 시 `{redirect_uri}?token={jwt}`, 실패 시 `?error={code}`로 리다이렉트 |
| `GET /api/auth/me` | 인증 필수. `{ userId, email, profileImageUrl, role }` / 미인증 `401` |
| `POST /api/tracks/upload` | `multipart/form-data`(`file` 필수, `title`·`artist`·`album`·`artworkFile` 선택) ➔ `202` `TrackResponse` |
| `GET /api/tracks` | `keyword`, `page`, `size`, `status` ➔ `createdAt DESC` 정렬 `TrackPageResponse` |
| `GET /api/tracks/{trackId}` | 상세·상태 조회 (클라이언트 변환 완료 폴링용) |
| `GET /api/tracks/{trackId}/play` | `READY`일 때 `StreamPlayResponse` + `STREAM` 이력 기록 / 미완료 `409` |
| `GET /api/tracks/{trackId}/download` | Body 없음. `X-Accel-Redirect` + `Content-Disposition`(RFC 5987) + `DOWNLOAD` 이력 기록 |

* **로그인 콜백 화이트리스트:** 웹 `http://localhost/auth/callback`, 앱 `musicapp://oauth2/callback` (`OAUTH2_ALLOWED_REDIRECT_URIS`, 완전 일치).
* **토큰 사용:** 모든 API 요청에 `Authorization: Bearer {jwt}`를 붙입니다. 로그아웃은 클라이언트가 토큰을 폐기합니다.
* **에러 응답:** `{ code, message, timestamp }` 단일 스키마. `400 INVALID_AUDIO_FORMAT / INVALID_ARTWORK / INVALID_REQUEST`, `401 UNAUTHORIZED`, `404 TRACK_NOT_FOUND`, `409 TRACK_NOT_READY`, `413 PAYLOAD_TOO_LARGE`, `500 INTERNAL_ERROR` (PRD-05 §7.2).

## 5. 공통 비기능 요구사항

### 5.1. 성능

* **미디어 바이트 비경유:** 청크·원본·앨범아트 바이트는 백엔드(톰캣) 스레드를 거치지 않습니다.
* **업로드 즉시 응답:** 동기 구간(검증·태그 추출·원본 저장)만 처리하고 `202`를 반환합니다. 변환은 비동기로 수행합니다.
* **변환 동시성 제한:** 전용 `transcodeExecutor`(`core 1`, `max 2`, `queue 50`)로 노드당 동시 변환 수를 제한하여 일반 API가 지연되지 않도록 합니다.
* **FFmpeg 타임아웃:** 최대 3분(`app.audio.ffmpeg-timeout-minutes`). 초과 시 강제 종료하고 `FAILED`로 처리합니다.
* **DB 커넥션 보호:** OSIV 비활성화(`open-in-view: false`).
* **목록 조회:** `size` 최대 100. `created_at DESC`, `(status, created_at DESC)` 인덱스를 활용합니다.

### 5.2. 보안

* **무상태 인증:** `HttpSession` 미사용(`STATELESS`). OAuth2 인가 요청(state)은 **쿠키 기반 저장소**에 보관하여 노드 간 분산 콜백에서도 로그인이 성공합니다.
* **Open Redirect 방지:** JWT는 화이트리스트와 완전 일치하는 `redirect_uri`로만 전달합니다.
* **MVP 권한 정책:** `/api/auth/me`를 제외한 모든 API는 `permitAll`입니다. 권한 통제는 추후 `SecurityFilterChain` 설정만으로 전환할 수 있도록 진입점을 백엔드 API로 단일화합니다.
* **시크릿 분리:** DB 비밀번호, Google Client ID/Secret, RustFS 키, JWT 서명 키는 환경변수로만 주입하고 저장소에 커밋하지 않습니다.
* **업로드 위변조 차단:** 음원·앨범자켓 모두 Tika Magic Number 기반 MIME 검증을 거칩니다. 확장자는 신뢰하지 않습니다.
* **경로 추측 방지:** 트랙 PK와 미디어 경로에 UUID를 사용합니다.
* **원본 보호:** `original/`은 익명 읽기를 금지하고 presigned URL(60초)로만 접근합니다. Nginx `internal;` 설정으로 외부 직접 호출도 차단합니다.
* **네트워크 노출 최소화:** RustFS(`9000`/`9001`)와 PostgreSQL(`5432`) 호스트 포트는 `127.0.0.1` 루프백에만 바인딩합니다. 외부 공개 포트는 Nginx `80` 하나입니다.

### 5.3. 운영 및 안정성

* **임시 파일 정리:** 트랙별 `${work-dir}/{uuid}/` 격리 폴더를 사용하고, `finally`에서 성공·실패와 무관하게 삭제합니다.
* **커밋 이후 비동기 실행:** 변환 이벤트는 `@TransactionalEventListener(AFTER_COMMIT)`로 수신합니다.
* **미완료 트랙 정리:** 큐가 포화되면 `FAILED`로 기록합니다. 기동 시 오래된 `PENDING`/`PROCESSING` 트랙을 `FAILED`로 정리하며, 자동 재변환은 MVP 범위에서 제외합니다.
* **업로드 보상 처리:** DB 저장이 실패하면 이미 업로드한 RustFS 객체를 삭제합니다.
* **프로파일:** `local`은 H2(`MODE=PostgreSQL`) + `ddl-auto: update`, `prod`는 PostgreSQL + PRD-07 `init.sql` + `ddl-auto: validate`입니다.
* **테스트:** Service 계층은 외부 인프라 없이 Mockito로 단위 테스트합니다. RustFS·FFmpeg는 인터페이스(`StorageService`, `FfmpegCommandRunner`)로 추상화합니다.

## 6. 플랫폼 분리 대비 원칙

**배포 환경에 따라 달라지는 호스트·URL은 코드와 설정 파일에 하드코딩하지 않고 모두 환경변수로 주입합니다.** 추후 웹·DB·스토리지가 별도 서버/플랫폼으로 분리되면 아래 값만 변경합니다.

| 분리 대상 | 백엔드 (PRD-05) | Nginx (PRD-04) | 그 외 필수 조치 |
|---|---|---|---|
| DB | `DB_HOST`, `DB_PORT` | - | 방화벽에서 `5432`를 백엔드 IP에만 허용, 필요 시 TLS |
| 스토리지 | `STORAGE_ENDPOINT` | `STORAGE_UPSTREAM`, `STORAGE_HOST_HEADER`(엔드포인트 호스트와 동일) | `9000`을 Nginx·백엔드 IP에만 허용, 퍼블릭망 경유 시 TLS 또는 사설망 |
| 백엔드 노드 | - | `BACKEND_UPSTREAM_1`, `BACKEND_UPSTREAM_2` | 노드 추가 시 upstream 항목 추가 |
| 웹 프론트 (별도 오리진/CDN) | `CORS_ALLOWED_ORIGINS`, `MEDIA_PUBLIC_BASE_URL`, `OAUTH2_ALLOWED_REDIRECT_URIS`·`OAUTH2_DEFAULT_REDIRECT_URI` | - | Google Console 리디렉션 URI 갱신 |

* 원본 보호는 presigned 서명에 기반하므로 네트워크 경계가 바뀌어도 수정할 필요가 없습니다.

## 7. 결정 이력 (2026-11-11 PRD 교차 검토)

| # | 발견된 문제 | 결정 | 반영 문서 |
|---|---|---|---|
| 1 | Spring OAuth2 기본 동작은 인가 요청을 `HttpSession`에 저장합니다. 2노드 라운드로빈에서는 콜백이 다른 노드로 가면 로그인이 실패합니다. | 쿠키 기반 `AuthorizationRequestRepository` 사용 | PRD-05 §6.1·§7.3, PRD-01 |
| 2 | JWT 전달 주소가 단일 설정(`http://localhost/`)이어서 앱 딥링크(`musicapp://`)를 지원하지 못합니다. 웹의 토큰 수신 방식도 정의되지 않았습니다. | `redirect_uri` 화이트리스트 다중 지원, `?token={jwt}` 형식, 웹 콜백 `/auth/callback` | PRD-05 §4·§6.1, PRD-02 §2.1, PRD-03 §3.1 |
| 3 | PRD-07의 `playback_logs`가 PRD-05에 정의되지 않았습니다. | MVP에 포함 (`/play` 시 STREAM, `/download` 시 DOWNLOAD 기록) | PRD-05 §5·§7.1.3 |
| 4 | 버킷 전체 익명 읽기와 9000 포트 공개 때문에 원본이 Nginx를 거치지 않고 노출될 수 있습니다. | 포트 루프백 바인딩, 익명 읽기는 `hls/`·`artwork/`만 허용, 원본은 presigned URL + `X-Accel-Redirect` | PRD-06 §3, PRD-04 §4, PRD-05 §7.1.2, PRD-07 §7, PRD-01 |
| 5 | 플랫폼 분리 시 Nginx·백엔드에 하드코딩된 호스트와 단일 오리진 전제를 수정해야 합니다. | 백엔드 `app.media.public-base-url`·`app.cors.allowed-origins` 추가, Nginx를 envsubst 템플릿으로 전환 | PRD-05 §4, PRD-04 §4·§7, PRD-06 §6 |
| 6 | User 엔티티에 `provider`·`lastLoginAt`이 없고, HLS 경로 저장값(URL과 S3 키)과 이벤트 DTO 필드명(`s3Key`/`originalS3Key`)이 문서마다 다릅니다. | PRD-07 DDL 기준으로 통일, DB에는 S3 키 저장, `originalS3Key`로 통일 | PRD-05 §5·§6.3, PRD-01 §4.2·§5.1 |
| 7 | 비동기 리스너가 커밋 전에 실행될 수 있고, 큐 포화나 서버 재시작 시 트랙이 PENDING에 영구히 멈춥니다. | `AFTER_COMMIT` 리스너, 큐 포화·재시작 시 `FAILED` 처리 | PRD-05 §6.3, PRD-01 §5.1 |
| 8 | Tika가 FLAC을 `audio/x-flac`으로 감지합니다. 한글 파일명 다운로드, 1초 미만 음원, 앨범자켓 검증, 에러 응답 형식이 정의되지 않았습니다. | 허용 MIME 추가, RFC 5987 `filename*`, 재생 시간 1초 보정, 앨범자켓 Tika·10MB 검증, 공통 에러 스키마 | PRD-05 §6.2·§7.1·§7.2 |
| 9 | PRD-05의 빌드 명세가 Spring Boot 3 스타터 명칭을 사용합니다. 로컬 H2와 `validate` 설정이 충돌합니다. | Boot 4 명칭(현재 `build.gradle.kts` 기준), `local`/`prod` 프로파일 분리 | PRD-05 §3.2·§4·§9 |
| 10 | Nginx의 `$upstream_http_content_disposition` 복사 방식은 동작이 보장되지 않고, `X-Forwarded-Host` 설정이 빠져 있습니다. | 변수 복사 제거(X-Accel-Redirect 원 응답 헤더 유지 활용), `X-Forwarded-Host` 추가, 통합 검증 항목 추가 | PRD-04 §4·§6 |
| 11 | `/api/auth/me`가 `permitAll`이면 미인증 요청의 응답이 정의되지 않습니다. | `/api/auth/me`만 인증 필수, 미인증 시 `401` JSON 응답 | PRD-05 §7.3 |

### 7.1. 통합 테스트에서 확인할 항목 (문서만으로는 확정 불가)

* Nginx가 `X-Accel-Redirect`를 처리할 때 백엔드 응답의 `Content-Disposition`을 유지하는지, `proxy_hide_header Content-Disposition`과 함께 써도 문제가 없는지 (PRD-04 §6-5)
* presigned 쿼리 문자열이 Nginx 내부 리다이렉트와 프록시를 거쳐도 서명 검증을 통과하는지 (PRD-04 §6-5)
* Spring Boot 4 환경에서 `jjwt-jackson`(Jackson 2)과 `io.minio:minio`(OkHttp 4)의 의존성 호환성 (PRD-05 §3.2)
* RustFS가 경로별 `Resource`(`hls/*`, `artwork/*`) 버킷 정책을 지원하는지 (PRD-06 §3, 원본 익명 접근 시 `403` 확인)

### 7.2. 향후 과제 (MVP 범위 밖)

* `auth_request /api/auth/check` 기반 스트리밍 권한 통제 (PRD-01 §5.3 2단계)
* Refresh Token 및 토큰 폐기(블랙리스트)
* 실패하거나 중단된 트랙의 자동 재변환, 외부 메시지 큐·워커 분리
* 다중 음질(ABR) 인코딩

## 8. 공통 규칙

* 용어 정의와 공통 비기능 요구사항(성능, 보안)은 이 파일에 둡니다.
* 기능을 구현하기 전에는 해당 번호의 PRD 파일을 반드시 읽습니다.
* 이 저장소는 PRD-05만 구현합니다. 다른 영역의 요구사항은 구현하지 않고 보고합니다.
* PRD 사이에 충돌이 발견되면 임의로 판단하지 말고 사용자에게 확인한 뒤, 결정 사항을 §7에 기록합니다.
* 인터페이스 계약(경로, 필드명, 헤더, 키 구조, 환경변수명)을 바꿀 때는 관련된 모든 PRD와 이 파일을 함께 갱신합니다.

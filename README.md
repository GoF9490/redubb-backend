# Music Streaming Backend

음원 스트리밍 및 원본 아카이빙 서비스(MVP)의 **백엔드 서버**입니다. 업로드된 음원의 원본과 HLS(`.m3u8`/`.ts`) 청크를 분리 저장하고, 스트리밍 진입점 조회·원본 다운로드 지시 API를 제공합니다.

* 스택: Spring Boot 4.1.2, Java 21, Gradle Kotlin DSL, Spring Security(Google OAuth2 + JWT), JPA(H2 / PostgreSQL), RustFS(S3 호환), FFmpeg
* 구성: Nginx(80) → 백엔드 클러스터(`web1`, `web2`) / RustFS(9000) / PostgreSQL(5432)
* 설계 기준 문서: `.claude/docs/PRD.md` 및 `PRD-05.md`

## 실행

```bash
./gradlew bootRun    # 기본 8080, local 프로파일(H2 콘솔: /h2-console)
./gradlew build      # 컴파일 + 테스트
./gradlew test       # 전체 테스트
```

### 주요 환경변수

| 이름 | 설명 | 필수 | 기본값 |
|---|---|:---:|---|
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | Google OAuth2 클라이언트 | O | - |
| `JWT_SECRET_KEY` | JWT 서명 키 (32바이트 이상) | O | - |
| `RUSTFS_ACCESS_KEY` / `RUSTFS_SECRET_KEY` | 스토리지 접근 키 | O | - |
| `DB_PASSWORD` | PostgreSQL 비밀번호 (`prod`) | prod | - |
| `SPRING_PROFILES_ACTIVE` | `local` / `prod` | | `local` |
| `STORAGE_ENDPOINT` | RustFS 엔드포인트 (Nginx `STORAGE_HOST_HEADER`와 host 일치) | | `http://rustfs:9000` |
| `OAUTH2_ALLOWED_REDIRECT_URIS` | 로그인 콜백 화이트리스트 (쉼표 구분) | | `http://localhost/auth/callback,musicapp://oauth2/callback` |
| `OAUTH2_DEFAULT_REDIRECT_URI` | `redirect_uri` 미지정 시 기본 콜백 | | `http://localhost/auth/callback` |
| `MEDIA_PUBLIC_BASE_URL` | `streamUrl`/`artworkUrl` 접두어 | | (빈 값, 상대경로) |
| `CORS_ALLOWED_ORIGINS` | CORS 허용 오리진 (쉼표 구분) | | (빈 값, CORS 비활성) |
| `JWT_EXPIRATION_MS` | 토큰 만료(ms) | | `86400000` (24시간) |
| `STORAGE_PRESIGNED_EXPIRY_SECONDS` | 원본 다운로드 presigned 유효 시간 | | `60` |

전체 목록은 `PRD-05.md` §4.3을 참조합니다.

---

# API 명세

## 1. 공통 사항

### 1.1. Base URL

Nginx 경유 `http://localhost`를 기준으로 합니다. 백엔드 직접 접근 시 `http://localhost:8080`입니다.

| 경로 | 처리 주체 |
|---|---|
| `/api/*`, `/oauth2/*`, `/login/oauth2/*` | 백엔드 |
| `/stream/*`, `/artwork/*` | Nginx → RustFS (백엔드는 URL만 응답) |
| `/internal-storage/*` | Nginx 내부 전용 (원본 다운로드) |

### 1.2. 인증

* 헤더: `Authorization: Bearer {jwt}`
* JWT는 Google 로그인 후 발급되며(§3.1), 기본 유효 기간은 24시간입니다. Refresh Token은 없고 로그아웃은 클라이언트가 토큰을 폐기합니다.
* **MVP 정책:** `GET /api/auth/me`만 인증이 필수이며, 나머지 API는 모두 `permitAll`입니다. 토큰이 없거나 무효여도 요청은 차단되지 않고 익명으로 처리됩니다.
* 인증된 사용자가 업로드·재생·다운로드하면 업로더/이력에 `userId`가 연결되고, 익명이면 `NULL`입니다.

### 1.3. 형식 규칙

| 항목 | 형식 |
|---|---|
| 응답 본문 | `application/json` (단, 다운로드는 본문 없음) |
| `createdAt` | UTC ISO-8601 (`2026-11-11T01:00:00Z`) |
| 에러 `timestamp` | Asia/Seoul ISO-8601 (`2026-11-11T10:00:00+09:00`) |
| `trackId` | UUID |
| 미디어 URL | `MEDIA_PUBLIC_BASE_URL` 값이 접두어. 빈 값이면 `/stream/...`처럼 상대경로 |

### 1.4. TrackStatus

| 값 | 의미 |
|---|---|
| `PENDING` | 원본 저장 완료, 변환 대기 |
| `PROCESSING` | FFmpeg HLS 변환 중 |
| `READY` | HLS 업로드 완료, 재생 가능 |
| `FAILED` | 변환 실패·타임아웃·큐 포화·서버 재시작 중단 |

전이: `PENDING → PROCESSING → READY | FAILED` (자동 재변환 없음)

## 2. 엔드포인트 요약

| 메서드 | 경로 | 인증 | 성공 | 설명 |
|---|---|:---:|:---:|---|
| GET | `/oauth2/authorization/google` | - | 302 | Google 로그인 진입 |
| GET | `/api/auth/me` | **필수** | 200 | 현재 로그인 사용자 조회 |
| POST | `/api/tracks/upload` | 선택 | 202 | 음원 업로드 |
| GET | `/api/tracks` | 선택 | 200 | 목록·키워드 검색 |
| GET | `/api/tracks/{trackId}` | 선택 | 200 | 상세·상태 조회 |
| GET | `/api/tracks/{trackId}/play` | 선택 | 200 | 스트리밍 진입점 조회 |
| GET | `/api/tracks/{trackId}/download` | 선택 | 200 | 원본 다운로드 지시 |

## 3. 엔드포인트 상세

### 3.1. Google 로그인

`GET /oauth2/authorization/google`

| 쿼리 | 타입 | 필수 | 설명 |
|---|---|:---:|---|
| `redirect_uri` | string (URL 인코딩) | 선택 | 로그인 후 토큰을 전달받을 클라이언트 콜백. 화이트리스트와 **완전 일치**해야 함 |

허용 콜백 (기본값): 웹 `http://localhost/auth/callback`, 모바일 `musicapp://oauth2/callback`

**흐름**

1. 클라이언트가 위 URL로 이동 → `302` Google 로그인 화면
2. 로그인 성공 → `302 {redirect_uri}?token={jwt}` (`redirect_uri` 미지정 시 기본 콜백)
3. 클라이언트는 콜백에서 `token`을 저장하고 이후 `Authorization: Bearer`로 사용

**실패 처리**

| 상황 | 리다이렉트 |
|---|---|
| 화이트리스트에 없는 `redirect_uri` | 토큰 없이 `{기본 콜백}?error=invalid_redirect_uri` |
| 로그인 실패 | `{콜백}?error={code}` — `access_denied`, `invalid_request`, `authorization_request_not_found`, `invalid_state_parameter`, `invalid_user_info`, `server_error`, `temporarily_unavailable`. 그 외는 `login_failed` |

**비고**

* 인가 요청(state)은 `oauth2_auth_request`, `oauth2_redirect_uri` 쿠키(HttpOnly, 180초)에 저장되어 노드 간 분산 콜백에서도 동작하며 처리 직후 삭제됩니다.
* 성공 응답에는 `Cache-Control: no-store`, `Pragma: no-cache`, `Referrer-Policy: no-referrer`가 붙습니다.
* 저장하는 사용자 정보는 `sub`, `email`, `picture`뿐이며 최초 로그인 시 자동 가입됩니다.

### 3.2. 현재 사용자 조회

`GET /api/auth/me` — 인증 필수

**응답 200**

```json
{
    "userId": 1,
    "email": "user@example.com",
    "profileImageUrl": "https://lh3.googleusercontent.com/a/example",
    "role": "ROLE_USER"
}
```

**에러:** `401 UNAUTHORIZED` (토큰 없음/무효, 삭제된 회원)

### 3.3. 음원 업로드

`POST /api/tracks/upload` — `multipart/form-data`

| 필드 | 타입 | 필수 | 제약 |
|---|---|:---:|---|
| `file` | file | **O** | MP3 / WAV / FLAC / M4A. 확장자가 아닌 실제 내용(Tika)으로 검증. 최대 100MB |
| `title` | string | | 최대 200자 |
| `artist` | string | | 최대 150자 |
| `album` | string | | 최대 200자 |
| `artworkFile` | file | | JPEG/PNG, 최대 10MB |

* 요청 전체 최대 110MB.
* 공백만 있는 값은 "비어 있음"으로 처리합니다. 제한 길이를 넘기면 `400`입니다.
* **제목:** 요청값 → ID3 태그 → 원본 파일명(확장자 제외) → `Unknown Title`
* **가수:** 요청값 → ID3 태그 → `Unknown Artist`
* **앨범:** 요청값 → ID3 태그 → `null`
* **앨범아트:** `artworkFile` → 음원 내장 이미지 → `null` (내장 이미지가 손상되었으면 무시하고 계속 진행)
* 재생 시간이 1초 미만이면 1초로 보정됩니다.
* 원본 저장까지만 동기 처리하고 즉시 응답하며, HLS 변환은 비동기입니다. `GET /api/tracks/{trackId}`로 상태를 폴링하세요.

**요청 예시**

```bash
curl -X POST http://localhost/api/tracks/upload \
    -H "Authorization: Bearer {jwt}" \
    -F "file=@song.mp3" \
    -F "title=My Song" \
    -F "artist=Artist" \
    -F "artworkFile=@cover.jpg"
```

**응답 202** — `TrackResponse`

```json
{
    "trackId": "3f2b8c1e-5a4d-4e7b-9c10-1a2b3c4d5e6f",
    "title": "My Song",
    "artist": "Artist",
    "album": null,
    "durationSeconds": 215,
    "status": "PENDING",
    "artworkUrl": "/artwork/3f2b8c1e-5a4d-4e7b-9c10-1a2b3c4d5e6f.jpg",
    "originalFilename": "song.mp3",
    "fileExtension": "mp3",
    "fileSizeBytes": 5242880,
    "createdAt": "2026-11-11T01:00:00Z"
}
```

**에러**

| 상태 | code | 조건 |
|---|---|---|
| 400 | `INVALID_AUDIO_FORMAT` | 허용되지 않는 형식, 손상 파일, 헤더 파싱 실패 |
| 400 | `INVALID_ARTWORK` | `artworkFile`이 JPEG/PNG가 아니거나 10MB 초과 |
| 400 | `INVALID_REQUEST` | `file` 누락, title/artist/album 길이 초과 |
| 413 | `PAYLOAD_TOO_LARGE` | 멀티파트 용량 초과 |
| 500 | `INTERNAL_ERROR` | 스토리지 업로드 실패 등 |

### 3.4. 음원 목록·검색

`GET /api/tracks`

| 쿼리 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| `keyword` | string | - | 제목 또는 가수 부분 일치(대소문자 무시). trim 후 200자 초과 시 400, 빈 값이면 전체 조회 |
| `status` | `PENDING`/`PROCESSING`/`READY`/`FAILED` | - | 상태 필터. 잘못된 값은 400 |
| `page` | int | `0` | 0 이상 (음수는 400) |
| `size` | int | `20` | 1 이상 (0 이하는 400), 100 초과 시 100으로 보정 |

정렬은 `createdAt DESC`(최신순) 고정입니다.

**응답 200** — `TrackPageResponse`

```json
{
    "content": [
        {
            "trackId": "3f2b8c1e-5a4d-4e7b-9c10-1a2b3c4d5e6f",
            "title": "My Song",
            "artist": "Artist",
            "album": null,
            "durationSeconds": 215,
            "status": "READY",
            "artworkUrl": null,
            "originalFilename": "song.mp3",
            "fileExtension": "mp3",
            "fileSizeBytes": 5242880,
            "createdAt": "2026-11-11T01:00:00Z"
        }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "hasNext": false
}
```

**에러:** `400 INVALID_REQUEST`

### 3.5. 음원 상세·상태 조회

`GET /api/tracks/{trackId}`

| 경로 변수 | 타입 | 설명 |
|---|---|---|
| `trackId` | UUID | 트랙 ID |

**응답 200** — `TrackResponse` (§3.3 예시와 동일 스키마). 모든 상태의 트랙을 조회할 수 있으며, 업로드 직후 `status`가 `READY`/`FAILED`가 될 때까지 폴링하는 용도로 사용합니다.

**에러:** `400 INVALID_REQUEST` (UUID 형식 오류), `404 TRACK_NOT_FOUND`

### 3.6. 스트리밍 진입점 조회

`GET /api/tracks/{trackId}/play`

`status == READY`일 때만 성공하며, 성공 시 재생 이력(`STREAM`)이 기록됩니다(기록 실패는 응답에 영향 없음).

**응답 200** — `StreamPlayResponse`

```json
{
    "trackId": "3f2b8c1e-5a4d-4e7b-9c10-1a2b3c4d5e6f",
    "streamUrl": "/stream/3f2b8c1e-5a4d-4e7b-9c10-1a2b3c4d5e6f/index.m3u8",
    "title": "My Song",
    "artist": "Artist",
    "durationSeconds": 215,
    "artworkUrl": "/artwork/3f2b8c1e-5a4d-4e7b-9c10-1a2b3c4d5e6f.jpg"
}
```

`streamUrl`은 HLS 매니페스트이며 청크는 Nginx가 RustFS에서 직접 전송합니다(HLS: AAC 192kbps, 6초 세그먼트).

**에러:** `400 INVALID_REQUEST`, `404 TRACK_NOT_FOUND`, `409 TRACK_NOT_READY`

### 3.7. 원본 다운로드

`GET /api/tracks/{trackId}/download`

백엔드는 본문 없이 **헤더로 Nginx에 내부 전송을 지시**하고, 실제 파일은 Nginx가 RustFS에서 가져와 클라이언트에 내려줍니다. 원본은 `PENDING` 상태부터 존재하므로 **상태와 무관하게** 허용되며, 성공 시 `DOWNLOAD` 이력이 기록됩니다.

**응답 200 (Body 없음)**

```http
X-Accel-Redirect: /internal-storage/original/2026/11/{trackId}.mp3?X-Amz-Algorithm=...&X-Amz-Signature=...
Content-Disposition: attachment; filename="song.mp3"; filename*=UTF-8''song.mp3
Content-Type: audio/mpeg
```

* `filename`은 ASCII 대체 이름, `filename*`은 RFC 5987 퍼센트 인코딩 원본 파일명(한글 지원)입니다.
* presigned 유효 시간은 기본 60초이며, `X-Accel-Redirect`는 Nginx 내부 헤더로 최종 클라이언트에는 전달되지 않습니다(Nginx 경유 시).

**에러:** `400 INVALID_REQUEST`, `404 TRACK_NOT_FOUND`, `500 INTERNAL_ERROR`

## 4. 데이터 스키마

### TrackResponse

| 필드 | 타입 | null | 설명 |
|---|---|:---:|---|
| `trackId` | UUID | | 트랙 ID |
| `title` | string | | 제목 |
| `artist` | string | | 가수 |
| `album` | string | O | 앨범 |
| `durationSeconds` | int | | 재생 시간(초, 최소 1) |
| `status` | TrackStatus | | 변환 상태 |
| `artworkUrl` | string | O | 앨범아트 URL (없으면 `null`) |
| `originalFilename` | string | | 업로드 원본 파일명 |
| `fileExtension` | string | | 감지된 확장자 (`mp3`/`wav`/`flac`/`m4a`) |
| `fileSizeBytes` | long | | 원본 크기(바이트) |
| `createdAt` | string | | 생성 시각 (UTC) |

내부 진단용 `errorMessage`는 응답에 포함되지 않습니다. 실패 여부는 `status == FAILED`로 확인합니다.

### TrackPageResponse

| 필드 | 타입 | 설명 |
|---|---|---|
| `content` | TrackResponse[] | 현재 페이지 항목 |
| `page` | int | 현재 페이지(0부터) |
| `size` | int | 적용된 페이지 크기 |
| `totalElements` | long | 전체 항목 수 |
| `totalPages` | int | 전체 페이지 수 |
| `hasNext` | boolean | 다음 페이지 존재 여부 |

### StreamPlayResponse

| 필드 | 타입 | null | 설명 |
|---|---|:---:|---|
| `trackId` | UUID | | 트랙 ID |
| `streamUrl` | string | | HLS 매니페스트 URL |
| `title` | string | | 제목 |
| `artist` | string | | 가수 |
| `durationSeconds` | int | | 재생 시간(초) |
| `artworkUrl` | string | O | 앨범아트 URL |

### UserResponse

| 필드 | 타입 | null | 설명 |
|---|---|:---:|---|
| `userId` | long | | 사용자 ID |
| `email` | string | | 이메일 |
| `profileImageUrl` | string | O | 구글 프로필 이미지 |
| `role` | string | | 권한 (`ROLE_USER`) |

### ErrorResponse

| 필드 | 타입 | 설명 |
|---|---|---|
| `code` | string | 에러 코드 (§5) |
| `message` | string | 사용자 표시용 메시지 |
| `timestamp` | string | 발생 시각 (`+09:00`) |

```json
{
    "code": "TRACK_NOT_READY",
    "message": "음원 변환이 아직 완료되지 않았습니다.",
    "timestamp": "2026-11-11T10:00:00+09:00"
}
```

## 5. 에러 코드

모든 오류는 위 `ErrorResponse` 단일 스키마로 응답합니다.

| 상태 | code | 발생 조건 |
|:---:|---|---|
| 400 | `INVALID_AUDIO_FORMAT` | 허용되지 않는 오디오 형식·손상 파일 |
| 400 | `INVALID_ARTWORK` | 앨범자켓이 JPEG/PNG가 아니거나 10MB 초과 |
| 400 | `INVALID_REQUEST` | 필수 파라미터/파트 누락, 잘못된 UUID·enum·숫자 형식, 길이·범위 위반 |
| 401 | `UNAUTHORIZED` | `/api/auth/me`에 토큰 없음/무효 |
| 404 | `TRACK_NOT_FOUND` | 존재하지 않는 `trackId` |
| 409 | `TRACK_NOT_READY` | `READY`가 아닌 트랙의 `/play` 호출 |
| 413 | `PAYLOAD_TOO_LARGE` | 업로드 용량 한도 초과 |
| 500 | `INTERNAL_ERROR` | 그 외 서버 오류 (상세는 응답에 노출하지 않음) |

그 외 Spring MVC 표준 예외(존재하지 않는 경로, 지원하지 않는 메서드 등)는 원래 HTTP 상태를 유지하며, 4xx는 `INVALID_REQUEST`, 5xx는 `INTERNAL_ERROR` 코드로 응답합니다.

## 6. 클라이언트 연동 흐름

**로그인**

1. `GET /oauth2/authorization/google?redirect_uri={콜백}`으로 이동
2. 콜백(`?token=` 또는 `?error=`)에서 토큰 저장
3. `GET /api/auth/me`로 사용자 정보 표시 (`401`이면 토큰 폐기)

**업로드 → 재생**

1. `POST /api/tracks/upload` → `202`, `status: PENDING`
2. `GET /api/tracks/{trackId}`를 주기적으로 폴링 → `READY` 또는 `FAILED`
3. `READY`이면 `GET /api/tracks/{trackId}/play`로 `streamUrl` 획득
4. HLS 플레이어로 `streamUrl` 재생

**다운로드:** `GET /api/tracks/{trackId}/download`를 Nginx 경유로 호출하면 원본 파일이 내려옵니다.

## 7. CORS

`CORS_ALLOWED_ORIGINS`가 비어 있으면(단일 오리진) CORS를 적용하지 않습니다. 값이 있으면 해당 오리진에 한해 `GET, POST, OPTIONS`와 `Authorization`, `Content-Type` 헤더를 허용하며 `allowCredentials`는 `false`입니다.

## 8. 참고 문서

| 파일 | 내용 |
|---|---|
| [`.claude/docs/PRD.md`](.claude/docs/PRD.md) | 인덱스·공통 규칙 |
| [`.claude/docs/PRD-01.md`](.claude/docs/PRD-01.md) | 전체 아키텍처 |
| [`.claude/docs/PRD-05.md`](.claude/docs/PRD-05.md) | 백엔드 상세 설계 |
| [`.claude/docs/PRD-06.md`](.claude/docs/PRD-06.md) | 음원 서버(RustFS·FFmpeg) |
| [`.claude/docs/PRD-07.md`](.claude/docs/PRD-07.md) | PostgreSQL 스키마 |

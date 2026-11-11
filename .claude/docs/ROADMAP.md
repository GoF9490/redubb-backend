# 백엔드 구현 로드맵 (PRD-05 기준)

> 기준 문서: `PRD.md`(공통 규칙), `PRD-01.md`(전체 구조), `PRD-05.md`(백엔드 상세). 엔티티는 `PRD-07.md` DDL을 계약으로 참조한다.
> 범위: **백엔드(PRD-05)만**. Nginx·RustFS 설정·`init.sql`·클라이언트는 구현하지 않는다(발견 시 보고).

## 0. 공통 진행 규칙

1. **태스크 사이클:** `탐색`(관련 PRD 절 재확인) → `계획` → `구현` → `검토` 순서로 진행한다. 태스크 하나는 한 세션(1~2시간)에 끝낸다.
2. **Service 테스트 필수:** Service 계층 코드를 작성하는 태스크는 해당 기능의 단위 테스트(`@ExtendWith(MockitoExtension.class)`, 외부 인프라 없음)를 **같은 태스크에서** 작성하고 `./gradlew test`까지 통과해야 완료다.
3. **완료 정의:** 완료 조건 전부 체크 + `./gradlew build` 통과 + 한국어 커밋(`/git:commit`) 후 태스크 제목 체크박스를 체크한다.
4. **범위 밖 / PRD 충돌:** 구현하지 말고 사용자에게 보고한다.
5. **주의 메모 태그:** `[탐색]` `[계획]` `[구현]` `[검토]` 중 해당 단계에서 특히 조심할 점만 적는다.
6. 코드 주석·문서는 한국어, 식별자는 영어(camelCase, 메서드는 동사 시작), 들여쓰기 4칸.

## Phase 간 의존 순서

```
Phase 0 (골격) → Phase 1 (도메인) → Phase 2 (인프라 추상화·미디어)
              → Phase 3 (업로드) → Phase 4 (HLS 변환) → Phase 5 (조회·재생·다운로드)
              → Phase 6 (인증) → Phase 7 (배포·통합 확인)
```
Phase 6의 `T6-1`, `T6-3`, `T6-6`은 Phase 2 이후 언제든 앞당길 수 있다.

---

## Phase 0 — 프로젝트 골격

### [x] T0-1 빌드 의존성 추가
- **목표:** PRD-05 §3.2 기준으로 `build.gradle.kts`에 누락 라이브러리를 추가한다(validation, JJWT 0.12.6, minio 8.5.14, tika-core 2.9.2, jaudiotagger 3.0.1).
- **선행:** 없음
- **완료 조건:**
  - [x] `./gradlew build` 통과
  - [x] `./gradlew dependencies`로 OkHttp 버전과 Jackson 2/3 공존 상태를 확인하고 결과를 커밋 메시지에 기록
  - [x] 기존 `MusicApplicationTests.contextLoads` 통과
- **주의:**
  - [탐색] Boot 4 BOM이 관리하는 OkHttp 버전과 minio 8.5.x 요구 버전 충돌 여부(PRD.md §7.1)
  - [검토] `jjwt-jackson`(Jackson 2)과 Boot 4 기본 Jackson 3가 공존해도 기동에 문제 없는지

### [x] T0-2 `application.yaml` 프로파일 구성
- **목표:** PRD-05 §4.2대로 공통 / `local`(H2, `MODE=PostgreSQL`) / `prod`(PostgreSQL, `validate`) 설정을 구성하고 테스트용 더미 값을 둔다.
- **선행:** T0-1
- **완료 조건:**
  - [x] `contextLoads`가 더미 환경값(`src/test/resources/application-test.yaml` 등)으로 통과
  - [x] `local` 프로파일 기동 시 `/h2-console` 경로 활성, `open-in-view=false` 확인(테스트로 검증)
  - [x] 시크릿 값이 저장소 파일에 평문으로 없음(`${...}` 주입만)
- **주의:**
  - [탐색] `GOOGLE_CLIENT_ID` 등 필수 환경변수 미주입 시 기동 실패 → 테스트 프로파일에서 더미 주입
  - [구현] 기존 `application.yaml`의 H2 설정(`testdb`)을 `streaming_db`로 교체
  - [검토] `.env` 파일이 `.gitignore`에 포함되는지

### [x] T0-3 `@ConfigurationProperties` 바인딩 5종
- **목표:** `JwtProperties`, `StorageProperties`, `AudioProperties`, `MediaProperties`, `CorsProperties`를 `config/properties`에 구현한다.
- **선행:** T0-2
- **완료 조건:**
  - [x] `ApplicationContextRunner` 테스트: 쉼표 구분 리스트(`allowed-redirect-uris`)가 `List<String>`으로 바인딩
  - [x] 빈 값 기본값(`public-base-url`, `allowed-origins`)이 `""`/빈 리스트로 바인딩
  - [x] 기본값(`presigned-expiry-seconds=60`, `ffmpeg-timeout-minutes=3`) 검증
- **주의:**
  - [계획] `@ConfigurationPropertiesScan` vs `@EnableConfigurationProperties` 중 하나로 통일
  - [구현] 빈 문자열이 `null`로 들어오는 경우 방어

### [x] T0-4 공통 예외 및 에러 응답
- **목표:** `ErrorCode` enum, `BusinessException` 계층(`InvalidAudioFormatException`, `InvalidArtworkException`, `TrackNotFoundException`, `TrackNotReadyException`), `ErrorResponse{code,message,timestamp}`, `@RestControllerAdvice`를 구현한다(PRD-05 §7.2).
- **선행:** T0-1
- **완료 조건:**
  - [x] `@WebMvcTest` + 테스트용 컨트롤러로 `400 INVALID_AUDIO_FORMAT / INVALID_ARTWORK / INVALID_REQUEST`, `404`, `409`, `413`, `500` 스키마 검증
  - [x] 500 응답 본문에 예외 상세·스택 미노출
  - [x] 잘못된 UUID 경로 변수 → `400 INVALID_REQUEST`
  - [x] `timestamp`가 ISO-8601(+09:00 또는 UTC) 형식
- **주의:**
  - [탐색] Boot 4/Spring 7에서 `MaxUploadSizeExceededException`, `MethodArgumentTypeMismatchException`의 패키지·핸들링 위치
  - [검토] 테스트용 컨트롤러는 `src/test`에만 두고 운영 코드에 섞지 않는다

### [x] T0-5 최소 Security 설정
- **목표:** 이후 Phase 6에서 확장할 최소 `SecurityConfig`(STATELESS, csrf/formLogin/httpBasic 비활성, 전체 `permitAll`)를 만든다.
- **선행:** T0-1
- **완료 조건:**
  - [x] `@WebMvcTest` 슬라이스로 임의 경로 요청 시 로그인 페이지 리다이렉트(302)가 없고 `404`(또는 컨트롤러 응답)가 반환
  - [x] 응답에 `Set-Cookie: JSESSIONID`가 없음
- **주의:**
  - [계획] `/api/auth/me`만 인증 필수로 두는 확정은 T6-2에서 한다(여기서는 골격만)
  - [검토] OAuth2 Client 스타터가 클라이언트 등록 없이 기동 실패하지 않는지

### [x] T0-6 테스트 기반(샘플 파일·헬퍼)
- **목표:** `src/test/resources/samples/`에 초소형 샘플(ID3+커버 1초 MP3, 무태그 1초 WAV, 1초 FLAC, 확장자만 `.mp3`인 텍스트 파일, jpg, png)을 만들고 로딩 헬퍼를 둔다. 10MB 초과 이미지는 테스트에서 바이트 배열로 생성한다.
- **선행:** 없음
- **완료 조건:**
  - [x] 샘플 로딩 테스트(`SampleFixtureTest`): 파일 존재·크기 > 0
  - [x] 샘플 생성 명령(ffmpeg 등)을 `src/test/resources/samples/README.md`에 기록
  - [x] 샘플 합계 크기 1MB 이하
- **주의:**
  - [탐색] 로컬에 ffmpeg가 없으면 생성 불가 → 사용자에게 설치/대체 방법 문의
  - [검토] 저작권 이슈가 없는 합성음(sine)만 사용

---

## Phase 1 — 도메인·영속성 (PRD-07 DDL 기준)

### [x] T1-1 엔티티·Enum
- **목표:** `TrackStatus`, `ActionType`, `User`, `Track`, `PlaybackLog`를 PRD-07 DDL과 1:1로 매핑한다.
- **선행:** T0-2
- **완료 조건:**
  - [x] `@DataJpaTest`: 3개 엔티티 저장·조회, `Track` UUID PK를 앱이 발급해 저장
  - [x] `User.providerId`·`email` 유니크 위반 시 예외
  - [x] 시간 컬럼이 `Instant`로 왕복 저장되고 `updatedAt`이 갱신 시 변경
  - [x] enum이 문자열로 저장(`status='PENDING'`)
- **주의:**
  - [탐색] PRD-07 §4·§6 DDL의 컬럼 길이·NULL 허용(`uploader_id`, `artwork_s3_key`, `hls_playlist_key`, `album`, `error_message`)
  - [구현] `@GeneratedValue` 사용 금지(UUID는 앱 발급), `@Enumerated(STRING)`
  - [검토] `ddl-auto: update`가 만든 H2 스키마와 DDL의 CHECK/기본값 차이는 T7-2에서 `validate`로 확인

### [x] T1-2 Repository 쿼리
- **목표:** `UserRepository.findByProviderId`, `TrackRepository`(키워드 검색 + status 필터 + `createdAt DESC` 페이징, 오래된 `PENDING/PROCESSING` 일괄 `FAILED` 갱신), `PlaybackLogRepository`를 구현한다.
- **선행:** T1-1
- **완료 조건:**
  - [x] `@DataJpaTest`: keyword가 제목 또는 가수에 부분 일치(대소문자 무시)
  - [x] keyword 없음 → 전체 최신순, status 필터 조합 검증
  - [x] 일괄 갱신 쿼리가 기준 시각(`updatedAt <` threshold) 이전 `PENDING/PROCESSING`만 `FAILED`+`errorMessage`로 변경하고 `READY`는 불변
- **주의:**
  - [구현] LIKE 와일드카드(`%`, `_`) 이스케이프 여부 결정
  - [검토] 벌크 UPDATE 후 영속성 컨텍스트 clear(`clearAutomatically`)와 `updatedAt` 처리

### [x] T1-3 `UserService` (Upsert)
- **목표:** OAuth2 정보(`sub`, `email`, `picture`)로 회원 조회/자동 생성/갱신(`lastLoginAt`).
- **선행:** T1-2
- **완료 조건:**
  - [x] `UserServiceTest` 1: 최초 로그인 시 신규 `User`(provider=GOOGLE, role=ROLE_USER, profileImageUrl) 저장
  - [x] `UserServiceTest` 2: 기존 `providerId`면 중복 저장 없이 email·profileImageUrl·lastLoginAt 갱신 후 반환
  - [x] `./gradlew test` 통과
- **주의:**
  - [계획] 이메일이 변경된 기존 회원의 email 유니크 충돌 처리 범위(MVP는 로그만/예외) 결정 후 기록
    - **결정(기록):** MVP는 사전 조회 없이 `DataIntegrityViolationException`을 그대로 전파한다. 동시 최초 로그인 경합(`uk_users_provider_id` 위반)도 미처리이며 재로그인 시 정상 동작한다. 기존 회원의 `picture`가 null이면 null로 덮어쓴다(PRD "최신값 갱신"). 메서드명은 `upsertUser`이므로 T6-4는 `upsertUser`를 호출한다. 시간은 `config/ClockConfig`의 `Clock` 빈을 주입한다.
  - [구현] 트랜잭션 경계(`@Transactional`)

### [x] T1-4 `PlaybackLogService`
- **목표:** STREAM/DOWNLOAD 이력 기록. `user`는 nullable, 저장 실패는 예외를 삼키고 로그만 남긴다(PRD-05 §7.1.3).
- **선행:** T1-2
- **완료 조건:**
  - [x] `PlaybackLogServiceTest`: 로그인 사용자/비로그인(`user=null`) 각각 저장 호출 검증
  - [x] 리포지토리가 예외를 던져도 메서드가 예외를 전파하지 않음
  - [x] `./gradlew test` 통과
- **주의:**
  - [계획] 호출자 트랜잭션이 롤백 전용으로 마킹되지 않도록 `REQUIRES_NEW` 또는 트랜잭션 밖 호출 여부 결정
    - **결정(기록):** `TransactionTemplate`(REQUIRES_NEW)을 서비스 내부에서 사용한다. 시그니처는 `recordLog(Track, Long userId, ActionType)`. `REQUIRES_NEW`의 커넥션 이중 사용, 존재하지 않는 userId의 FK 위반 시 이력 유실은 MVP 한계. Mockito로는 트랜잭션 동작을 검증할 수 없으므로 T5-3/T5-4 [검토]에서 `@SpringBootTest`(H2)로 확인한다.
  - [검토] 예외를 삼키되 `log.warn`에 trackId 포함

---

## Phase 2 — 외부 인프라 추상화·미디어 처리 컴포넌트

### [x] T2-1 `MediaUrlResolver`
- **목표:** S3 키 ➔ `streamUrl`/`artworkUrl` 변환(`app.media.public-base-url` 적용).
- **선행:** T0-3
- **완료 조건:**
  - [x] `MediaUrlResolverTest`: base 빈 값 → `/stream/{uuid}/index.m3u8`, 값 있음 → `{base}/stream/...`
  - [x] `artworkS3Key == null` → `artworkUrl == null`
  - [x] base 끝에 `/`가 있어도 `//` 중복 없음
  - [x] `./gradlew test` 통과
- **주의:** [구현] DB에는 URL이 아닌 S3 키만 저장(`hls_playlist_key`)

### [x] T2-2 `StorageService` 인터페이스 + MinIO 구현 (단건 연산)
- **목표:** 버킷 존재 확인/생성(기동 시), 원본·앨범아트 업로드(Content-Type 지정), 객체 삭제, presigned GET URL 생성, 객체 다운로드를 `StorageService` 인터페이스와 MinIO 구현체(`StorageConfig`로 `MinioClient` 빈)로 만든다.
- **선행:** T0-3
- **완료 조건:**
  - [x] `MinioClient`를 Mock으로 둔 단위 테스트: 업로드 시 지정한 key·contentType이 전달
  - [x] 버킷이 있으면 생성 호출 없음, 없으면 생성 호출
  - [x] presign 호출 시 만료 시간과 고정 region 사용(네트워크 호출 없이 서명)
  - [x] SDK 예외가 도메인 예외(`500 INTERNAL_ERROR` 대상)로 변환
  - [x] `./gradlew test` 통과
- **주의:**
  - [탐색] minio 8.5.14의 `getPresignedObjectUrl`·`region` 지정 방식(Context7로 확인)
  - [계획] 인터페이스 메서드는 `Resource`/`InputStream` 등 파일 객체를 이벤트로 넘기지 않는 형태로 설계
  - [검토] 버킷 정책 설정은 `storage-init` 소관 — 코드에 넣지 않는다

### [x] T2-3 `StorageService.uploadDirectoryRecursively`
- **목표:** 로컬 디렉터리를 `Files.walk`로 순회해 `hls/{uuid}/` 하위에 그대로 업로드하고 확장자별 Content-Type(`.m3u8`, `.ts`)을 지정한다.
- **선행:** T2-2
- **완료 조건:**
  - [x] `@TempDir` 테스트: 하위 폴더 포함 파일의 S3 키가 `hls/{uuid}/<상대경로>`로 생성
  - [x] `.m3u8 → application/vnd.apple.mpegurl`, `.ts → video/mp2t` 검증
  - [x] 하나라도 업로드 실패 시 예외 전파
  - [x] `./gradlew test` 통과
- **주의:**
  - [구현] Windows에서 `\` 경로 구분자를 `/`로 변환
  - [검토] 알 수 없는 확장자의 기본 Content-Type(`application/octet-stream`)

### [x] T2-4 `AudioMetadataValidator` ① Tika 검증
- **목표:** 음원 MIME 위변조 검증, MIME→확장자 정규화, 앨범자켓(JPEG/PNG, 10MB) 검증.
- **선행:** T0-6
- **완료 조건:**
  - [x] 정상 MP3/WAV/FLAC(`audio/x-flac` 포함) 통과
  - [x] 확장자만 `.mp3`인 텍스트 파일 → `InvalidAudioFormatException`
  - [x] 정규화: `audio/mpeg→mp3`, wav 계열→`wav`, flac 계열→`flac`, `audio/mp4→m4a`
  - [x] 아트워크: jpg/png 통과, 비이미지·10MB 초과 → `InvalidArtworkException`
  - [x] `./gradlew test` 통과
- **주의:**
  - [탐색] Tika가 샘플 WAV/M4A를 어떤 MIME으로 반환하는지 실제 확인(허용 목록에 없는 변형 발견 시 PRD 갱신 여부를 사용자에게 문의)
    - **확인 결과(기록):** MP3=`audio/mpeg`, WAV=`audio/vnd.wave`, FLAC=`audio/x-flac`, M4A=`audio/mp4` — 모두 PRD 허용 목록 안이며 변형은 없었다. M4A 실파일 `sample.m4a`를 추가했다(T2-5 JAudioTagger 검증에도 사용).
  - [검토] 검증 실패 시 임시 파일 삭제는 호출자(`TrackService`)의 finally 책임임을 명확히

### [x] T2-5 `AudioMetadataValidator` ② 태그·커버 추출
- **목표:** JAudioTagger로 재생 시간(1초 미만 → 1초 보정), 제목/가수/앨범 Fallback, 내장 앨범 커버 추출.
- **선행:** T2-4
- **완료 조건:**
  - [x] ID3 MP3에서 제목·가수·재생 시간·커버 바이트 추출
  - [x] 무태그 WAV: `title=파일명(확장자 제외)`, `artist="Unknown Artist"`, `album=null`
  - [x] request 값이 태그보다 우선
  - [x] 헤더 파싱 실패 → `InvalidAudioFormatException`
  - [x] 1초 미만 샘플은 `durationSeconds=1` (실파일 샘플이 모두 1초라 `normalizeDuration` 단위 테스트로 검증)
  - [x] `./gradlew test` 통과
- **주의:**
  - [탐색] JAudioTagger 3.0.1이 `File` 경로·확장자에 의존 → 임시 파일 확장자 규칙 확인
    - **확인 결과(기록):** `AudioFileIO.read(File)`은 확장자로 리더를 선택한다. `.bin`은 `CannotReadException`, 틀린 확장자는 `InvalidAudioFrameException`. 임시 파일은 반드시 Tika 감지 확장자(`AudioValidationResult.extension()`)로 만들어야 한다(T3-2 호출자 계약). 무태그 WAV/FLAC/M4A도 `getTag()`는 null이 아니었다(빈 태그 객체). 태그가 있는 M4A의 태그·커버 추출은 샘플이 없어 미검증(T7-3 스모크에서 확인). 내장 커버가 JPEG/PNG가 아니면 T3-2가 `InvalidArtworkException`을 잡아 커버를 null로 무시한다.
  - [구현] JAudioTagger 로거 소음 억제(테스트/운영 로그 레벨)
  - [검토] 공백 문자열을 "비어 있음"으로 취급

### [x] T2-6 `FfmpegCommandRunner`
- **목표:** `FfmpegCommandRunner` 인터페이스와 `ProcessBuilder` 구현(명령 구성, 타임아웃 후 `destroyForcibly`, 출력 스트림 소비, 마지막 로그 500자 요약).
- **선행:** T0-3
- **완료 조건:**
  - [x] 명령 인자 빌더 단위 테스트: PRD-05 §6.3-3 명령과 동일한 인자(`-b:a 192k -ar 44100 -ac 2 -hls_time 6 -hls_list_size 0`, `chunk_%03d.ts`)
  - [x] 결과 타입(성공/비정상 종료/타임아웃) 반환 테스트(실행기 부분은 가짜 프로세스 스크립트 또는 명령 주입으로 검증)
  - [x] 로그 요약이 500자 이하
  - [x] `./gradlew test` 통과
  - **구현 메모(기록):** `outputDir`은 러너가 만들지 않으므로 T4-2가 미리 생성해야 한다. 시작 실패는 예외 대신 `FAILED(-1, "FFMPEG_START_FAILED")`, 인터럽트는 `FAILED(-1, "INTERRUPTED")`로 반환한다. 이 PC에 ffmpeg가 없어 실제 FFmpeg 실행(`index.m3u8`, `chunk_000.ts` 생성)은 미검증이며 T7-3 스모크에서 확인한다.
- **주의:**
  - [계획] 테스트 가능성을 위해 "명령 생성"과 "프로세스 실행"을 분리
  - [구현] `redirectErrorStream(true)` 후 출력 소비 필수(버퍼 막힘 방지), 경로에 공백 있는 Windows 환경 고려
  - [검토] 타임아웃 시 자식 프로세스 잔류 여부

---

## Phase 3 — 업로드 동기 파이프라인

### [x] T3-1 변환 이벤트·디스패처
- **목표:** `TranscodeEventDto(trackId, originalS3Key)`, `TranscodeTaskDispatcher` 인터페이스, `ApplicationEventPublisher` 기반 구현.
- **선행:** T1-1
- **완료 조건:**
  - [x] 디스패처 단위 테스트: `dispatch(dto)`가 `publishEvent(dto)`를 정확히 1회 호출
  - [x] DTO에 파일 객체·로컬 경로 필드가 없음(필드 2개만)
  - [x] `./gradlew test` 통과
- **주의:** [검토] 호출부(`TrackService`)가 변환 구현체를 직접 참조하지 않는지

### [x] T3-2 `TrackService.upload` 정상 경로
- **목표:** 임시 파일 저장 → Tika 검증 → 메타/커버 결정(커스텀 > 내장 > null) → 원본·앨범아트 저장 → `PENDING` 저장 → 이벤트 발행.
- **선행:** T1-2, T2-2, T2-5, T3-1
- **완료 조건:**
  - [x] `TrackServiceTest` 업로드 ①: 커스텀 `artworkFile` 있으면 우선 저장, 없으면 내장 커버 저장, 둘 다 없으면 `artworkS3Key=null`
  - [x] 원본 키가 `original/{yyyy}/{mm}/{uuid}.{ext}`, 앨범 키가 `artwork/{uuid}.jpg`, Content-Type이 실제 MIME
  - [x] 저장된 `Track`이 `status=PENDING`, `hlsPlaylistKey=null`
  - [x] `TranscodeEventDto(trackId, originalS3Key)` 1회 발행
  - [x] `./gradlew test` 통과
  - **구현 메모(기록):** 시그니처 `Track upload(TrackUploadRequest, Long userId)`. 임시 파일은 `{uuid}_raw.upload`로 저장 → Tika 검증 → `{uuid}_raw.{ext}`로 이름 변경 → `extractMetadata`. 스토리지 업로드는 트랜잭션 밖, `save`+`dispatch`만 `TransactionTemplate`으로 묶음. `userId`가 있으면 `findById().orElse(null)`로 uploader 설정(존재하지 않으면 익명). 길이 정책: request로 직접 보낸 title/artist/album 한도(200/150/200자) 초과는 `InvalidRequestException`(400), 태그·파일명 파생값과 originalFilename(255)은 절단. 0바이트 artworkFile은 없는 것으로 취급, 내장 커버가 유효하지 않으면 null로 무시. `userId`가 있는 경로는 Mockito로만 검증했으므로 detached `User`를 `Track.uploader`로 저장하는 실제 DB 동작은 T4-5/T6-7 통합 확인 시 함께 본다.
- **주의:**
  - [계획] 트랜잭션 경계: 스토리지 업로드는 DB 트랜잭션 밖, 엔티티 저장+이벤트 발행은 트랜잭션 안(커밋 후 리스너 동작 전제)
  - [구현] `uuid` 생성은 서비스에서, 연·월 계산은 `Clock` 주입(테스트 고정)
  - [검토] `request.title` 길이(200자)·`artist`(150자) 초과 시 처리

### [x] T3-3 보상 처리·임시 파일 정리
- **목표:** DB 저장 실패 시 업로드한 원본·앨범아트 삭제, `finally`에서 임시 파일 삭제, 비로그인 업로드(`uploader=null`) 지원.
- **선행:** T3-2
- **완료 조건:**
  - [x] `TrackServiceTest` 업로드 ②: 저장 예외 시 `deleteObject`가 원본·앨범아트에 호출되고 예외 전파
  - [x] 성공·실패·검증 실패 모두에서 임시 파일 삭제 검증(`@TempDir` 하의 work-dir)
  - [x] `uploader` 없음/있음 두 경로 저장 검증
  - [x] 삭제 자체가 실패해도 원래 예외가 가려지지 않음
  - [x] `./gradlew test` 통과
  - **구현 메모(기록):** 업로드에 성공한 키만 `uploadedKeys`에 담아 `RuntimeException` 시 삭제(`deleteObject(null)` 불가). 보상 삭제 실패는 `log.error`(key만)+`addSuppressed`. `Files.size`는 업로드 전에 계산. 한계: 커밋 직후 연결 오류처럼 커밋 여부가 불확실한 예외에서도 보상 삭제가 실행될 수 있음(MVP 한계, 이 경우 변환이 FAILED로 끝남). 원본 업로드 자체 실패 시 삭제하지 않는 근거는 S3 putObject의 원자성이며, 대용량(멀티파트) 업로드 도중 실패 시 미완료 파트는 객체로 보이지 않는다(스토리지 수명주기 정리는 인프라 소관). AFTER_COMMIT 리스너가 커밋 실패 시 실행되지 않는지는 Mockito로 검증할 수 없어 **T4-5 통합 테스트에서 확인**한다.
- **주의:**
  - [계획] 이벤트 발행 후 커밋 실패 시 리스너가 실행되지 않으므로(AFTER_COMMIT) 보상과 충돌하지 않는지 확인
  - [검토] 앨범아트가 없는 경우 `deleteObject(null)` 호출 방지

### [x] T3-4 업로드 컨트롤러
- **목표:** `TrackController`의 `POST /api/tracks/upload`와 `TrackUploadRequest`, `TrackResponse` DTO.
- **선행:** T3-3, T0-4, T2-1
- **완료 조건:**
  - [x] `@WebMvcTest`: 정상 업로드 → `202` + `TrackResponse`(`status="PENDING"`, `errorMessage` 미노출)
  - [x] `file` 누락 → `400 INVALID_REQUEST`
  - [x] 서비스가 던지는 `InvalidAudioFormatException` → `400 INVALID_AUDIO_FORMAT`
  - [x] 용량 초과 → `413 PAYLOAD_TOO_LARGE`
  - [x] `./gradlew test` 통과
  - **구현 메모(기록):** 바인딩은 `@RequestPart("file")`·`@RequestPart(value="artworkFile", required=false)`·`@RequestParam(required=false)` 개별 파라미터 방식(file 누락 시 `MissingServletRequestPartException` → 기존 핸들러 400). `TrackResponse.from(Track, MediaUrlResolver)`는 T5-1/T5-2에서 재사용한다. `userId`는 `resolveUserId()` 한 곳에서 null 반환(T6-7 교체 지점). `createdAt`은 `Instant`(Z) 직렬화. 413은 서비스 mock이 `MaxUploadSizeExceededException`을 던지는 핸들러 매핑 검증뿐이며 **실제 Tomcat/multipart 한도 통합은 T7-3 스모크에서 확인**한다.
- **주의:**
  - [탐색] Boot 4 MockMvc multipart 테스트 API
  - [검토] `TrackResponse` 필드가 PRD-05 §7.1.1과 정확히 일치(`artworkUrl`은 resolver 경유)

---

## Phase 4 — 비동기 HLS 변환

### [x] T4-1 `transcodeExecutor` 및 큐 포화 처리
- **목표:** `AsyncConfig`에 `transcodeExecutor`(core 1, max 2, queue 50)를 등록하고, 큐 포화(`TaskRejectedException`) 시 트랙을 `FAILED(TRANSCODE_QUEUE_FULL)`로 기록한다.
- **선행:** T3-1
- **완료 조건:**
  - [x] 설정 테스트: executor의 core/max/queue 값 검증
  - [x] 거부 발생 시 `FAILED`와 `errorMessage="TRANSCODE_QUEUE_FULL"` 갱신 검증(단위 테스트)
  - [x] `./gradlew test` 통과
- **주의:**
  - [계획] AFTER_COMMIT 시점의 `@Async` 거부 예외는 호출부로 전파되지 않을 수 있다 → 예외를 어디서 잡을지(커스텀 `RejectedExecutionHandler` 등) 먼저 설계
    - **결정·실측(기록):** Spring 7.0.10에서 `AFTER_COMMIT` 리스너가 던진 예외는 `afterCompletion`에서 로그로만 남고 `TransactionTemplate.execute`로 전파되지 않는다(통합 테스트 대조군으로 확인). 거부된 `@Async` 작업에는 `trackId`가 없어 `RejectedExecutionHandler`/`AsyncUncaughtExceptionHandler`로는 `FAILED` 기록이 불가능하다. 그래서 **리스너와 `@Async`를 분리**했다: 동기 `TranscodeEventRelay`(`@TransactionalEventListener(AFTER_COMMIT)`)가 `TranscodeProcessor.process(dto)`(`@Async("transcodeExecutor")`)를 호출하고 `TaskRejectedException`을 잡아 `TranscodeRejectionHandler`(`REQUIRES_NEW`)로 `FAILED(TRANSCODE_QUEUE_FULL)` 기록(실제 H2 반영 확인). `TranscodeProcessor` 구현체가 아직 없으므로 릴레이는 `ObjectProvider`로 지연 주입하고 부재 시 경고 로그만 남긴다.
  - [검토] `@EnableAsync` 위치와 `AsyncUncaughtExceptionHandler`
    - **결정(기록):** `@EnableAsync`는 `AsyncConfig`에 둔다. `AsyncConfigurer`는 구현하지 않고 기본 `SimpleAsyncUncaughtExceptionHandler`를 쓴다(작업 내부 예외는 T4-2/T4-3이 직접 `FAILED` 처리). 종료 시 실행 중 작업은 인터럽트(PRD 무지정, 기본값 유지). 참고: `ThreadPoolExecutor`는 큐(50)가 가득 차야 두 번째 스레드(max 2)를 만들므로 평소 동시 변환은 1건이다.

### [x] T4-2 `HlsTranscodeService` 성공 경로
- **목표:** `TranscodeProcessor`를 구현하고 `@Async("transcodeExecutor")`로 동작하는 `HlsTranscodeService.process(dto)`가 `PROCESSING` → 원본 다운로드 → FFmpeg → 재귀 업로드 → `READY`(`hls/{uuid}/index.m3u8`)로 처리한다. (이벤트 수신 `@TransactionalEventListener(AFTER_COMMIT)`는 T4-1의 동기 `TranscodeEventRelay`가 담당하며 `process`를 호출한다. 구현체가 생기면 릴레이의 `ObjectProvider` 지연 주입을 필수 주입으로 전환한다.)
- **선행:** T2-3, T2-6, T4-1
- **완료 조건:**
  - [x] `HlsTranscodeServiceTest` 성공: 상태 전이 `PROCESSING → READY`, `hlsPlaylistKey="hls/{uuid}/index.m3u8"`
  - [x] `uploadDirectoryRecursively`가 `${work-dir}/{uuid}/hls`를 대상으로 호출
  - [x] 작업 디렉터리 `${work-dir}/{uuid}/input.{ext}` 경로 규칙 검증
  - [x] `./gradlew test` 통과
  - **구현 메모(기록):** `process`는 짧은 `REQUIRES_NEW` 트랜잭션으로 `PROCESSING`을 FFmpeg 시작 전에 커밋하고, 다운로드·FFmpeg·업로드는 트랜잭션 밖에서 수행하며, 성공 시 `READY`+`hlsPlaylistKey`를 별도 커밋한다. `PENDING`이 아니거나 없는 트랙은 경고 로그 후 건너뛴다(다중 노드 동시 처리 경합은 낙관적 락이 없어 MVP 한계). 원본 키는 이벤트 키 사용(DB와 다르면 warn). **T4-3 선반영:** 성공이 아닌 `FfmpegResult`(TIMEOUT은 `FFMPEG_TIMEOUT`, 그 외는 `logSummary`)와 예외(클래스명)를 `FAILED`로 기록하고 `finally`에서 `work-dir/{uuid}` 전체를 삭제한다. T4-3은 이 위에 메시지 규칙·500자 제한·업로드 예외·정리 중 예외 비가림 검증을 보강한다. `TranscodeEventRelay`는 `TranscodeProcessor` 필수 주입으로 전환했다.
- **주의:**
  - [탐색] 상태 갱신은 별도 트랜잭션(리스너는 새 트랜잭션 필요: `REQUIRES_NEW`)
  - [구현] 존재하지 않는 `trackId` 수신 시 예외 전파 대신 로그
  - [검토] `PROCESSING` 전이를 FFmpeg 시작 전에 커밋하는지

### [x] T4-3 실패·타임아웃·정리
- **목표:** FFmpeg 비정상 종료/예외/타임아웃을 `FAILED`로 기록하고(`FFMPEG_TIMEOUT`, 에러 메시지 500자 제한) 임시 폴더를 항상 삭제한다.
- **선행:** T4-2
- **완료 조건:**
  - [x] `HlsTranscodeServiceTest` 실패: `exitCode != 0` → `FAILED` + 로그 요약 기록
  - [x] 타임아웃 → `FAILED`, `errorMessage="FFMPEG_TIMEOUT"`
  - [x] 성공·실패·예외 모든 경우 임시 폴더 삭제 검증
  - [x] 업로드 도중 예외 시에도 `FAILED` 처리
  - [x] `./gradlew test` 통과
- **주의:**
  - [구현] `errorMessage` 500자 초과 절단(DB 컬럼 길이 확인)
    - **확인(기록):** `Track.markFailed`가 500자로 절단하고 DB 컬럼도 `length=500`이며 `FfmpegResult`도 500자 이하를 보장한다. 서비스에 중복 절단은 두지 않는다. 예외 경로의 `errorMessage`는 스토리지 키·경로 노출을 피하려 예외 **클래스명만** 기록한다(상세는 서버 로그).
  - [검토] `finally` 정리 중 예외가 원래 예외를 덮지 않는지
    - **확인(기록):** 정리 로직을 package-private `deleteWorkDir`로 분리하고 Mockito spy로 정리 실패를 결정적으로 재현해 READY/FAILED 결과가 가려지지 않음을 검증했다.
  - **알려진 한계(기록):** 업로드 도중 실패한 트랙의 부분 업로드 객체(`hls/{uuid}/`)는 정리하지 않는다(`StorageService`에 prefix 삭제 API 없음). 인터럽트 플래그가 선 채로의 `FAILED` 기록은 최선 노력(로그만)이며, 이때 `PROCESSING`으로 남은 트랙은 T4-4 기동 복구가 정리한다.

### [x] T4-4 `TranscodeRecoveryRunner`
- **목표:** 기동 시 `updatedAt`이 `ffmpeg-timeout-minutes × 2` 이상 경과한 `PENDING/PROCESSING` 트랙만 `FAILED("INTERRUPTED_BY_RESTART")`로 갱신한다(자동 재변환 제외).
- **선행:** T1-2
- **완료 조건:**
  - [x] `TranscodeRecoveryRunnerTest`: `Clock` 고정 시 threshold 시각이 `now - timeout×2`로 계산되어 리포지토리에 전달
  - [x] 갱신 건수 0건일 때도 정상 종료
  - [x] `./gradlew test` 통과
- **주의:**
  - [계획] 다중 노드 재시작 시 정상 변환 중인 트랙 보호가 핵심(PRD-05 §6.3-5)
    - **결정(기록):** 같은 조건의 UPDATE가 중복 실행될 뿐이라 멱등이며, 정상 변환 중인 `PROCESSING`은 `updatedAt`이 최근이라 기본값(timeout 3분) 기준 6분 이내에는 보호된다. `TransactionTemplate`으로 벌크 UPDATE의 트랜잭션 경계를 명시한다. 복구 쿼리 실패는 `error` 로그만 남기고 기동을 계속한다(복구는 부가 정리이며 멱등이라 다음 기동 때 재정리).
    - **알려진 한계(기록):** 변환 큐가 용량 50·core 1이라 부하 시 큐 뒤쪽 `PENDING`은 수십 분 대기할 수 있다. 큐 대기가 `timeout×2`(6분)를 넘는 `PENDING`은 다른 노드 재시작 시 `FAILED("INTERRUPTED_BY_RESTART")`가 될 수 있다(이후 워커가 꺼내도 `HlsTranscodeService`의 `PENDING` 가드가 `FAILED` 트랙을 건너뜀). PRD 정책(`updatedAt` 기준)을 따른 한계이며 `PENDING` 임계 분리 같은 개선은 MVP 이후로 미룬다.
  - [검토] 테스트 프로파일에서 Runner가 실제 DB를 건드려 다른 테스트에 영향 주지 않는지
    - **확인(기록):** `@Profile("!test")`로 `@SpringBootTest`(`local`+`test`)에서는 Runner가 등록되지 않는다(Runner 로그가 나온 결과 파일은 `TranscodeRecoveryRunnerTest` 하나뿐).

### [x] T4-5 이벤트 연결 통합 확인
- **목표:** 업로드 트랜잭션 **커밋 이후**에만 변환 리스너가 호출되는지 확인한다.
- **선행:** T4-3, T3-3
- **완료 조건:**
  - [x] `@SpringBootTest` + `StorageService`/`FfmpegCommandRunner` Mock: 커밋 시 리스너 호출, 롤백 시 미호출
  - [x] 리스너가 `transcodeExecutor` 스레드에서 실행됨(스레드명 검증)
  - [x] `./gradlew test` 통과
  - **통합 검증 결과(기록):** 앞 태스크에서 이관된 항목을 모두 실제 `@SpringBootTest`(H2)로 확인했다 — ① 커밋 후에만 변환 호출, 롤백(`setRollbackOnly`) 시 미호출, ② `transcode-` 스레드 실행, ③ 실제 큐 포화(실행 2 + 큐 50 = 52건 수용, 53번째 거부 → `FAILED(TRANSCODE_QUEUE_FULL)`, 나머지 52건 `READY`), ④ `userId`가 있는 업로드의 `Track.uploader` 실제 DB 저장(T3-2 이관), ⑤ 업로드 → 이벤트 → `process` → `READY` end-to-end(`hlsPlaylistKey=hls/{uuid}/index.m3u8`), ⑥ `dispatch` 실패 시 `upload` 전체 경로의 롤백 + 보상 삭제(T3-3 이관). 전체 테스트 2회 연속 통과로 플레이크 없음을 확인했다.
- **주의:**
  - [탐색] `@Transactional` 테스트는 기본 롤백이라 AFTER_COMMIT이 호출되지 않음 → `TransactionTemplate` 사용
  - [검토] 비동기 검증은 `Awaitility` 없이도 가능한 방식(CountDownLatch) 우선
    - **결정(기록):** 스레드 동기화·블록/해제는 `CountDownLatch`, 상태 폴링은 `spring-boot-starter-test`에 이미 포함된 Awaitility 4.3.0을 사용했다(`build.gradle.kts` 수정 없음). 롤백 미호출 검증은 시간 의존적 부정 검증(`after(300ms).never()`)이며 Javadoc에 근거를 남겼다.

---

## Phase 5 — 조회·재생·다운로드

### [x] T5-1 목록·상세 서비스
- **목표:** `TrackService.getTracks/getTrack`과 `TrackPageResponse` 변환(`size` 최대 100 제한).
- **선행:** T1-2, T2-1
- **완료 조건:**
  - [x] `TrackServiceTest` 조회 ①: keyword 없음 → 전체 최신순 호출, keyword 있음 → 검색 쿼리 호출
  - [x] `size=500` 요청 시 100으로 보정
  - [x] 존재하지 않는 id → `TrackNotFoundException`
  - [x] `TrackPageResponse`가 `{content,page,size,totalElements,totalPages,hasNext}` 고정 스키마
  - [x] `./gradlew test` 통과
- **주의:** [구현] Spring `Page`를 그대로 직렬화하지 않는다
  - **구현 메모(기록):** `TrackService.getTracks(keyword, status, page, size)`는 `Page<Track>`, `getTrack(UUID)`는 `Track`을 반환하고 DTO 변환은 T5-2 컨트롤러가 `TrackPageResponse.from(Page<Track>, MediaUrlResolver)`/`TrackResponse.from`으로 한다(T3-4와 동일 방식). 분기: keyword 없음/있음 × status 없음/있음 = 4개 repository 메서드. 정책: `size>100`은 100으로 보정, `page<0`·`size<1`은 `InvalidRequestException`(400), keyword는 trim 후 200자 초과 시 400(제목 한도와 맞춤), 공백 keyword는 전체 조회, 상세 조회는 모든 status 허용. 조회 메서드는 `@Transactional(readOnly = true)`(TrackService가 CGLIB 프록시가 되지만 기존 통합 테스트는 영향 없음). `TrackResponse.from`은 LAZY `uploader`를 읽지 않는다. `TrackPageResponse`의 JSON 직렬화(고정 6필드) 확인은 T5-2 `@WebMvcTest`에서 한다. 테스트는 기존 `TrackServiceTest`를 건드리지 않고 `TrackQueryServiceTest`로 분리했다.

### [x] T5-2 목록·상세 컨트롤러
- **목표:** `GET /api/tracks`, `GET /api/tracks/{trackId}`.
- **선행:** T5-1
- **완료 조건:**
  - [x] `@WebMvcTest`: 기본값(page 0, size 20), 쿼리 파라미터 전달 검증
  - [x] 잘못된 UUID → `400`, 없는 트랙 → `404 TRACK_NOT_FOUND`
  - [x] `./gradlew test` 통과
- **주의:** [검토] `status` 파라미터가 잘못된 enum 값일 때 `400 INVALID_REQUEST`
  - **확인(기록):** 실행으로 확인 — `status=FOO`·소문자 `ready`·`page=abc`·`size=abc`는 400 `INVALID_REQUEST`(enum 변환은 대소문자 구분이며 그대로 둔다). `status=`/`keyword=`(빈 값)는 400이 아니라 서비스에 `status=null`, `keyword=""`로 전달된다. `size=500`은 컨트롤러가 보정하지 않고 서비스에 전달(보정은 서비스 책임). `GET /api/tracks/upload`는 UUID 변환 실패로 400. 목록 응답은 키 정확히 6개, 상세는 11개 필드(`errorMessage` 미노출), `createdAt`은 `Z` 포함 ISO-8601.

### [x] T5-3 재생 진입점
- **목표:** `getPlayInfo`와 `GET /api/tracks/{id}/play` (`READY` 검증, `409`, `STREAM` 이력).
- **선행:** T5-1, T1-4
- **완료 조건:**
  - [x] `TrackServiceTest` 재생 ①: `PROCESSING`/`PENDING`/`FAILED` → `TrackNotReadyException`
  - [x] `READY` → `StreamPlayResponse(trackId,streamUrl,title,artist,durationSeconds,artworkUrl)` 반환
  - [x] 성공 시 `PlaybackLogService`가 `STREAM`으로 1회 호출, 실패 시 호출 없음
  - [x] 컨트롤러 `@WebMvcTest`: `200`/`409`/`404`
  - [x] `./gradlew test` 통과
  - **구현 메모(기록):** `getPlayInfo(UUID, Long userId)`는 조회(404) → `READY` 검증(409) → 응답 조립 → `recordLog(STREAM)`(마지막) 순서이며 `@Transactional`을 두지 않는다(읽기 전용 트랜잭션 안에서 `REQUIRES_NEW`를 호출하면 요청당 커넥션 2개를 동시에 점유해 풀 고갈 위험). `READY`인데 `hlsPlaylistKey`가 null이면 경고 로그 후 409. `TrackService` 생성자 끝에 `MediaUrlResolver`·`PlaybackLogService`가 추가되었고 T5-4가 그대로 재사용한다. 컨트롤러는 `resolveUserId()`(T6-7 교체 지점)를 사용한다.
- **주의:** [검토] 이력 기록 실패가 응답을 막지 않는지(T1-4와 연동)
  - [검토] `PlaybackLogService`가 `TransactionTemplate`(REQUIRES_NEW)로 동작하므로, 호출자(`TrackService`)가 `readOnly` 트랜잭션이어도 이력이 저장되고 저장 실패 시 `UnexpectedRollbackException`이 나지 않는지 `@SpringBootTest`(H2) 통합 테스트로 확인한다(Mockito로는 검증 불가).
    - **확인(기록, `PlayInfoIntegrationTest`):** 익명(`user=null`)·로그인(`user` 연결) `STREAM` 이력이 실제로 저장된다. `readOnly` `TransactionTemplate` 안에서 `recordLog`를 호출해도 이력이 저장되고 커밋 시 예외가 없다. 존재하지 않는 `userId`(`getReferenceById` → FK 위반)에서도 `getPlayInfo`는 정상 응답하고 이력은 0건이며 `UnexpectedRollbackException`은 발생하지 않는다(readOnly 안에서도 동일). 통합 테스트 DB는 `ddl-auto=update`라 `ON DELETE CASCADE`가 없어 기반 클래스가 `PlaybackLog`를 먼저 삭제한다.

### [x] T5-4 원본 다운로드 지시
- **목표:** `getDownloadInfo`와 `GET /api/tracks/{id}/download` — presigned URL을 `X-Accel-Redirect`로 변환하고 RFC 5987 `Content-Disposition`을 구성한다.
- **선행:** T5-1, T2-2, T1-4
- **완료 조건:**
  - [x] `TrackServiceTest` 다운로드 ①: presigned URL 스텁에서 `/internal-storage/original/...?{서명 쿼리}`를 **쿼리 재인코딩 없이** 생성
  - [x] 한글 파일명 → `filename*=UTF-8''<퍼센트 인코딩>`과 ASCII 대체 `filename` 동시 포함
  - [x] `PENDING` 상태에서도 다운로드 허용, 없는 트랙 `404`
  - [x] 성공 시 `DOWNLOAD` 이력 1회 기록
  - [x] 컨트롤러 테스트: `200`, Body 비어 있음, `X-Accel-Redirect`·`Content-Disposition`·`Content-Type` 헤더 검증
  - [x] `./gradlew test` 통과
- **주의:**
  - [탐색] PRD-05 §7.1.2의 경로 분리 규칙(`/{bucket}/` 이후 경로 + 원본 쿼리)
    - **구현(기록):** `InternalRedirectPathConverter`가 `java.net.URI` 재조립 없이 문자열로 `/{bucket}/` 접두를 제거하고 `/internal-storage/`를 붙이며 쿼리(`?`부터 끝까지)를 한 글자도 바꾸지 않는다. 실제 `MinioClient`가 서명한 URL로 쿼리가 바이트 단위로 보존됨을 검증했다. 전제는 path-style(`{scheme}://{host}[:{port}]/{bucket}/{key}?{query}`, endpoint에 경로 접두 없음)이며 깨지면 `IllegalStateException`(500, 메시지에 URL·서명 없음).
  - [구현] `Content-Disposition`의 따옴표·줄바꿈·특수문자(헤더 인젝션) 이스케이프
    - **구현(기록):** `ContentDispositionBuilder`가 제어문자(CR/LF 포함)를 제거하고 경로 구분자 앞부분을 잘라 255자로 절단한 뒤, ASCII 대체(`" \ ; %`와 비ASCII는 `_`)와 RFC 5987 `filename*`(attr-char 외 대문자 `%XX`, `URLEncoder` 미사용)를 함께 만든다. 확장자 앞 본문에 영문·숫자가 없으면 ASCII 대체는 `track-{uuid}.{ext}`로 대체한다. 정확값 테스트: `"한글 파일.mp3"` → `filename*=UTF-8''%ED%95%9C%EA%B8%80%20%ED%8C%8C%EC%9D%BC.mp3`.
  - [검토] Nginx가 헤더를 유지하는지는 통합 테스트 소관(PRD.md §7.1) — 본 태스크 범위 밖
    - **이관(기록):** `X-Accel-Redirect`·`Content-Disposition`·`Content-Type` 유지와 presigned 쿼리가 Nginx를 통과해도 보존되는지는 **T7-3 스모크/통합 테스트에서 확인**한다.
  - [검토] `DOWNLOAD` 이력도 T5-3과 동일하게 `PlaybackLogService`(REQUIRES_NEW) 경유이므로, 호출자 트랜잭션 영향 없이 저장되는지 통합 테스트(H2)로 함께 확인한다.
    - **확인(기록, `DownloadIntegrationTest`):** 익명·로그인 `DOWNLOAD` 이력 저장, 존재하지 않는 `userId`(readOnly 호출자 포함)에서도 정상 응답과 이력 0건, presign 실패 시 예외 전파와 이력 0건. `TrackService` 생성자 끝에 `StorageProperties`가 추가되었다.

---

## Phase 6 — 인증 (Google OAuth2 + JWT)

### [x] T6-1 `JwtTokenProvider`
- **목표:** HMAC-SHA256 JWT 생성·파싱·검증(`sub=userId`, `role`).
- **선행:** T0-3
- **완료 조건:**
  - [x] `JwtTokenProviderTest`: 정상 토큰 왕복, 만료 토큰 거부, 서명 위조 거부, 형식 오류 거부
  - [x] 서명 키가 32바이트 미만이면 기동/생성 시 명확한 예외
  - [x] `./gradlew test` 통과
- **주의:** [구현] JJWT 0.12.x 신 API(`Jwts.builder().signWith(key)`, `parser().verifyWith`) 사용, `Clock`/만료 주입으로 테스트 가능하게
  - **구현 메모(기록):** API는 `String createToken(Long userId, String role)`와 `Optional<JwtClaims> parseToken(String token)`이다. 무효 토큰(null·공백·형식 오류·만료·위조·비숫자 subject·`role` 부재)은 예외 없이 `Optional.empty()`를 반환하므로 T6-2 필터는 `parseToken(...).ifPresent(...)`만 쓰면 된다(무효 토큰은 익명 통과). 키는 UTF-8 **32바이트 이상**(PRD의 "32자"는 ASCII 기준이며 바이트 기준이 더 엄격), 미만이면 빈 생성 시 `IllegalStateException`(메시지에 키 값 없음). 만료는 주입된 `java.time.Clock` 기준(경계 테스트로 확인). 로그는 분류 문자열만 남긴다(만료 debug, 그 외 warn).
  - **보안 확인(기록):** `verifyWith`만으로는 키가 충분히 길면 같은 키로 서명한 HS384/HS512 토큰도 통과하므로, 파싱 후 헤더 알고리즘이 정확히 HS256인지 명시적으로 확인한다. 68바이트 키로 서명한 HS512 토큰이 거부됨을 테스트로 확인했다. `alg=none`·서명 없는 토큰·다른 키 서명·payload 변조도 모두 거부된다.
  - **Jackson 공존 확인(기록, T0-1 이관 항목):** `jjwt-jackson 0.12.6`(jackson-databind 2.21.7)과 Boot의 Jackson 3(`tools.jackson`)가 클래스패스에 함께 있는 상태에서 실제 토큰 생성·파싱 왕복이 통과했다. 충돌 없음.

### [x] T6-2 JWT 필터·`/api/auth/me`·Security 확정
- **목표:** `JwtAuthenticationFilter`(무효 토큰은 익명 통과), `RestAuthenticationEntryPoint`(401 공통 JSON), `AuthController`, `SecurityConfig`에서 `/api/auth/me`만 인증 필수.
- **선행:** T6-1, T1-3, T0-5
- **완료 조건:**
  - [x] `@WebMvcTest`: 유효 토큰 → `200 UserResponse{userId,email,profileImageUrl,role}`
  - [x] 토큰 없음/무효 → `401 UNAUTHORIZED` JSON(리다이렉트 없음)
  - [x] 무효 토큰으로 `/api/tracks`는 차단되지 않음
  - [x] `UserService`(또는 조회 서비스) 단위 테스트: 존재하지 않는 userId → 401 처리 경로
  - [x] `./gradlew test` 통과
- **주의:** [계획] 필터가 `UserDetails` 대신 어떤 Principal을 넣을지(userId `Long`) 결정 — 이후 T6-7에서 재사용
  - **결정(기록):** principal은 `AuthenticatedUser(Long userId, String role)` record이며 `UsernamePasswordAuthenticationToken(principal, null, [SimpleGrantedAuthority(role)])`로 `SecurityContext`에 넣는다. **T6-7은 컨트롤러에서 `@AuthenticationPrincipal AuthenticatedUser`로 받으면 된다**(익명 요청의 principal은 문자열 `"anonymousUser"`라 타입 불일치로 `null`이 주입되어 "토큰 없음 → `null` 전달"과 그대로 맞는다). 필터는 `@Component`가 아니라 `SecurityConfig`에서 `new`로 생성해 `addFilterBefore(UsernamePasswordAuthenticationFilter)`로 등록한다(서블릿 컨테이너 이중 등록 방지). 토큰이 없거나 무효이거나 Bearer 형식이 아니면(예: Basic) 아무것도 설정하지 않고 익명 통과, 이미 인증이 있으면 덮어쓰지 않는다. 401은 `RestAuthenticationEntryPoint`(Boot `JsonMapper`)가 `ErrorResponse(UNAUTHORIZED)` JSON으로 응답하며 리다이렉트·`WWW-Authenticate`가 없다. `AccessDeniedHandler`는 ROLE 기반 제어가 없어 두지 않았다. 유효 토큰이지만 회원이 DB에 없으면 `UserService.getUser`가 `UnauthorizedException`(`BusinessException` 하위)을 던져 `GlobalExceptionHandler`(무수정)가 401 JSON으로 처리한다.
  - **확인(기록):** `STATELESS`의 `SecurityContextHolderFilter`가 요청 종료 시 컨텍스트를 정리해 인증이 요청 간에 새지 않음을 연속 요청 테스트(`notLeakAuthenticationBetweenRequests`)로 증명했다. `MVC GlobalExceptionHandler`가 만든 401은 `Content-Type: application/json`(charset 없음), `EntryPoint` 응답은 `application/json;charset=UTF-8`이며 키(code/message/timestamp)와 code·message는 같다.
  - **T6-5 회귀 항목(기록):** `oauth2Login`을 추가하면 `/api/**`의 기본 인증 진입점이 로그인 페이지 리다이렉트로 바뀔 수 있다. T6-5에서 `oauth2Login` 추가 후에도 미인증 `/api/auth/me`가 여전히 **401 JSON(리다이렉트 없음)** 인지 확인하는 회귀 테스트를 반드시 추가한다.

### [x] T6-3 쿠키 기반 인가 요청 저장소
- **목표:** `HttpCookieOAuth2AuthorizationRequestRepository` — 인가 요청과 `redirect_uri`를 쿠키(`oauth2_auth_request`, `oauth2_redirect_uri`; HttpOnly, Path=/, Max-Age=180, SameSite=Lax)에 저장한다.
- **선행:** T0-5
- **완료 조건:**
  - [x] 저장 후 로드 시 동일한 `OAuth2AuthorizationRequest` 복원(`MockHttpServletRequest/Response`)
  - [x] 쿠키 속성(HttpOnly, Path, Max-Age, SameSite) 검증
  - [x] 삭제 시 두 쿠키 Max-Age=0
  - [x] 변조된 쿠키 값 → null 반환(예외 없음)
  - [x] `./gradlew test` 통과
- **주의:**
  - [탐색] Spring Security 7의 `OAuth2AuthorizationRequestRepository` 시그니처·직렬화 방식 (Context7로 확인)
    - **확인(기록):** Spring Security 7.1.2(jar 소스 확인)에서 인터페이스 이름은 `OAuth2AuthorizationRequestRepository`가 **아니라** `org.springframework.security.oauth2.client.web.AuthorizationRequestRepository<T extends OAuth2AuthorizationRequest>`이다(ROADMAP·PRD의 옛 표기와 다름). 메서드는 `load/save/removeAuthorizationRequest` 3개이며 `removeAuthorizationRequest`는 요청을 반환하면서 저장분을 삭제한다. `oauth2Login().authorizationEndpoint().authorizationRequestRepository(...)`에 전달한다.
  - [검토] 쿠키 값 직렬화는 안전한 방식(Java 직렬화 역직렬화 취약점 주의)
    - **결정(기록):** Java 직렬화·`SerializationUtils`는 쓰지 않는다. 전용 record를 JSON(Jackson 3 `JsonMapper`, 다형성 타이핑 없음)으로 변환해 Base64URL(무패딩)로 인코딩하고 복원은 필드만 읽어 `OAuth2AuthorizationRequest.authorizationCode()` 빌더로 재구성한다. 실제 Java 직렬화 스트림 바이트(`rO0AB`)를 쿠키로 넣어도 예외 없이 `null`(역직렬화 시도 없음)임을 테스트로 확인했다.
  - **HMAC 서명 미적용 근거(기록):** 쿠키는 브라우저와 `state`를 묶는 역할이며, 서명 여부와 무관하게 공격자는 정상 흐름에서 받은 유효 쿠키를 그대로 복사할 수 있어 서명은 쿠키 심기 공격을 막지 못한다. 서명이 막는 필드 조작(`redirectUri`, `clientId`)은 Google의 등록 redirect 검증과 코드 교환 단계에서 이미 거부된다. 별도 서명 키(설정 키 추가)의 비용 대비 이득이 작아 MVP에서는 하지 않는다.
  - **구현 메모(기록):** `@Component`가 아니며 T6-5에서 `SecurityConfig`가 `new`로 만들어 `oauth2Login`에 전달하고 **같은 인스턴스를 성공·실패 핸들러에 주입**해야 한다. 핸들러는 `loadRedirectUri(request)`로 읽은 뒤 `removeAuthorizationRequestCookies(request, response)`로 두 쿠키를 지운다(`removeAuthorizationRequest`는 `oauth2_auth_request`만 삭제). `redirect_uri` 쿠키는 Base64URL로 저장(헤더 인젝션 불가, 512자 초과·없음·공백이면 값 쿠키를 만들지 않고 **잔존 쿠키 삭제 헤더를 내보내 이전 시도의 값이 180초 안에 재사용되지 않게 한다**). `Secure`는 `request.isSecure()`일 때만 붙고, 값이 3072자를 넘는 쿠키는 `null`로 처리한다.

### [x] T6-4 `CustomOidcUserService` (구 `CustomOAuth2UserService`)
- **목표:** Google 응답에서 `sub/email/picture`만 추출해 `UserService.upsertUser`를 호출한다.
- **선행:** T1-3
- **완료 조건:**
  - [x] 단위 테스트: 속성 매핑(`sub/email/picture`) 후 `UserService` 호출
  - [x] 필수 속성(`sub`, `email`) 누락 시 `OAuth2AuthenticationException`
  - [x] `./gradlew test` 통과
- **주의:**
  - [탐색] scope에 `openid`가 있으면 `OidcUserService` 경로가 사용된다 → `CustomOAuth2UserService`만으로 충분한지 확인하고, 필요 시 OIDC 서비스 커스터마이징 여부를 사용자에게 문의
    - **확인·결정(기록):** Spring Security 7.1.2 소스 확인 결과 `OAuth2LoginAuthenticationProvider`는 인가 요청 scope에 `openid`가 있으면 `null`을 반환하고 `OidcAuthorizationCodeAuthenticationProvider`가 처리하며, 이 provider는 `OAuth2UserService<OidcUserRequest, OidcUser>`(기본 `OidcUserService`)만 호출한다. 따라서 PRD §4.2의 scope(`openid, email, profile`)에서는 `userInfoEndpoint().userService(...)`로 등록한 일반 서비스가 호출되지 않아 Upsert가 실행되지 않는다. **안 A 채택:** scope를 유지하고 `OidcUserService`에 위임하는 `CustomOidcUserService`를 만들어 **T6-5에서 `userInfoEndpoint(u -> u.oidcUserService(customOidcUserService))`로 등록**한다. 안 B(`openid` 제거)는 ID 토큰 서명·nonce 검증 포기와 PRD scope 변경이 필요해 기각했다. 라이브러리 추가·기술스택 변경 없이 PRD 동작을 유지하는 같은 스택 내 확장이라 사용자 문의 없이 진행했고, **최종 보고에서 사용자에게 알린다.** PRD-05 §5·§6.1·§7.3의 클래스명·샘플 코드를 이에 맞게 정정했다.
  - [검토] 불필요한 개인정보(이름 등)를 저장하지 않는다
    - **확인(기록):** `sub`·`email`·`picture`만 추출하고, 반환 principal(`AuthenticatedOidcUser`)의 클레임/attributes/ID 토큰에 `name`·`given_name`·`family_name`이 없음을 테스트로 확인했다. 최소 클레임으로 `OidcIdToken`을 재구성하지만, `OidcAuthorizationCodeAuthenticationProvider`가 서명·클레임·nonce 검증을 `loadUser` 호출 전에 끝내고 이후 재검증하지 않아 영향이 없음을 jar 소스로 확인했다. `email_verified`는 Boolean `false` 또는 문자열 `"false"`일 때만 거부하며 다른 타입은 예외 없이 통과한다. 예외 메시지·로그에는 속성 값·토큰이 없다.
  - **T6-5 연동 메모(기록):** `AuthenticatedOidcUser`(`getUserId()`, `getRole()`)가 principal이므로 성공 핸들러는 `authentication.getPrincipal()`을 캐스팅해 `jwtTokenProvider.createToken(userId, role)`로 발급한다. 실제 Google 응답을 쓰는 통합 검증은 하지 않았다(위임 객체를 Mock으로 대체한 단위 테스트).

### [x] T6-5 성공·실패 핸들러
- **목표:** `OAuth2AuthenticationSuccessHandler`(화이트리스트 완전 일치 시 `?token=`, 불일치 시 `default?error=invalid_redirect_uri`, 쿠키 삭제)와 `OAuth2AuthenticationFailureHandler`(`?error=`).
- **선행:** T6-1, T6-3
- **완료 조건:**
  - [x] `OAuth2AuthenticationSuccessHandlerTest` 1: 웹·앱 `redirect_uri` 쿠키 → `{redirect_uri}?token={jwt}` 리다이렉트
  - [x] 2: 화이트리스트 외 URI → 토큰 없이 `?error=invalid_redirect_uri`
  - [x] 3: 처리 후 두 쿠키 삭제
  - [x] `redirect_uri` 없음 → 기본 URI로 `?token=`
  - [x] 부분 일치/대소문자·쿼리 추가 변형 URI 거부
  - [x] 실패 핸들러가 `?error={code}`로 리다이렉트
  - [x] `./gradlew test` 통과
- **주의:** [검토] Open Redirect 방지 — 완전 일치(scheme·host·port·path), `musicapp://` 커스텀 스킴 URI 파싱 동작
  - **구현(기록):** `RedirectUriPolicy.isAllowed`는 화이트리스트와의 **문자열 완전 일치(대소문자 구분)** 이며 요청 값을 trim·소문자화·디코딩·URI 파싱하지 않는다(파싱 불일치 우회 방지). 설정이 잘못되면(화이트리스트 비어 있음, 기본 URI가 화이트리스트에 없음, 항목에 `? # \ 공백 제어문자`) **기동 시 fail-fast**. 거부 시 토큰 없이 기본 URI + `?error=invalid_redirect_uri`. 토큰은 `{uri}?token={jwt}`(값 URL 인코딩, `UriComponentsBuilder` 미사용으로 `musicapp://` 재정규화 방지), 응답은 `sendRedirect` 대신 `302` + `Location` 직접 설정하며 `Cache-Control: no-store`·`Pragma: no-cache`·`Referrer-Policy: no-referrer`를 붙인다. 모든 경로가 **읽기 → 검증 → 쿠키 삭제 → 응답** 순서로 같은 종료 지점을 지난다. principal이 `AuthenticatedOidcUser`가 아니면 `error=login_failed`, JWT 발급 실패는 `error=server_error`. 실패 핸들러의 오류 코드는 허용 목록 7개만 그대로 전달하고 그 외(예외 메시지 포함)는 `login_failed`로 치환한다. 우회 시도 23종(`javascript:`, `//evil.com`, `localhost.evil.com`, `@evil.com`, `#frag`, `?x=1`, 대문자, 공백, CRLF, `%2e`, trailing slash, `:80` 등)이 모두 거부됨을 테스트로 확인했다. 쿠키 저장소는 `SecurityConfig`의 `@Bean`이며 `oauth2Login`과 두 핸들러가 같은 인스턴스를 공유한다. `@EnableConfigurationProperties(JwtProperties.class)`를 `SecurityConfig`에 추가했다(`@WebMvcTest`에서는 `@ConfigurationPropertiesScan`이 적용되지 않아 `JwtProperties` 빈이 없기 때문이며 전체 컨텍스트에서 중복 문제 없음).
  - **동작 메모(기록):** 저장소가 빈 값/공백 `redirect_uri` 쿠키를 "없음"으로 취급하므로 이 경우 기본 URI로 `?token=`이 붙는다(기본 URI는 설정이 신뢰하는 값이라 안전). `RedirectUriPolicy.isAllowed("")`는 false다.
  - [검토] **T6-2 이관 회귀:** `oauth2Login`을 `SecurityConfig`에 추가한 뒤에도 미인증 `GET /api/auth/me`가 로그인 페이지 리다이렉트가 아니라 `401 UNAUTHORIZED` JSON을 반환하는지 확인하는 `@WebMvcTest`(또는 통합) 회귀 테스트를 추가한다(`exceptionHandling.authenticationEntryPoint`를 명시했는데도 `oauth2Login`이 `/api/**`의 진입점을 바꾸지 않는지 확인).
    - **확인(기록, `OAuth2LoginFlowTest`):** `oauth2Login` 추가 후에도 미인증 `GET /api/auth/me`는 401 JSON이며 `Location` 헤더가 없다. `GET /oauth2/authorization/google`은 302로 `accounts.google.com`에 이동하며 `state`·`nonce`·`client_id`·`scope=openid`·`redirect_uri`를 포함하고 `oauth2_auth_request`(HttpOnly, SameSite=Lax)·`oauth2_redirect_uri` 쿠키가 생기며 **`JSESSIONID`는 없다**. 쿠키 없이 `/login/oauth2/code/google`을 호출하면 `?error=authorization_request_not_found`로 302되어 실패 핸들러까지 연결됨을 확인했다. `GET /login`은 404(기본 로그인 페이지 미노출). 실제 Google 호출을 쓰는 검증은 하지 않았다(콜백 성공 처리는 핸들러 단위 테스트로 갈음).
  - **범위 밖 위험(기록):** JWT가 URL 쿼리(`{redirect_uri}?token=...`)로 전달되므로 웹 콜백 요청(`/auth/callback?token=...`)이 **Nginx 접근 로그에 `token` 쿼리로 남을 수 있다**. 로그 포맷 설정은 PRD-04(Nginx) 소관이라 이 저장소에서는 해결하지 않으며, 통합 단계에서 `/auth/callback`의 쿼리를 접근 로그에서 제외하거나 마스킹하는 설정을 검토한다(MVP 이후 과제: 토큰을 URL 대신 단기 일회용 코드 교환 방식으로 전환).

### [x] T6-6 CORS 설정
- **목표:** `app.cors.allowed-origins`가 비어 있으면 CORS 비활성, 값이 있으면 해당 오리진에 한해 `GET, POST, OPTIONS`와 `Authorization`, `Content-Type` 허용(`allowCredentials=false`).
- **선행:** T0-3, T0-5
- **완료 조건:**
  - [x] 빈 설정: 프리플라이트 요청에 `Access-Control-Allow-Origin` 없음
  - [x] 허용 오리진: 프리플라이트 성공, 비허용 오리진 거부
  - [x] `./gradlew test` 통과
- **주의:** [구현] `CorsConfigurationSource` 빈이 `SecurityConfig`에서 참조되는 구조(PRD-05 §7.3)
  - **구현(기록):** `CorsConfig`가 `CorsConfigurationSource` 빈을 만들고 `SecurityConfig`가 `@Import(CorsConfig.class)`로 포함해 `.cors(...)`에 전달한다(`SecurityConfig`를 임포트하는 기존 슬라이스 테스트 4곳은 수정 없이 통과). 빈 설정이면 설정을 등록하지 않아 CORS가 적용되지 않고, 값이 있으면 `/**`에 `GET, POST, OPTIONS`·`Authorization, Content-Type`·`allowCredentials=false`·`maxAge=3600`을 등록한다. 빈 생성 시 오리진을 검증해 `*`·`null`·경로·쿼리·fragment·userinfo·끝 슬래시·scheme 없음·`http/https` 외 scheme은 **기동 시 거부(fail-fast)** 한다. `exposedHeaders`는 두지 않는다(PRD 계약에 없음, 기본 구성은 단일 오리진).
  - **실측 동작(기록):** 빈 설정의 `OPTIONS /api/auth/me`·일반 GET은 CORS 헤더 없이 200(브라우저는 `Allow-Origin`이 없어 차단). 허용 오리진 프리플라이트는 200 + `Allow-Origin/Methods/Headers/Max-Age`(`Allow-Credentials` 없음), 인증 없는 `GET /api/auth/me`는 401 JSON이며 `Allow-Origin`이 함께 붙는다(브라우저가 본문을 읽을 수 있음). 비허용 오리진은 프리플라이트·일반 GET 모두 403, 허용 오리진에서 `DELETE`·`X-Custom` 헤더 프리플라이트도 403.
  - **동작 메모(기록):** Spring `CorsConfiguration`의 오리진 비교는 끝 슬래시 제거와 대소문자 무시로 일치하므로 요청 `Origin`의 대소문자·끝 슬래시 변형도 허용된다(호스트·스킴은 대소문자를 구분하지 않고 브라우저가 보내는 `Origin`은 항상 소문자·끝 슬래시 없음이라 우회 경로가 아니며, 응답 `Allow-Origin`은 요청 값을 그대로 돌려준다). 공백만 있는 항목은 `CorsProperties`가 이미 제거하므로 CORS 비활성이 된다(안전한 기본값).
  - **T7 이관(기록):** Nginx 뒤 **동일 오리진 요청의 `Origin` 헤더 판정**은 Nginx의 `Host`/`X-Forwarded-*` 전달 설정에 영향을 받을 수 있다. `allowed-origins`가 비어 있지 않은 분리 배포 구성에서만 문제이므로 T7 통합 단계에서 확인한다.

### [ ] T6-7 인증 사용자 연결 (업로더·이력)
- **목표:** 인증된 사용자를 업로드 `uploader`와 재생·다운로드 이력 `user`에 연결한다.
- **선행:** T6-2, T3-4, T5-4
- **완료 조건:**
  - [ ] 컨트롤러 테스트: 토큰 있음 → 서비스에 userId 전달, 없음 → null 전달
  - [ ] `TrackServiceTest`: userId가 있을 때 `uploader` 설정, 없을 때 null
  - [ ] 존재하지 않는 userId 토큰이어도 업로드가 실패하지 않음(익명 처리)
  - [ ] `./gradlew test` 통과
- **주의:** [계획] 컨트롤러에서 Principal→userId 변환을 한 곳(예: argument resolver 또는 헬퍼)으로 모아 중복 방지

---

## Phase 7 — 배포 준비 및 백엔드 측 확인

### [ ] T7-1 Dockerfile·환경변수 예시
- **목표:** PRD-05 §2.2 `Dockerfile`, `.env.example`(PRD-05 §4.3 변수 전체), `.gitignore`에 `.env` 추가.
- **선행:** T6-7
- **완료 조건:**
  - [ ] `./gradlew build` 후 `docker build` 성공
  - [ ] `.env.example`에 PRD-05 §4.3 모든 환경변수 포함(시크릿은 예시 값만)
  - [ ] `git check-ignore .env` 성공
- **주의:** [검토] 이미지에 시크릿이 포함되지 않는지, `docker-compose`는 범위 밖(보고만)

### [ ] T7-2 `prod` 스키마 검증
- **목표:** PRD-07 `init.sql`을 적용한 PostgreSQL에서 `ddl-auto: validate`로 엔티티 매핑 일치를 확인한다.
- **선행:** T1-1, T7-1
- **완료 조건:**
  - [ ] 로컬 PostgreSQL 컨테이너에 `init.sql` 적용 후 `prod` 프로파일 기동 성공
  - [ ] 매핑 불일치 발견 시 엔티티 수정(또는 PRD-07 변경 필요 시 보고)
- **주의:** [탐색] `init.sql` 작성·수정은 PRD-07 범위 — 읽기만 하고, 불일치는 사용자에게 보고

### [ ] T7-3 수동 스모크 (백엔드 + RustFS + FFmpeg)
- **목표:** 실제 RustFS와 FFmpeg로 업로드 → `READY` → `/play` → `/download` 헤더까지 백엔드 단독 확인.
- **선행:** T7-2
- **완료 조건:**
  - [ ] 샘플 MP3 업로드 후 폴링으로 `READY` 도달, RustFS에 `original/`, `artwork/`, `hls/{uuid}/index.m3u8`, `chunk_*.ts` 존재
  - [ ] `/download` 응답의 presigned URL로 직접 GET 시 원본 반환, 서명 없이는 거부
  - [ ] 결과를 체크리스트로 기록
- **주의:**
  - [검토] Nginx 의존 항목(`X-Accel-Redirect` 헤더 유지, presigned 쿼리 보존, RustFS 경로별 정책)은 통합 테스트로 이관한다(PRD.md §7.1)
  - [탐색] `STORAGE_ENDPOINT` host:port가 presign 서명 Host가 됨(PRD-05 §4.2 주의)

---

## 범위 밖 / 이관 항목

- Nginx 설정, RustFS 버킷 정책(`storage-init`), `init.sql`, 웹·앱 클라이언트: 구현하지 않고 계약 확인만 한다.
- 통합 테스트로 이관: `X-Accel-Redirect` + `Content-Disposition` 유지, presigned 쿼리 서명 보존, RustFS `hls/*`·`artwork/*` 정책(PRD.md §7.1).
- MVP 이후 과제: `/api/auth/check`, Refresh Token·블랙리스트, 자동 재변환·외부 큐, ABR(PRD.md §7.2).

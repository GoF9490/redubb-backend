# 작업 진행 기록 (PROGRESS)

> 새 세션은 이 파일 → `ROADMAP.md` 순으로 읽고 이어서 작업한다.
> 진행 방식: 메인이 `spring-backend-expert`에게 태스크를 하나씩 지시(PLAN → 승인 → IMPLEMENT) → 메인이 코드 검토·테스트 재실행 → 커밋 → 다음 태스크.

**📅 최종 업데이트**: 2026-11-14

## 현재 작업
- **ROADMAP 전체 완료(16/16).** 남은 것은 아래 "사용자 결정 필요" 항목뿐.

## 완료한 작업
- R0-1: `@Primary`(ab80674)는 이미 커밋돼 있어 추가 커밋 없음. 기준선 측정 — 테스트 클래스 57, 테스트 450, 실패·스킵 0. (c679e3f)
- R0-2: Java 137개 중 133개 파일 공백 4칸 → 탭 전환. `git diff -w` 비어 있음, 잔존 공백 들여쓰기 0건, 빌드·테스트 57/450 유지. (87b48fb)
  - 정렬용 이어쓰기 28줄은 "직전 비정렬 줄 들여쓰기 + 탭 2개"로 정규화.
  - Javadoc ` *` 앞 공백 1칸은 정렬용이라 유지.
- R1-1: config·exception·security·Util 4종을 `global/`로 이동(메인 37 + 테스트 24, `git mv`). `@ConfigurationPropertiesScan` 문자열 갱신, `ContentDispositionBuilder`·`InternalRedirectPathConverter`·`LogTailBuffer`만 public 확장(상수는 package-private 유지). 메인이 `build --rerun-tasks` 재실행해 57/450·실패 0 확인, 비 import/package 변경 라인 점검. (d3e4ddb)
- R1-2: `User`·`PlaybackLog` 계열 메인 10 + 테스트 5를 `user/`·`playback/`으로 이동(`git mv`). package/import 외 변경 없음, 접근 제어자 확장 없음. `EntityMappingTest`는 R1-4까지 `entity/` 유지, `AuthControllerTest`는 `user/controller`로 이동. 메인이 `build --rerun-tasks` 재실행해 57/450·실패 0 확인.
- R1-3: `storage/service`(3), `transcode/service`(11), `transcode/dto`(`TranscodeEventDto`) 이동 + 테스트 13개. `TranscodeIntegrationTestBase`만 `public abstract`로 확장(타 패키지의 Track 통합 테스트가 상속). `FakeProcessMain`·`SampleFixtures`·테스트 리소스는 `support`에 유지. 메인이 `build --rerun-tasks` 재실행해 57/450·실패 0 확인.
- R1-4: `Track` 계열 메인 12 + 테스트 16을 `track/`으로 이동, 레이어 패키지(`controller`·`service`·`repository`·`entity`·`dto`) 제거. `TrackRepository` JPQL 문자열 FQN 3곳을 `track.entity.TrackStatus`로 수정. `EntityMappingTest`는 `track/entity`로 이동. 테스트 루트의 `support/`·`MusicApplicationTests`는 유지(완료 조건은 메인 소스 기준). 접근 제어자 확장 없음. 메인이 `build --rerun-tasks` 재실행해 57/450·실패 0 확인.
- R2-1: `CustomOidcUserService`·`MinioStorageService`·`ApplicationEventTranscodeTaskDispatcher`를 `@Component`로 전환(어노테이션·import만 변경). `@Service`는 `UserService`·`PlaybackLogService`·`TrackService`·`HlsTranscodeService` 4개에만 남음. 메인 재검증 57/450·실패 0. (커밋 메시지 참고)
- R2-2: `UserService`·`PlaybackLogService`를 인터페이스 + Impl로 분리. 인터페이스에 `@Transactional` 없음, `UserServiceImpl` 클래스 레벨 `@Transactional`·`PlaybackLogServiceImpl` `TransactionTemplate`(REQUIRES_NEW) 그대로. 소비자 소스 수정 없음. 테스트 `*ServiceImplTest`로 변경, `PlaybackLogServiceImplTest`에 DOWNLOAD 케이스 +1(테스트 기준선 451). 메인 재검증 57/451·실패 0. `UserTest`(Entity)는 R4-1에서 작성.
- R2-3: `TrackService`를 인터페이스(5개 메서드) + `TrackServiceImpl`로 분리. `@Transactional(readOnly)`(getTracks·getTrack)·업로드 보상 처리(`TransactionTemplate`) 그대로. `getPlayInfo`/`getDownloadInfo`의 `this.getTrack` self-invocation은 기존 구조라 보존. 4개 서비스 테스트를 `TrackServiceImplTest`로 통합(`@Nested` Upload 24·Query 11·Play 7·Download 10, 메서드 47개·52건 유지). 메인이 통합 전후 테스트 본문을 대조해 fixture(생성자 구성) 외 차이 없음을 확인. 재검증 451·실패 0.
- R2-4: `TranscodeProcessor`를 `HlsTranscodeService` 인터페이스로 통합, 구현은 `HlsTranscodeServiceImpl`. `@Async("transcodeExecutor")`는 Impl의 `process`에 유지(원래 `@Transactional` 없음), `FFMPEG_TIMEOUT_MESSAGE`는 Impl 유지. 실제 주입받는 main 클래스는 `TranscodeEventRelay` 하나뿐(ROADMAP 메모 정정). `HlsTranscodeServiceImplTest` 18건 유지. 에이전트 2회 + 메인 1회 `--rerun-tasks`로 비동기·큐 포화 통합 테스트 안정 확인(451·실패 0).
- R3-1: `AsyncProperties`·`TrackProperties`(record) 추가, `application.yaml`에 `app.async`·`app.track`(PRD §4.2 값 1/2/50, 20/100). `AsyncConfig` 풀 크기 상수·`TrackServiceImpl.MAX_PAGE_SIZE` 제거. `getTracks`의 `size`를 `Integer`로 바꿔 미지정 시 서비스가 기본값 적용(컨트롤러 `defaultValue` 제거, API 응답 동일). **사용자에게 알릴 결정**: 이로 인해 `TrackControllerTest`(5곳)·`AuthControllerTest`(1곳)의 `eq(20)`/`anyInt()` 매처를 `isNull()`/`any()`로 수정(단언 의미 불변, 승인 범위 6곳). `application-test.yaml`에 풀 크기 고정. `TrackServiceImplTest` +4(기준선 455). 메인 재검증 455·실패 0, 포화 통합 테스트 에이전트 2회 안정.
- R3-2: `JwtProperties`(`authorizationCookieMaxAgeSeconds`=180, `allowedFailureErrorCodes` 7개)·`CorsProperties`(`allowedMethods`·`allowedHeaders`) 확장, 값은 기존 코드와 PRD §4.2가 동일. 쿠키 저장소·실패 핸들러 생성자에 `JwtProperties` 추가(`SecurityConfig` 갱신), 구 상수·`CorsConfig` 고정 목록 제거. 테스트는 생성자 인자만 수정(단언 불변). 메인이 `--rerun-tasks` 3회 반복 455·실패 0 확인, 에이전트도 3회.
- R3-3: `AudioProperties`에 `hlsAudioSampleRate`(44100)·`hlsAudioChannels`(2)·`errorMessageMaxLength`(500)·`recoveryStaleThresholdMultiplier`(2)·`allowedAudioMimeTypes`(7)·`allowedArtworkMimeTypes`(2) 추가(값은 기존 코드와 PRD §4.2 동일). 오류 메시지 절단 책임을 `HlsTranscodeServiceImpl.recordFailure`로 이전, `Track.ERROR_MESSAGE_MAX_LENGTH`는 public DDL 상한(500) 유지, 설정값 > 500이면 서비스 생성자에서 기동 실패. `AudioMetadataValidator`는 목록이 비었거나 매핑 없는 MIME이면 기동 실패(MIME→확장자 매핑은 코드 유지). 신규 테스트 `HlsTranscodeServiceImplTest` +3, `TrackTest` +1(기준선 459). 기존 테스트는 생성자 인자만 수정. 메인 재검증 459·실패 0.
- R4-1: 허용 범위 단위 테스트 +20(`UserTest` 7·`PlaybackLogTest` 4 신규, `TrackTest` +6, `LikePatternEscaperTest` +1, `LogTailBufferTest` +2). `src/main`·기존 기대값 불변, Spring 컨텍스트 없음. PRD §8 체크리스트 대조: 서비스 4종은 기존 테스트로 충족, `TrackStatus`·`ActionType`은 로직 없어 대상 아님. 메인 재검증 479·실패 0.
- R4-2: 비허용 테스트·지원 클래스·테스트 리소스 삭제(java 50개 350건 + 리소스 9개, 삭제 목록은 커밋 메시지). 삭제 직전 479건 그린 확인, 삭제 후 129건·14 suite 통과, Spring 컨텍스트 테스트 0개(메인 재검증). 남은 테스트: `*ServiceImplTest` 4, Entity 3, Util 4. `src/main`·`build.gradle.kts` 불변.
- R5-1: 규칙 점검 위반 0건(공백 들여쓰기·`@Service`↔`*ServiceImpl`·직접 주입·루트 패키지·PRD §5 트리). `CLAUDE.md` 갱신(개요·테스트 명령·현재 상태). `bootRun` 확인: 기본 `local`은 RustFS 미연결로 `StorageBucketInitializer` fail-fast(설계), `local,test`는 기동 완료·`/api/tracks` 200·`/api/auth/me` 401·`/h2-console` 302, 종료 후 8080 비어 있음. 업로드·재생·다운로드는 실제 스토리지와 수동 확인 필요. 메인 재검증 129·실패 0.

## 검토 필요 / 보고 사항
- **PRD-05와 코드가 다른 부분(R5-1 보고, PRD 미수정)**: (1) `TrackTest` 금지 전이 미충족, (2) PRD §8에 없는 `PlaybackLogTest` 추가, (3) §4.2에 없어 코드에 남긴 기준값 8건(`KEYWORD_MAX_LENGTH`, `/internal-storage/`·`/stream/`·`/artwork/` 접두어, `PREFLIGHT_MAX_AGE_SECONDS`, 쿠키 크기 한도 2개, `KILL_WAIT_MILLIS`·`READER_JOIN_MILLIS`), (4) 기동 시 검증(fail-fast) 3종과 `Track.ERROR_MESSAGE_MAX_LENGTH` public화, (5) `@Profile("!test")`·`test` 프로파일 용도, (6) R4-2 삭제로 인한 테스트 범위 축소. PRD 반영 여부는 사용자 결정.
- **사용자 알림(R4-2 보호 상실)**: 삭제로 아래 자동 회귀 보호가 사라짐. (1) 트랜잭션·비동기: `PlaybackLogServiceImpl` REQUIRES_NEW 분리·readOnly 호출자 flush·rollback-only 비전파, 업로드 보상·커밋 후 이벤트, `@Async`/`@TransactionalEventListener` 연결, 큐 포화→`TRANSCODE_QUEUE_FULL`. (2) 보안: JWT 발급·검증, redirect 화이트리스트, OAuth2 쿠키·핸들러, SecurityConfig·CORS 정책 테스트(98건+). (3) 영속·스키마: `EntityMappingTest`(PRD-07 DDL 매핑)·Repository 쿼리 테스트 → `prod`의 `ddl-auto: validate` 기동에만 의존. (4) 설정 배선·기동 fail-fast: `ConfigurationProperties*Test`·`MusicApplicationTests`. (5) 웹 계약: Controller·DTO 직렬화·`GlobalExceptionHandler`. (6) `@Component` 로직(FFmpeg 명령 빌더·러너, MIME 검증 등). 필요 시 git 이력(커밋 90cb496 이전)에서 복구 가능.
- **사용자 결정 필요(R4-2 보고)**: 삭제 후 안 쓰이는 테스트 스타터 4개(`data-jpa-test`, `security-oauth2-client-test`, `security-test`, `webmvc-test`)는 정리 후보이나 `spring-boot-starter-test`가 `build.gradle.kts`에 따로 선언돼 있지 않아 JUnit·Mockito·AssertJ가 이들의 전이 의존성임. 정리하려면 `spring-boot-starter-test` 추가가 선행(`build.gradle.kts` 수정이라 이번 범위 밖).
- **사용자 결정 필요(R4-1 보고)**: PRD-05 §8 `TrackTest`의 "금지 전이" 검증은 `Track.markProcessing/markReady/markFailed`에 상태 가드가 없어 검증 불가(동작 변경 없이는 불가). 해소하려면 (a) `Track`에 전이 가드를 도입하는 별도 태스크(동작 변경, PRD·사용자 확인 필요) 또는 (b) PRD 문구를 현 구현에 맞게 정정. 이번 리팩터링 범위에서는 미충족으로 남김.
- **PRD §4.2에 없어 옮기지 않은 상수(R3-1 보고, R3-3 이후 최종 정리 때 재확인)**: `TrackController` `page` `defaultValue="0"`, `TrackServiceImpl`의 `TITLE/ARTIST/ALBUM/KEYWORD/FILENAME_MAX_LENGTH`(일부 DDL 계약)·`UNKNOWN_FILENAME`·아트워크/원본 키 형식, `AsyncConfig.THREAD_NAME_PREFIX`, `ContentDispositionBuilder.FILENAME_MAX_LENGTH`, `InternalRedirectPathConverter.INTERNAL_PREFIX`, `HlsTranscodeServiceImpl`의 `INPUT_FILE_PREFIX`/`HLS_DIR_NAME`/`HLS_KEY_PREFIX`/`FFMPEG_TIMEOUT_MESSAGE`(포맷·프로토콜 상수).
- **R3-3에서 옮기지 않은 상수(PRD §4.2에 없음)**: `MIME_TO_EXTENSION` 매핑, `AudioMetadataValidator.FALLBACK_TITLE/ARTIST`·`MIN_DURATION_SECONDS`, `Track.DEFAULT_ARTIST`, `FfmpegCommandBuilder`의 `PLAYLIST_FILE_NAME`·`SEGMENT_FILE_PATTERN`·코덱/HLS 인자, `ProcessFfmpegCommandRunner`의 `KILL_WAIT_MILLIS`(5000)·`READER_JOIN_MILLIS`(5000)·`START_FAILED_SUMMARY`·`INTERRUPTED_SUMMARY`, `TranscodeRecoveryRunner.INTERRUPTED_MESSAGE`, `TranscodeRejectionHandler.QUEUE_FULL_MESSAGE`.
- **사용자 결정 필요(R3-2 보고)**: `CorsConfig.PREFLIGHT_MAX_AGE_SECONDS`(3600) 외부화를 원하면 PRD §4.2에 `app.cors.max-age-seconds`를 추가하는 결정이 먼저 필요. 그 외 `HttpCookieOAuth2AuthorizationRequestRepository.MAX_COOKIE_VALUE_LENGTH`(3072)·`MAX_REDIRECT_URI_LENGTH`(512)·`CorsConfig.ALLOWED_SCHEMES`·`OAuth2AuthenticationFailureHandler.ERROR_LOGIN_FAILED`는 프로토콜·안전 한도 상수로 코드 유지.
- `CorsConfigTest`는 빈을 직접 생성해 `@Primary`를 증명하지 못함. 실질 증거는 `CorsEnabledTest`·`SecurityConfigTest`. 제거 시 `NoUniqueBeanDefinitionException` 재현은 하지 않음.
- 테스트 기준선은 R4-2 이후 129(14 suite, 허용 범위 단위 테스트만).
- `.editorconfig` 없음 → 탭 설정 고정이 필요하면 별도 판단(범위 밖).
- 환경: python 없음(grep/awk/sed 사용), `core.autocrlf=true`로 LF→CRLF 경고 출력(내용 영향 없음, 작업 트리·인덱스는 LF).

## 다음 작업
- R5-1 규칙 준수 감사 및 문서 동기화 (ROADMAP 끝까지 자율 진행)

## 작업 원칙 (사용자 지시)
- 기존 테스트 코드는 ROADMAP 후반(R4-2)에서 삭제된다. 그 전까지는 수정·이동한 코드에 대해 **기존 테스트를 직접 실행하며 검토**한다(에이전트 보고에 의존하지 않고 메인이 `./gradlew build --rerun-tasks` 또는 해당 테스트를 재실행해 통과와 테스트 수를 확인). 삭제 전까지가 회귀 안전망이다.

## 서브에이전트 소통 방식 (파일 기반)
> 서브에이전트 응답이 중간에 잘리는 경우가 빈번해, `spring-backend-expert`와는 **파일을 통해** 업무를 주고받는다.

- 업무 전달(메인 → 에이전트): `.claude/agent-comm/spring-backend-expert/PLAN.md`에 지시 내용(대상 태스크, 단계 PLAN/APPROVED/REVISE/IMPLEMENT, 추가 지시)을 작성하고, 에이전트에게는 "PLAN.md를 읽고 수행하라"만 호출 프롬프트로 전달한다.
- 보고 수신(에이전트 → 메인): 에이전트가 계획·구현 결과·완료 보고를 `.claude/agent-comm/spring-backend-expert/TASKS.md`에 작성하도록 지시한다. 메인은 호출 응답 본문이 아니라 TASKS.md를 읽어 보고를 확인한다.
- 단계가 바뀔 때마다(PLAN → 승인 → IMPLEMENT) 메인은 PLAN.md를 갱신하고, 에이전트는 TASKS.md를 덮어써 최신 보고만 남긴다.
- 두 파일은 통신용 임시 파일이다. 커밋 대상에서 제외하고(`.claude/agent-comm/`), 태스크가 끝나면 필요한 내용만 PROGRESS.md에 요약해 남긴다.

## 결정 이력
- 진행 문서 위치는 `.claude/docs/PROGRESS.md`.
- 서브에이전트와의 소통은 PLAN.md(지시)·TASKS.md(보고) 파일 기반으로 한다. (응답 잘림 방지)

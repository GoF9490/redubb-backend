# 백엔드 리팩터링 로드맵 (Java 코딩 규칙 적용)

> 기준: `.claude/rules/spring-java.md`(최우선), `PRD-05.md` v1.5 §4(설정)·§5(패키지·서비스)·§8(테스트).
> 범위: **동작 변경 없는 구조 리팩터링**. API 계약·DB 스키마·응답 형식은 바꾸지 않는다. 범위 밖 요구사항이나 PRD 충돌을 발견하면 구현하지 말고 보고한다.

**📅 최종 업데이트**: 2026-11-14
**📊 진행 상황**: 4/16 Tasks 완료

## 진행 보드

- Phase 0 — 기준선 확보
  - [x] R0-1 미커밋 변경 정리 및 기준선 측정
  - [x] R0-2 들여쓰기 탭 전환
- Phase 1 — 도메인별 패키지 전환
  - [x] R1-1 `global` 패키지 구성
  - [x] R1-2 `user`·`playback` 도메인 이동
  - [ ] R1-3 `storage`·`transcode` 도메인 이동
  - [ ] R1-4 `track` 도메인 이동
- Phase 2 — Service 인터페이스/Impl 분리
  - [ ] R2-1 도메인 서비스가 아닌 클래스의 `@Component` 전환
  - [ ] R2-2 `UserService`·`PlaybackLogService` 분리
  - [ ] R2-3 `TrackService` 분리 및 테스트 통합
  - [ ] R2-4 `HlsTranscodeService` 분리 (`TranscodeProcessor` 통합)
- Phase 3 — 설정값 외부화
  - [ ] R3-1 `AsyncProperties`·`TrackProperties` 도입
  - [ ] R3-2 `JwtProperties`·`CorsProperties` 확장
  - [ ] R3-3 `AudioProperties` 확장
- Phase 4 — 테스트 범위 정리
  - [ ] R4-1 허용 범위 단위 테스트 보강
  - [ ] R4-2 비허용 테스트 삭제
- Phase 5 — 최종 검토
  - [ ] R5-1 규칙 준수 감사 및 문서 동기화

## 0. 공통 진행 규칙

1. **태스크 사이클:** `탐색` → `계획` → `구현` → `검토`. 태스크 하나는 한 세션(1~2시간)에 끝낸다.
2. **동작 보존:** 각 태스크는 `./gradlew build` 통과(Phase 3까지는 기존 테스트 전체 그린 포함)가 완료 조건이다. 테스트가 깨지면 기대값을 고치지 말고 리팩터링을 바로잡는다. 단, 패키지·클래스 이름 변경에 따른 import/참조 수정은 허용한다.
3. **테스트 작성 범위:** 새로 쓰는 테스트는 Service(Impl)·Entity·Util 단위 테스트만(JUnit 5 + Mockito, Spring 컨텍스트 없음). 기존 비허용 테스트는 R4-2 전까지 **지우지 않고 함께 옮긴다**.
4. **이동 방식:** 파일 이동은 `git mv`로 하여 이력을 보존한다. 이동과 내용 변경은 가능하면 커밋을 나눈다.
5. **완료 정의:** 완료 조건 전부 체크 + 한국어 커밋(`/git:commit`) 후 태스크 체크박스를 체크한다.
6. 주석·문서는 한국어, 식별자는 영어 camelCase, 들여쓰기는 **탭**(R0-2 이후).

## Phase 간 의존 순서

R0-1 → R0-2 → Phase 1(R1-1 → R1-2 → R1-3 → R1-4) → Phase 2(R2-1 → R2-2 → R2-3 → R2-4) → Phase 3(R3-1·R3-2·R3-3, 순서 무관) → R4-1 → R4-2 → R5-1

탭 전환(R0-2)을 패키지 이동보다 먼저 해서 이후 diff가 순수 이동·로직 변경만 보이게 한다.

---

## Phase 0 — 기준선 확보

### [x] R0-1 미커밋 변경 정리 및 기준선 측정
- **목표:** 작업 트리의 미커밋 변경(`CorsConfig`의 `@Primary` 추가)을 검토·커밋하고, 리팩터링 전 테스트 기준선을 기록한다.
- **선행:** 없음
- **완료 조건:** (테스트: 기존 테스트 실행만)
  - [x] `CorsConfig` `@Primary` 변경 의도 확인 후 커밋
  - [x] `./gradlew build --rerun-tasks` 통과
  - [x] 테스트 클래스 수·테스트 수를 이 문서 하단 "기준선" 항목에 기록
- **주의:**
  - [탐색] `@Primary` 추가가 `mvcHandlerMappingIntrospector`와 타입이 겹치는 문제 때문인지 `CorsConfigTest`·`CorsEnabledTest`로 확인

### [x] R0-2 들여쓰기 탭 전환
- **목표:** `src/main/java`, `src/test/java`의 모든 Java 파일에서 줄 앞 공백 4칸을 탭으로 바꾼다.
- **선행:** R0-1
- **완료 조건:** (테스트: 기존 테스트 실행만)
  - [x] Java 파일 전체에서 줄 앞 공백 들여쓰기 0건(`^( {4})+\S` 검색 결과 없음)
  - [x] `./gradlew build` 통과
  - [x] 공백만 바뀐 커밋 하나로 분리(`git diff -w` 결과가 비어 있음)
- **주의:**
  - [계획] 이어진 줄 들여쓰기(공백 8칸)는 탭 2개로 바꾼다. 4칸 단위가 아닌 정렬용 공백(Javadoc ` * ` 앞 1칸 등)은 그대로 둔다
  - [구현] 텍스트 블록(`"""`)과 문자열 리터럴 안의 공백은 바꾸지 않는다
  - [검토] `.editorconfig` 추가는 범위 밖. 필요하면 보고만 한다

---

## Phase 1 — 도메인별 패키지 전환 (PRD-05 §5)

> 공통 주의: 같은 패키지라서 쓸 수 있던 **package-private 멤버**(`static final` 상수, 생성자, 메서드)가 이동 후 컴파일 오류를 낸다. 접근 범위를 넓히기 전에 그 멤버가 꼭 다른 패키지에 보여야 하는지 먼저 판단한다. 테스트도 대상과 같은 패키지 구조로 함께 옮긴다.

### [x] R1-1 `global` 패키지 구성
- **목표:** 공통 코드를 `global/` 아래로 옮긴다.
  - `config/*` → `global/config`, `config/properties/*` → `global/properties`
  - `exception/*` → `global/exception`, `security/*` → `global/security`
  - Util → `global/util`: `ContentDispositionBuilder`, `InternalRedirectPathConverter`(현재 `service`), `LikePatternEscaper`(현재 `repository`), `LogTailBuffer`(현재 `service`)
- **선행:** R0-2
- **완료 조건:** (테스트: 기존 테스트 이동 + 실행)
  - [x] PRD-05 §5 트리의 `global/` 구성과 일치
  - [x] 해당 테스트(`config`·`exception`·`security`·Util 테스트)가 같은 구조로 이동
  - [x] `./gradlew build` 통과
- **주의:**
  - [탐색] `@ConfigurationPropertiesScan`/`@EnableConfigurationProperties` 대상 경로, `SecurityConfig`가 `new`로 만드는 빈이 패키지에 의존하는지 확인
  - [구현] `ContentDispositionBuilder.FILENAME_MAX_LENGTH`, `InternalRedirectPathConverter.INTERNAL_PREFIX`가 package-private이다. 다른 패키지 참조 여부 확인
  - [검토] `ErrorTestController`, `SecurityTestController` 같은 테스트 지원 클래스도 함께 이동(R4-2에서 삭제 예정)

### [ ] R1-2 `user`·`playback` 도메인 이동
- **목표:**
  - `user/`: `User`(entity), `UserRepository`, `UserService`·`OAuth2UserInfo`(service), `UserResponse`(dto/response), `AuthController`
  - `playback/`: `PlaybackLog`·`ActionType`(entity), `PlaybackLogRepository`, `PlaybackLogService`
- **선행:** R1-1
- **완료 조건:** (테스트: 기존 테스트 이동 + 실행)
  - [ ] PRD-05 §5 `user/`, `playback/` 트리와 일치
  - [ ] `./gradlew build` 통과
- **주의:**
  - [탐색] `global/security`(`CustomOidcUserService`, `AuthenticatedOidcUser`)가 `UserService`·`User`를 참조하므로 import 변경 범위가 넓다
  - [검토] JPA 엔티티 스캔은 루트 패키지 기준이라 설정 변경이 필요 없는지 `EntityMappingTest`로 확인

### [ ] R1-3 `storage`·`transcode` 도메인 이동
- **목표:**
  - `storage/service`: `StorageService`, `MinioStorageService`, `StorageBucketInitializer`
  - `transcode/service`: `HlsTranscodeService`, `TranscodeProcessor`, `TranscodeTaskDispatcher`, `ApplicationEventTranscodeTaskDispatcher`, `TranscodeEventRelay`, `TranscodeRejectionHandler`, `TranscodeRecoveryRunner`, `FfmpegCommandRunner`, `ProcessFfmpegCommandRunner`, `FfmpegCommandBuilder`, `FfmpegResult`
  - `transcode/dto`: `TranscodeEventDto`(현재 `dto/event`)
- **선행:** R1-2
- **완료 조건:** (테스트: 기존 테스트 이동 + 실행)
  - [ ] PRD-05 §5 트리와 일치
  - [ ] `Transcode*IntegrationTest`, `TranscodeIntegrationTestBase`가 이동 후에도 통과
  - [ ] `./gradlew build` 통과
- **주의:**
  - [구현] `ProcessFfmpegCommandRunner`의 package-private 상수(`START_FAILED_SUMMARY` 등)와 `FakeProcessMain`(test support)의 연결 확인
  - [검토] `@Async("transcodeExecutor")`·`@TransactionalEventListener` 연결은 이동 후 `TranscodeEventWiringIntegrationTest`로 검증

### [ ] R1-4 `track` 도메인 이동
- **목표:** `Track`·`TrackStatus`(entity), `TrackRepository`, `TrackService`·`AudioMetadataValidator`·`MediaUrlResolver`(service), `TrackUploadRequest`(dto/request), `TrackResponse`·`TrackPageResponse`·`StreamPlayResponse`·`DownloadInfo`(dto/response), `TrackController`를 `track/`으로 옮기고, 비게 된 레이어 패키지(`controller`, `service`, `repository`, `entity`, `dto`)를 없앤다.
- **선행:** R1-3
- **완료 조건:** (테스트: 기존 테스트 이동 + 실행)
  - [ ] 루트 아래에 `global`, `track`, `user`, `playback`, `transcode`, `storage` 패키지와 `MusicApplication`만 남음
  - [ ] `./gradlew build` 통과
- **주의:**
  - [구현] `TrackService`의 package-private 상수(`TITLE_MAX_LENGTH`, `MAX_PAGE_SIZE` 등)를 테스트가 참조한다. 테스트 이동 경로를 맞춘다
  - [검토] 예외 클래스(`TrackNotFoundException` 등)는 PRD상 `global/exception`에 남긴다(도메인으로 옮기지 않음)

---

## Phase 2 — Service 인터페이스/Impl 분리 (spring-java.md §2)

### [ ] R2-1 도메인 서비스가 아닌 클래스의 `@Component` 전환
- **목표:** `@Service`가 붙은 비도메인 클래스 `CustomOidcUserService`, `ApplicationEventTranscodeTaskDispatcher`, `MinioStorageService`를 `@Component`로 바꾼다.
- **선행:** R1-4
- **완료 조건:** (테스트: 기존 테스트 실행만, 신규 작성 없음)
  - [ ] `@Service`가 `XxxServiceImpl`이 될 클래스(`UserService`, `PlaybackLogService`, `TrackService`, `HlsTranscodeService`)에만 남음
  - [ ] `./gradlew build` 통과
- **주의:**
  - [검토] `@Component`로 바꾼 클래스의 기존 테스트(`CustomOidcUserServiceTest`, `MinioStorageServiceTest` 등)는 R4-2에서 삭제한다. 지금은 유지

### [ ] R2-2 `UserService`·`PlaybackLogService` 분리
- **목표:** 두 서비스를 인터페이스 + `XxxServiceImpl`로 나누고, 주입받는 쪽은 인터페이스 타입을 쓰게 한다. 테스트는 `UserServiceImplTest`, `PlaybackLogServiceImplTest`로 이름을 바꾼다.
- **선행:** R2-1
- **완료 조건:** (테스트: **단위 테스트 필수**)
  - [ ] `UserServiceImplTest`: PRD-05 §8 시나리오(신규 저장, 기존 회원 갱신) 포함
  - [ ] `PlaybackLogServiceImplTest`: §8 시나리오(ActionType별 저장, 비로그인 `user=null`, 저장 실패 시 예외 삼킴) 포함
  - [ ] 두 테스트 모두 Spring 컨텍스트 없이 `@ExtendWith(MockitoExtension.class)`
  - [ ] `./gradlew build` 통과
- **주의:**
  - [계획] 인터페이스에는 외부에서 호출하는 public 메서드만 둔다. 내부 헬퍼는 Impl의 private으로 남긴다
  - [구현] `@Transactional`은 Impl 메서드에 둔다(인터페이스에 두지 않음)
  - [검토] `PlaybackLogService`가 트랜잭션 전파(`REQUIRES_NEW` 등)로 호출자 트랜잭션과 분리돼 있다면 그대로 유지되는지 확인

### [ ] R2-3 `TrackService` 분리 및 테스트 통합
- **목표:** `TrackService` 인터페이스 + `TrackServiceImpl`로 나누고, `TrackServiceTest`·`TrackQueryServiceTest`·`TrackServicePlayTest`·`TrackServiceDownloadTest`를 `TrackServiceImplTest`로 합친다.
- **선행:** R2-2
- **완료 조건:** (테스트: **단위 테스트 필수**)
  - [ ] `TrackController`가 `TrackService` 인터페이스를 주입받음
  - [ ] `TrackServiceImplTest`가 PRD-05 §8의 1~7번 시나리오를 모두 포함(누락 시 추가)
  - [ ] 합친 뒤 테스트 메서드 수가 합치기 전 합계 이상
  - [ ] `./gradlew build` 통과
- **주의:**
  - [계획] 4개 테스트를 한 클래스로 합칠 때 `@Nested`(업로드/조회/재생/다운로드)로 묶어 가독성을 유지
  - [구현] `TrackUploadRollbackIntegrationTest` 등 통합 테스트가 구체 클래스를 `@MockitoBean`/`@Autowired`하고 있으면 인터페이스 타입으로 바꾼다
  - [검토] 업로드 보상 처리(DB 실패 시 객체 삭제)가 `@Transactional` 프록시 경계에 의존하는지, 분리 후에도 같은지 확인

### [ ] R2-4 `HlsTranscodeService` 분리 (`TranscodeProcessor` 통합)
- **목표:** 기존 `TranscodeProcessor` 인터페이스를 `HlsTranscodeService` 인터페이스로 이름을 바꿔 합치고, 구현을 `HlsTranscodeServiceImpl`로 옮긴다. 테스트는 `HlsTranscodeServiceImplTest`로 바꾼다.
- **선행:** R2-3
- **완료 조건:** (테스트: **단위 테스트 필수**)
  - [ ] `TranscodeProcessor` 제거, `TranscodeEventRelay`·`TranscodeRejectionHandler`·`TranscodeRecoveryRunner`가 `HlsTranscodeService` 타입을 주입받음
  - [ ] `HlsTranscodeServiceImplTest`: §8 시나리오(성공 시 READY·`hlsPlaylistKey`, 실패 시 FAILED·임시 폴더 삭제, 타임아웃 시 `FFMPEG_TIMEOUT`) 포함
  - [ ] `Transcode*IntegrationTest` 통과(비동기·큐 포화 동작 보존)
  - [ ] `./gradlew build` 통과
- **주의:**
  - [탐색] `@Async`가 인터페이스 메서드와 Impl 중 어디에 있는지, 프록시 방식(CGLIB 기본)에서 동작이 바뀌지 않는지 확인
  - [구현] `HlsTranscodeService.FFMPEG_TIMEOUT_MESSAGE` 같은 public 상수를 외부에서 참조하면 위치(인터페이스 vs Impl)를 정해 일관되게 옮긴다
  - [검토] 같은 클래스 안의 자기 호출로 `@Async`/`@Transactional`이 무시되는 경로가 새로 생기지 않았는지 확인

---

## Phase 3 — 설정값 외부화 (spring-java.md §4, PRD-05 §4.2)

> 공통 주의: 목표 yaml은 PRD-05 §4.2를 그대로 따른다. PRD에 **없는** 코드 상수(예: 쿠키 값 최대 길이, CORS preflight max-age, 프로세스 kill 대기 시간)는 옮길지 임의로 정하지 말고 목록으로 정리해 보고한다. 프로토콜·포맷 상수(`Bearer `, `index.m3u8`, 에러 코드 문자열 등)는 기준값이 아니므로 코드에 남긴다.
> Properties·Config 클래스에는 새 테스트를 쓰지 않는다. 기존 `ConfigurationPropertiesBindingTest`는 R4-2 전까지 갱신만 한다.

### [ ] R3-1 `AsyncProperties`·`TrackProperties` 도입
- **목표:**
  - `app.async.transcode.{core-pool-size, max-pool-size, queue-capacity}` → `AsyncProperties`, `AsyncConfig` 상수 제거
  - `app.track.{default-page-size, max-page-size}` → `TrackProperties`, `TrackServiceImpl.MAX_PAGE_SIZE`와 `TrackController`의 `defaultValue = "20"` 제거
- **선행:** R2-4
- **완료 조건:** (테스트: **`TrackServiceImplTest` 갱신 필수**)
  - [ ] `application.yaml`에 두 섹션 추가(PRD-05 §4.2 값 그대로)
  - [ ] `TrackServiceImplTest`에 size 미지정 시 기본값 적용, 최대값 초과 시 제한 케이스 포함
  - [ ] `AsyncConfigTest`, `TranscodeQueue*IntegrationTest`가 상수 대신 프로퍼티 값으로 통과
  - [ ] `./gradlew build` 통과
- **주의:**
  - [계획] 어노테이션 속성(`defaultValue`)에는 프로퍼티를 넣을 수 없다. `@RequestParam(required = false) Integer size`로 받고 기본값은 서비스에서 적용하는 방식을 검토
  - [구현] `TRANSCODE_EXECUTOR` 빈 이름과 `THREAD_NAME_PREFIX`는 기준값이 아니므로 상수로 유지
  - [검토] 통합 테스트가 `AsyncConfig.QUEUE_CAPACITY`로 큐 포화를 재현하고 있으므로 테스트 프로퍼티로 값을 고정했는지 확인

### [ ] R3-2 `JwtProperties`·`CorsProperties` 확장
- **목표:**
  - `app.jwt.authorization-cookie-max-age-seconds` → `HttpCookieOAuth2AuthorizationRequestRepository.COOKIE_MAX_AGE_SECONDS` 대체
  - `app.jwt.allowed-failure-error-codes` → `OAuth2AuthenticationFailureHandler.ALLOWED_ERROR_CODES` 대체
  - `app.cors.{allowed-methods, allowed-headers}` → `CorsConfig` 고정 목록 대체
- **선행:** R2-4
- **완료 조건:** (테스트: 기존 테스트 실행만. 대상이 모두 `@Component`/Config라 신규 작성 없음)
  - [ ] `application.yaml`에 PRD-05 §4.2 값 추가
  - [ ] `SecurityConfigTest`, `OAuth2LoginFlowTest`, `Cors*Test`, `HttpCookieOAuth2AuthorizationRequestRepositoryTest`, `OAuth2AuthenticationFailureHandlerTest` 통과
  - [ ] PRD에 없는 상수(`MAX_COOKIE_VALUE_LENGTH`, `MAX_REDIRECT_URI_LENGTH`, `PREFLIGHT_MAX_AGE_SECONDS`, `ALLOWED_SCHEMES`)는 처리 방침을 보고
  - [ ] `./gradlew build` 통과
- **주의:**
  - [탐색] `HttpCookieOAuth2AuthorizationRequestRepository`는 `SecurityConfig`에서 `new`로 만들므로 생성자에 `JwtProperties`를 넘기도록 바꿔야 한다
  - [구현] 빈 목록·`null` 처리는 기존 `PropertyUtils.normalizeList`를 재사용
  - [검토] 보안 민감 태스크. 테스트를 `--rerun-tasks`로 2~3회 반복 실행

### [ ] R3-3 `AudioProperties` 확장
- **목표:** `app.audio`에 `hls-audio-sample-rate`, `hls-audio-channels`, `error-message-max-length`, `recovery-stale-threshold-multiplier`, `allowed-audio-mime-types`, `allowed-artwork-mime-types`를 추가하고 코드의 고정값을 대체한다.
  - `FfmpegCommandBuilder`의 `"-ar", "44100", "-ac", "2"`
  - `Track.ERROR_MESSAGE_MAX_LENGTH` / `FfmpegResult.MAX_SUMMARY_LENGTH`(500)
  - `TranscodeRecoveryRunner`의 정리 기준 배수
  - `AudioMetadataValidator`의 허용 MIME 목록(`MIME_TO_EXTENSION` 키, `ALLOWED_ARTWORK_MIMES`)
- **선행:** R2-4
- **완료 조건:** (테스트: **`HlsTranscodeServiceImplTest`·`TrackTest` 갱신 필수**)
  - [ ] `application.yaml` `app.audio`가 PRD-05 §4.2와 일치
  - [ ] 오류 메시지 자르기 길이가 설정값을 따르는지 단위 테스트로 확인
  - [ ] `./gradlew build` 통과
- **주의:**
  - [계획] 엔티티(`Track`)는 프로퍼티를 주입받을 수 없다. 자르기를 호출자(서비스)로 옮기거나 길이를 인자로 받는 방식 중 택일. `@Column(length = 500)`은 DDL 계약(PRD-07)이므로 상수로 남기고, 설정값이 컬럼 길이를 넘지 않도록 막는다
  - [구현] MIME → 확장자 매핑은 코드에 남기고, 허용 목록만 yaml로 옮긴다. yaml 목록에 매핑 없는 MIME이 있으면 기동 시 실패시키는 검증을 검토
  - [검토] `FfmpegCommandBuilderTest`, `AudioMetadataValidatorTest`(R4-2 삭제 예정)가 바뀐 생성자로 통과하는지 확인

---

## Phase 4 — 테스트 범위 정리 (spring-java.md §3, PRD-05 §8)

### [ ] R4-1 허용 범위 단위 테스트 보강
- **목표:** PRD-05 §8 표에서 아직 없는 테스트를 채운다.
  - `UserTest`(Entity): 재로그인 시 `email`·`profileImageUrl`·`lastLoginAt` 갱신
  - `TrackTest`: 상태 전이 허용·금지, READY 시 `hlsPlaylistKey`, FAILED 시 `errorMessage`
  - Util 4종(`ContentDispositionBuilderTest`, `InternalRedirectPathConverterTest`, `LikePatternEscaperTest`, `LogTailBufferTest`)이 `global/util` 경로에 있고 §8 시나리오를 포함하는지
- **선행:** Phase 3 완료
- **완료 조건:** (테스트: **단위 테스트 필수**)
  - [ ] §8 표의 모든 클래스·시나리오에 대응하는 테스트 존재(체크리스트로 대조)
  - [ ] 모두 Spring 컨텍스트 없이 실행
  - [ ] `./gradlew build` 통과
- **주의:**
  - [탐색] `TrackStatus`, `ActionType` Enum에 로직이 있으면 Enum 테스트도 대상이다

### [ ] R4-2 비허용 테스트 삭제
- **목표:** 규칙상 허용되지 않는 테스트와 그 지원 클래스를 삭제한다.
  - Controller: `AuthControllerTest`, `TrackControllerTest`
  - Repository: `TrackRepositoryTest`, `UserRepositoryTest`, `PlaybackLogRepositoryTest`
  - Config·Properties: `ApplicationProfileTest`, `AsyncConfigTest`, `StorageConfigTest`, `SecurityConfigTest`, `Cors*Test`, `OAuth2LoginFlowTest`, `ConfigurationProperties*Test`
  - DTO: `TranscodeEventDtoTest`, `TrackResponseTest`, `TrackPageResponseTest`
  - `@Component`: `AudioMetadataValidatorTest`, `MediaUrlResolverTest`, `MinioStorageServiceTest`, `FfmpegCommandBuilderTest`, `ProcessFfmpegCommandRunnerTest`, `ApplicationEventTranscodeTaskDispatcherTest`, `TranscodeEventRelayTest`, `TranscodeRejectionHandlerTest`, `TranscodeRecoveryRunnerTest`, `global/security`의 각 테스트, `GlobalExceptionHandlerTest`
  - 통합: `*IntegrationTest`, `TranscodeIntegrationTestBase`, `EntityMappingTest`(`@DataJpaTest`), `MusicApplicationTests`
  - 지원 클래스: `ErrorTestController`, `SecurityTestController`, `FakeProcessMain`, `SampleFixtureTest`(남은 테스트가 쓰지 않으면 `SampleFixtures`와 test 리소스도)
- **선행:** R4-1
- **완료 조건:** (테스트: 남은 테스트 실행)
  - [ ] **삭제 직전** `./gradlew build --rerun-tasks` 전체 그린 확인(마지막 회귀 확인)
  - [ ] 남은 테스트가 `*ServiceImplTest`, Entity 테스트, Util 테스트뿐
  - [ ] 남은 테스트 중 Spring 컨텍스트를 띄우는 것이 0개(`@SpringBootTest`·`@WebMvcTest`·`@DataJpaTest` 검색 결과 없음)
  - [ ] `./gradlew build` 통과
- **주의:**
  - [계획] `MusicApplicationTests.contextLoads`는 규칙상 삭제 대상이지만 유일한 기동 확인이다. 삭제하면 R5-1의 `bootRun` 확인으로 대신한다
  - [구현] 테스트 전용 의존성(예: H2 test scope, `spring-security-test`)이 남은 테스트에서 안 쓰이게 돼도 `build.gradle.kts`는 바꾸지 않는다. 정리가 필요하면 보고만 한다
  - [검토] 삭제 전후 테스트 목록을 커밋 메시지에 남긴다

---

## Phase 5 — 최종 검토

### [ ] R5-1 규칙 준수 감사 및 문서 동기화
- **목표:** 규칙 전 항목을 점검하고 문서를 현재 구조에 맞춘다.
- **선행:** R4-2
- **완료 조건:** (테스트: 전체 실행 + 수동 기동)
  - [ ] 점검: 공백 들여쓰기 0건 / `@Service`는 `*ServiceImpl`에만 / `*ServiceImpl` 타입 직접 주입 0건 / 레이어별 루트 패키지 0개
  - [ ] 하드코딩 기준값 재검색 결과(남긴 상수와 이유) 정리
  - [ ] 환경변수를 넣고 `./gradlew bootRun`(local, H2) 기동 확인
  - [ ] `CLAUDE.md`의 "현재 상태" 절과 테스트 명령 예시(`com.streaming.music.service.TrackServiceTest`) 갱신, README에 옛 경로가 있으면 갱신
  - [ ] `./gradlew build --rerun-tasks` 통과
- **주의:**
  - [검토] PRD-05와 실제 코드가 다른 부분(예: PRD에 없는 클래스, 남긴 상수)은 PRD를 고치지 말고 목록으로 보고한다

---

## 기준선 (R0-1에서 기록)
- 테스트 클래스 수: 57 (2026-11-14, `build --rerun-tasks` 통과, skipped/failures/errors 0)
- 테스트 수: 450

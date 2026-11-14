# 작업 진행 기록 (PROGRESS)

> 새 세션은 이 파일 → `ROADMAP.md` 순으로 읽고 이어서 작업한다.
> 진행 방식: 메인이 `spring-backend-expert`에게 태스크를 하나씩 지시(PLAN → 승인 → IMPLEMENT) → 메인이 코드 검토·테스트 재실행 → 커밋 → 다음 태스크.

**📅 최종 업데이트**: 2026-11-14

## 현재 작업
- Phase 2 완료(10/16). Phase 3 R3-1(`AsyncProperties`·`TrackProperties` 도입) PLAN 단계 진행. 사용자 지시 범위: ROADMAP 전체 자율 진행.

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

## 검토 필요 / 보고 사항
- `CorsConfigTest`는 빈을 직접 생성해 `@Primary`를 증명하지 못함. 실질 증거는 `CorsEnabledTest`·`SecurityConfigTest`. 제거 시 `NoUniqueBeanDefinitionException` 재현은 하지 않음.
- 테스트 기준선은 R2-2 이후 451(PlaybackLog DOWNLOAD +1). R2-3 합치기 전 TrackService 계열 합계 52(TrackServiceTest 24, Play 7, Download 10, Query 11). 재집계는 `build/test-results/test/*.xml`.
- `.editorconfig` 없음 → 탭 설정 고정이 필요하면 별도 판단(범위 밖).
- 환경: python 없음(grep/awk/sed 사용), `core.autocrlf=true`로 LF→CRLF 경고 출력(내용 영향 없음, 작업 트리·인덱스는 LF).

## 다음 작업
- Phase 3: R3-1 → R3-2 → R3-3, Phase 4(R4-1 → R4-2), R5-1 (ROADMAP 끝까지 자율 진행)

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

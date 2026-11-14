# 작업 진행 기록 (PROGRESS)

> 새 세션은 이 파일 → `ROADMAP.md` 순으로 읽고 이어서 작업한다.
> 진행 방식: 메인이 `spring-backend-expert`에게 태스크를 하나씩 지시(PLAN → 승인 → IMPLEMENT) → 메인이 코드 검토·테스트 재실행 → 커밋 → 다음 태스크.

**📅 최종 업데이트**: 2026-11-14

## 현재 작업
- Phase 1 진행 중(4/16). R1-2 완료, R1-3(`storage`·`transcode` 도메인 이동) PLAN 단계 진행. 사용자 지시 범위: Phase 1(R1-1~R1-4) 자율 진행.

## 완료한 작업
- R0-1: `@Primary`(ab80674)는 이미 커밋돼 있어 추가 커밋 없음. 기준선 측정 — 테스트 클래스 57, 테스트 450, 실패·스킵 0. (c679e3f)
- R0-2: Java 137개 중 133개 파일 공백 4칸 → 탭 전환. `git diff -w` 비어 있음, 잔존 공백 들여쓰기 0건, 빌드·테스트 57/450 유지. (87b48fb)
  - 정렬용 이어쓰기 28줄은 "직전 비정렬 줄 들여쓰기 + 탭 2개"로 정규화.
  - Javadoc ` *` 앞 공백 1칸은 정렬용이라 유지.
- R1-1: config·exception·security·Util 4종을 `global/`로 이동(메인 37 + 테스트 24, `git mv`). `@ConfigurationPropertiesScan` 문자열 갱신, `ContentDispositionBuilder`·`InternalRedirectPathConverter`·`LogTailBuffer`만 public 확장(상수는 package-private 유지). 메인이 `build --rerun-tasks` 재실행해 57/450·실패 0 확인, 비 import/package 변경 라인 점검. (d3e4ddb)
- R1-2: `User`·`PlaybackLog` 계열 메인 10 + 테스트 5를 `user/`·`playback/`으로 이동(`git mv`). package/import 외 변경 없음, 접근 제어자 확장 없음. `EntityMappingTest`는 `entity/` 유지(R1-4에서 재배치 결정), `AuthControllerTest`는 `user/controller`로 이동. 메인이 `build --rerun-tasks` 재실행해 57/450·실패 0 확인.

## 검토 필요 / 보고 사항
- R1-3 이동 시 `MinioStorageService`가 `storage/service`로 가면 `InternalRedirectPathConverterTest`(현재 `global/util`)의 import 갱신 필요(에이전트 보고).
- `TrackRepository` JPQL 문자열의 `com.streaming.music.entity.TrackStatus`는 R1-4(entity 이동) 때 함께 수정해야 함(문자열이라 컴파일러가 못 잡음). R1-2에서 `User`·`PlaybackLog` 이동 시 유사한 문자열 FQN이 없는지도 확인.
- R1-4에서 `AuthControllerTest`(`user/controller`)의 `TrackController` import도 갱신 필요(에이전트 보고). `EntityMappingTest`(`entity/`) 재배치 위치도 R1-4에서 결정.
- `CorsConfigTest`는 빈을 직접 생성해 `@Primary`를 증명하지 못함. 실질 증거는 `CorsEnabledTest`·`SecurityConfigTest`. 제거 시 `NoUniqueBeanDefinitionException` 재현은 하지 않음.
- 클래스별 테스트 수 비교용: TrackService 계열 합계 52(TrackServiceTest 24, Play 7, Download 10, Query 11). 재집계는 `build/test-results/test/*.xml`.
- `.editorconfig` 없음 → 탭 설정 고정이 필요하면 별도 판단(범위 밖).
- 환경: python 없음(grep/awk/sed 사용), `core.autocrlf=true`로 LF→CRLF 경고 출력(내용 영향 없음, 작업 트리·인덱스는 LF).

## 다음 작업
- R1-3 `storage`·`transcode` 도메인 이동 → R1-4 (Phase 1 끝까지 자율 진행)

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

# 작업 진행 기록 (PROGRESS)

> 새 세션은 이 파일 → `ROADMAP.md` 순으로 읽고 이어서 작업한다.
> 진행 방식: 메인이 `spring-backend-expert`에게 태스크를 하나씩 지시(PLAN → 승인 → IMPLEMENT) → 메인이 코드 검토·테스트 재실행 → 커밋 → 다음 태스크.

**📅 최종 업데이트**: 2026-11-14

## 현재 작업
- R0-2 (들여쓰기 탭 전환) PLAN 단계 시작 전

## 완료한 작업
- R0-1: `@Primary`(ab80674)는 이미 커밋돼 있어 추가 커밋 없음. 기준선 측정 완료 — 테스트 클래스 57, 테스트 450, 실패·스킵 0 (메인이 `build --rerun-tasks`로 재검증).

## 검토 필요 / 보고 사항
- `CorsConfigTest`는 빈을 직접 생성해 `@Primary`를 증명하지 못함. 실질 증거는 `CorsEnabledTest`·`SecurityConfigTest`(컨텍스트 로딩). 제거 시 `NoUniqueBeanDefinitionException` 재현은 하지 않음.
- 클래스별 테스트 수 표는 R2-3(TrackService 계열 합계 52: TrackServiceTest 24, Play 7, Download 10, Query 11)·R4-2 비교용. 필요하면 `build/test-results/test/*.xml`로 재집계.
- 실행 환경에 python 없음(집계는 grep/awk 사용).

## 다음 작업
- R0-2 → (Phase 0 종료)

## 결정 이력
- 진행 문서 위치는 `.claude/docs/PROGRESS.md`.

# CLAUDE.md

이 파일은 Claude Code(claude.ai/code)가 이 저장소에서 작업할 때 참고하는 안내서이다.

## 프로젝트 개요

음원 스트리밍 및 원본 아카이빙 서비스(MVP)의 **백엔드 서버**이다. 업로드된 음원의 원본과 HLS(`.m3u8`/`.ts`) 청크를 분리 저장하고 스트리밍/다운로드 API를 제공한다. 기능 구현(PRD-05 기준)은 완료되어 있고, 현재는 **Java 코딩 규칙(`.claude/rules/spring-java.md`)에 맞춘 코드 리팩터링**이 다음 작업이다.

- Spring Boot 4.1.2, Java 21 toolchain, Gradle Kotlin DSL (`build.gradle.kts`), Lombok
- 패키지 루트: `com.streaming.music`

## 명령어

Windows에서는 `gradlew.bat`(PowerShell) 또는 `./gradlew`(Git Bash)를 사용한다. 별도의 lint 설정은 없다.

```bash
./gradlew build                  # 컴파일 + 테스트
./gradlew bootRun                # 앱 실행 (기본 8080, local 프로파일, H2 콘솔: /h2-console)
./gradlew test                   # 전체 테스트
./gradlew test --tests "com.streaming.music.service.TrackServiceTest"           # 단일 테스트 클래스
./gradlew test --tests "com.streaming.music.service.TrackServiceTest.메서드명"   # 단일 테스트 메서드
```

## 규칙 및 문서

### Java 코딩 규칙 (중요)
**Java 코드를 작성·수정할 때는 `.claude/rules/spring-java.md`를 반드시 먼저 읽고 따른다.** 들여쓰기(탭), 서비스 인터페이스/`Impl` 분리, 테스트 범위, 설정값 관리, 도메인별 패키지 구조를 정의하며, 다른 규칙과 충돌하면 Java 파일 작업에서는 이 규칙이 우선한다.

### PRD 우선 규칙
`.claude/docs/`의 PRD가 설계의 단일 기준이다. **기능 구현 전에는 해당 번호의 PRD 파일을 반드시 읽는다.** 인덱스·공통 규칙·용어·결정 이력은 `PRD.md`, 진행 현황은 `ROADMAP.md`에 있다.

| 파일 | 범위 |
|---|---|
| `PRD-01.md` | 전체 구조 및 아키텍처 (항상 기준으로 참조) |
| `PRD-05.md` | **백엔드 서버 (이 저장소의 구현 대상)** |
| `PRD-02`~`04`, `06`, `07` | 웹/앱 클라이언트, Nginx, 음원 서버, DB. **인터페이스 계약 확인용으로만 읽는다.** |

### 작업 범위
- 이 저장소는 **백엔드(PRD-05)만** 구현한다.
- 범위 밖 요구사항을 발견하면 구현하지 말고 보고한다.
- PRD-05와 다른 영역 문서가 충돌하면 임의로 판단하지 말고 사용자에게 묻는다.

## 현재 상태: 코드와 PRD-05(v1.5) 간 차이

PRD-05 v1.5는 규칙을 반영한 목표 구조를 정의하지만 **소스는 아직 이전 구조**이다. 리팩터링은 아래 차이를 PRD-05 §4·§5·§8 기준으로 맞추는 작업이다.

- **패키지**: 현재 레이어별(`config`, `controller`, `service` …) ➔ 도메인별(`global`, `track`, `user`, `playback`, `transcode`, `storage`).
- **서비스**: `TrackService` 등이 단일 클래스 ➔ 인터페이스 + `XxxServiceImpl`. 도메인 서비스가 아닌 클래스(`CustomOidcUserService`, `ApplicationEventTranscodeTaskDispatcher`, `MinioStorageService` 등)는 `@Component`.
- **테스트**: Service(Impl)·Entity·Util만 허용. 그 외 클래스의 기존 테스트는 PRD-05 §8 기준으로 정리하고, 서비스 테스트는 `XxxServiceImplTest`로 변경.
- **설정값**: 코드에 고정된 수치·목록(스레드 풀, 쿠키 Max-Age, 페이지 크기, 허용 MIME 등)을 `application.yaml`(`app.async`, `app.track`, `app.jwt`, `app.audio`, `app.cors`)로 이동.
- **들여쓰기**: 기존 Java 소스는 공백 4칸 ➔ 탭.

## 아키텍처 핵심 (상세는 PRD-01·PRD-05)

- **무상태(Stateless)**: `HttpSession` 미사용. Google OAuth2 로그인 후 JWT 발급, `JwtAuthenticationFilter`로 Bearer 검증 (sub/email/picture만 저장, 자동 Upsert).
- **미디어 바이트 격리**: 스트리밍 청크·원본 다운로드는 백엔드가 직접 전송하지 않는다. Nginx ➔ RustFS(S3 호환)에서 처리하고, 백엔드는 `X-Accel-Redirect`(`/internal-storage/*`)로 지시만 한다.
- **업로드 파이프라인**: 동기(Tika 검증, JAudioTagger 추출, 원본 저장) 후 즉시 응답 ➔ 비동기(`@Async`) FFmpeg HLS 변환·업로드. `Track` 상태: `PENDING → PROCESSING → READY / FAILED`.
- **보안 정책**: MVP는 `/api/auth/me`를 제외한 모든 API `permitAll`. 추후 Spring Security 설정만으로 권한 통제가 가능하도록 진입점을 단일화한다.
- **외부 인프라 추상화**: RustFS·FFmpeg는 인터페이스로 분리해 Service 테스트에서 Mock으로 대체한다.
- **설정**: 민감 정보는 환경변수로 분리하고 `@ConfigurationProperties`로 바인딩한다 (PRD-05 §4).

## 공통 코딩 규칙

- 코드 주석, 커밋 메시지, 문서는 **한국어**로 작성한다.
- 변수/함수명은 영어, 변수는 camelCase, 함수명은 동사로 시작한다 (예: `getUserData`).
- 들여쓰기 등 Java 세부 규칙은 `.claude/rules/spring-java.md`를 따른다.

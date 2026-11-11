# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

음원 스트리밍 및 원본 아카이빙 서비스(MVP)의 **백엔드 서버**이다. 업로드된 음원의 원본과 HLS(`.m3u8`/`.ts`) 청크를 분리 저장하고 스트리밍/다운로드 API를 제공한다. 현재 저장소는 Spring Boot 스켈레톤 상태(`MusicApplication` + `application.yaml`만 존재)이며, 실제 구현은 PRD 문서를 기준으로 진행한다.

- Spring Boot 4.1.2, Java 21 toolchain, Gradle Kotlin DSL (`build.gradle.kts`), Lombok 사용
- 패키지 루트: `com.streaming.music`

## 명령어

Windows에서는 `gradlew.bat`(PowerShell) 또는 `./gradlew`(Git Bash)를 사용한다.

```bash
./gradlew build                  # 컴파일 + 테스트
./gradlew bootRun                # 앱 실행 (기본 8080, H2 콘솔: /h2-console)
./gradlew test                   # 전체 테스트
./gradlew test --tests "com.streaming.music.MusicApplicationTests"            # 단일 테스트 클래스
./gradlew test --tests "com.streaming.music.service.TrackServiceTest.메서드명"  # 단일 테스트 메서드
```

별도의 lint 설정은 없다.

## 문서 우선 규칙 (중요)

`.claude/docs/`의 PRD가 설계의 단일 기준이다. **기능 구현 전에는 해당 번호의 PRD 파일을 반드시 읽는다** (`PRD.md`의 공통 규칙).

| 파일 | 범위 |
|---|---|
| `PRD.md` | 인덱스 및 공통 규칙 |
| `PRD-01.md` | 전체 구조 및 아키텍처 |
| `PRD-02.md` / `PRD-03.md` | 웹 / 모바일 앱 클라이언트 |
| `PRD-04.md` | Nginx 리버스 프록시 |
| `PRD-05.md` | **백엔드 서버 (이 저장소의 주 대상)** |
| `PRD-06.md` | 음원 서버 (RustFS + FFmpeg) |
| `PRD-07.md` | PostgreSQL DB |

## 작업 범위 (중요)
- 이 저장소는 **백엔드(PRD-05)만** 구현한다.
- PRD-01(전체 구조)은 항상 기준 문서로 참조한다.
- 그 외의 PRD-NN 문서는 **인터페이스 계약 확인용으로만 읽는다.**
- 범위 밖 요구사항을 발견하면 구현하지 말고 보고한다.
- PRD-05와 다른 영역 문서가 충돌하면 임의로 판단하지 말고 사용자에게 묻는다.

## 아키텍처 (여러 파일을 봐야 이해되는 핵심)

- **전체 구성**: Nginx(80) → 백엔드 클러스터(`web1`, `web2`) / RustFS(S3 호환 스토리지, MinIO 대체, 포트 9000/9001) / PostgreSQL. 5개 도커 컨테이너 구성.
- **무상태(Stateless)**: `HttpSession`을 쓰지 않는다. 인증은 Google OAuth2 로그인 후 JWT 발급, `JwtAuthenticationFilter`로 Bearer 토큰 검증 (무가입 방식: sub/email/picture만 저장, 자동 Upsert).
- **미디어 바이트 전송 격리**: 스트리밍 청크와 원본 다운로드는 백엔드가 직접 전송하지 않고 Nginx ➔ RustFS 구간에서 처리한다. 백엔드는 `X-Accel-Redirect`(`/internal-storage/*`)로 내부 리다이렉트만 지시한다.
- **업로드 파이프라인**: 동기 구간(Tika 위변조 검증, JAudioTagger 태그/앨범아트 추출, 원본 저장) 후 즉시 응답 → 비동기(`@Async`) `HlsTranscodeService`가 FFmpeg로 HLS 분할 후 RustFS에 재귀 업로드. `Track` 상태: `PENDING → PROCESSING → READY / FAILED`.
- **보안 정책**: MVP는 모든 API `permitAll`이되, 추후 Spring Security 설정만으로 권한 통제가 가능하도록 진입점을 단일화한다.
- **레이어드 구조**: Controller → Service → Repository 단방향. 외부 인프라(RustFS, FFmpeg)는 인터페이스로 추상화하여 Service 단위 테스트에서 Mock으로 대체한다. 세부 패키지 구조(`config`, `security`, `controller`, `dto`, `entity`, `repository`, `service`, `exception`)는 PRD-05 §5 참조.
- **설정**: 민감 정보는 환경변수로 분리하고 `@ConfigurationProperties`(`StorageProperties`, `AudioProperties`, `JwtProperties`)로 바인딩 (PRD-05 §4).

## 현재 코드와 PRD 간 차이

- `build.gradle.kts`에는 PRD-05 §3.2에 명시된 라이브러리(AWS S3 SDK, Tika, JAudioTagger, JWT 등)가 아직 없다. 구현 시 PRD 명세를 확인하고 추가한다.
- `application.yaml`은 현재 H2 인메모리 DB 설정만 있다. PRD상 운영 DB는 PostgreSQL이다 (PRD-07).

## 코딩 규칙

- 코드 주석, 커밋 메시지, 문서는 **한국어**로 작성한다.
- 변수/함수명은 영어, 변수는 camelCase, 함수명은 동사로 시작한다 (예: `getUserData`).
- 들여쓰기는 4칸 공백이다.

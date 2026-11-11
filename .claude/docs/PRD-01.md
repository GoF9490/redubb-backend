# \[PRD\] 01\_전체\_구조\_및\_아키텍처 (v2.4 - 문서 간 정합성 보완안)

> **v2.4 변경 요약 (2026-11-11):** 이벤트 DTO 필드명 `originalS3Key` 통일, 원본 다운로드 presigned 방식 전환, 스토리지 접근 정책(루프백 바인딩·경로별 익명 읽기) 및 플랫폼 분리 대비 원칙 추가, 서버 명칭 `web-server-1/2` 통일. 상세는 `PRD.md` §7 참조.

## 1. 개요 및 목적

본 문서는 외부 퍼블릭 클라우드(AWS 등) 종속성을 배제하고, 온프레미스(On-Premise) 및 사내 로컬 환경에서 도커(Docker) 컨테이너 기반으로 구축되는 **음원 스트리밍 및 원본 아카이빙 서비스 MVP**의 전체 시스템 아키텍처를 정의합니다.

웹페이지와 모바일 앱 클라이언트가 음원 파일(MP3, WAV 등)을 업로드하면 **원본 파일**과 **실시간 스트리밍용 청크(HLS `.m3u8` / `.ts`) 파일**을 분리 저장하며, 다중 백엔드 서버 클러스터링과 미디어 트래픽 우회 구조를 통해 안정적인 실시간 스트리밍 및 원본 다운로드 환경을 제공하는 것을 목표로 합니다.

> **\[중요 아키텍처 업데이트 — MinIO 도커 이미지 폐기에 따른 RustFS 채택\]**
> 기존 오픈소스 MinIO가 웹 콘솔 기능을 축소하고 공식 GitHub 저장소 아카이브 및 Docker Hub 이미지(`minio/minio`, `minio/mc`)를 전면 삭제함에 따라, 신규 컨테이너 생성 시 `pull access denied` 오류가 발생합니다. 이에 따라 기존 설계(포트 `9000`/`9001`, S3 API, 백엔드 SDK 코드, 디렉터리 구조)를 단 한 줄의 비즈니스 로직 수정 없이 1:1 Drop-in Replacement로 대체할 수 있는 Apache-2.0 오픈소스 S3 호환 스토리지인 `RustFS` (`rustfs/rustfs:latest`)를 표준 음원 스토리지 서버로 채택합니다.

## 2. 아키텍처 핵심 설계 원칙 (확정 정책)

1. **클라이언트-서버 및 6대 논리 섹션 분리:** 전체 시스템을 **웹페이지(Web)**, **모바일 앱(Mobile App)**, **리버스 프록시 서버(Nginx)**, **백엔드 서버 클러스터(Backend)**, **음원 서버(RustFS Storage)**, 데이터베이스(PostgreSQL)의 6개 핵심 섹션으로 분리하여 구성합니다.

2. **무상태(Stateless) 다중 백엔드 클러스터:** 백엔드 API 서버(`web-server-1`, `web-server-2`, 약칭 `web1`/`web2`)는 인메모리 세션(`HttpSession`)을 배제하고(OAuth2 로그인 인가 요청 state도 쿠키에 저장) 무상태(Stateless) 구조로 동작하여, Nginx 로드 밸런서가 어느 노드로 트래픽을 분산하더라도 동일한 정합성을 유지합니다.

3. **API 트래픽과 미디어(청크/원본) 바이트 전송의 격리:** 실시간 스트리밍 청크(`.ts`) 전송 및 대용량 원본 파일 다운로드 시 발생하는 대규모 I/O가 백엔드 애플리케이션(톰캣) 스레드를 점유하지 않도록, 실제 파일 바이트 전송은 **Nginx ➔ 음원 서버(RustFS)** 구간에서 전담 처리합니다.

4. **백엔드 내부 비동기(Async) HLS 변환 및 결합도 분리:** 음원 업로드 시 클라이언트가 인코딩 완료까지 대기하지 않도록 원본 저장 직후 응답을 반환하고, 백엔드 내부의 전용 비동기 스레드 풀(`@Async`)에서 FFmpeg 청크 분할을 수행하며 상태값(`PENDING` ➔ `PROCESSING` ➔ `READY` / `FAILED`)으로 생명주기를 관리합니다.

5. **MVP 전체 개방(`permitAll`) 및 추후 Spring Security 권한 통제 대비 진입점 단일화:** 초기 MVP 개발 속도를 높이기 위해 모든 API와 스트리밍·다운로드 접근 권한을 전체 개방하되, 추후 클라이언트 수정 없이 **Spring Security 설정만으로 즉각 권한 제어를 적용할 수 있도록 요청 진입점과 Nginx 내부 리다이렉트(`X-Accel-Redirect`) 구조**를 선제적으로 적용합니다.

## 3. 전체 시스템 구성 (6대 핵심 섹션 및 5개 도커 컨테이너)

| 섹션 구분 | 구성 요소 (컨테이너/모듈) | 핵심 역할 및 책임 | 비고 (MVP 기준) | 
| ----- | ----- | ----- | ----- | 
| **1. 웹페이지 (Web)** | Web Client (SPA) | 브라우저 기반 단건 음원 업로드 UI, HLS 웹 플레이어(`hls.js` 등) 연동 재생, 원본 다운로드 호출 및 목록 조회 | Nginx 정적 호스팅 또는 프론트 환경 | 
| **2. 모바일 앱 (App)** | Mobile Client (Android) | 모바일 환경 음원 업로드, 네이티브 HLS 플레이어(`Media3 ExoPlayer`) 백그라운드 재생 및 원본 파일 다운로드 | REST API 및 HLS 표준 규격 통신 | 
| **3. 리버스 프록시 서버** | `nginx` (Port: 80) | 단일 진입점, 백엔드(`web1`, `web2`) 로드 밸런싱, `/stream/*` 다이렉트 프록시 및 캐싱, `/internal-storage/*` 내부 리다이렉트 다운로드 처리 | 업로드 용량 제한(`client_max_body_size`) 및 CORS 제어 | 
| **4. 백엔드 서버** | `web-server-1`, `web-server-2` (Spring Boot + FFmpeg) | 음원 멀티파트 업로드 수신 및 원본 적재, 내부 비동기(`@Async`) FFmpeg HLS 청크 변환, 메타데이터 CRUD, 추후 Spring Security 기반 인가 제어 | 2개 컨테이너 클러스터 구성 (컨테이너 내 FFmpeg 포함) | 
| **5. 음원 서버** | `rustfs` (Port: 9000 / 콘솔: 9001) | Apache-2.0 오픈소스 S3 호환 오브젝트 스토리지. 업로드된 **원본 오디오(`original/`)**, **앨범 커버(`artwork/`)**, 분할된 **HLS 정적 파일(`hls/*.m3u8`, `*.ts`)** 영구 보관 | MinIO 1:1 대체 (Nginx 및 백엔드 내부망 S3 통신) | 
| **6. 데이터베이스 (DB)** | `postgres-db` (Port: 5432) | 회원 정보, 음원 메타데이터, 스토리지 객체 경로(`s3Key`), 비동기 변환 상태(`status`) 중앙 관리 | 영구 볼륨(Docker Volume) 마운트 필수 | 

## 4. 시스템 트래픽 라우팅 및 주요 기능별 데이터 흐름

### 4.1. 게이트웨이(`nginx`) URI 분기 규칙

* `/` 또는 `/static/*` ➔ **웹페이지 정적 파일** 서빙

* `/api/*` ➔ 백엔드 서버 클러스터(`web-server-1`, `web-server-2`)로 로드 밸런싱 전달

* `/stream/*` ➔ 백엔드 스레드를 거치지 않고 음원 서버(`rustfs`의 `hls/` 경로)로 다이렉트 프록시 전송 (실시간 스트리밍 전용)

* `/artwork/*` ➔ 백엔드 스레드를 거치지 않고 음원 서버(`rustfs`의 `artwork/` 경로)로 다이렉트 프록시 전송 (앨범 커버 이미지 서빙)

* `/internal-storage/*` ➔ `internal;` 지시어가 적용된 내부 전용 경로. 외부 직접 호출은 차단되며, 백엔드가 반환한 **`X-Accel-Redirect` 헤더**에 의해서만 **음원 서버(`rustfs`의 `original/` 경로)** 파일을 클라이언트로 스트리밍 다운로드

### 4.2. 주요 기능별 상세 데이터 흐름 (Data Flow)

| 시나리오 | 단계별 데이터 및 트래픽 흐름 | 핵심 아키텍처 포인트 | 
| ----- | ----- | ----- | 
| **A. 음원 업로드 및 비동기 청크 분할** | 1\. 클라이언트(Web/App)가 `POST /api/tracks/upload`로 음원 파일(`MultipartFile`) 및 메타데이터 전송  2\. **Nginx**가 용량 확인 후 가용 백엔드 서버(`web1` 또는 `web2`)로 전달  3\. **백엔드 서버**가 파일 기본 검증 후 RustFS(`original/`)에 원본을 저장하고, **DB**에 `PENDING` 상태로 기록한 뒤 내부 변환 이벤트를 발행하고 클라이언트에 즉시 응답  4\. 백엔드 내부 전용 비동기 스레드(`@Async`)가 업로드 트랜잭션 커밋 이후 `{ trackId, originalS3Key }` 정보를 받아 **RustFS**에서 원본을 읽고, **FFmpeg**로 `.m3u8` 및 `.ts` 청크를 생성하여 RustFS(`hls/`)에 재귀 업로드 후 **DB** 상태를 `READY`로 갱신 | 멀티파트 단일 API로 빠르게 구현하되, 대용량 WAV 변환 시간 동안 HTTP 응답이 지연되지 않도록 **업로드 수신과 FFmpeg 인코딩을 비동기 분리** | 
| **B. 실시간 청크 스트리밍 재생** | 1\. **클라이언트**가 재생 시작 전 백엔드의 `GET /api/tracks/{id}/play`를 호출하여 스트리밍 매니페스트 진입점 경로(`/stream/{track_uuid}/index.m3u8`) 획득  2\. 플레이어가 **Nginx(`/stream/{track_uuid}/index.m3u8`)** 요청 ➔ **RustFS** 다이렉트 프록시 응답  3\. 플레이어가 매니페스트에 정의된 6초 단위 청크(`/stream/{track_uuid}/chunk_000.ts` 등)를 순차 요청 ➔ **Nginx 프록시 캐시** 또는 **RustFS**에서 바이트 스트림 즉시 전송 | 백엔드 스레드는 최초 진입점 URL 조회만 처리하고, **반복적인 오디오 청크 전송은 Nginx-RustFS 구간에서 직접 처리** | 
| **C. 음원 원본 파일 다운로드** | 1\. **클라이언트**가 백엔드 다운로드 API(`GET /api/tracks/{id}/download`) 호출  2\. **백엔드 서버**가 DB에서 원본 저장 경로를 조회하고 짧은 유효기간(기본 60초)의 **RustFS presigned URL**을 생성한 뒤, 본문(Body) 없이 응답 헤더에 `X-Accel-Redirect: /internal-storage/original/...?X-Amz-...`(presigned 경로+서명 쿼리) 및 `Content-Disposition: attachment`를 담아 반환  3\. **Nginx**가 해당 헤더를 감지하여 내부적으로 **RustFS**에 서명된 요청을 보내 원본 파일을 읽어 클라이언트에게 직접 다운로드 전송 | 백엔드 메모리/스레드를 소모하지 않으면서도 **모든 다운로드 요청의 첫 진입점을 백엔드 API로 고정**. 원본(`original/`)은 익명 읽기가 금지되어 서명 없이는 RustFS에서도 조회 불가 | 

## 5. 확장성 및 보안 대비 상세 설계 명세 (확정된 4대 정책 반영)

### 5.1. \[정책 1 & 2\] 백엔드 내부 비동기 변환 및 추후 확장을 고려한 결합도 분리

MVP에서는 컨테이너 관리 복잡도를 낮추기 위해 별도 워커 서버를 두지 않고 `web-server-1`, `web-server-2` 내부에서 FFmpeg 변환을 수행하되, 서버 안정성과 추후 외부 메시지 큐/워커 분리 확장을 위해 아래 **3가지 설계 규칙**을 엄격히 준수합니다.

1. **전용 스레드 풀(Thread Pool) 동시성 제한:** FFmpeg 프로세스가 백엔드 CPU를 독점하여 일반 API가 마비되는 것을 막기 위해, 변환 전용 `ThreadPoolTaskExecutor`의 동시 실행 스레드 수(`corePoolSize` / `maxPoolSize`)를 **인스턴스당 1\~2개**로 제한하고 나머지는 큐에서 순차 대기시킵니다.

2. **메모리 파일 전달 금지 및 식별자 DTO(`{ trackId, originalS3Key }`) 전달:** 같은 서버 내부라도 메모리의 `MultipartFile`이나 로컬 임시 경로를 비동기 스레드로 직접 넘기지 않습니다. 반드시 업로드 서비스가 RustFS `original/`에 원본 저장을 완료한 뒤, **식별자 데이터 객체(`TranscodeEventDto: { trackId, originalS3Key }`)만 변환 모듈로 전달**합니다.

3. **호출부와 실행부의 인터페이스 분리:** 업로드 서비스는 변환 로직을 직접 호출하지 않고 이벤트 발행 인터페이스(`ApplicationEventPublisher` 또는 `TranscodeTaskDispatcher`)만 호출하며, 실행부는 `@Async @TransactionalEventListener(phase = AFTER_COMMIT)`로 수신합니다(커밋 전 조회 경합 방지). (추후 외부 브로커 및 별도 워커로 분리 시 핵심 비즈니스 코드 수정 없이 어댑터만 교체 가능)

### 5.2. \[정책 3\] 단일 표준 음질(Single Bitrate) 규격 및 다중 음질(ABR) 확장 대비 구조

백엔드 CPU 부하와 스토리지 용량을 최적화하기 위해 MVP는 **단일 표준 음질**로 인코딩하되, 추후 코드 수정 없이 다중 음질(Adaptive Bitrate)로 전환할 수 있는 파일 구조를 적용합니다.

| 항목 | MVP 확정 규격 | 선정 사유 및 설계 규칙 | 
| ----- | ----- | ----- | 
| **스트리밍 오디오 코덱** | **AAC (`aac`, 44.1kHz, Stereo)** | 웹 브라우저(`hls.js`, Safari) 및 모바일(iOS `AVPlayer`, Android `ExoPlayer`) 전 환경 100% 네이티브 호환 | 
| **스트리밍 비트레이트** | **192kbps 단일 음질** | 음질 열화를 최소화하면서도 6초 청크 1개당 약 140KB 수준으로 모바일 네트워크 버퍼링 방지 | 
| **청크(Segment) 분할 길이** | **6초 (`-hls_time 6`)** | Apple HLS 권장 표준. 초기 재생 시작 속도(TTFB)와 구간 탐색(Seeking) 반응성 최적화 | 
| **스토리지 업로드 방식** | **임시 출력 폴더 전체 재귀 업로드 (Recursive Upload)** | FFmpeg가 `/tmp/{track_uuid}/` 하위에 생성한 모든 파일과 하위 폴더를 파일명 하드코딩 없이 그대로 RustFS `hls/{track_uuid}/`에 동기화 (추후 다중 음질 하위 폴더 추가 시 자바 코드 수정 불필요) | 

* **저장소(`rustfs`) 디렉터리 구조:**

  * **원본 보관 경로:** `audio-storage/original/{yyyy}/{mm}/{track_uuid}.{ext}` (업로드된 `.mp3`, `.wav` 원본 그대로 보존)

  * **앨범 커버 경로:** `audio-storage/artwork/{track_uuid}.jpg`

  * **HLS 스트리밍 경로:** `audio-storage/hls/{track_uuid}/index.m3u8` 및 `chunk_{000..N}.ts` (추후 다중 음질 전환 시에도 기존 단일 음질 트랙과 재인코딩 없이 100% 하위 호환 재생)

### 5.3. \[정책 4\] MVP 전체 개방(`permitAll`) 및 추후 Spring Security 권한 전환 설계

초기 MVP에서는 인증·권한 여부에 관계없이 모든 기능을 열어두어 빠르게 개발하고, 추후 **Spring Security 설정 변경만으로 권한별 접근 통제를 즉시 활성화**할 수 있도록 설계합니다.

| 대상 기능 | MVP 초기 설정 (전체 개방) | 추후 권한 통제 전환 방법 (클라이언트 수정 없음) | 
| ----- | ----- | ----- | 
| **업로드 및 메타데이터 API**  (`/api/tracks/**`) | Spring Security `SecurityFilterChain`에서 모든 엔드포인트 `permitAll()` 허용 | Spring Security 설정에서 경로별 `.authenticated()` 또는 `.hasRole()` 조건만 활성화 | 
| **원본 파일 다운로드**  (`GET /api/tracks/{id}/download`) | `permitAll()`로 개방하되, 클라이언트는 반드시 백엔드 API를 호출하고 백엔드가 `X-Accel-Redirect` 헤더로 Nginx-RustFS 다운로드를 트리거 | 진입점이 백엔드 API이므로 **Spring Security에서 `/api/tracks/*/download` 경로만 권한 제한**하면 즉시 통제 완료 | 
| **실시간 HLS 스트리밍**  (`GET /api/tracks/{id}/play` 및 `/stream/*`) | 백엔드 재생 진입점 조회 API(`/play`)와 Nginx `/stream/*` 프록시 경로 모두 전체 개방 | **1단계:** 백엔드 재생 정보 조회 API(`/api/tracks/*/play`)를 Spring Security로 통제  **2단계(강화 시):** Nginx `/stream/*` 블록에 `auth_request /api/auth/check;` 1줄을 추가하여 Spring Security 토큰 검증 API와 연동 (`/api/auth/check`는 MVP 범위 밖이며 2단계 전환 시 PRD-05에 추가) |

* **스토리지 접근 정책 (v2.4 보완):** RustFS(`9000`/`9001`)와 PostgreSQL(`5432`) 호스트 포트는 `127.0.0.1` 루프백에만 바인딩하여 외부 네트워크 노출을 차단합니다. 버킷 익명 읽기(`s3:GetObject`)는 Nginx 다이렉트 프록시 대상인 `hls/*`, `artwork/*`에만 허용하고, `original/*`은 백엔드가 발급한 presigned URL로만 접근합니다(PRD-06 §3, PRD-05 §7.1.2).
* **플랫폼 분리 대비:** 각 섹션 간 호스트·URL(DB, 스토리지 엔드포인트, Nginx upstream, 미디어 공개 URL, CORS 오리진)은 모두 환경변수로 주입하여, 추후 웹·DB·스토리지가 별도 플랫폼으로 분리되어도 코드 수정 없이 설정 변경만으로 대응합니다(PRD.md §6). 

## 6. 섹션별 개발 순서 및 후속 상세 PRD 작성 로드맵

본 전체 아키텍처 확정안을 바탕으로, **백엔드 서버 ➔ 데이터베이스 ➔ 음원 서버 ➔ 리버스 프록시 ➔ 웹페이지** 순으로 개발을 진행하여 **1차 통합 테스트**를 완료한 뒤, 마지막으로 **모바일 앱**을 개발합니다.

| 개발 단계 | 문서 번호 | 후속 PRD 문서명 | 핵심 정의 및 구현 항목 | 
| ----- | ----- | ----- | ----- | 
| **Step 1 (1순위)** | **PRD-05** | **백엔드 서버 (Backend Cluster) 상세 설계** | Spring Boot REST API 명세, 이벤트 분리형 `@Async` FFmpeg 변환기, S3(RustFS) 버킷 자동 초기화, Spring Security 구조 | 
| **Step 2 (2순위)** | **PRD-07** | **데이터베이스 (PostgreSQL) 상세 설계** | 음원 메타데이터(`tracks`), 변환 상태(`PENDING` ➔ `READY`), 회원(`users`) 테이블 스키마 및 인덱스 설계 | 
| **Step 3 (3순위)** | **PRD-06** | **음원 서버 (RustFS & FFmpeg) 상세 설계** | RustFS 도커 구축 및 버킷 정책 자동 설정, FFmpeg 단일 음질(AAC 192k, 6초 청크) 변환 명령어, 폴더 재귀 업로드 및 임시 파일 정리 | 
| **Step 4 (4순위)** | **PRD-04** | **리버스 프록시 서버 (Nginx) 상세 설계** | `web1`/`web2` 업스트림 로드 밸런싱, `/stream/*` 프록시 캐시 설정, `/internal-storage/*` `X-Accel-Redirect` 설정 | 
| **Step 5 (5순위)** | **PRD-02** | **웹페이지 (Web Frontend) 상세 설계** | 단건 업로드 UI 및 변환 상태 표시, `hls.js` 기반 스트리밍 플레이어, 원본 다운로드 연동 | 
| **Milestone 1** | **통합 검증** | **1차 통합 테스트 (Web E2E 검증)** | 웹 환경에서 업로드 ➔ 비동기 HLS 변환 ➔ 실시간 스트리밍 재생 ➔ 원본 파일 다운로드 전 구간 통합 테스트 | 
| **Step 6 (6순위)** | **PRD-03** | **모바일 앱 (Mobile App) 상세 설계** | 검증된 백엔드/미디어 파이프라인 기반 모바일 파일 업로드, 네이티브 HLS 플레이어 백그라운드 재생 및 다운로드 | 

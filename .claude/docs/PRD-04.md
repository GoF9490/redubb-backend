# [PRD-04] 리버스 프록시 서버 (Nginx) 상세 설계 (v1.2 - 문서 간 정합성 보완안)

> **v1.2 변경 요약 (2026-11-11):** 설정 파일을 환경변수 템플릿(`nginx.conf.template`)으로 전환, `/internal-storage/` presigned 쿼리 전달 및 Content-Disposition 처리 방식 수정, `X-Forwarded-Host` 헤더 추가, 통합 검증 항목과 플랫폼 분리 시 수정 포인트(§7) 추가. 상세는 `PRD.md` §7 참조.

## 1. 개요 및 역할 정의
본 문서는 음원 스트리밍 서비스 MVP의 최전방 관문(Single Entry Point) 역할을 수행하는 **리버스 프록시 및 로드 밸런서 서버(`nginx`, Port: `80`)**의 상세 설정과 구축 절차를 정의합니다.

전체 아키텍처(`PRD-01`), 백엔드(`PRD-05`), 음원 서버(`PRD-06`)에서 확정된 설계 원칙에 따라 **① 다중 백엔드(`web1`, `web2`) 로드 밸런싱**, **② 실시간 HLS 스트리밍(`/stream/*`) 및 앨범 커버(`/artwork/*`) 다이렉트 프록시·캐싱**, **③ `X-Accel-Redirect` 기반의 안전한 원본 파일 다운로드(`/internal-storage/*`)**, 그리고 **④ 웹페이지(SPA) 정적 파일 서빙**을 단일 설정 파일(`nginx.conf`)로 통합 구현합니다.

또한 **Nginx를 처음 다루는 초보 작업자도 복사·붙여넣기만으로 컨테이너 구동과 라우팅 검증까지 마칠 수 있도록 단계별(Step-by-Step) 가이드**를 제공합니다.

## 2. Nginx URI 라우팅 테이블 및 핵심 동작 규칙

클라이언트(웹 브라우저 및 모바일 앱)는 오직 **Nginx(`80`번 포트)**로만 요청을 보내며, Nginx는 요청 주소(URI Prefix)를 보고 아래 5가지 경로로 트래픽을 자동 분배합니다.

| 요청 URI 경로 | 전달 대상 (Upstream) | 백엔드(Spring) 경유 여부 | 핵심 역할 및 보안·성능 포인트 |
| :--- | :--- | :---: | :--- |
| **`/api/*`**<br>**`/oauth2/*`**<br>**`/login/oauth2/*`** | **`web-server-1:8080`**<br>**`web-server-2:8080`**<br>(로드 밸런싱) | **O (경유)** | 음원 업로드, 목록 조회, Google OAuth2 로그인 처리. 두 백엔드 서버로 트래픽을 균등 분산하며 대용량 파일 업로드(`100MB`) 허용 |
| **`/stream/*`** | **`rustfs:9000`**<br>(`/audio-storage/hls/*`) | **X (완전 우회)** | `.m3u8` 매니페스트 및 6초 단위 `.ts` 오디오 청크 실시간 스트리밍. **Nginx 로컬 디스크 캐시**를 적용해 반복 재생 시 RustFS 부하 최소화 및 CORS 헤더 부여 |
| **`/artwork/*`** | **`rustfs:9000`**<br>(`/audio-storage/artwork/*`) | **X (완전 우회)** | 추출·등록된 앨범 커버 이미지(`.jpg`) 초고속 정적 서빙 및 브라우저 캐싱 적용 |
| **`/internal-storage/*`** | **`rustfs:9000`**<br>(`/audio-storage/*`) | **내부 전용 (`internal;`)** | **외부에서 URL을 직접 입력해 접근하면 무조건 차단(`404`).** 오직 백엔드의 다운로드 API(`GET /api/tracks/{id}/download`)가 반환한 `X-Accel-Redirect` 헤더(presigned 경로+서명 쿼리)에 의해서만 원본 파일 다운로드 수행. RustFS `original/`은 익명 읽기가 금지되어 서명 없이는 조회 불가(PRD-06 §3) |
| **`/` (그 외 경로)** | **Nginx 내부 정적 폴더**<br>(`/usr/share/nginx/html`) | **X (완전 우회)** | 웹페이지 프론트엔드(`PRD-02`)의 HTML/JS/CSS 파일 서빙 (`try_files`로 새로고침 시 `404` 방지) |

## 3. 초보자가 가장 자주 겪는 3가지 Nginx 오류와 사전 방지 설계

본 PRD의 `nginx.conf`에는 초보자가 구축할 때 흔히 겪는 3가지 대표적인 에러를 예방하는 설정이 미리 반영되어 있습니다.

1. **음원 업로드 시 `413 Request Entity Too Large` 에러 방지:**
   * **원인:** Nginx의 기본 파일 업로드 허용 크기는 단 **`1MB`**입니다. 이 설정을 안 바꾸면 5MB짜리 MP3나 50MB짜리 WAV를 올릴 때 백엔드까지 가지도 못하고 Nginx에서 즉시 차단됩니다.
   * **해결:** `client_max_body_size 110M;` 설정을 추가하여 백엔드(`PRD-05`)의 업로드 제한과 동일하게 맞췄습니다.
2. **Google OAuth2 로그인 시 리다이렉트 주소 불일치 에러 방지:**
   * **원인:** Nginx가 요청을 백엔드로 넘길 때 원래 접속 주소(`Host`, `X-Forwarded-Proto`) 헤더를 안 넘겨주면, 백엔드가 자기 주소를 내부 도커 이름(`http://backend_cluster:8080`)으로 착각해 구글 로그인이 실패합니다.
   * **해결:** `X-Forwarded-Host`, `X-Forwarded-Proto`, `X-Real-IP` 헤더 전달 설정을 기본 포함했습니다.
3. **웹 플레이어(`hls.js`) 재생 시 브라우저 CORS 차단 에러 방지:**
   * **원인:** 웹이나 모바일에서 `.m3u8`과 `.ts` 조각을 가져갈 때 CORS 허용 헤더가 없으면 브라우저 보안 정책이 재생을 막습니다.
   * **해결:** `/stream/`과 `/artwork/` 블록에 `Access-Control-Allow-Origin *` 및 `Range` 관련 헤더를 기본 주입했습니다.

## 4. 표준 `nginx.conf.template` 전체 설정 명세 (복사·붙여넣기용)

프로젝트 내 `./nginx/templates/nginx.conf.template` 경로에 저장하여 사용하는 완성형 설정 템플릿입니다.

> **환경변수 템플릿 방식 (v1.2 보완 — 플랫폼 분리 대비):** 공식 `nginx` 도커 이미지는 기동 시 `/etc/nginx/templates/*.template` 파일의 `${환경변수}`를 `envsubst`로 치환하여 `NGINX_ENVSUBST_OUTPUT_DIR`(본 구성은 `/etc/nginx`)에 출력합니다. 정의된 환경변수 이름만 치환되므로 `$remote_addr` 같은 Nginx 내장 변수는 그대로 유지됩니다. 백엔드·스토리지 호스트가 다른 서버/플랫폼으로 이동해도 `docker-compose.yml`의 환경변수만 바꾸면 됩니다.
>
> | 템플릿 변수 | 기본값 (단일 호스트 compose) | 설명 |
> | :--- | :--- | :--- |
> | `BACKEND_UPSTREAM_1` / `BACKEND_UPSTREAM_2` | `web-server-1:8080` / `web-server-2:8080` | 백엔드 노드 주소 |
> | `STORAGE_UPSTREAM` | `rustfs:9000` | RustFS S3 API 주소 |
> | `STORAGE_HOST_HEADER` | `rustfs:9000` | RustFS로 보내는 `Host` 헤더. **백엔드 `STORAGE_ENDPOINT`의 host:port와 반드시 동일** (presigned 서명 검증 기준, PRD-05 §7.1.2) |
> | `STORAGE_BUCKET_NAME` | `audio-storage` | 버킷명 (PRD-05/06과 동일 값) |

```nginx
user  nginx;
worker_processes  auto;

error_log  /var/log/nginx/error.log notice;
pid        /var/run/nginx.pid;

events {
    worker_connections  1024;
}

http {
    include       /etc/nginx/mime.types;
    default_type  application/octet-stream;

    # HLS 스트리밍 표준 MIME 타입 명시 보장
    types {
        application/vnd.apple.mpegurl m3u8;
        video/mp2t                    ts;
    }

    log_format  main  '$remote_addr - $remote_user [$time_local] "$request" '
                      '$status $body_bytes_sent "$http_referer" '
                      '"$http_user_agent" upstream: $upstream_addr cache: $upstream_cache_status';

    access_log  /var/log/nginx/access.log  main;

    sendfile        on;
    tcp_nopush      on;
    tcp_nodelay     on;
    keepalive_timeout  65;

    # [핵심 1] 대용량 WAV/MP3 + 앨범자켓 이미지 업로드 허용 (기본 1MB -> 110MB로 확대)
    client_max_body_size 110M;

    # [핵심 2] HLS 오디오 청크(.ts) 반복 재생 속도 향상을 위한 Nginx 로컬 디스크 캐시 공간 정의
    proxy_cache_path /var/cache/nginx/hls_cache levels=1:2 keys_zone=hls_cache:10m max_size=2g inactive=24h use_temp_path=off;

    # [핵심 3] 다중 백엔드 서버 클러스터 (web-server-1, web-server-2) 로드 밸런싱 풀 정의
    # 호스트:포트는 환경변수로 주입 (플랫폼 분리 시 환경변수만 변경)
    upstream backend_cluster {
        server ${BACKEND_UPSTREAM_1} max_fails=3 fail_timeout=10s;
        server ${BACKEND_UPSTREAM_2} max_fails=3 fail_timeout=10s;
        keepalive 32;
    }

    # RustFS S3 스토리지 내부 업스트림 정의
    upstream s3_storage {
        server ${STORAGE_UPSTREAM};
        keepalive 32;
    }

    server {
        listen       80;
        server_name  localhost;

        # ------------------------------------------------------------
        # 1. 백엔드 REST API 및 Google OAuth2 인증 라우팅 (로드 밸런싱)
        # ------------------------------------------------------------
        location ~ ^/(api|oauth2|login/oauth2)/ {
            proxy_pass http://backend_cluster;
            proxy_http_version 1.1;
            proxy_set_header Connection "";

            # 클라이언트의 실제 IP 및 접속 도메인 정보를 백엔드(Spring Boot)로 전달
            proxy_set_header Host $http_host;
            proxy_set_header X-Forwarded-Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;

            # 대용량 음원 파일 멀티파트 업로드 시 타임아웃 방지 (2분)
            proxy_connect_timeout 60s;
            proxy_send_timeout    120s;
            proxy_read_timeout    120s;
        }

        # ------------------------------------------------------------
        # 2. 실시간 HLS 스트리밍 다이렉트 바이패스 (/stream/* -> RustFS hls/*)
        # ------------------------------------------------------------
        location /stream/ {
            # [정책 4 확장 포인트] 추후 스트리밍에도 엄격한 권한 검증이 필요할 경우 아래 1줄만 주석 해제
            # auth_request /api/auth/check;

            # /stream/{uuid}/index.m3u8 요청을 RustFS의 /audio-storage/hls/{uuid}/index.m3u8로 직행 연결
            proxy_pass http://s3_storage/${STORAGE_BUCKET_NAME}/hls/;
            proxy_http_version 1.1;
            proxy_set_header Connection "";
            proxy_set_header Host ${STORAGE_HOST_HEADER};

            # S3 내부 익명 읽기(s3:GetObject) 충돌 방지를 위해 불필요한 클라이언트 인증 헤더 제거
            proxy_set_header Authorization "";
            proxy_hide_header x-amz-id-2;
            proxy_hide_header x-amz-request-id;

            # Nginx 로컬 프록시 캐시 적용 (.ts 조각은 캐싱하고 .m3u8은 최신 상태 유지)
            proxy_cache hls_cache;
            proxy_cache_valid 200 206 24h;
            proxy_cache_key "$uri";
            add_header X-Cache-Status $upstream_cache_status always;

            # 웹/모바일 플레이어 전 환경 호환을 위한 CORS 완전 개방 헤더
            add_header 'Access-Control-Allow-Origin' '*' always;
            add_header 'Access-Control-Allow-Methods' 'GET, HEAD, OPTIONS' always;
            add_header 'Access-Control-Allow-Headers' 'Range, Origin, Accept, Content-Type' always;
            add_header 'Access-Control-Expose-Headers' 'Content-Length, Content-Range' always;

            if ($request_method = 'OPTIONS') {
                return 204;
            }
        }

        # ------------------------------------------------------------
        # 3. 앨범 커버 이미지 다이렉트 서빙 (/artwork/* -> RustFS artwork/*)
        # ------------------------------------------------------------
        location /artwork/ {
            proxy_pass http://s3_storage/${STORAGE_BUCKET_NAME}/artwork/;
            proxy_http_version 1.1;
            proxy_set_header Connection "";
            proxy_set_header Host ${STORAGE_HOST_HEADER};
            proxy_set_header Authorization "";

            proxy_cache hls_cache;
            proxy_cache_valid 200 7d;
            add_header Cache-Control "public, max-age=604800, immutable";
            add_header 'Access-Control-Allow-Origin' '*' always;
        }

        # ------------------------------------------------------------
        # 4. 원본 음원 안전 다운로드 전용 내부 경로 (X-Accel-Redirect 연동)
        # ------------------------------------------------------------
        location /internal-storage/ {
            # [핵심 보안] 외부 사용자가 브라우저에서 /internal-storage/...를 직접 호출하면 404 차단!
            # 오직 백엔드(GET /api/tracks/{id}/download)가 응답 헤더로 지시할 때만 내부적으로 동작함
            internal;

            # 백엔드가 X-Accel-Redirect로 넘긴 presigned 경로+서명 쿼리를 그대로 RustFS로 전달
            # 예) /internal-storage/original/2026/11/{uuid}.wav?X-Amz-Algorithm=...&X-Amz-Signature=...
            #   ➔ http://rustfs:9000/audio-storage/original/2026/11/{uuid}.wav?X-Amz-...
            # (prefix location + URI 포함 proxy_pass는 쿼리 문자열을 재인코딩 없이 그대로 붙임)
            proxy_pass http://s3_storage/${STORAGE_BUCKET_NAME}/;
            proxy_http_version 1.1;
            proxy_set_header Connection "";
            # [필수] presigned 서명은 Host 헤더를 포함하므로 백엔드 STORAGE_ENDPOINT의 host:port와 반드시 일치
            proxy_set_header Host ${STORAGE_HOST_HEADER};
            proxy_set_header Authorization "";

            # X-Accel-Redirect 처리 시 Nginx는 백엔드 원 응답의 Content-Type·Content-Disposition 등을 유지하므로
            # 별도 변수 복사 없이 백엔드가 지정한 파일명(filename*=UTF-8'')이 그대로 전달됨.
            # RustFS 응답 쪽의 동일 헤더나 내부 식별 헤더는 숨김 처리
            proxy_hide_header Content-Disposition;
            proxy_hide_header x-amz-id-2;
            proxy_hide_header x-amz-request-id;

            # 대용량 원본 WAV 다운로드 시 Nginx 디스크 버퍼링 비활성화 (즉시 스트리밍 전송)
            proxy_buffering off;
        }

        # ------------------------------------------------------------
        # 5. 웹페이지 프론트엔드 (SPA) 정적 파일 서빙
        # ------------------------------------------------------------
        location / {
            root   /usr/share/nginx/html;
            index  index.html index.htm;
            # SPA 라우팅 지원: 새로고침 시 404가 나지 않고 index.html로 연결
            try_files $uri $uri/ /index.html;
        }
    }
}
```

## 5. [초보자용 Step-by-Step] Nginx 컨테이너 설치 및 환경 구축 절차

### Step 1. 작업 폴더 및 초기 파일 생성
터미널에서 아래 명령어를 순서대로 실행하여 Nginx 설정 폴더와 임시 웹페이지 화면을 만듭니다.

```bash
# 1. 인프라 폴더 안에 nginx 템플릿 폴더와 웹 정적 파일 폴더 생성
mkdir -p ./nginx/templates ./frontend-dist

# 2. 위 4절의 표준 설정 내용을 ./nginx/templates/nginx.conf.template 파일로 저장
# (VS Code 탐색기에서 해당 파일을 만들고 4절 내용을 붙여넣습니다)

# 3. 웹페이지(PRD-02) 개발 전 Nginx 정상 작동을 눈으로 확인하기 위한 임시 index.html 생성
cat << 'EOF' > ./frontend-dist/index.html
<!DOCTYPE html>
<html lang="ko">
<head><meta charset="UTF-8"><title>Music Streaming MVP</title></head>
<body><h1>Music Streaming Nginx Reverse Proxy is Running!</h1></body>
</html>
EOF
```

### Step 2. `docker-compose.yml`에 `nginx` 컨테이너 추가
기존 `postgres-db`, `rustfs`가 정의된 `docker-compose.yml`에 `nginx` 서비스를 추가합니다.

```yaml
services:
  nginx:
    image: nginx:1.26-alpine
    container_name: nginx-proxy
    restart: unless-stopped
    ports:
      - "80:80" # 외부 클라이언트(웹/모바일)가 접속하는 유일한 진입 포트
    environment:
      NGINX_ENVSUBST_OUTPUT_DIR: /etc/nginx # 템플릿 치환 결과를 /etc/nginx/nginx.conf로 출력
      BACKEND_UPSTREAM_1: ${BACKEND_UPSTREAM_1:-web-server-1:8080}
      BACKEND_UPSTREAM_2: ${BACKEND_UPSTREAM_2:-web-server-2:8080}
      STORAGE_UPSTREAM: ${STORAGE_UPSTREAM:-rustfs:9000}
      STORAGE_HOST_HEADER: ${STORAGE_HOST_HEADER:-rustfs:9000}
      STORAGE_BUCKET_NAME: ${STORAGE_BUCKET_NAME:-audio-storage}
    volumes:
      - ./nginx/templates:/etc/nginx/templates:ro # 설정 템플릿 읽기 전용 마운트 (기동 시 envsubst 치환)
      - ./frontend-dist:/usr/share/nginx/html:ro    # 웹페이지 정적 파일 마운트
      - nginx-hls-cache:/var/cache/nginx/hls_cache  # HLS 청크 캐시 저장 볼륨
    depends_on:
      - rustfs
      - web-server-1
      - web-server-2
    healthcheck:
      test: ["CMD-SHELL", "wget -q --spider http://localhost/ || exit 1"]
      interval: 10s
      timeout: 3s
      retries: 3

volumes:
  nginx-hls-cache:
```

### Step 3. 설정 문법 검사(`nginx -t`) 및 컨테이너 실행
Nginx는 설정 파일에 오타(세미콜론 `;` 누락 등)가 있으면 켜지지 않습니다. 아래 명령어로 문법을 검사하고 실행합니다.

```bash
# 1. Nginx 컨테이너 백그라운드 실행
docker compose up -d nginx

# 2. [필수] Nginx 설정 파일 문법 검사 ('syntax is ok' 및 'test is successful' 확인)
docker exec nginx-proxy nginx -t

# 3. 환경변수 치환 결과 확인 (upstream 주소와 Host 헤더가 기대값으로 바뀌었는지)
docker exec nginx-proxy grep -nE "server |Host |proxy_pass" /etc/nginx/nginx.conf

# 4. 템플릿(nginx.conf.template)을 수정한 경우: 템플릿 치환은 컨테이너 기동 시점에만 수행되므로 재시작
docker compose restart nginx
```

## 6. [초보자 자가 진단] 라우팅 및 보안 작동 4단계 검증 가이드

구축이 끝난 뒤 터미널에서 `curl` 명령어로 4가지 핵심 경로가 설계대로 동작하는지 즉시 검증할 수 있습니다.

1. **웹페이지 정적 서빙 확인 (`200 OK`):**
   ```bash
   curl -I http://localhost/
   # 결과에 HTTP/1.1 200 OK가 출력되면 정상
   ```
2. **원본 보관소 외부 직접 접근 차단 보안 확인 (`404 Not Found`가 나와야 정상!):**
   ```bash
   curl -I http://localhost/internal-storage/original/test.wav
   # internal; 설정 덕분에 외부에서 직접 부르면 HTTP/1.1 404 Not Found로 완벽 차단됨
   ```
3. **HLS 스트리밍 바이패스 및 CORS 헤더 확인:**
   ```bash
   curl -I http://localhost/stream/test.m3u8
   # 파일이 아직 없더라도 Access-Control-Allow-Origin: * 헤더와 X-Cache-Status 헤더가 보이면 RustFS 프록시 연결 성공
   ```
4. **백엔드(`web-server-1`, `web-server-2`) 로드 밸런싱 분산 확인:**
   ```bash
   # API를 여러 번 호출한 뒤 Nginx 로그에서 upstream 주소가 web-server-1과 web-server-2로 번갈아 찍히는지 확인
   docker logs --tail 20 nginx-proxy
   ```
5. **[통합 테스트 필수] presigned 원본 다운로드 및 파일명 헤더 확인:**
   ```bash
   # 업로드된 트랙 ID로 다운로드 API 호출 → 200 응답, 원본 바이트 수신, 한글 파일명 헤더 유지 확인
   curl -sS -D - -o /tmp/download.bin http://localhost/api/tracks/{trackId}/download | grep -iE "HTTP/|content-disposition|content-type"
   # 기대값: HTTP/1.1 200, Content-Disposition: attachment; filename="..."; filename*=UTF-8''...
   # 403(SignatureDoesNotMatch)이면 STORAGE_HOST_HEADER와 백엔드 STORAGE_ENDPOINT 호스트 불일치 또는 쿼리 재인코딩 문제
   # Content-Disposition이 누락되면 proxy_hide_header Content-Disposition 줄을 제거하고 재검증
   ```
6. **원본 익명 접근 차단 확인 (RustFS 직접 호출 시 `403`이 나와야 정상):**
   ```bash
   docker exec nginx-proxy wget -S -O /dev/null http://rustfs:9000/audio-storage/original/2026/11/{uuid}.wav 2>&1 | grep "HTTP/"
   ```
7. **Google OAuth2 멀티노드 로그인 확인:** 브라우저에서 로그인을 5회 이상 반복하여, 인가 요청과 콜백이 서로 다른 노드로 분산되어도 `authorization_request_not_found` 오류 없이 `/auth/callback?token=...`으로 복귀하는지 확인합니다(PRD-05 §6.1 쿠키 기반 인가 요청 저장소).

## 7. 플랫폼 분리 시 수정 포인트

웹·백엔드·스토리지·DB가 단일 도커 호스트를 벗어나 별도 서버/플랫폼으로 분리될 때 Nginx에서 필요한 조치입니다.

| 항목 | 단일 호스트(현재) | 분리 시 조치 |
| :--- | :--- | :--- |
| upstream 주소 | 도커 서비스명 (`web-server-1:8080`, `rustfs:9000`) | `BACKEND_UPSTREAM_*`, `STORAGE_UPSTREAM` 환경변수를 사설 IP/도메인으로 변경 (설정 파일 수정 불필요) |
| 스토리지 Host 헤더 | `rustfs:9000` | `STORAGE_HOST_HEADER`를 백엔드 `STORAGE_ENDPOINT` 호스트와 동일하게 변경 |
| 구간 암호화 | 도커 내부망 평문 HTTP | 퍼블릭망을 경유하면 upstream을 `https://`로 전환하거나 VPN/VPC 사설망 사용 |
| 접근 통제 | 도커 네트워크 격리 + 루프백 바인딩 | 방화벽/보안그룹으로 RustFS·백엔드 포트를 Nginx(및 백엔드) IP에서만 허용. `hls/`·`artwork/` 익명 읽기 경로도 동일하게 제한 |
| 웹 정적 파일 | Nginx `/usr/share/nginx/html` | CDN 등 별도 오리진으로 이전 시 백엔드 `CORS_ALLOWED_ORIGINS`와 `MEDIA_PUBLIC_BASE_URL` 설정 (PRD-05 §4.3) |

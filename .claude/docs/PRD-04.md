# [PRD-04] 리버스 프록시 서버 (Nginx) 상세 설계 (v1.1 - RustFS 연동 확정안)

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
| **`/internal-storage/*`** | **`rustfs:9000`**<br>(`/audio-storage/*`) | **내부 전용 (`internal;`)** | **외부에서 URL을 직접 입력해 접근하면 무조건 차단(`404`).** 오직 백엔드의 다운로드 API(`GET /api/tracks/{id}/download`)가 반환한 `X-Accel-Redirect` 헤더에 의해서만 원본 파일 다운로드 수행 |
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

## 4. 표준 `nginx.conf` 전체 설정 명세 (복사·붙여넣기용)

프로젝트 내 `./nginx/nginx.conf` 경로에 그대로 저장하여 사용할 수 있는 완성형 설정 파일입니다.

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

    # [핵심 3] 다중 백엔드 서버 클러스터 (web1, web2) 로드 밸런싱 풀 정의
    upstream backend_cluster {
        server web-server-1:8080 max_fails=3 fail_timeout=10s;
        server web-server-2:8080 max_fails=3 fail_timeout=10s;
        keepalive 32;
    }

    # RustFS S3 스토리지 내부 업스트림 정의
    upstream s3_storage {
        server rustfs:9000;
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
            proxy_pass http://s3_storage/audio-storage/hls/;
            proxy_http_version 1.1;
            proxy_set_header Connection "";
            proxy_set_header Host rustfs:9000;

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
            proxy_pass http://s3_storage/audio-storage/artwork/;
            proxy_http_version 1.1;
            proxy_set_header Connection "";
            proxy_set_header Host rustfs:9000;
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

            # 백엔드가 보낸 파일명 다운로드 헤더(Content-Disposition)를 변수에 보존
            set $download_disposition $upstream_http_content_disposition;

            proxy_pass http://s3_storage/audio-storage/;
            proxy_http_version 1.1;
            proxy_set_header Connection "";
            proxy_set_header Host rustfs:9000;
            proxy_set_header Authorization "";

            # 대용량 원본 WAV 다운로드 시 Nginx 디스크 버퍼링 비활성화 (즉시 스트리밍 전송)
            proxy_buffering off;
            add_header Content-Disposition $download_disposition always;
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
# 1. 인프라 폴더 안에 nginx 설정 폴더와 웹 정적 파일 폴더 생성
mkdir -p ./nginx ./frontend-dist

# 2. 위 4절의 표준 설정 내용을 ./nginx/nginx.conf 파일로 저장
# (VS Code 탐색기에서 ./nginx/nginx.conf 파일을 만들고 4절 내용을 붙여넣습니다)

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
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro # 설정 파일 읽기 전용 마운트
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

# 3. 추후 nginx.conf 파일을 수정했을 때 컨테이너를 끄지 않고 1초 만에 설정 반영하기
docker exec nginx-proxy nginx -s reload
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
4. **백엔드(`web1`, `web2`) 로드 밸런싱 분산 확인:**
   ```bash
   # API를 여러 번 호출한 뒤 Nginx 로그에서 upstream 주소가 web-server-1과 web-server-2로 번갈아 찍히는지 확인
   docker logs --tail 20 nginx-proxy
   ```

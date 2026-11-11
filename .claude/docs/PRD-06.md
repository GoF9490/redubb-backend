# [PRD-06] 음원 서버 (RustFS 스토리지 & FFmpeg 변환 엔진) 상세 설계 (v1.1)

## 1. 개요 및 스토리지 스택 교체 배경
본 문서는 음원 스트리밍 서비스 MVP에서 **원본 오디오 파일**, **실시간 스트리밍용 HLS 청크 파일(`.m3u8`, `.ts`)**, 그리고 **추출된 앨범 커버 이미지(`.jpg`)**를 영구 보관하고 서빙하는 **음원 서버(`rustfs` 오브젝트 스토리지 및 `FFmpeg` 변환 파이프라인)**의 상세 구축 명세를 정의합니다.

### 1.1. MinIO 도커 이미지 삭제 이슈 및 `RustFS` 채택 사유
1. **MinIO 공식 도커 이미지 삭제 및 아카이브 이슈:**
   * MinIO는 2025년 5월 오픈소스 버전의 웹 관리 콘솔 기능을 제거한 데 이어, 2025년 10월 공식 무료 도커 이미지 배포를 중단(Source-only 전환)했고, 2026년 공식 GitHub 저장소 아카이브 및 Docker Hub의 `minio/minio`, `minio/mc` 이미지를 삭제했습니다.
   * 이로 인해 기존 `minio/minio:RELEASE.2024-10-02T17-50-41Z` 태그로는 신규 도커 컨테이너 생성(`docker pull`)이 불가능합니다.
2. **기존 PRD 수정 사항을 최소화하는 최적의 오픈소스 대안 — `RustFS` (`rustfs/rustfs:latest`):**
   * **포트 및 웹 콘솔 100% 동일:** MinIO와 완벽히 동일하게 **`9000`번 포트(S3 API)**와 **`9001`번 포트(웹 브라우저 GUI 관리 콘솔)**를 기본 제공합니다.
   * **백엔드 코드 수정 0% (1:1 Drop-in Replacement):** AWS S3 프로토콜 및 표준 Bucket Policy(`PutBucketPolicy`)를 그대로 지원하므로, 백엔드(`PRD-05`)에 작성된 `io.minio:minio` SDK 코드나 `AWS S3` 클라이언트를 수정 없이 그대로 사용할 수 있습니다.
   * **라이선스 및 성능 우위:** SeaweedFS(포트 `8333`, 별도 JSON 설정 필요)나 Garage(포트 `3900`, 웹 콘솔 미내장 및 수동 클러스터 레이아웃 할당 필요)와 달리, **RustFS**는 **Apache-2.0 오픈소스 라이선스** 기반으로 단일 컨테이너 실행 즉시 S3 API와 웹 콘솔이 활성화되어 초보자가 구축하기에 가장 간편합니다.

## 2. 음원 스토리지(`audio-storage`) 디렉터리 레이아웃 표준

`PRD-01` 및 `PRD-05`의 보안·캐싱 분리 원칙에 따라 **`audio-storage` 단일 버킷 내부를 `original/`, `artwork/`, `hls/` 3개의 최상위 폴더(Prefix)로 엄격히 분리**하여 저장합니다.

```text
[RustFS S3 Bucket: audio-storage]
├── original/                                         # [1] 원본 다운로드 전용 보관소 (외부 직접 노출 차단)
│   └── 2026/
│       └── 11/
│           └── 550e8400-e29b-41d4-a716-446655440000.wav
│
├── artwork/                                          # [2] 추출된 내장 앨범 커버 이미지 보관소
│   └── 550e8400-e29b-41d4-a716-446655440000.jpg
│
└── hls/                                              # [3] 실시간 스트리밍용 HLS 청크 보관소
    └── 550e8400-e29b-41d4-a716-446655440000/         # 트랙 고유 UUID 폴더 (재귀 업로드 단위)
        ├── index.m3u8                                # HLS 재생 목록 매니페스트 진입점
        ├── chunk_000.ts                              # 0~6초 구간 오디오 조각 (AAC 192kbps)
        ├── chunk_001.ts                              # 6~12초 구간 오디오 조각
        └── chunk_002.ts                              # 12~18초 구간 오디오 조각 ...
```

### 2.1. 폴더별 용도 및 파일 업로드 시 필수 `Content-Type` 규칙
백엔드(`PRD-05`)에서 S3/MinIO SDK(`putObject` / `uploadObject`)로 파일을 올릴 때, 웹 브라우저와 모바일 플레이어가 파일을 올바르게 인식하도록 아래 **표준 MIME 타입(`Content-Type`)을 반드시 지정**해야 합니다.

| 버킷 내 경로 패턴 | 저장 대상 | 필수 지정 `Content-Type` | Nginx 연동 경로 및 접근 통제 방식 |
| :--- | :--- | :--- | :--- |
| **`original/{yyyy}/{mm}/{track_uuid}.{ext}`** | 업로드된 원본 음원 (`.mp3`, `.wav`, `.flac`) | `audio/mpeg`, `audio/wav` 등 (Tika 감지값) | **`/internal-storage/original/*`**<br>외부 직접 호출 차단(`internal;`), 백엔드 `X-Accel-Redirect` 헤더로만 다운로드 허용 |
| **`artwork/{track_uuid}.jpg`** | 추출 또는 수정된 앨범 커버 이미지 | `image/jpeg` 또는 `image/png` | **`/artwork/*`**<br>Nginx ➔ RustFS 다이렉트 프록시 서빙 |
| **`hls/{track_uuid}/index.m3u8`** | HLS 스트리밍 재생 순서표(매니페스트) | **`application/vnd.apple.mpegurl`** | **`/stream/*`**<br>Nginx ➔ RustFS 다이렉트 프록시 (캐시 짧게 유지) |
| **`hls/{track_uuid}/chunk_{NNN}.ts`** | 6초 단위로 분할된 오디오 스트림 조각 | **`video/mp2t`** | **`/stream/*`**<br>Nginx ➔ RustFS 다이렉트 프록시 (Nginx 로컬 캐싱 적용) |

## 3. [초보자용 Step-by-Step] RustFS 컨테이너 설치 및 초기 환경 구축 절차

초보 작업자도 실수 없이 구축할 수 있도록 **① 환경변수 파일 준비 ➔ ② 도커 컴포즈 실행 ➔ ③ 버킷 및 정책 자동 초기화 확인 ➔ ④ 웹 콘솔(GUI) 눈으로 확인하기** 순서로 진행합니다.

### Step 1. 프로젝트 루트에 `.env` 환경변수 파일 설정
작업 폴더에 `.env` 파일을 만들고 RustFS 관리자 아이디와 비밀번호를 설정합니다. (이 값은 백엔드 `PRD-05`의 `application.yml`에서도 동일하게 사용됩니다.)

```ini
# .env 파일 예시 (비밀번호는 반드시 8글자 이상으로 설정합니다)
RUSTFS_ACCESS_KEY=rustfsadmin
RUSTFS_SECRET_KEY=rustfs_secret_key_123!
STORAGE_BUCKET_NAME=audio-storage
```

### Step 2. `docker-compose.yml`에 RustFS 및 자동 초기화 컨테이너 작성
기존에 삭제된 `minio/mc` 이미지 대신, AWS 공식 이미지(`amazon/aws-cli`)를 활용한 `storage-init` 컨테이너(및 백엔드 `@PostConstruct` 자동 초기화)를 구성하여 `docker compose up -d` 실행 즉시 `audio-storage` 버킷과 내부 프록시 읽기 권한(Bucket Policy)이 자동으로 만들어지도록 설정합니다.

```yaml
services:
  # 1. RustFS 오브젝트 스토리지 서버 본체 (MinIO 1:1 Drop-in 대체)
  rustfs:
    image: rustfs/rustfs:latest
    container_name: rustfs
    restart: unless-stopped
    command: /data
    environment:
      RUSTFS_ACCESS_KEY: ${RUSTFS_ACCESS_KEY}
      RUSTFS_SECRET_KEY: ${RUSTFS_SECRET_KEY}
      RUSTFS_ADDRESS: ":9000"
      RUSTFS_CONSOLE_ADDRESS: ":9001"
      RUSTFS_CONSOLE_ENABLE: "true"
      TZ: Asia/Seoul
    ports:
      - "9000:9000" # S3 API 통신 포트
      - "9001:9001" # 웹 브라우저 관리 콘솔(GUI) 접속 포트
    volumes:
      - rustfs-data:/data # 컨테이너를 지워도 음원 파일이 유지되도록 영구 볼륨 연결
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:9000/health || exit 1"]
      interval: 5s
      timeout: 3s
      retries: 5

  # 2. 버킷(audio-storage) 및 내부 Nginx 프록시용 읽기 정책 자동 세팅 컨테이너 (1회 실행 후 종료)
  storage-init:
    image: amazon/aws-cli:latest
    container_name: storage-init
    depends_on:
      rustfs:
        condition: service_healthy
    environment:
      AWS_ACCESS_KEY_ID: ${RUSTFS_ACCESS_KEY}
      AWS_SECRET_ACCESS_KEY: ${RUSTFS_SECRET_KEY}
      AWS_DEFAULT_REGION: us-east-1
    entrypoint: >
      /bin/sh -c "
      aws --endpoint-url http://rustfs:9000 s3api head-bucket --bucket ${STORAGE_BUCKET_NAME:-audio-storage} 2>/dev/null ||
      aws --endpoint-url http://rustfs:9000 s3 mb s3://${STORAGE_BUCKET_NAME:-audio-storage};
      cat << 'EOF' > /tmp/policy.json
      {
        \"Version\": \"2012-10-17\",
        \"Statement\": [
          {
            \"Effect\": \"Allow\",
            \"Principal\": \"*\",
            \"Action\": [\"s3:GetObject\"],
            \"Resource\": [\"arn:aws:s3:::${STORAGE_BUCKET_NAME:-audio-storage}/*\"]
          }
        ]
      }
      EOF
      aws --endpoint-url http://rustfs:9000 s3api put-bucket-policy --bucket ${STORAGE_BUCKET_NAME:-audio-storage} --policy file:///tmp/policy.json;
      echo '=== RustFS audio-storage 버킷 생성 및 내부 프록시 읽기 정책 설정 완료 ===';
      exit 0;
      "

volumes:
  rustfs-data:
```

> **왜 `s3:GetObject` 읽기 정책을 설정하나요?**
> RustFS 컨테이너는 외부에 직접 노출되지 않고 앞단의 **Nginx**와 **백엔드 서버**만 접근할 수 있는 도커 내부망에 위치합니다. 버킷에 객체 조회(`s3:GetObject`) 정책을 켜두면(목록 조회 `s3:ListBucket`은 비허용하여 보안 유지), Nginx가 `/stream/*`, `/artwork/*`, `/internal-storage/*` 경로로 파일을 가져갈 때 복잡한 S3 서명 헤더 없이도 초고속으로 파일을 프록시할 수 있습니다. 외부 사용자의 원본 무단 접근은 앞단 Nginx의 `internal;` 지시어가 완벽히 차단합니다.

### Step 3. 컨테이너 실행 및 정상 구동 확인 명령어
터미널에서 아래 명령어를 순서대로 입력하여 RustFS가 정상적으로 켜지고 버킷이 만들어졌는지 확인합니다.

```bash
# 1. 백그라운드로 RustFS 및 초기화 컨테이너 실행
docker compose up -d rustfs storage-init

# 2. 초기화 컨테이너 로그 확인 ('버킷 생성 및 내부 프록시 읽기 정책 설정 완료' 문구 확인)
docker logs storage-init

# 3. RustFS 컨테이너 상태 확인 (STATUS가 healthy로 표시되어야 정상)
docker ps --filter "name=rustfs"
```

### Step 4. 웹 브라우저 콘솔(GUI)로 눈으로 확인하기 (초보자 권장)
1. 웹 브라우저를 열고 주소창에 **`http://localhost:9001`**을 입력합니다.
2. RustFS 로그인 화면이 나오면 `.env`에 설정한 Access Key(`rustfsadmin`)와 Secret Key(`rustfs_secret_key_123!`)를 입력하고 **Login**을 클릭합니다.
3. 버킷 목록에서 **`audio-storage`** 버킷이 생성되어 있으면 스토리지 인프라 구축이 성공적으로 완료된 것입니다.
4. 추후 백엔드 서버에서 음원을 업로드하면 웹 콘솔의 `audio-storage` 버킷 안에서 `original/`, `artwork/`, `hls/` 폴더에 파일이 적재되는 것을 직접 눈으로 확인할 수 있습니다.

## 4. FFmpeg 청크 변환 엔진 상세 동작 원리 및 검증 가이드

FFmpeg는 별도 서버가 아니라 백엔드 컨테이너(`web1`, `web2`) 리눅스 내부에 설치된 프로그램(`/usr/bin/ffmpeg`)이며, 백엔드의 `HlsTranscodeService`가 자바 `ProcessBuilder`로 이 프로그램을 실행하여 원본 음원을 쪼갭니다.

### 4.1. 표준 FFmpeg 변환 명령어 해부 (초보자 이해용)
백엔드가 내부적으로 실행하는 명령어의 각 옵션이 어떤 역할을 하는지 정리한 표입니다.

```bash
ffmpeg -y -i /tmp/audio-work/{track_uuid}/input.wav \
  -vn \
  -c:a aac -b:a 192k -ar 44100 -ac 2 \
  -f hls -hls_time 6 -hls_list_size 0 \
  -hls_segment_filename "/tmp/audio-work/{track_uuid}/hls/chunk_%03d.ts" \
  "/tmp/audio-work/{track_uuid}/hls/index.m3u8"
```

| FFmpeg 옵션 | 의미 및 왜 이 옵션을 쓰는가? |
| :--- | :--- |
| **`-y`** | 만약 임시 폴더에 같은 이름의 파일이 있으면 물어보지 않고 덮어쓰기 (백그라운드 실행 멈춤 방지) |
| **`-i input.wav`** | 변환할 원본 오디오 파일 경로 지정 (MP3, WAV, FLAC 등 모든 오디오 자동 인식) |
| **`-vn`** | **Video No:** 원본 MP3 안에 들어있는 앨범 커버 이미지(비디오 스트림으로 인식됨)를 제거하고 **순수 소리만 추출** (이게 빠지면 변환 에러가 나거나 청크 용량이 비정상적으로 커짐) |
| **`-c:a aac -b:a 192k`** | 오디오 코덱을 웹·아이폰·안드로이드 공통 표준인 **AAC**로 변환하고, 음질을 **192kbps**로 고정 |
| **`-ar 44100 -ac 2`** | 샘플링 레이트를 음악 표준인 **44.1kHz**, 채널을 **스테레오(2채널)**로 통일 |
| **`-f hls -hls_time 6`** | 출력 형식을 **HLS**로 지정하고, 음원을 정확히 **6초 간격**으로 자름 |
| **`-hls_list_size 0`** | 생방송용이 아닌 **전체 곡 재생(VOD)**이므로, 처음부터 끝까지 모든 조각 목록을 `index.m3u8`에 빠짐없이 기록 (`0`으로 안 하면 최근 몇 개 조각만 기록됨) |
| **`-hls_segment_filename ".../chunk_%03d.ts"`** | 잘라낸 6초짜리 파일 이름을 `chunk_000.ts`, `chunk_001.ts`, `chunk_002.ts`처럼 3자리 숫자로 자동 생성 |

### 4.2. 생성되는 `index.m3u8` 파일 실제 내부 모습
FFmpeg 변환이 끝나면 만들어지는 `index.m3u8` 텍스트 파일은 아래와 같은 형태를 가집니다. 웹/앱 플레이어는 이 텍스트 파일을 먼저 읽은 뒤 적혀 있는 순서대로 `.ts` 조각들을 요청합니다.

```m3u8
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-MEDIA-SEQUENCE:0
#EXT-X-PLAYLIST-TYPE:VOD
#EXT-X-TARGETDURATION:6
#EXTINF:6.000000,
chunk_000.ts
#EXTINF:6.000000,
chunk_001.ts
#EXTINF:4.250000,
chunk_002.ts
#EXT-X-ENDLIST
```

### 4.3. [초보자 자가 진단] 터미널에서 FFmpeg 정상 동작 10초 테스트
백엔드 개발 공간(리눅스 컨테이너 터미널)에서 FFmpeg가 제대로 설치되어 HLS 청크를 잘 만들어내는지, 자바 코드를 짜기 전에 아래 명령어로 먼저 테스트해 볼 수 있습니다.

```bash
# 1. 테스트용 임시 폴더 생성
mkdir -p /tmp/ffmpeg-test/hls

# 2. 실제 음원 파일 없이도 FFmpeg 내장 기능으로 15초짜리 테스트용 신호음 WAV 파일 생성
ffmpeg -y -f lavfi -i "sine=frequency=440:duration=15" /tmp/ffmpeg-test/sample.wav

# 3. 생성된 15초 WAV 파일을 우리 서비스 표준 옵션(AAC 192k, 6초 단위)으로 HLS 변환 실행
ffmpeg -y -i /tmp/ffmpeg-test/sample.wav \
  -vn -c:a aac -b:a 192k -ar 44100 -ac 2 \
  -f hls -hls_time 6 -hls_list_size 0 \
  -hls_segment_filename "/tmp/ffmpeg-test/hls/chunk_%03d.ts" \
  "/tmp/ffmpeg-test/hls/index.m3u8"

# 4. 결과 확인 (index.m3u8 파일과 chunk_000.ts, chunk_001.ts, chunk_002.ts 총 4개 파일이 보이면 성공!)
ls -la /tmp/ffmpeg-test/hls/
cat /tmp/ffmpeg-test/hls/index.m3u8

# 5. 테스트 후 임시 폴더 깔끔하게 삭제
rm -rf /tmp/ffmpeg-test
```

## 5. 로컬 임시 폴더(`/tmp/audio-work`) 관리 및 장애 방지 수칙

백엔드(`web1`, `web2`)가 음원을 검증하고 FFmpeg로 변환하는 동안 컨테이너 내부의 `/tmp/audio-work` 폴더를 임시 작업장으로 사용합니다. 컨테이너 디스크가 꽉 차서 서버가 멈추는 현상(Disk Full)을 막기 위해 아래 3가지 규칙을 적용합니다.

1. **트랙별 격리된 작업 폴더 생성:** 동시에 2개의 음원이 변환될 때 파일이 섞이지 않도록 반드시 `/tmp/audio-work/{track_uuid}/`처럼 고유 UUID 이름으로 하위 폴더를 만들어 작업합니다.
2. **`try - finally` 즉시 삭제 원칙 (`PRD-05` 연동):** RustFS 업로드가 성공하든 중간에 FFmpeg 에러가 나든 관계없이, 자바 코드의 `finally` 블록에서 `FileSystemUtils.deleteRecursively(workDir)`를 호출해 해당 UUID 작업 폴더를 즉시 삭제합니다.
3. **FFmpeg 프로세스 타임아웃 강제 (`Process.waitFor`):** 손상된 오디오 파일로 인해 FFmpeg 프로세스가 멈춘 채 영원히 끝나지 않는 현상(Hang)을 방지하기 위해, `process.waitFor(3, TimeUnit.MINUTES)`로 최대 3분 제한을 걸고 초과 시 `process.destroyForcibly()`로 강제 종료 후 `FAILED` 처리합니다.

# [PRD-02] 웹페이지 (Web Frontend) 상세 설계

## 1. 개요 및 기술 스택
본 문서는 음원 스트리밍 서비스 MVP의 웹 클라이언트 화면인 **웹페이지(Web Frontend)**의 UI/UX 레이아웃, 동적 DOM 및 Ajax 통신 구조, HLS 실시간 스트리밍 플레이어 연동, 그리고 도커 Nginx 컨테이너 기반 정적 배포 절차를 정의합니다.

전체적인 시각적 디자인과 레이아웃은 **[YouTube Music](https://music.youtube.com/)**의 다크 테마 인터페이스를 레퍼런스로 삼으며, 전체 아키텍처(`PRD-01`), 리버스 프록시(`PRD-04`), 백엔드(`PRD-05`)와 단일 도메인(Single Origin)으로 매끄럽게 통합됩니다.

### 1.1. 핵심 기술 스택 및 라이브러리 선정
대중성(1순위) > 사용 난이도(2순위) > 성능(3순위) 원칙에 따라 검증된 표준 라이브러리를 사용합니다.

| 구분 | 선정 기술 / 라이브러리 | 선정 사유 및 핵심 역할 |
| :--- | :--- | :--- |
| **코어 프레임워크** | **React `19.x` + Vite** | 빠른 HMR(Hot Module Replacement) 개발 속도와 경량 정적 번들(`dist`) 생성 지원 (CSR 렌더링) |
| **HLS 스트리밍 엔진** | **`hls.js`** | 크롬·엣지·파이어폭스 등 기본 `<audio>` 태그가 `.m3u8`을 직접 읽지 못하는 브라우저에서 `.m3u8` 매니페스트 파싱 및 6초 단위 `.ts` 청크 버퍼링 재생을 처리하는 표준 라이브러리 |
| **비동기 통신 (Ajax)** | **`axios` (또는 Fetch API)** | 페이지 새로고침 없는 키워드 검색(`GET /api/tracks?keyword=...`), 멀티파트 음원 업로드(`POST /api/tracks/upload`), 재생 정보 호출 수행 |
| **클라이언트 태그 파서** | **`jsmediatags`** | 업로드 모달에서 음원 파일 선택 즉시 서버 전송 전에 브라우저 메모리에서 ID3 태그(제목, 가수, 내장 앨범자켓 이미지)를 추출해 입력칸에 자동 채움 |
| **아이콘 시스템** | **`lucide-react`** | 재생(`Play`), 일시정지(`Pause`), 볼륨(`Volume2`), 반복재생(`Repeat`), 다운로드(`Download`), 검색(`Search`) 등 유튜브 뮤직 스타일 벡터 아이콘 제공 |
| **서빙 및 배포 환경** | **도커 `nginx` 컨테이너 내장 서빙** | Vite 빌드 결과물(`dist`)을 `PRD-04`의 Nginx `/usr/share/nginx/html`에 마운트하여 정적 웹 서빙과 백엔드 로드 밸런싱을 단일 진입점(`Port: 80`)에서 통합 처리 |

## 2. 화면 레이아웃 및 핵심 UI/UX 상세 명세

전체 화면은 **[YouTube Music](https://music.youtube.com/)** 다크 모드 스타일(`배경: #030303 ~ #121212`, `컴포넌트 표면: #212121`, `포인트 컬러: #FF0000 / #FFFFFF`)을 기반으로 **① 상단 헤더(로그인/프로필)**, **② 중앙 모달(로그인 모달 / 업로드 모달)**, **③ 히어로 검색창 및 동적 음원 리스트**, **④ 하단 고정 재생 컨트롤 바**의 4개 영역으로 구성됩니다.

### 2.1. 우측 상단 로그인 버튼 · 로그인 모달 · 프로필 팝오버 흐름
사용자의 인증 상태(`GET /api/auth/me` 응답 유무 및 JWT 보유 여부)에 따라 우측 상단 UI가 동적으로 전환됩니다.

1. **비로그인 상태 (로그인 버튼 및 Google 로그인 모달):**
   * 화면 우측 상단에 **`[로그인]`** 버튼이 표시됩니다.
   * `[로그인]` 버튼을 클릭하면 화면 중앙에 배경이 어두워진(Backdrop) 자그마한 **로그인 모달창**이 뜹니다.
   * 모달창 중앙에는 구글 로고와 함께 **`"Sign with Google"`** 버튼 하나만 깔끔하게 배치되며, 이 버튼을 클릭하면 백엔드의 Google OAuth2 인증 진입점(`GET /oauth2/authorization/google`)으로 이동하여 로그인을 수행합니다.
2. **로그인 완료 상태 (구글 프로필 이미지 및 업로드 팝오버 메뉴):**
   * Google OAuth2 로그인이 완료되면 우측 상단의 `[로그인]` 버튼 자리에 구글 계정에서 받아온 **사용자의 프로필 이미지(`profileImageUrl`)가 원형 아바타 형태로 표기**됩니다.
   * 해당 프로필 이미지를 클릭하면 이미지 바로 아래에 작은 **팝오버(Popover) 드롭다운 메뉴**가 열리며 **`"업로드"`** 옵션(및 `"로그아웃"` 옵션)이 표시됩니다.
   * 팝오버에서 **`"업로드"`**를 클릭하면 음악 파일을 등록할 수 있는 **음원 업로드 모달창**이 화면 중앙에 열립니다.

### 2.2. 음원 업로드 모달 (메타데이터 자동 추출 및 수정 저장)
1. **파일 선택 및 로컬 메타데이터 자동 완성:**
   * 사용자가 업로드 모달에서 음원 파일(`.mp3`, `.wav`, `.flac`)을 선택하면, 브라우저가 `jsmediatags`를 통해 파일 내부 태그를 즉시 스캔합니다.
   * 추출된 **제목(`title`), 가수(`artist`), 앨범명(`album`)**이 모달 내 텍스트 입력창에 자동으로 채워지며, 내장된 **앨범자켓(Artwork) 이미지**가 있으면 좌측 썸네일 미리보기 영역에 즉시 렌더링됩니다.
   * 태그 정보가 없는 파일(예: 무손실 `.wav`)을 선택한 경우, **원본 파일명(확장자 제외)**이 제목 입력창에 자동 기입되고 가수 입력창에는 `'Unknown Artist'`, 앨범자켓 영역에는 기본 음표 플레이스홀더 이미지가 표시됩니다.
2. **사용자 직접 수정 및 멀티파트 저장 (`POST /api/tracks/upload`):**
   * 사용자는 자동으로 채워진 **제목, 가수, 앨범명을 자유롭게 수정**할 수 있으며, 앨범자켓 미리보기를 클릭해 **원하는 커스텀 커버 이미지 파일(`.jpg`, `.png`)로 교체**할 수도 있습니다.
   * `[저장(업로드)]` 버튼을 누르면 `FormData`(`file`, `title`, `artist`, `album`, 선택적 `artworkFile`)를 구성하여 Ajax(`POST /api/tracks/upload`)로 전송하고, 업로드 완료 즉시 모달을 닫고 메인 음원 리스트를 최신화합니다.

### 2.3. 히어로 섹션 (실시간 Ajax 검색창 및 최신순 동적 음원 리스트)
1. **상단 검색창 (Ajax 기반 동적 필터링):**
   * 히어로 섹션 상단 중앙에 검색창(`"노래 제목 또는 가수를 검색하세요..."`)을 배치합니다.
   * 사용자가 검색어를 입력하면 페이지 새로고침 없이 **Ajax(`GET /api/tracks?keyword={검색어}`)를 비동기 호출**하여, 입력한 단어가 **노래 제목(`title`) 또는 가수(`artist`)**에 포함된 곡들만 받아와 아래 리스트 DOM을 즉시 업데이트합니다. (과도한 API 호출 방지를 위해 `300ms` Debounce 적용)
2. **음원 리스트 영역 (업로드 날짜 내림차순 정렬):**
   * 검색창 아래 리스트에는 저장된 노래들이 **업로드 날짜 기준 내림차순(`created_at DESC`, 가장 최근에 올린 곡이 맨 위)**으로 정렬되어 출력됩니다.
   * **각 리스트 아이템(Row) 표시 정보:**
     * 좌측: **앨범자켓 썸네일 이미지** (`/artwork/{track_uuid}.jpg`, 없을 시 기본 다크 썸네일)
     * 중앙: **노래 제목(Bold)**, **가수명**, **재생 시간(`mm:ss`)**, 비동기 변환 중일 경우 **`[변환 중...]` 상태 뱃지**
     * 우측 끝: **원본 파일 다운로드(`⬇`) 아이콘 버튼** — 클릭 시 이벤트 버블링(`e.stopPropagation()`)을 막아 재생을 건드리지 않고 백엔드 원본 다운로드 주소(`GET /api/tracks/{trackId}/download`)를 호출하여 원본 파일(`.mp3`, `.wav`)을 즉시 다운로드합니다.

### 2.4. 하단 고정 노래 재생 UI 영역 (Bottom Player Bar)
리스트에서 변환이 완료된(`READY`) 노래 행을 클릭하면, 화면 최하단에 고정된 플레이어 바가 활성화되며 스트리밍 재생을 시작합니다.

| 영역 구분 | 구현 컴포넌트 및 상세 동작 방식 |
| :--- | :--- |
| **좌측: 현재 곡 정보 영역** | 클릭한 노래의 **앨범 자켓 썸네일**, **노래 제목**, **가수명**을 실시간으로 바인딩하여 표시합니다. |
| **중앙: 재생/멈춤 및 진행 슬라이더** | 1. **재생/일시정지 토글 버튼:** 오디오가 멈춰 있을 때는 **재생(`▶`) 아이콘**, 재생 중일 때는 **일시정지(`⏸`) 아이콘**으로 번갈아 표기되며 클릭 시 즉시 상태를 전환합니다.<br>2. **노래 재생 슬라이더(Seek Bar):** 현재 재생 시각 / 총 재생 시간(`01:15 / 03:40`) 표시와 함께 노래 진행에 따라 슬라이더 바가 실시간 이동하며, 사용자가 슬라이더를 드래그하면 해당 시간(`audio.currentTime`)의 `.ts` 청크 구간으로 즉시 이동(Seeking)합니다. |
| **우측: 볼륨·반복재생·다운로드** | 1. **볼륨 조절 슬라이더:** `0.0 ~ 1.0` 범위로 음량을 실시간 조절(`audio.volume`)하며 좌측 스피커 아이콘 클릭 시 음소거(Mute)를 토글합니다.<br>2. **반복재생(Loop) 토글 버튼:** 클릭 시 활성화 색상(레드/화이트 강조)으로 바뀌며 현재 곡이 끝났을 때(`ended` 이벤트) 처음부터 다시 자동 반복 재생(`audio.loop = true/false`)합니다.<br>3. **원본 다운로드 버튼(`⬇`):** 현재 감상 중인 곡의 원본 파일을 즉시 다운로드합니다. |

## 3. HLS 실시간 스트리밍(`hls.js`) 및 Ajax 연동 구조

### 3.1. 컴포넌트 폴더 구조
```text
frontend/
├── index.html
├── package.json
├── vite.config.js
└── src/
    ├── main.jsx
    ├── App.jsx
    ├── api/
    │   └── trackApi.js          # Axios 인스턴스 (목록/검색 조회, 멀티파트 업로드, 재생 URL 조회)
    ├── components/
    │   ├── Header.jsx           # 로고, 검색창, 우측 상단 [로그인] 버튼 / 구글 프로필 이미지 & 팝오버
    │   ├── GoogleLoginModal.jsx # 중앙 "Sign with Google" 단일 버튼 모달
    │   ├── UploadModal.jsx      # 파일 선택 시 태그/앨범자켓 자동 추출 및 수정 업로드 모달
    │   ├── TrackList.jsx        # 업로드 최신순 동적 리스트 및 행별 원본 다운로드 버튼
    │   └── BottomPlayerBar.jsx  # 하단 고정 재생/일시정지, 진행 슬라이더, 볼륨 슬라이더, 반복재생 바
    └── styles/
        └── youtube-music.css    # 유튜브 뮤직 스타일 다크 테마 스타일시트
```

### 3.2. `BottomPlayerBar.jsx` 내 HLS 스트리밍 재생 핵심 로직
리스트에서 곡을 클릭하면 백엔드(`GET /api/tracks/{id}/play`)로부터 `/stream/{track_uuid}/index.m3u8` 주소를 받아온 뒤, 브라우저 환경에 맞춰 `hls.js`로 Nginx ➔ MinIO 청크 스트림을 연결합니다.

```javascript
import Hls from 'hls.js';

export function attachHlsStream(audioElement, streamUrl, onReadyToPlay) {
  // 1. 크롬, 엣지, 파이어폭스 등 MSE 지원 브라우저 (hls.js 사용)
  if (Hls.isSupported()) {
    const hls = new Hls({
      maxBufferLength: 30, // 6초 청크 최대 5개(30초) 선버퍼링
    });
    hls.loadSource(streamUrl); // 예: "/stream/550e8400.../index.m3u8"
    hls.attachMedia(audioElement);
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      audioElement.play();
      if (onReadyToPlay) onReadyToPlay();
    });
    return hls;
  }
  // 2. iOS Safari / macOS Safari 등 네이티브 HLS 지원 브라우저
  else if (audioElement.canPlayType('application/vnd.apple.mpegurl')) {
    audioElement.src = streamUrl;
    audioElement.addEventListener('loadedmetadata', () => {
      audioElement.play();
      if (onReadyToPlay) onReadyToPlay();
    });
    return null;
  }
}
```

## 4. [초보자용 Step-by-Step] 개발 환경 구축 및 도커 Nginx 배포 절차

### Step 1. 리눅스 컨테이너(또는 개발 환경)에 Node.js 20 LTS 설치
```bash
# 1. Node.js 20 LTS 설치 (Ubuntu/Debian 기준)
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
apt-get install -y nodejs

# 2. 설치 버전 확인
node -v
npm -v
```

### Step 2. React(Vite) 프로젝트 생성 및 필수 패키지 설치
```bash
# 1. 작업 폴더로 이동하여 Vite 기반 React 프로젝트 생성
cd /workspace
npm create vite@latest frontend -- --template react
cd frontend

# 2. 기본 패키지 및 스트리밍·태그파싱·통신·아이콘 라이브러리 일괄 설치
npm install
npm install hls.js axios jsmediatags lucide-react
```

### Step 3. `vite.config.js` 설정 (빌드 출력 경로를 Nginx 마운트 폴더로 자동 지정)
`npm run build`를 실행했을 때 결과물이 `PRD-04`에서 설정한 Nginx 정적 폴더(`../frontend-dist`)로 바로 떨어지도록 설정하면, 빌드 명령어 한 번으로 Nginx 웹페이지가 즉시 업데이트됩니다.

```javascript
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    // 빌드 결과물을 Nginx 컨테이너가 마운트하고 있는 폴더로 직접 출력
    outDir: '../frontend-dist',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    // 개발 서버(npm run dev) 실행 시에도 /api, /stream 요청을 Nginx(80)로 자동 연결
    proxy: {
      '/api': 'http://localhost:80',
      '/oauth2': 'http://localhost:80',
      '/login': 'http://localhost:80',
      '/stream': 'http://localhost:80',
      '/artwork': 'http://localhost:80',
    },
  },
});
```

### Step 4. 빌드 및 도커 Nginx 정적 서빙 반영 확인
```bash
# 1. React 프론트엔드 프로덕션 빌드 실행 (../frontend-dist 폴더에 파일 자동 생성)
cd /workspace/frontend
npm run build

# 2. Nginx 마운트 폴더에 index.html과 assets 폴더가 생성되었는지 확인
ls -la /workspace/frontend-dist

# 3. 브라우저에서 http://localhost 접속하여 유튜브 뮤직 스타일 웹페이지 정상 출력 확인
```

# [PRD-03] 모바일 앱 (Android Mobile App) 상세 설계

> **변경 요약 (2026-11-11 백엔드 계약 정합):** 로그인 진입 시 `redirect_uri=musicapp://oauth2/callback` 지정, 토큰 저장·Bearer 헤더 사용·로그아웃 규칙, 변환 미완료(`409`) 및 다운로드 파일명 처리 명시. 상세는 `PRD.md` §7 참조.

## 1. 개요 및 개발 목표
본 문서는 1차 통합 테스트(웹 E2E 검증)를 마친 백엔드(`PRD-05`)·음원 서버(`PRD-06`)·리버스 프록시(`PRD-04`) 파이프라인에 연동되는 **Android 네이티브 모바일 앱**의 상세 기술 스택, UI/UX 구조, 백그라운드 HLS 스트리밍 아키텍처, 그리고 초보자를 위한 리눅스 컨테이너 내 Android 빌드 인프라 구축 절차를 정의합니다.

웹페이지(`PRD-02`)와 동일한 기능(Google OAuth2 로그인 및 프로필 팝오버 업로드, 메타데이터 자동 추출·수정 업로드, 실시간 키워드 검색, 하단 플레이어 바 및 원본 다운로드)을 **[YouTube Music 모바일 앱](https://music.youtube.com/)** 다크 테마 디자인으로 구현하며, 모바일 OS 특성에 맞춰 **화면 꺼짐(잠금 화면) 및 타 앱 전환 시에도 음악이 끊기지 않는 네이티브 백그라운드 재생(`Media3 ExoPlayer` + `MediaSessionService`)**을 제공합니다.

### 1.1. 웹뷰(WebView) 방식을 배제하고 네이티브 앱을 채택한 이유
1. **백그라운드 재생 지속성 보장:** 모바일 웹뷰는 사용자가 화면을 끄거나 카카오톡 등 다른 앱으로 전환하면 안드로이드 OS의 배터리·메모리 절전 정책에 의해 수 분 내에 오디오 스레드가 강제 종료됩니다. 네이티브 **Foreground Media Service**를 사용해야만 무중단 스트리밍이 보장됩니다.
2. **OS 시스템 미디어 컨트롤러 및 하드웨어 연동:** 잠금 화면 알림창(MediaStyle Notification) 제어, 블루투스 이어폰/차량 오디오 버튼(재생·일시정지) 연동, 전화 수신 시 자동 일시정지(Audio Focus 제어)는 네이티브 `MediaSession`을 통해서만 완벽히 제어할 수 있습니다.

## 2. 기술 스택 및 라이브러리 선정 (Kotlin DSL 기준)

`PRD-05`와 동일하게 **대중성(1순위) > 사용 난이도(2순위) > 성능(3순위)** 기준으로 안드로이드 공식 표준 라이브러리를 선정했습니다.

| 분류 | 선정 기술 / 라이브러리 | 대중성 | 사용 난이도 | 선정 사유 및 핵심 역할 |
| :--- | :--- | :---: | :---: | :--- |
| **언어 및 UI 툴킷** | **Kotlin + Jetpack Compose (Material 3)** | 최상 | 낮음 | XML 없이 선언형 코드로 유튜브 뮤직 스타일의 다크 테마 UI, 모달, 슬라이더를 직관적으로 구현 |
| **HLS 미디어 엔진** | **Google Media3 (`media3-exoplayer`, `media3-exoplayer-hls`, `media3-session`)** | 최상 | 낮음 | 구글 공식 오디오/비디오 엔진. 백엔드의 `.m3u8` 주소를 넣으면 6초 단위 `.ts` 청크 버퍼링과 잠금 화면 알림창 컨트롤러를 자동으로 연결 |
| **네트워크 통신** | **Retrofit2 + OkHttp3 + Kotlinx Serialization (또는 Gson)** | 최상 | 매우 낮음 | 안드로이드 REST API 비동기 통신(`GET /api/tracks`, `POST /api/tracks/upload` 멀티파트 업로드) 압도적 표준 1위 |
| **비동기 이미지 로더** | **Coil (`io.coil-kt:coil-compose`)** | 최상 | 매우 낮음 | Jetpack Compose 전용 비동기 이미지 로더. 앨범 커버(`/artwork/{uuid}.jpg`)와 구글 프로필 사진 URL을 캐싱하여 렌더링 |
| **로컬 태그 추출** | **Android 내장 `MediaMetadataRetriever`** | 최상 | 매우 낮음 | 외부 라이브러리 설치 없이 안드로이드 OS 기본 내장 클래스로 업로드할 파일의 제목·가수·내장 앨범자켓 이미지를 즉시 추출 |
| **OAuth2 로그인** | **Chrome Custom Tabs (`androidx.browser:browser`)** | 최상 | 매우 낮음 | 구글 보안 정책(WebView OAuth 차단 정책)을 준수하며 `/oauth2/authorization/google` 로그인을 수행하고 딥링크로 JWT를 수신 |

## 3. 모바일 UI/UX 레이아웃 및 기능 명세 (`PRD-02` & 유튜브 뮤직 앱 기준)

전체 화면은 첨부된 **YouTube Music 모바일 앱** 레퍼런스와 `PRD-02`의 UX 흐름을 모바일 세로 화면에 최적화하여 단일 메인 화면 + 모달/바텀시트 구조로 구성합니다.

### 3.1. 상단 검색 바 및 우측 상단 로그인/프로필 영역
1. **상단 알약형(Pill) 검색창:**
   * 화면 최상단에 둥근 다크 그레이(`#212121`) 검색창을 배치합니다.
   * 사용자가 검색어를 입력하면 `300ms` Debounce 후 `GET /api/tracks?keyword={검색어}`를 비동기 호출하여 노래 제목 또는 가수가 일치하는 결과로 아래 리스트를 즉시 갱신합니다.
2. **우측 상단 로그인 버튼 및 `"Sign with Google"` 모달:**
   * **비로그인 시:** 검색창 우측에 **`[로그인]`** 버튼이 표시되며, 터치 시 화면 중앙에 컴팩트한 모달 다이얼로그가 뜨고 **`"Sign with Google"`** 버튼 하나가 노출됩니다.
   * 버튼을 누르면 Chrome Custom Tabs가 열려 백엔드 Google OAuth2(`{BASE_URL}/oauth2/authorization/google?redirect_uri=musicapp%3A%2F%2Foauth2%2Fcallback`) 인증을 진행하고, 완료 시 앱 전용 딥링크(`musicapp://oauth2/callback?token={jwt}`)로 복귀하여 JWT를 저장합니다. (`redirect_uri`는 백엔드 화이트리스트 `OAUTH2_ALLOWED_REDIRECT_URIS`에 등록된 값과 완전히 일치해야 하며, 로그인 실패 시 `?error={code}`가 전달됩니다. PRD-05 §6.1)
   * **토큰 저장 및 사용:** 수신한 JWT는 `EncryptedSharedPreferences`(또는 DataStore)에 저장하고, OkHttp `Interceptor`로 모든 API 요청에 `Authorization: Bearer {jwt}` 헤더를 붙입니다. `GET /api/auth/me`가 `401`을 반환하면 토큰을 삭제하고 비로그인 상태로 전환합니다. 로그아웃은 저장된 토큰 삭제로 처리합니다(무상태 JWT).
3. **로그인 후 구글 프로필 이미지 및 `"업로드"` 팝오버 메뉴:**
   * 로그인 완료 시 `[로그인]` 버튼 자리에 `GET /api/auth/me`에서 받아온 **구글 프로필 이미지(`profileImageUrl`)가 원형 아바타**로 표시됩니다.
   * 프로필 이미지를 터치하면 바로 아래에 드롭다운 팝오버 메뉴(`DropdownMenu`)로 **`"업로드"`** 옵션이 나타나며, 클릭 시 **음원 업로드 모달**이 열립니다.

### 3.2. 음원 업로드 모달 (로컬 태그 자동 채우기 및 수정 저장)
1. **파일 선택 및 자동 추출:**
   * 모바일 기기 내 오디오 파일(`.mp3`, `.wav` 등)을 선택하면 안드로이드 내장 `MediaMetadataRetriever`가 즉시 동작하여 **제목(`METADATA_KEY_TITLE`), 가수(`METADATA_KEY_ARTIST`), 앨범명, 내장 앨범자켓 비트맵(`embeddedPicture`)**을 추출해 모달 입력창과 썸네일에 자동으로 채워줍니다. (태그가 없는 WAV 파일 등은 파일명을 제목으로, `'Unknown Artist'`를 가수로 자동 입력)
2. **메타데이터/자켓 수정 및 업로드 (`POST /api/tracks/upload`):**
   * 사용자는 자동 입력된 제목·가수를 직접 수정하거나 스마트폰 갤러리에서 다른 앨범자켓 이미지(`artworkFile`)를 선택해 변경할 수 있습니다.
   * `[저장(업로드)]` 버튼을 누르면 Retrofit2 `@Multipart`로 백엔드에 전송하고 완료 즉시 리스트를 새로고침합니다.

### 3.3. 메인 트랙 리스트 (업로드 최신순 정렬 및 원본 다운로드)
* 검색창 아래 리스트(`LazyColumn`)에는 저장된 노래들이 **업로드 날짜 기준 내림차순(`created_at DESC`)**으로 나열됩니다.
* **각 아이템 행(Row) 구성:**
  * 좌측: 정사각형 라운드 **앨범자켓 썸네일** (`/artwork/{uuid}.jpg`)
  * 중앙: **노래 제목(흰색 굵은 글씨)**, 그 아래에 **`노래 • {가수명} • {재생시간(mm:ss)}`** 표기 (변환 중일 경우 `[변환 중]` 뱃지 표시. 변환 미완료 곡의 `GET /api/tracks/{id}/play`는 `409 TRACK_NOT_READY`를 반환하며, 에러 응답 형식 `{ code, message, timestamp }`는 PRD-05 §7.2 참조)
  * 우측 끝: **더보기(`⋮`) 또는 원본 다운로드(`⬇`) 아이콘 버튼** — 터치 시 안드로이드 시스템 `DownloadManager`를 통해 `GET /api/tracks/{trackId}/download` 경로의 원본 음원 파일을 스마트폰 `Downloads` 폴더로 즉시 내려받습니다. (백엔드가 `X-Accel-Redirect`로 Nginx 내부 다운로드를 지시하므로 앱은 일반 HTTP 다운로드로 처리하면 되며, 파일명은 응답의 `Content-Disposition`(`filename*=UTF-8''...`)을 따릅니다.)

### 3.4. 하단 재생 컨트롤 UI (미니 플레이어 바 & 확장 플레이어 컨트롤)
리스트에서 노래를 터치하면 화면 하단에 고정된 플레이어 UI가 활성화되며, `PRD-02`와 동일한 제어 기능을 제공합니다.

| 구성 요소 | 상세 동작 명세 |
| :--- | :--- |
| **앨범자켓 및 곡 정보** | 현재 선택된 노래의 앨범자켓 이미지, 노래 제목, 가수명을 표시합니다. |
| **재생 / 일시정지 토글 버튼** | `ExoPlayer.isPlaying` 상태와 실시간 동기화되어 **재생(`▶`)**과 **일시정지(`⏸`)** 아이콘이 번갈아 표기되며 터치 시 즉시 전환됩니다. |
| **노래 재생 구간 슬라이더** | 현재 재생 위치(`currentPosition`)와 전체 길이(`duration`)를 연결한 Compose `Slider`로, 드래그 시 `exoPlayer.seekTo(positionMs)`를 호출해 해당 `.ts` 청크 구간으로 즉시 이동합니다. |
| **볼륨 조절 슬라이더** | `exoPlayer.volume` (`0.0f ~ 1.0f`) 또는 시스템 미디어 볼륨과 연동되어 드래그로 음량을 실시간 조절합니다. |
| **반복재생(Loop) 토글 버튼** | 터치할 때마다 `Player.REPEAT_MODE_OFF`와 `Player.REPEAT_MODE_ONE`(한 곡 무한 반복) 상태를 토글하며 아이콘 활성화 색상을 변경합니다. |

## 4. Google Media3 (`ExoPlayer`) 백그라운드 스트리밍 설계

### 4.1. 6초 단위 HLS 청크 스트리밍 재생 코드 구조
백엔드 `GET /api/tracks/{id}/play`에서 응답받은 `/stream/{track_uuid}/index.m3u8` 경로를 `HlsMediaSource` 기반 `MediaItem`으로 등록하여 재생합니다.

```kotlin
// MediaItem 생성 (알림창 및 잠금 화면에 표시할 메타데이터 함께 바인딩)
val mediaMetadata = MediaMetadata.Builder()
    .setTitle(track.title)
    .setArtist(track.artist)
    .setArtworkUri(Uri.parse("$baseUrl${track.artworkUrl}"))
    .build()

val mediaItem = MediaItem.Builder()
    .setMediaId(track.trackId)
    .setUri("$baseUrl${track.streamUrl}") // 예: http://10.0.2.2/stream/{uuid}/index.m3u8
    .setMimeType(MimeTypes.APPLICATION_M3U8)
    .setMediaMetadata(mediaMetadata)
    .build()

exoPlayer.setMediaItem(mediaItem)
exoPlayer.prepare()
exoPlayer.play()
```

### 4.2. 백그라운드 서비스 (`PlaybackService`) 및 `AndroidManifest.xml` 필수 설정
화면이 꺼져도 재생이 유지되고 시스템 알림창에 미디어 컨트롤러가 자동으로 뜨도록 `MediaSessionService`를 등록합니다. 또한 로컬 도커 Nginx(`http://`)와의 평문 통신을 허용하기 위해 **`android:usesCleartextTraffic="true"`**를 필수로 지정합니다.

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 1. 네트워크 통신 및 백그라운드 오디오 서비스 필수 권한 -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />

    <!-- 2. 로컬 도커 Nginx(http://) 접속을 위해 usesCleartextTraffic="true" 필수 지정 -->
    <application
        android:usesCleartextTraffic="true"
        android:label="Music Streaming MVP"
        android:theme="@style/Theme.Material3.Dark.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <!-- Google OAuth2 로그인 완료 후 앱 복귀용 딥링크 스킴 -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="musicapp" android:host="oauth2" android:path="/callback" />
            </intent-filter>
        </activity>

        <!-- 3. 잠금 화면 및 백그라운드 무중단 재생용 Media3 서비스 -->
        <service
            android:name=".player.PlaybackService"
            android:foregroundServiceType="mediaPlayback"
            android:exported="true">
            <intent-filter>
                <action android:name="androidx.media3.session.MediaSessionService" />
            </intent-filter>
        </service>
    </application>
</manifest>
```

## 5. [초보자용 Step-by-Step] 컨테이너 내 Android 빌드 환경 구축 및 연동 절차

리눅스 도커 컨테이너 내부 터미널에서 무거운 Android Studio GUI 없이도 **명령줄 도구(Command-line Tools)만으로 안드로이드 앱을 빌드하고 APK를 뽑아낼 수 있는 절차**입니다.

### Step 1. 리눅스 컨테이너 내부에 Android SDK Command-line Tools 설치
이미 `PRD-05`에서 설치한 OpenJDK 21 환경을 그대로 활용하며, 아래 명령어로 Android SDK를 설치합니다.

```bash
# 1. Android SDK 설치 폴더 생성
mkdir -p /opt/android-sdk/cmdline-tools
cd /opt/android-sdk/cmdline-tools

# 2. 구글 공식 Android Command-line Tools(Linux용) 다운로드 및 압축 해제
wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip
unzip -q cmdline-tools.zip
mv cmdline-tools latest
rm cmdline-tools.zip

# 3. 환경변수(ANDROID_HOME) 등록
export ANDROID_HOME=/opt/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# 4. 라이선스 일괄 자동 동의 및 Android 15 (API 35) 빌드 도구 설치
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

### Step 2. `app/build.gradle.kts` 핵심 의존성 구성
```kotlin
dependencies {
    // 1. Jetpack Compose (Material 3 Dark UI)
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // 2. Google Media3 (ExoPlayer + HLS 6초 청크 스트리밍 + 백그라운드 세션)
    val media3Version = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    // 3. Retrofit2 & OkHttp3 (REST API 및 멀티파트 음원 업로드)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 4. Coil (앨범자켓 및 구글 프로필 아바타 비동기 이미지 로드)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // 5. Chrome Custom Tabs (Google OAuth2 로그인)
    implementation("androidx.browser:browser:1.8.0")
}
```

### Step 3. [초보자 필수 체크] 안드로이드 앱에서 도커 Nginx 서버 접속 주소 설정
안드로이드 앱 코드에서 서버 주소(`BASE_URL`)를 적을 때 **`http://localhost`라고 적으면 도커 서버가 아니라 스마트폰 자기 자신을 가리켜서 접속 에러(`Connection Refused`)가 발생**합니다. 테스트 환경에 맞춰 아래 주소를 입력해야 합니다.

| 테스트 기기 환경 | 앱 코드에 설정할 Nginx 서버 주소 (`BASE_URL`) | 이유 및 확인 방법 |
| :--- | :--- | :--- |
| **PC 안드로이드 에뮬레이터 (AVD)** | **`http://10.0.2.2:80`** (또는 `http://10.0.2.2`) | 안드로이드 에뮬레이터에서 내 PC의 `localhost(127.0.0.1)`로 연결해 주는 구글 공식 특수 IP 주소입니다. |
| **실제 안드로이드 스마트폰 (동일 공유기 Wi-Fi)** | **`http://192.168.x.x:80`** (내 PC의 내부 IP) | 스마트폰과 도커가 떠 있는 PC가 같은 Wi-Fi에 연결된 상태에서, PC 터미널에 `ipconfig`(윈도우) 또는 `ifconfig`(맥/리눅스)를 쳐서 나온 PC의 내부 IP 주소를 입력합니다. |

### Step 4. 터미널에서 APK 빌드 및 실행 확인
```bash
# 1. 모바일 프로젝트 폴더로 이동하여 디버그 APK 빌드 실행
cd /workspace/mobile-app
chmod +x ./gradlew
./gradlew assembleDebug

# 2. 빌드된 APK 파일 생성 확인 (이 파일을 에뮬레이터나 스마트폰에 설치)
ls -lh app/build/outputs/apk/debug/app-debug.apk
```

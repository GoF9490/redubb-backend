# 테스트 샘플 파일

`AudioMetadataValidatorTest` 등에서 실제 Tika / JAudioTagger 동작을 검증하기 위한 초소형 샘플이다.
합계 1MB 이하를 유지한다 (`SampleFixtureTest`가 검증). 모든 음원은 합성 사인파(sine)이며 저작권 이슈가 없다.

| 파일 | 용도 |
|---|---|
| `sample-tagged.mp3` | ID3v2 제목(`Sample Title`)/가수(`Sample Artist`)/앨범(`Sample Album`) + 커버 이미지 내장, 1초 |
| `sample-untagged.wav` | 태그 없는 1초 WAV (Fallback 검증용) |
| `sample.flac` | 태그 없는 1초 FLAC |
| `fake-audio.mp3` | 확장자만 `.mp3`인 텍스트 파일 (위변조 검증용) |
| `cover.jpg`, `cover.png` | 64x64 단색 앨범자켓 이미지 |

10MB 초과 이미지는 파일로 두지 않고 `SampleFixtures.createOversizedImageBytes()`로 테스트에서 생성한다.

## 생성 방법 (일회성, Docker)

로컬에 ffmpeg를 설치하지 않고 Docker 이미지로 생성했다. 생성 결과를 저장소에 커밋하므로 테스트 실행 시 ffmpeg는 필요 없다.

- 이미지: `linuxserver/ffmpeg:latest` (ffmpeg 9.0.2, 2026-11-06 빌드)
- 다이제스트: `sha256:822d04f0b2d0c4847430f5cc90f20ea48be866bb502ece63fdeb729400bf5361`
- 실행 환경: Windows PowerShell (Git Bash는 마운트 경로가 자동 변환되므로 `MSYS_NO_PATHCONV=1`을 붙이고 `C:\...` 형식을 쓴다)
- 공통 옵션 `-fflags +bitexact -map_metadata -1`: `Lavf` 인코더 태그를 제거해 무태그 파일을 만든다.

```powershell
$d = "C:\WorkSpace\re-dubb\backend\music\src\test\resources\samples"

# 1) 커버 이미지 (jpg, png)
docker run --rm -v "${d}:/out" linuxserver/ffmpeg:latest -y -f lavfi -i "color=c=0x3366cc:s=64x64" -frames:v 1 -update 1 -fflags +bitexact /out/cover.jpg
docker run --rm -v "${d}:/out" linuxserver/ffmpeg:latest -y -f lavfi -i "color=c=0xcc6633:s=64x64" -frames:v 1 -update 1 -fflags +bitexact /out/cover.png

# 2) ID3 태그 + 커버 내장 MP3 (1초, 64kbps)
docker run --rm -v "${d}:/out" linuxserver/ffmpeg:latest -y -f lavfi -i "sine=frequency=440:duration=1" -i /out/cover.jpg -map 0:a -map 1:v -c:a libmp3lame -b:a 64k -ar 22050 -ac 1 -c:v copy -id3v2_version 3 -metadata title="Sample Title" -metadata artist="Sample Artist" -metadata album="Sample Album" -metadata:s:v title="Album cover" -metadata:s:v comment="Cover (front)" /out/sample-tagged.mp3

# 3) 무태그 WAV (1초, 16kHz mono)
docker run --rm -v "${d}:/out" linuxserver/ffmpeg:latest -y -f lavfi -i "sine=frequency=440:duration=1" -ar 16000 -ac 1 -fflags +bitexact -map_metadata -1 /out/sample-untagged.wav

# 4) FLAC (1초, 16kHz mono)
docker run --rm -v "${d}:/out" linuxserver/ffmpeg:latest -y -f lavfi -i "sine=frequency=440:duration=1" -ar 16000 -ac 1 -fflags +bitexact -map_metadata -1 /out/sample.flac

# 5) 가짜 mp3 (텍스트)
Set-Content -Path "$d\fake-audio.mp3" -Value "This is not an audio file." -Encoding ascii
```

## 생성 결과 확인

```powershell
docker run --rm --entrypoint ffprobe -v "${d}:/out" linuxserver/ffmpeg:latest -hide_banner /out/sample-tagged.mp3
```

- `sample-tagged.mp3`: title/artist/album 태그, 비디오(mjpeg, attached pic) 스트림 존재, 길이 1.0초
- `sample-untagged.wav`, `sample.flac`: format 태그 없음, 길이 1.0초

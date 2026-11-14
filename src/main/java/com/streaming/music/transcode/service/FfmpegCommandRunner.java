package com.streaming.music.transcode.service;

import java.nio.file.Path;

/**
 * FFmpeg 프로세스 실행 추상화. 서비스 단위 테스트에서는 Mock으로 대체한다.
 */
public interface FfmpegCommandRunner {

	/**
	 * 입력 음원을 AAC 192k 6초 단위 HLS(index.m3u8, chunk_%03d.ts)로 변환한다.
	 * 출력 디렉터리는 호출자가 미리 생성해야 한다.
	 * 비정상 종료, 타임아웃, 실행 실패는 예외 대신 결과 타입으로 반환한다.
	 */
	FfmpegResult transcodeToHls(Path inputFile, Path outputDir);
}

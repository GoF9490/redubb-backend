package com.streaming.music.transcode.service;

import com.streaming.music.global.properties.AudioProperties;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * PRD-05 §6.3-3 표준 FFmpeg HLS 변환 명령 인자를 생성한다(프로세스 실행 없음).
 * 인자를 배열로 전달하므로 셸을 거치지 않고 공백 경로도 안전하다.
 */
@Component
public class FfmpegCommandBuilder {

	public static final String PLAYLIST_FILE_NAME = "index.m3u8";
	public static final String SEGMENT_FILE_PATTERN = "chunk_%03d.ts";

	private final AudioProperties audioProperties;

	public FfmpegCommandBuilder(AudioProperties audioProperties) {
		this.audioProperties = audioProperties;
	}

	public List<String> buildHlsCommand(Path inputFile, Path outputDir) {
		return List.of(
				audioProperties.ffmpegPath(),
				"-y", "-i", inputFile.toString(),
				"-vn", "-c:a", "aac",
				"-b:a", audioProperties.hlsAudioBitrate(),
				"-ar", String.valueOf(audioProperties.hlsAudioSampleRate()),
				"-ac", String.valueOf(audioProperties.hlsAudioChannels()),
				"-f", "hls",
				"-hls_time", String.valueOf(audioProperties.hlsSegmentSeconds()),
				"-hls_list_size", "0",
				"-hls_segment_filename", outputDir.resolve(SEGMENT_FILE_PATTERN).toString(),
				outputDir.resolve(PLAYLIST_FILE_NAME).toString());
	}
}

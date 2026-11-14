package com.streaming.music.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.streaming.music.global.properties.AudioProperties;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FfmpegCommandBuilderTest {

	private FfmpegCommandBuilder createBuilder() {
		return new FfmpegCommandBuilder(
				new AudioProperties("/tmp/audio-work", "/usr/bin/ffmpeg", 3, 6, "192k", 10485760L));
	}

	@Test
	@DisplayName("PRD-05 6.3-3 표준 명령과 동일한 인자 리스트를 생성한다")
	void buildPrdStandardCommand() {
		Path input = Path.of("work", "uuid", "input.wav");
		Path outputDir = Path.of("work", "uuid", "hls");

		List<String> command = createBuilder().buildHlsCommand(input, outputDir);

		assertThat(command).containsExactly(
				"/usr/bin/ffmpeg",
				"-y", "-i", input.toString(),
				"-vn", "-c:a", "aac", "-b:a", "192k", "-ar", "44100", "-ac", "2",
				"-f", "hls", "-hls_time", "6", "-hls_list_size", "0",
				"-hls_segment_filename", outputDir.resolve("chunk_%03d.ts").toString(),
				outputDir.resolve("index.m3u8").toString());
	}

	@Test
	@DisplayName("공백이 포함된 경로도 하나의 인자로 유지한다")
	void keepPathWithSpaceAsSingleArgument() {
		Path input = Path.of("my work", "input file.mp3");
		Path outputDir = Path.of("my work", "hls dir");

		List<String> command = createBuilder().buildHlsCommand(input, outputDir);

		assertThat(command).contains(input.toString(), outputDir.resolve("index.m3u8").toString());
		assertThat(command).hasSize(22);
	}

	@Test
	@DisplayName("설정 값(비트레이트, 세그먼트 초, 실행 경로)을 반영한다")
	void applyAudioProperties() {
		FfmpegCommandBuilder builder = new FfmpegCommandBuilder(
				new AudioProperties("/w", "C:\\ffmpeg\\ffmpeg.exe", 3, 10, "128k", 1L));

		List<String> command = builder.buildHlsCommand(Path.of("a.wav"), Path.of("out"));

		assertThat(command.get(0)).isEqualTo("C:\\ffmpeg\\ffmpeg.exe");
		assertThat(command).containsSubsequence("-b:a", "128k").containsSubsequence("-hls_time", "10");
	}
}

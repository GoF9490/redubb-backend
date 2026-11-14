package com.streaming.music.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 음원 처리(FFmpeg/HLS) 설정 (app.audio.*, PRD-05 §4.2).
 */
@ConfigurationProperties(prefix = "app.audio")
public record AudioProperties(
		@DefaultValue("/tmp/audio-work") String workDir,
		@DefaultValue("/usr/bin/ffmpeg") String ffmpegPath,
		@DefaultValue("3") int ffmpegTimeoutMinutes,
		@DefaultValue("6") int hlsSegmentSeconds,
		@DefaultValue("192k") String hlsAudioBitrate,
		@DefaultValue("10485760") long maxArtworkSizeBytes
) {
}

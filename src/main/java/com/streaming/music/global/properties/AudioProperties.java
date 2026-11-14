package com.streaming.music.global.properties;

import java.util.List;
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
		@DefaultValue("10485760") long maxArtworkSizeBytes,
		@DefaultValue("44100") int hlsAudioSampleRate,
		@DefaultValue("2") int hlsAudioChannels,
		@DefaultValue("500") int errorMessageMaxLength,
		@DefaultValue("2") int recoveryStaleThresholdMultiplier,
		@DefaultValue({"audio/mpeg", "audio/wav", "audio/x-wav", "audio/vnd.wave",
				"audio/flac", "audio/x-flac", "audio/mp4"}) List<String> allowedAudioMimeTypes,
		@DefaultValue({"image/jpeg", "image/png"}) List<String> allowedArtworkMimeTypes
) {

	public AudioProperties {
		requirePositive("hls-audio-sample-rate", hlsAudioSampleRate);
		requirePositive("hls-audio-channels", hlsAudioChannels);
		requirePositive("error-message-max-length", errorMessageMaxLength);
		requirePositive("recovery-stale-threshold-multiplier", recoveryStaleThresholdMultiplier);
		allowedAudioMimeTypes = PropertyUtils.normalizeList(allowedAudioMimeTypes);
		allowedArtworkMimeTypes = PropertyUtils.normalizeList(allowedArtworkMimeTypes);
	}

	private static void requirePositive(String key, int value) {
		if (value < 1) {
			throw new IllegalArgumentException("app.audio." + key + "는 1 이상이어야 합니다: " + value);
		}
	}
}

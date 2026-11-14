package com.streaming.music.dto.response;

import com.streaming.music.entity.Track;
import com.streaming.music.service.MediaUrlResolver;
import java.util.UUID;

/**
 * 재생 진입점 응답 DTO (PRD-05 §7.1.1).
 *
 * <p>streamUrl/artworkUrl은 {@link MediaUrlResolver}를 경유해 조립한다.
 */
public record StreamPlayResponse(
		UUID trackId,
		String streamUrl,
		String title,
		String artist,
		int durationSeconds,
		String artworkUrl
) {

	/** READY 트랙을 재생 응답으로 변환한다. */
	public static StreamPlayResponse from(Track track, MediaUrlResolver mediaUrlResolver) {
		return new StreamPlayResponse(
				track.getTrackId(),
				mediaUrlResolver.resolveStreamUrl(track),
				track.getTitle(),
				track.getArtist(),
				track.getDurationSeconds(),
				mediaUrlResolver.resolveArtworkUrl(track)
		);
	}
}

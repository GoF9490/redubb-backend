package com.streaming.music.service;

import com.streaming.music.global.properties.MediaProperties;
import com.streaming.music.entity.Track;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * S3 키 기반 트랙 정보를 클라이언트 공개 URL로 변환한다 (PRD-05 §5, §7.1.1).
 *
 * <p>DB에는 URL이 아닌 S3 키만 저장하며, URL은 응답 시점에 이 클래스가 조립한다.
 * {@code app.media.public-base-url}이 빈 값이면 상대경로("/stream/...")를 반환한다.
 */
@Component
public class MediaUrlResolver {

	private final String baseUrl;

	public MediaUrlResolver(MediaProperties mediaProperties) {
		Objects.requireNonNull(mediaProperties, "mediaProperties");
		// 끝의 '/'를 모두 제거하여 '//' 중복을 방지한다 (생성 시 1회 계산)
		this.baseUrl = stripTrailingSlashes(mediaProperties.publicBaseUrl());
	}

	/**
	 * HLS 재생 URL({base}/stream/{trackId}/index.m3u8)을 반환한다.
	 *
	 * <p>hlsPlaylistKey가 null(PENDING/PROCESSING 등 미완료)이면 null을 반환한다.
	 * 트랙 상태 검증(TRACK_NOT_READY 409)은 TrackService의 책임이다.
	 */
	public String resolveStreamUrl(Track track) {
		Objects.requireNonNull(track, "track");
		if (track.getHlsPlaylistKey() == null) {
			return null;
		}
		return baseUrl + "/stream/" + track.getTrackId() + "/index.m3u8";
	}

	/**
	 * 앨범아트 URL({base}/artwork/{trackId}.jpg)을 반환한다. artworkS3Key가 null이면 null이다.
	 *
	 * <p>PRD-05 §7.1.1에 따라 artwork 객체 키는 항상 artwork/{uuid}.jpg 이므로,
	 * 키 내용과 무관하게 trackId로 경로를 조립한다.
	 */
	public String resolveArtworkUrl(Track track) {
		Objects.requireNonNull(track, "track");
		if (track.getArtworkS3Key() == null) {
			return null;
		}
		return baseUrl + "/artwork/" + track.getTrackId() + ".jpg";
	}

	private static String stripTrailingSlashes(String value) {
		if (value == null) {
			return "";
		}
		int end = value.length();
		while (end > 0 && value.charAt(end - 1) == '/') {
			end--;
		}
		return value.substring(0, end);
	}
}

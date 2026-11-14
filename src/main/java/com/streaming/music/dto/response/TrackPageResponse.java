package com.streaming.music.dto.response;

import com.streaming.music.entity.Track;
import com.streaming.music.service.MediaUrlResolver;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 트랙 목록 페이지 응답 DTO (PRD-05 §7.1.1).
 *
 * <p>Spring {@link Page}를 그대로 직렬화하지 않고 고정 스키마로 변환한다.
 */
public record TrackPageResponse(
		List<TrackResponse> content,
		int page,
		int size,
		long totalElements,
		int totalPages,
		boolean hasNext
) {

	/** 엔티티 페이지를 고정 스키마 응답으로 변환한다. */
	public static TrackPageResponse from(Page<Track> trackPage, MediaUrlResolver mediaUrlResolver) {
		List<TrackResponse> content = trackPage.getContent().stream()
				.map(track -> TrackResponse.from(track, mediaUrlResolver))
				.toList();
		return new TrackPageResponse(
				content,
				trackPage.getNumber(),
				trackPage.getSize(),
				trackPage.getTotalElements(),
				trackPage.getTotalPages(),
				trackPage.hasNext()
		);
	}
}

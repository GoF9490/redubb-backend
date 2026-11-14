package com.streaming.music.track.service;

import com.streaming.music.track.dto.request.TrackUploadRequest;
import com.streaming.music.track.dto.response.DownloadInfo;
import com.streaming.music.track.dto.response.StreamPlayResponse;
import com.streaming.music.track.entity.Track;
import com.streaming.music.track.entity.TrackStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;

/**
 * 음원 업로드·조회·재생 정보·다운로드 지시 서비스 (PRD-05 §6.2, §7.1).
 */
public interface TrackService {

	/**
	 * 음원을 검증·저장하고 PENDING 트랙을 만든 뒤 변환 이벤트를 발행한다.
	 *
	 * @param request 업로드 요청
	 * @param userId  인증 사용자 ID (null이거나 존재하지 않으면 익명 업로드)
	 * @return 저장된 트랙 (status=PENDING)
	 */
	Track upload(TrackUploadRequest request, Long userId);

	/**
	 * 트랙 목록을 최신순(createdAt DESC)으로 조회한다.
	 *
	 * <p>size가 100을 넘으면 100으로 보정하고, page &lt; 0 또는 size &lt; 1이면 400으로 거부한다.
	 * keyword는 trim 후 200자를 넘으면 400이며, 비어 있으면 전체 조회로 처리한다.
	 */
	Page<Track> getTracks(String keyword, TrackStatus status, int page, int size);

	/** 트랙 단건을 조회한다. 모든 status를 허용하며 없으면 TrackNotFoundException. */
	Track getTrack(UUID trackId);

	/**
	 * 재생 진입점 정보를 반환하고 STREAM 이력을 기록한다.
	 * READY가 아니면 TrackNotReadyException(409), 없으면 TrackNotFoundException(404)이다.
	 *
	 * @param userId 인증 사용자 ID (비로그인이면 null)
	 */
	StreamPlayResponse getPlayInfo(UUID trackId, Long userId);

	/**
	 * 원본 다운로드 지시 정보를 반환하고 DOWNLOAD 이력을 기록한다. status와 무관하게 허용한다.
	 *
	 * @param userId 인증 사용자 ID (비로그인이면 null)
	 * @throws IllegalStateException presigned URL이 path-style 전제를 벗어난 경우(500)
	 */
	DownloadInfo getDownloadInfo(UUID trackId, Long userId);
}

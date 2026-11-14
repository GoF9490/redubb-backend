package com.streaming.music.playback.service;

import com.streaming.music.playback.entity.ActionType;
import com.streaming.music.track.entity.Track;

/**
 * 재생(STREAM)·다운로드(DOWNLOAD) 이력 기록 서비스 (PRD-05 §7.1.3).
 */
public interface PlaybackLogService {

	/**
	 * 이력 1건을 저장한다. 저장 중 RuntimeException은 전파하지 않는다.
	 *
	 * @param track      대상 트랙 (필수)
	 * @param userId     로그인 사용자 ID, 비로그인이면 null
	 * @param actionType STREAM 또는 DOWNLOAD (필수)
	 */
	void recordLog(Track track, Long userId, ActionType actionType);
}

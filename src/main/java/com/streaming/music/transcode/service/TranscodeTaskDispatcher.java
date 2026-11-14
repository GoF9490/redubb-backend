package com.streaming.music.transcode.service;

import com.streaming.music.transcode.dto.TranscodeEventDto;

/**
 * 비동기 HLS 변환 요청을 발행하는 포트.
 * <p>
 * 호출부(TrackService)가 변환 구현체(HlsTranscodeService)를 직접 참조하지 않도록 결합도를 분리한다.
 * 호출자(TrackService)의 트랜잭션 안에서 발행하며, 수신부는 T4-2에서
 * {@code @Async("transcodeExecutor") @TransactionalEventListener(phase = AFTER_COMMIT)}로 구현한다.
 * 일반 {@code @EventListener}는 커밋 전 실행 경합이 있어 사용하지 않는다.
 */
public interface TranscodeTaskDispatcher {

	/**
	 * 변환 요청 이벤트를 발행한다.
	 *
	 * @param dto 변환 대상 정보 (null 불가)
	 */
	void dispatch(TranscodeEventDto dto);
}

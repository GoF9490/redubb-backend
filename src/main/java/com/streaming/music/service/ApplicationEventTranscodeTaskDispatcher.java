package com.streaming.music.service;

import com.streaming.music.dto.event.TranscodeEventDto;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * {@link ApplicationEventPublisher} 기반 변환 이벤트 발행 구현체.
 * <p>
 * 이 클래스에는 {@code @Transactional}을 붙이지 않는다. 호출자(TrackService) 트랜잭션 안에서
 * {@code publishEvent}가 호출되며, 수신부는 T4-2에서
 * {@code @Async("transcodeExecutor") @TransactionalEventListener(phase = AFTER_COMMIT)}로 구현한다.
 * 일반 {@code @EventListener}는 커밋 전 실행 경합 때문에 사용하지 않는다.
 * (이번 태스크에서는 리스너를 만들지 않는다.)
 */
@Service
@RequiredArgsConstructor
public class ApplicationEventTranscodeTaskDispatcher implements TranscodeTaskDispatcher {

	private final ApplicationEventPublisher eventPublisher;

	@Override
	public void dispatch(TranscodeEventDto dto) {
		Objects.requireNonNull(dto, "dto must not be null");
		eventPublisher.publishEvent(dto);
	}
}

package com.streaming.music.transcode.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.streaming.music.transcode.dto.TranscodeEventDto;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ApplicationEventTranscodeTaskDispatcherTest {

	@Mock
	private ApplicationEventPublisher eventPublisher;

	@InjectMocks
	private ApplicationEventTranscodeTaskDispatcher dispatcher;

	@Test
	@DisplayName("dispatch는 publishEvent를 정확히 1회 호출한다")
	void dispatchPublishesEventOnce() {
		TranscodeEventDto dto = new TranscodeEventDto(UUID.randomUUID(), "original/2026/11/a.mp3");

		dispatcher.dispatch(dto);

		verify(eventPublisher, times(1)).publishEvent(dto);
		verifyNoMoreInteractions(eventPublisher);
	}

	@Test
	@DisplayName("dto가 null이면 NPE가 발생하고 이벤트는 발행되지 않는다")
	void dispatchRejectsNullDto() {
		assertThatThrownBy(() -> dispatcher.dispatch(null))
				.isInstanceOf(NullPointerException.class);

		verifyNoInteractions(eventPublisher);
	}
}

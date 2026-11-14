package com.streaming.music.transcode.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;

import com.streaming.music.transcode.dto.TranscodeEventDto;
import com.streaming.music.track.entity.Track;
import com.streaming.music.track.entity.TrackStatus;
import com.streaming.music.track.repository.TrackRepository;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 큐 포화 처리 설계의 전제를 실제 컨텍스트와 H2로 검증한다.
 * 대조군(Spring 7.0.10 실측): AFTER_COMMIT 리스너가 던진 예외는 afterCompletion 단계에서 로깅만 되고
 * execute는 예외 없이 끝난다. 아무도 상태를 바꾸지 않으므로 트랙은 PENDING에 남는다.
 * 이것이 거부를 trackId를 아는 릴레이에서 잡아 FAILED로 기록해야 하는 이유다.
 * 실제 경로: 릴레이는 거부를 잡아 REQUIRES_NEW로 FAILED를 저장한다.
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class TranscodeQueueFullIntegrationTest {

	/** 대조군 이벤트. 운영 릴레이는 이 타입을 수신하지 않는다. */
	record ControlEvent(String name) {
	}

	@TestConfiguration
	static class ControlListenerConfig {

		@Bean
		ThrowingControlListener throwingControlListener() {
			return new ThrowingControlListener();
		}
	}

	static class ThrowingControlListener {

		static final AtomicBoolean invoked = new AtomicBoolean(false);

		@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
		void onControl(ControlEvent event) {
			invoked.set(true);
			throw new TaskRejectedException("control rejected");
		}
	}

	@MockitoBean
	private HlsTranscodeService processor;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private TrackRepository trackRepository;

	@Test
	@DisplayName("대조군(Spring 7.0.10 실측): AFTER_COMMIT 리스너 예외는 execute로 전파되지 않아 후속 처리 없이 트랙이 PENDING에 남는다")
	void leavesTrackPendingWhenAfterCommitListenerThrows() {
		UUID trackId = UUID.randomUUID();
		trackRepository.save(Track.builder()
				.trackId(trackId).title("t").durationSeconds(1).originalFilename("a.mp3")
				.fileExtension("mp3").fileSizeBytes(1L).mimeType("audio/mpeg")
				.originalS3Key("original/a.mp3").build());
		ThrowingControlListener.invoked.set(false);

		try {
			assertThatCode(() -> transactionTemplate.executeWithoutResult(
					status -> eventPublisher.publishEvent(new ControlEvent("c"))))
					.doesNotThrowAnyException();

			// 리스너는 실제로 실행되었지만(예외 발생) 아무도 트랙 상태를 바꾸지 않는다
			assertThat(ThrowingControlListener.invoked).isTrue();
			assertThat(trackRepository.findById(trackId).orElseThrow().getStatus())
					.isEqualTo(TrackStatus.PENDING);
		} finally {
			trackRepository.deleteById(trackId);
		}
	}

	@Test
	@DisplayName("릴레이 경로: 거부되어도 execute는 예외 없이 끝나고 트랙이 FAILED로 저장된다")
	void marksTrackFailedWithoutPropagatingWhenRejected() {
		UUID trackId = UUID.randomUUID();
		trackRepository.save(Track.builder()
				.trackId(trackId).title("t").durationSeconds(1).originalFilename("a.mp3")
				.fileExtension("mp3").fileSizeBytes(1L).mimeType("audio/mpeg")
				.originalS3Key("original/a.mp3").build());
		TranscodeEventDto dto = new TranscodeEventDto(trackId, "original/a.mp3");
		doThrow(new TaskRejectedException("full")).when(processor).process(dto);

		try {
			transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(dto));

			Track saved = trackRepository.findById(trackId).orElseThrow();
			assertThat(saved.getStatus()).isEqualTo(TrackStatus.FAILED);
			assertThat(saved.getErrorMessage()).isEqualTo("TRANSCODE_QUEUE_FULL");
		} finally {
			trackRepository.deleteById(trackId);
		}
	}
}

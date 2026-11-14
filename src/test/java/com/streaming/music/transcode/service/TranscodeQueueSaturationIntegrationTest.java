package com.streaming.music.transcode.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.streaming.music.global.config.AsyncConfig;
import com.streaming.music.track.entity.Track;
import com.streaming.music.track.entity.TrackStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실제 {@code transcodeExecutor}(core 1 / max 2 / queue 50)를 FFmpeg Mock 블록으로 점유해
 * 큐 포화 → 거부 → 릴레이의 FAILED(TRANSCODE_QUEUE_FULL) 기록을 실제 경로로 확인한다.
 * 수용 한도는 실행 2 + 대기 50 = 52건이고 53번째가 거부된다.
 */
class TranscodeQueueSaturationIntegrationTest extends TranscodeIntegrationTestBase {

	private final CountDownLatch release = new CountDownLatch(1);

	@Override
	protected void releaseBlockedThreads() {
		release.countDown();
	}

	@Test
	@DisplayName("풀과 큐가 가득 차면 다음 작업은 거부되어 FAILED(TRANSCODE_QUEUE_FULL)로 기록되고 나머지는 정상 완료된다")
	void marksTrackFailedWhenExecutorSaturated() throws InterruptedException {
		int capacity = AsyncConfig.MAX_POOL_SIZE + AsyncConfig.QUEUE_CAPACITY;
		CountDownLatch running = new CountDownLatch(AsyncConfig.MAX_POOL_SIZE);
		when(ffmpegCommandRunner.transcodeToHls(any(), any())).thenAnswer(invocation -> {
			running.countDown();
			if (!release.await(30, TimeUnit.SECONDS)) {
				throw new IllegalStateException("해제 대기 시간 초과");
			}
			Path outputDir = invocation.getArgument(1);
			Files.writeString(outputDir.resolve(FfmpegCommandBuilder.PLAYLIST_FILE_NAME), "#EXTM3U");
			return FfmpegResult.success("ok");
		});

		List<UUID> accepted = new ArrayList<>();
		for (int i = 0; i < capacity; i++) {
			UUID trackId = savePendingTrack();
			accepted.add(trackId);
			dispatchInOwnTransaction(trackId);
		}
		// 실행 2개가 모두 블록 중이어야 큐가 비지 않는다
		assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

		UUID rejectedId = savePendingTrack();
		dispatchInOwnTransaction(rejectedId);

		Track rejected = trackRepository.findById(rejectedId).orElseThrow();
		assertThat(rejected.getStatus()).isEqualTo(TrackStatus.FAILED);
		assertThat(rejected.getErrorMessage()).isEqualTo("TRANSCODE_QUEUE_FULL");

		release.countDown();
		for (UUID trackId : accepted) {
			waitForStatus(trackId, TrackStatus.READY);
		}
	}

	private void dispatchInOwnTransaction(UUID trackId) {
		transactionTemplate.executeWithoutResult(status -> dispatcher.dispatch(eventOf(trackId)));
	}
}

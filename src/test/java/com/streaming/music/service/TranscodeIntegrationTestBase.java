package com.streaming.music.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.streaming.music.dto.event.TranscodeEventDto;
import com.streaming.music.entity.Track;
import com.streaming.music.entity.TrackStatus;
import com.streaming.music.playback.repository.PlaybackLogRepository;
import com.streaming.music.repository.TrackRepository;
import com.streaming.music.user.repository.UserRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 변환 이벤트 연결 통합 테스트 공통 기반 (T4-5).
 * <p>
 * 스토리지와 FFmpeg 실행기만 Mock으로 대체하고 나머지(이벤트 릴레이, 실제 {@code @Async} 프록시, H2)는 실제로 쓴다.
 * work-dir은 JVM 전체에서 하나의 임시 폴더를 공유해 컨텍스트 캐시가 깨지지 않게 한다.
 * 각 테스트가 끝나면 비동기 작업이 모두 끝날 때까지 기다린 뒤 트랙/회원 데이터를 지운다.
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
abstract class TranscodeIntegrationTestBase {

	static final Path WORK_DIR = createWorkDir();

	@MockitoBean
	protected StorageService storageService;

	@MockitoBean
	protected FfmpegCommandRunner ffmpegCommandRunner;

	@Autowired
	protected TrackRepository trackRepository;

	@Autowired
	protected UserRepository userRepository;

	@Autowired
	protected PlaybackLogRepository playbackLogRepository;

	@Autowired
	protected TransactionTemplate transactionTemplate;

	@Autowired
	protected TranscodeTaskDispatcher dispatcher;

	@Autowired
	@Qualifier("transcodeExecutor")
	protected ThreadPoolTaskExecutor transcodeExecutor;

	/** 테스트가 만든 트랙 ID (정리 대상). */
	protected final List<UUID> createdTrackIds = new ArrayList<>();

	/** 테스트가 만든 회원 ID (정리 대상). */
	protected final List<Long> createdUserIds = new ArrayList<>();

	@DynamicPropertySource
	static void registerWorkDir(DynamicPropertyRegistry registry) {
		registry.add("app.audio.work-dir", WORK_DIR::toString);
	}

	private static Path createWorkDir() {
		try {
			Path dir = Files.createTempDirectory("t45-work-");
			dir.toFile().deleteOnExit();
			return dir;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** 하위 클래스가 블록 중인 Mock 스레드를 풀어야 할 때 재정의한다. */
	protected void releaseBlockedThreads() {
	}

	@AfterEach
	void cleanUpAfterEach() {
		releaseBlockedThreads();
		waitUntilExecutorIdle();
		// 재생 이력은 Track/User를 FK로 참조하므로 먼저 지운다(ON DELETE CASCADE 없음)
		playbackLogRepository.deleteAll();
		trackRepository.deleteAllById(createdTrackIds);
		userRepository.deleteAllById(createdUserIds);
		createdTrackIds.clear();
		createdUserIds.clear();
	}

	/** 변환 executor의 실행 중 작업과 대기 큐가 모두 비었음을 기다린다. */
	protected void waitUntilExecutorIdle() {
		ThreadPoolExecutor pool = transcodeExecutor.getThreadPoolExecutor();
		Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(20))
				.until(() -> pool.getActiveCount() == 0 && pool.getQueue().isEmpty());
	}

	/** FFmpeg Mock이 index.m3u8을 만들고 성공을 반환하게 한다(진입 시 hook 실행). */
	protected void stubFfmpegSuccess(Runnable onEntered) {
		when(ffmpegCommandRunner.transcodeToHls(any(), any())).thenAnswer(invocation -> {
			onEntered.run();
			Path outputDir = invocation.getArgument(1);
			Files.writeString(outputDir.resolve(FfmpegCommandBuilder.PLAYLIST_FILE_NAME), "#EXTM3U");
			return FfmpegResult.success("ok");
		});
	}

	protected UUID savePendingTrack() {
		UUID trackId = UUID.randomUUID();
		trackRepository.save(buildPendingTrack(trackId));
		createdTrackIds.add(trackId);
		return trackId;
	}

	/** 재생 가능한 READY 트랙을 저장한다. */
	protected UUID saveReadyTrack() {
		UUID trackId = UUID.randomUUID();
		Track track = buildPendingTrack(trackId);
		track.markReady("stream/" + trackId + "/index.m3u8");
		trackRepository.save(track);
		createdTrackIds.add(trackId);
		return trackId;
	}

	protected Track buildPendingTrack(UUID trackId) {
		return Track.builder()
				.trackId(trackId).title("t").durationSeconds(1).originalFilename("a.mp3")
				.fileExtension("mp3").fileSizeBytes(1L).mimeType("audio/mpeg")
				.originalS3Key(originalKeyOf(trackId)).build();
	}

	protected String originalKeyOf(UUID trackId) {
		return "original/" + trackId + ".mp3";
	}

	protected TranscodeEventDto eventOf(UUID trackId) {
		return new TranscodeEventDto(trackId, originalKeyOf(trackId));
	}

	protected void waitForStatus(UUID trackId, TrackStatus expected) {
		Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
				.until(() -> trackRepository.findById(trackId)
						.map(track -> track.getStatus() == expected).orElse(false));
	}
}

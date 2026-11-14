package com.streaming.music.service;

import com.streaming.music.global.config.AsyncConfig;
import com.streaming.music.global.properties.AudioProperties;
import com.streaming.music.dto.event.TranscodeEventDto;
import com.streaming.music.entity.Track;
import com.streaming.music.entity.TrackStatus;
import com.streaming.music.repository.TrackRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;

/**
 * 비동기 HLS 변환 파이프라인 (PRD-05 §6.3).
 * <p>
 * 흐름: PROCESSING 커밋 → 원본 다운로드 → FFmpeg → {@code hls/{uuid}} 재귀 업로드 → READY 커밋.
 * 다운로드·FFmpeg·업로드는 트랜잭션 밖에서 수행하고, 상태 갱신만 짧은 {@code REQUIRES_NEW} 트랜잭션으로 커밋한다.
 * PROCESSING 전이는 FFmpeg 시작 전에 커밋된다.
 * <p>
 * 성공이 아닌 결과(FAILED/TIMEOUT)나 예외는 FAILED로 기록하고, 임시 디렉터리는 항상 삭제한다.
 * 예외 경로의 errorMessage에는 민감 정보(스토리지 키·경로) 노출을 피하려 예외 클래스명만 기록한다(상세는 서버 로그).
 * 인터럽트 플래그가 선 채로 FAILED 기록을 시도하는 경우는 최선 노력이며 실패하면 로그만 남긴다.
 * FAILED 메시지에는 입력·출력 경로가 섞인 FFmpeg 로그가 들어갈 수 있으므로 API 응답(TrackResponse)에 노출하지 않는다.
 * <p>
 * MVP 한계: PENDING 가드는 같은 트랜잭션 안에서 읽은 상태 기준이며 낙관적 락이 없어,
 * 다중 노드가 같은 트랙을 동시에 처리하는 경합은 막지 못한다.
 */
@Slf4j
@Service
public class HlsTranscodeService implements TranscodeProcessor {

	private static final String INPUT_FILE_PREFIX = "input.";
	private static final String HLS_DIR_NAME = "hls";
	private static final String HLS_KEY_PREFIX = "hls/";
	public static final String FFMPEG_TIMEOUT_MESSAGE = "FFMPEG_TIMEOUT";

	private final TrackRepository trackRepository;
	private final StorageService storageService;
	private final FfmpegCommandRunner ffmpegCommandRunner;
	private final AudioProperties audioProperties;
	private final TransactionTemplate requiresNewTemplate;

	public HlsTranscodeService(TrackRepository trackRepository,
			StorageService storageService,
			FfmpegCommandRunner ffmpegCommandRunner,
			AudioProperties audioProperties,
			PlatformTransactionManager transactionManager) {
		this.trackRepository = trackRepository;
		this.storageService = storageService;
		this.ffmpegCommandRunner = ffmpegCommandRunner;
		this.audioProperties = audioProperties;
		this.requiresNewTemplate = new TransactionTemplate(transactionManager);
		this.requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Override
	@Async(AsyncConfig.TRANSCODE_EXECUTOR)
	public void process(TranscodeEventDto dto) {
		UUID trackId = dto.trackId();
		Optional<String> extension = claimProcessing(dto);
		if (extension.isEmpty()) {
			return;
		}
		try {
			Path trackDir = Paths.get(audioProperties.workDir()).resolve(trackId.toString());
			Path inputFile = trackDir.resolve(INPUT_FILE_PREFIX + extension.get());
			Path hlsDir = trackDir.resolve(HLS_DIR_NAME);

			downloadOriginal(dto.originalS3Key(), inputFile);
			Files.createDirectories(hlsDir);

			FfmpegResult result = ffmpegCommandRunner.transcodeToHls(inputFile, hlsDir);
			if (!result.isSuccess()) {
				recordFailure(trackId, resolveFailureMessage(result));
				return;
			}

			String keyPrefix = HLS_KEY_PREFIX + trackId;
			storageService.uploadDirectoryRecursively(hlsDir, keyPrefix);
			markReady(trackId, keyPrefix + "/" + FfmpegCommandBuilder.PLAYLIST_FILE_NAME);
		} catch (Exception e) {
			log.error("HLS 변환 중 예외가 발생했습니다. trackId={}", trackId, e);
			recordFailure(trackId, e.getClass().getSimpleName());
		} finally {
			cleanupWorkDir(trackId);
		}
	}

	/** PENDING 트랙만 PROCESSING으로 전이하고 커밋한다. 처리 대상이 아니면 빈 값을 반환한다. */
	private Optional<String> claimProcessing(TranscodeEventDto dto) {
		try {
			return requiresNewTemplate.execute(status -> {
				Optional<Track> found = trackRepository.findById(dto.trackId());
				if (found.isEmpty()) {
					log.warn("변환 대상 트랙이 없습니다. trackId={}", dto.trackId());
					return Optional.<String>empty();
				}
				Track track = found.get();
				if (track.getStatus() != TrackStatus.PENDING) {
					log.warn("PENDING이 아닌 트랙의 변환 이벤트를 무시합니다. trackId={}, status={}",
							dto.trackId(), track.getStatus());
					return Optional.<String>empty();
				}
				if (!dto.originalS3Key().equals(track.getOriginalS3Key())) {
					log.warn("이벤트의 원본 키가 DB와 다릅니다. 이벤트 키를 사용합니다. trackId={}", dto.trackId());
				}
				String extension = track.getFileExtension();
				track.markProcessing();
				trackRepository.save(track);
				return Optional.of(extension);
			});
		} catch (Exception e) {
			log.error("PROCESSING 전이에 실패했습니다. trackId={}", dto.trackId(), e);
			return Optional.empty();
		}
	}

	private void downloadOriginal(String originalS3Key, Path inputFile) throws IOException {
		Files.createDirectories(inputFile.getParent());
		storageService.downloadObject(originalS3Key, inputFile);
	}

	private String resolveFailureMessage(FfmpegResult result) {
		if (result.status() == FfmpegResult.Status.TIMEOUT) {
			return FFMPEG_TIMEOUT_MESSAGE;
		}
		return result.logSummary();
	}

	private void markReady(UUID trackId, String hlsPlaylistKey) {
		requiresNewTemplate.executeWithoutResult(status -> {
			Track track = findTrack(trackId);
			track.markReady(hlsPlaylistKey);
			trackRepository.save(track);
		});
	}

	/** FAILED 기록. 갱신 실패는 로그만 남기고 삼킨다. */
	private void recordFailure(UUID trackId, String message) {
		try {
			updateTrack(trackId, track -> track.markFailed(message));
		} catch (Exception e) {
			log.error("FAILED 갱신에 실패했습니다. trackId={}", trackId, e);
		}
	}

	private void updateTrack(UUID trackId, Consumer<Track> mutation) {
		requiresNewTemplate.executeWithoutResult(status -> {
			Track track = findTrack(trackId);
			mutation.accept(track);
			trackRepository.save(track);
		});
	}

	private Track findTrack(UUID trackId) {
		return trackRepository.findById(trackId)
				.orElseThrow(() -> new IllegalStateException("트랙이 사라졌습니다. trackId=" + trackId));
	}

	/** 작업 디렉터리 전체 삭제. 실패해도 원래 결과를 가리지 않는다. */
	private void cleanupWorkDir(UUID trackId) {
		try {
			deleteWorkDir(Paths.get(audioProperties.workDir()).resolve(trackId.toString()));
		} catch (Exception e) {
			log.warn("임시 작업 디렉터리 삭제에 실패했습니다. trackId={}", trackId, e);
		}
	}

	/** 디렉터리 재귀 삭제. 정리 실패를 테스트에서 재현할 수 있도록 package-private으로 분리했다. */
	void deleteWorkDir(Path dir) throws IOException {
		FileSystemUtils.deleteRecursively(dir);
	}
}

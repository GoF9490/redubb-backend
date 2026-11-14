package com.streaming.music.service;

import com.streaming.music.global.properties.AudioProperties;
import com.streaming.music.global.properties.StorageProperties;
import com.streaming.music.dto.response.DownloadInfo;
import com.streaming.music.dto.event.TranscodeEventDto;
import com.streaming.music.dto.request.TrackUploadRequest;
import com.streaming.music.dto.response.StreamPlayResponse;
import com.streaming.music.playback.entity.ActionType;
import com.streaming.music.entity.Track;
import com.streaming.music.entity.TrackStatus;
import com.streaming.music.user.entity.User;
import com.streaming.music.playback.service.PlaybackLogService;
import com.streaming.music.global.exception.InvalidArtworkException;
import com.streaming.music.global.exception.InvalidRequestException;
import com.streaming.music.global.exception.TrackNotFoundException;
import com.streaming.music.global.exception.TrackNotReadyException;
import com.streaming.music.global.util.LikePatternEscaper;
import com.streaming.music.global.util.ContentDispositionBuilder;
import com.streaming.music.global.util.InternalRedirectPathConverter;
import com.streaming.music.repository.TrackRepository;
import com.streaming.music.user.repository.UserRepository;
import com.streaming.music.service.AudioMetadataValidator.AudioMetadata;
import com.streaming.music.service.AudioMetadataValidator.AudioValidationResult;
import com.streaming.music.service.AudioMetadataValidator.MetadataOverrides;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 음원 업로드 동기 파이프라인 (PRD-05 §6.2).
 *
 * <p><b>트랜잭션 경계:</b> 스토리지 업로드는 DB 트랜잭션 밖에서 수행하고, 엔티티 저장과 변환 이벤트 발행만
 * {@link TransactionTemplate}으로 묶는다(수신부는 AFTER_COMMIT). 어노테이션을 쓰지 않으므로
 * 같은 클래스 내부 호출(self-invocation)에서도 트랜잭션이 적용된다.
 *
 * <p><b>길이 제한 정책:</b> 사용자가 request로 직접 보낸 title(200)/artist(150)/album(200)이 한도를 넘으면
 * 조용히 바꾸지 않고 {@link InvalidRequestException}(400)으로 거부한다(공백만 있는 값은 "비어 있음"이라 통과).
 * 태그·파일명에서 파생된 최종 값이 한도를 넘으면 사용자 입력이 아니므로 한도까지 절단해 저장한다.
 * originalFilename(255)도 절단한다.
 *
 * <p><b>보상 처리:</b> 스토리지 업로드 이후 구간(앨범아트 업로드, 저장, 이벤트 발행, 커밋)에서
 * {@link RuntimeException}이 나면 이미 올라간 원본·앨범아트를 삭제하고 원래 예외를 그대로 던진다.
 * 한계: TransactionTemplate.execute가 커밋 직후 연결 오류 등으로 예외를 던지면 DB 커밋 여부가 불확실한데도
 * 보상 삭제가 실행될 수 있다(극히 드문 경우, MVP 한계). 이 경우 DB에 PENDING 행이 남고 객체는 삭제되어
 * T4-2의 변환이 FAILED로 끝난다. 커밋 실패 시 AFTER_COMMIT 리스너 미실행은 T4-5에서 통합 확인한다.
 *
 * <p><b>키 시간 기준:</b> 원본 키의 yyyy/mm은 주입된 {@link Clock}(운영은 UTC) 기준이다.
 */
@Slf4j
@Service
public class TrackService {

	static final int TITLE_MAX_LENGTH = 200;
	static final int ARTIST_MAX_LENGTH = 150;
	static final int ALBUM_MAX_LENGTH = 200;
	static final int FILENAME_MAX_LENGTH = 255;
	static final String UNKNOWN_FILENAME = "unknown";
	static final int MAX_PAGE_SIZE = 100;
	static final int KEYWORD_MAX_LENGTH = 200;

	private final StorageService storageService;
	private final AudioMetadataValidator audioMetadataValidator;
	private final TranscodeTaskDispatcher transcodeTaskDispatcher;
	private final TrackRepository trackRepository;
	private final UserRepository userRepository;
	private final AudioProperties audioProperties;
	private final Clock clock;
	private final TransactionTemplate transactionTemplate;
	private final MediaUrlResolver mediaUrlResolver;
	private final PlaybackLogService playbackLogService;
	private final StorageProperties storageProperties;

	public TrackService(StorageService storageService,
						AudioMetadataValidator audioMetadataValidator,
						TranscodeTaskDispatcher transcodeTaskDispatcher,
						TrackRepository trackRepository,
						UserRepository userRepository,
						AudioProperties audioProperties,
						Clock clock,
						PlatformTransactionManager transactionManager,
						MediaUrlResolver mediaUrlResolver,
						PlaybackLogService playbackLogService,
						StorageProperties storageProperties) {
		this.storageProperties = storageProperties;
		this.mediaUrlResolver = mediaUrlResolver;
		this.playbackLogService = playbackLogService;
		this.storageService = storageService;
		this.audioMetadataValidator = audioMetadataValidator;
		this.transcodeTaskDispatcher = transcodeTaskDispatcher;
		this.trackRepository = trackRepository;
		this.userRepository = userRepository;
		this.audioProperties = audioProperties;
		this.clock = clock;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	/**
	 * 음원을 검증·저장하고 PENDING 트랙을 만든 뒤 변환 이벤트를 발행한다.
	 *
	 * @param request 업로드 요청
	 * @param userId  인증 사용자 ID (null이거나 존재하지 않으면 익명 업로드)
	 * @return 저장된 트랙 (status=PENDING)
	 */
	public Track upload(TrackUploadRequest request, Long userId) {
		validateRequest(request);

		UUID trackId = UUID.randomUUID();
		Path workDir = Paths.get(audioProperties.workDir());
		Path uploadTempFile = workDir.resolve(trackId + "_raw.upload");
		Path rawFile = null;
		try {
			Files.createDirectories(workDir);
			MultipartFile file = request.file();
			file.transferTo(uploadTempFile);

			// Tika 감지 확장자를 알아야 최종 파일명이 정해지므로 임시 확장자로 저장 후 이름을 바꾼다
			AudioValidationResult validation = audioMetadataValidator.validateAudio(uploadTempFile);
			String extension = validation.extension();
			String mimeType = validation.mimeType();
			rawFile = workDir.resolve(trackId + "_raw." + extension);
			Files.move(uploadTempFile, rawFile, StandardCopyOption.REPLACE_EXISTING);

			String originalFilename = sanitizeFilename(file.getOriginalFilename());
			AudioMetadata metadata = audioMetadataValidator.extractMetadata(rawFile, originalFilename,
					new MetadataOverrides(request.title(), request.artist(), request.album()));

			// 앨범아트 검증은 스토리지 업로드 이전에 끝내 검증 실패 시 부작용을 없앤다
			byte[] artworkBytes = null;
			String artworkMimeType = null;
			MultipartFile customArtwork = request.artworkFile();
			if (customArtwork != null && !customArtwork.isEmpty()) {
				artworkBytes = customArtwork.getBytes();
				artworkMimeType = audioMetadataValidator.detectArtworkMimeType(artworkBytes);
			} else if (metadata.embeddedArtwork() != null) {
				try {
					artworkMimeType = audioMetadataValidator.detectArtworkMimeType(metadata.embeddedArtwork());
					artworkBytes = metadata.embeddedArtwork();
				} catch (InvalidArtworkException e) {
					log.warn("내장 앨범 커버가 유효하지 않아 무시합니다. trackId={}", trackId);
				}
			}

			String originalKey = buildOriginalKey(trackId, extension);
			String artworkKey = artworkBytes != null ? "artwork/" + trackId + ".jpg" : null;

			// 보상 구간 밖에서 미리 계산해 업로드 이후에는 IOException이 발생하지 않게 한다
			long fileSizeBytes = Files.size(rawFile);

			// 실제 업로드에 성공한 키만 담으므로 보상 삭제에서 null 키가 호출되지 않는다
			List<String> uploadedKeys = new ArrayList<>();
			try {
				storageService.uploadFile(originalKey, rawFile, mimeType);
				uploadedKeys.add(originalKey);
				if (artworkKey != null) {
					storageService.uploadBytes(artworkKey, artworkBytes, artworkMimeType);
					uploadedKeys.add(artworkKey);
				}

				return saveTrackAndDispatch(trackId, userId, metadata, originalFilename, extension, mimeType,
						fileSizeBytes, originalKey, artworkKey);
			} catch (RuntimeException e) {
				compensateUploads(uploadedKeys, e);
				throw e;
			}
		} catch (IOException e) {
			throw new UncheckedIOException("업로드 파일 처리에 실패했습니다.", e);
		} finally {
			deleteQuietly(uploadTempFile);
			deleteQuietly(rawFile);
		}
	}

	/**
	 * 트랙 목록을 최신순(createdAt DESC)으로 조회한다.
	 *
	 * <p>size가 100을 넘으면 100으로 보정하고, page &lt; 0 또는 size &lt; 1이면 400으로 거부한다.
	 * keyword는 trim 후 200자를 넘으면 400이며, 비어 있으면 전체 조회로 처리한다.
	 */
	@Transactional(readOnly = true)
	public Page<Track> getTracks(String keyword, TrackStatus status, int page, int size) {
		if (page < 0) {
			throw new InvalidRequestException("page는 0 이상이어야 합니다.");
		}
		if (size < 1) {
			throw new InvalidRequestException("size는 1 이상이어야 합니다.");
		}
		String trimmedKeyword = keyword == null ? "" : keyword.trim();
		if (trimmedKeyword.length() > KEYWORD_MAX_LENGTH) {
			throw new InvalidRequestException("keyword는 " + KEYWORD_MAX_LENGTH + "자를 초과할 수 없습니다.");
		}

		Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
		if (trimmedKeyword.isEmpty()) {
			return status == null
					? trackRepository.findAllByOrderByCreatedAtDesc(pageable)
					: trackRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
		}
		String pattern = LikePatternEscaper.toContainsPattern(trimmedKeyword);
		return status == null
				? trackRepository.searchByKeyword(pattern, pageable)
				: trackRepository.searchByKeywordAndStatus(pattern, status, pageable);
	}

	/** 트랙 단건을 조회한다. 모든 status를 허용하며 없으면 {@link TrackNotFoundException}. */
	@Transactional(readOnly = true)
	public Track getTrack(UUID trackId) {
		return trackRepository.findById(trackId)
				.orElseThrow(() -> new TrackNotFoundException("트랙을 찾을 수 없습니다. trackId=" + trackId));
	}

	/**
	 * 재생 진입점 정보를 반환하고 STREAM 이력을 기록한다 (PRD-05 §7.1, §7.1.3).
	 *
	 * <p>순서: 조회(없으면 404) → READY 검증(아니면 409) → 응답 조립 → 이력 기록(마지막).
	 * 실패 경로에서는 이력을 기록하지 않는다.
	 *
	 * <p><b>트랜잭션:</b> 이 메서드에는 {@code @Transactional}을 두지 않는다. 조회는 repository 트랜잭션에서
	 * 수행되고, 이력 호출은 어떤 트랜잭션에도 속하지 않는다(요청당 커넥션 동시 점유 방지).
	 * 응답에 쓰는 Track 필드는 모두 스칼라이며 uploader(LAZY)는 접근하지 않으므로 detached여도 안전하다.
	 * 이력 저장 실패는 {@link PlaybackLogService}가 삼키므로 응답에 영향이 없다.
	 *
	 * @param userId 인증 사용자 ID (비로그인이면 null)
	 */
	public StreamPlayResponse getPlayInfo(UUID trackId, Long userId) {
		Track track = getTrack(trackId);
		if (track.getStatus() != TrackStatus.READY) {
			throw new TrackNotReadyException();
		}
		if (track.getHlsPlaylistKey() == null) {
			log.warn("READY 트랙에 HLS 플레이리스트 키가 없습니다. trackId={}", trackId);
			throw new TrackNotReadyException();
		}
		StreamPlayResponse response = StreamPlayResponse.from(track, mediaUrlResolver);
		playbackLogService.recordLog(track, userId, ActionType.STREAM);
		return response;
	}

	/**
	 * 원본 다운로드 지시 정보를 반환하고 DOWNLOAD 이력을 기록한다 (PRD-05 §7.1, §7.1.2, §7.1.3).
	 *
	 * <p>순서: 조회(없으면 404) → presigned URL 생성 → 내부 경로 변환 → 헤더 조립 → 이력 기록(마지막).
	 * 원본은 PENDING부터 존재하므로 status와 무관하게 허용한다. 실패 경로(404, presign 실패, URL 형식 오류)에서는
	 * 이력을 기록하지 않는다. {@code @Transactional}을 두지 않는 이유는 {@link #getPlayInfo}와 같다.
	 * presigned URL·redirect 값에는 서명이 있으므로 로그에 남기지 않는다.
	 *
	 * @param userId 인증 사용자 ID (비로그인이면 null)
	 * @throws IllegalStateException presigned URL이 path-style 전제를 벗어난 경우(500)
	 */
	public DownloadInfo getDownloadInfo(UUID trackId, Long userId) {
		Track track = getTrack(trackId);
		String presignedUrl = storageService.createPresignedGetUrl(
				track.getOriginalS3Key(), storageProperties.presignedExpirySeconds());
		String redirectUri;
		try {
			redirectUri = InternalRedirectPathConverter.toInternalRedirectUri(
					presignedUrl, storageProperties.bucketName());
		} catch (IllegalStateException e) {
			throw new IllegalStateException("다운로드 경로 변환에 실패했습니다. trackId=" + trackId, e);
		}
		DownloadInfo info = new DownloadInfo(
				redirectUri,
				ContentDispositionBuilder.build(track.getOriginalFilename(), trackId, track.getFileExtension()),
				track.getMimeType());
		playbackLogService.recordLog(track, userId, ActionType.DOWNLOAD);
		return info;
	}

	private Track saveTrackAndDispatch(UUID trackId, Long userId, AudioMetadata metadata, String originalFilename,
			String extension, String mimeType, long fileSizeBytes,
			String originalKey, String artworkKey) {
		User uploader = userId != null ? userRepository.findById(userId).orElse(null) : null;
		Instant now = Instant.now(clock);
		Track track = Track.builder()
				.trackId(trackId)
				.uploader(uploader)
				.title(truncate(metadata.title(), TITLE_MAX_LENGTH))
				.artist(truncate(metadata.artist(), ARTIST_MAX_LENGTH))
				.album(truncate(metadata.album(), ALBUM_MAX_LENGTH))
				.durationSeconds(metadata.durationSeconds())
				.originalFilename(originalFilename)
				.fileExtension(extension)
				.fileSizeBytes(fileSizeBytes)
				.mimeType(mimeType)
				.originalS3Key(originalKey)
				.artworkS3Key(artworkKey)
				.status(TrackStatus.PENDING)
				.createdAt(now)
				.updatedAt(now)
				.build();

		return transactionTemplate.execute(status -> {
			Track saved = trackRepository.save(track);
			transcodeTaskDispatcher.dispatch(new TranscodeEventDto(saved.getTrackId(), originalKey));
			return saved;
		});
	}

	/** 이미 올라간 객체를 삭제한다. 삭제 실패는 로그(key만)와 suppressed로만 남겨 원래 예외를 가리지 않는다. */
	private void compensateUploads(List<String> uploadedKeys, RuntimeException cause) {
		for (String key : uploadedKeys) {
			try {
				storageService.deleteObject(key);
			} catch (RuntimeException deleteException) {
				log.error("보상 삭제에 실패했습니다. key={}", key);
				cause.addSuppressed(deleteException);
			}
		}
	}

	private void validateRequest(TrackUploadRequest request) {
		if (request == null || request.file() == null) {
			throw new InvalidRequestException("음원 파일(file)은 필수입니다.");
		}
		checkLength("title", request.title(), TITLE_MAX_LENGTH);
		checkLength("artist", request.artist(), ARTIST_MAX_LENGTH);
		checkLength("album", request.album(), ALBUM_MAX_LENGTH);
	}

	private void checkLength(String fieldName, String value, int maxLength) {
		if (value != null && !value.isBlank() && value.length() > maxLength) {
			throw new InvalidRequestException(fieldName + "은(는) " + maxLength + "자를 초과할 수 없습니다.");
		}
	}

	/** 경로 구분자 앞부분을 제거하고(IE 전체 경로 대응) 길이를 절단한다. null/빈 값은 "unknown". */
	private String sanitizeFilename(String filename) {
		if (filename == null) {
			return UNKNOWN_FILENAME;
		}
		String name = filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1);
		if (name.isBlank()) {
			return UNKNOWN_FILENAME;
		}
		return truncate(name, FILENAME_MAX_LENGTH);
	}

	private String buildOriginalKey(UUID trackId, String extension) {
		LocalDate today = LocalDate.now(clock);
		return String.format("original/%d/%02d/%s.%s", today.getYear(), today.getMonthValue(), trackId, extension);
	}

	private String truncate(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength);
	}

	/** 임시 파일 삭제 실패가 원래 예외를 가리지 않도록 로그만 남긴다. */
	private void deleteQuietly(Path path) {
		if (path == null) {
			return;
		}
		try {
			Files.deleteIfExists(path);
		} catch (IOException | RuntimeException e) {
			log.warn("임시 파일 삭제에 실패했습니다: {}", path.getFileName(), e);
		}
	}
}

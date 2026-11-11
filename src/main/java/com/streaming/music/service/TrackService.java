package com.streaming.music.service;

import com.streaming.music.config.properties.AudioProperties;
import com.streaming.music.dto.event.TranscodeEventDto;
import com.streaming.music.dto.request.TrackUploadRequest;
import com.streaming.music.entity.Track;
import com.streaming.music.entity.TrackStatus;
import com.streaming.music.entity.User;
import com.streaming.music.exception.InvalidArtworkException;
import com.streaming.music.exception.InvalidRequestException;
import com.streaming.music.repository.TrackRepository;
import com.streaming.music.repository.UserRepository;
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
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
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

    private final StorageService storageService;
    private final AudioMetadataValidator audioMetadataValidator;
    private final TranscodeTaskDispatcher transcodeTaskDispatcher;
    private final TrackRepository trackRepository;
    private final UserRepository userRepository;
    private final AudioProperties audioProperties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public TrackService(StorageService storageService,
                        AudioMetadataValidator audioMetadataValidator,
                        TranscodeTaskDispatcher transcodeTaskDispatcher,
                        TrackRepository trackRepository,
                        UserRepository userRepository,
                        AudioProperties audioProperties,
                        Clock clock,
                        PlatformTransactionManager transactionManager) {
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

            storageService.uploadFile(originalKey, rawFile, mimeType);
            if (artworkKey != null) {
                storageService.uploadBytes(artworkKey, artworkBytes, artworkMimeType);
            }

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
                    .fileSizeBytes(Files.size(rawFile))
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
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 파일 처리에 실패했습니다.", e);
        } finally {
            deleteQuietly(uploadTempFile);
            deleteQuietly(rawFile);
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

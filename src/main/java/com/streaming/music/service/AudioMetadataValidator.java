package com.streaming.music.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.tika.Tika;
import org.apache.tika.io.TikaInputStream;
import org.springframework.stereotype.Component;

import com.streaming.music.config.properties.AudioProperties;
import com.streaming.music.exception.InvalidArtworkException;
import com.streaming.music.exception.InvalidAudioFormatException;

/**
 * 업로드 음원/앨범자켓 검증기 (PRD-05 §6.1).
 * <p>
 * 이 클래스는 파일을 삭제하지 않는다. 검증 실패 시 임시 파일 삭제는
 * 호출자({@code TrackService})의 finally 책임이다.
 */
@Component
public class AudioMetadataValidator {

    /** 허용 오디오 MIME → 저장 확장자 정규화 매핑 (허용 목록의 단일 기준). */
    private static final Map<String, String> MIME_TO_EXTENSION = Map.of(
            "audio/mpeg", "mp3",
            "audio/wav", "wav",
            "audio/x-wav", "wav",
            "audio/vnd.wave", "wav",
            "audio/flac", "flac",
            "audio/x-flac", "flac",
            "audio/mp4", "m4a"
    );

    /** 허용 앨범자켓 MIME. */
    private static final Set<String> ALLOWED_ARTWORK_MIMES = Set.of("image/jpeg", "image/png");

    /** Tika 파사드는 detect에 대해 스레드 안전하므로 재사용한다. */
    private final Tika tika = new Tika();

    private final AudioProperties audioProperties;

    public AudioMetadataValidator(AudioProperties audioProperties) {
        this.audioProperties = audioProperties;
    }

    /** 음원 검증 결과 (Tika 감지 MIME과 정규화된 저장 확장자). */
    public record AudioValidationResult(String mimeType, String extension) {
    }

    /**
     * 임시 파일의 실제 내용(매직 넘버) 기반 MIME을 검증한다.
     * 파일명 힌트는 사용하지 않아 확장자 위변조가 통하지 않는다.
     * 임시 파일 삭제는 호출자의 책임이다.
     *
     * @throws InvalidAudioFormatException 허용되지 않는 MIME이거나 읽기에 실패한 경우
     */
    public AudioValidationResult validateAudio(Path tempFile) {
        String detectedMime;
        try (InputStream inputStream = TikaInputStream.get(tempFile)) {
            detectedMime = tika.detect(inputStream);
        } catch (IOException e) {
            throw new InvalidAudioFormatException("음원 파일을 읽을 수 없습니다.", e);
        }
        String normalizedMime = normalizeMime(detectedMime);
        String extension = MIME_TO_EXTENSION.get(normalizedMime);
        if (extension == null) {
            throw new InvalidAudioFormatException("허용되지 않는 오디오 형식입니다: " + detectedMime);
        }
        return new AudioValidationResult(normalizedMime, extension);
    }

    /**
     * 앨범자켓을 검증하고 감지된 MIME(image/jpeg 또는 image/png)을 반환한다.
     * 검증 순서: 빈 값 → 크기 → MIME.
     *
     * @throws InvalidArtworkException 비어 있거나 크기 초과이거나 JPEG/PNG가 아닌 경우
     */
    public String detectArtworkMimeType(byte[] artworkBytes) {
        if (artworkBytes == null || artworkBytes.length == 0) {
            throw new InvalidArtworkException("앨범자켓이 비어 있습니다.");
        }
        if (artworkBytes.length > audioProperties.maxArtworkSizeBytes()) {
            throw new InvalidArtworkException("앨범자켓 크기가 제한을 초과했습니다.");
        }
        String normalizedMime = normalizeMime(tika.detect(artworkBytes));
        if (!ALLOWED_ARTWORK_MIMES.contains(normalizedMime)) {
            throw new InvalidArtworkException("앨범자켓은 JPEG/PNG만 허용됩니다: " + normalizedMime);
        }
        return normalizedMime;
    }

    /**
     * MIME을 저장 확장자로 변환한다.
     *
     * @throws InvalidAudioFormatException 허용 목록에 없는 MIME인 경우
     */
    static String resolveExtension(String mimeType) {
        String extension = MIME_TO_EXTENSION.get(normalizeMime(mimeType));
        if (extension == null) {
            throw new InvalidAudioFormatException("허용되지 않는 오디오 형식입니다: " + mimeType);
        }
        return extension;
    }

    /** 파라미터(;charset 등)를 제거하고 소문자로 정규화한다. */
    private static String normalizeMime(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        int paramIndex = mimeType.indexOf(';');
        String base = paramIndex >= 0 ? mimeType.substring(0, paramIndex) : mimeType;
        return base.trim().toLowerCase(Locale.ROOT);
    }
}

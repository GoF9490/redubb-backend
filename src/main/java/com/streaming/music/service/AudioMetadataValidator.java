package com.streaming.music.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.tika.Tika;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException;
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagException;
import org.jaudiotagger.tag.images.Artwork;
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

    static final String FALLBACK_TITLE = "Unknown Title";
    static final String FALLBACK_ARTIST = "Unknown Artist";
    private static final int MIN_DURATION_SECONDS = 1;

    /** 강한 참조로 보관해 GC 후 로그 레벨 설정이 사라지는 것을 막는다. */
    private static final Logger JAUDIOTAGGER_LOGGER = Logger.getLogger("org.jaudiotagger");

    static {
        // JAudioTagger의 과도한 java.util.logging 출력 억제 (실패 원인은 예외 cause로 보존한다)
        JAUDIOTAGGER_LOGGER.setLevel(Level.OFF);
    }

    /** Tika 파사드는 detect에 대해 스레드 안전하므로 재사용한다. */
    private final Tika tika = new Tika();

    private final AudioProperties audioProperties;

    public AudioMetadataValidator(AudioProperties audioProperties) {
        this.audioProperties = audioProperties;
    }

    /** 음원 검증 결과 (Tika 감지 MIME과 정규화된 저장 확장자). */
    public record AudioValidationResult(String mimeType, String extension) {
    }

    /** 업로드 요청에서 사용자가 확인·수정한 텍스트 값 (null 또는 공백이면 비어 있음으로 취급). */
    public record MetadataOverrides(String title, String artist, String album) {
    }

    /**
     * 추출·보정이 끝난 음원 메타데이터.
     *
     * @param embeddedArtwork 음원에 내장된 앨범 커버 바이트. 내장 커버가 없으면 {@code null}
     */
    public record AudioMetadata(String title, String artist, String album,
                                int durationSeconds, byte[] embeddedArtwork) {
    }

    /**
     * JAudioTagger로 재생 시간·태그·내장 커버를 추출하고 Fallback 규칙을 적용한다 (PRD-05 §6.1 3번).
     * <ul>
     *   <li>제목: request &gt; 태그 &gt; 원본 파일명(확장자 제외) &gt; "Unknown Title"</li>
     *   <li>가수: request &gt; 태그 &gt; "Unknown Artist"</li>
     *   <li>앨범: request &gt; 태그 &gt; null</li>
     *   <li>재생 시간: 1초 미만은 1초로 보정</li>
     * </ul>
     * <p>
     * <b>호출자 계약</b>
     * <ul>
     *   <li>JAudioTagger는 확장자로 리더를 선택하므로 임시 파일은 반드시 Tika 감지 확장자
     *       ({@link AudioValidationResult#extension()})로 만들어야 한다.</li>
     *   <li>임시 파일 삭제는 호출자({@code TrackService})의 finally 책임이다.</li>
     *   <li>제목/가수 길이 제한(200/150자) 처리는 호출자 소관이며 여기서는 자르지 않는다.</li>
     *   <li>내장 커버는 검증 없이 바이트만 반환한다. 호출자(T3-2)는 {@link #detectArtworkMimeType(byte[])}로
     *       검증하고, {@link InvalidArtworkException}이 발생하면 내장 커버를 null로 무시하고 업로드를 계속한다
     *       (내장 커버는 사용자 입력이 아니므로 업로드를 거부하지 않는다).</li>
     * </ul>
     * 이 메서드는 상태 없는 읽기 전용 호출만 수행한다.
     *
     * @param originalFilename 사용자가 올린 원본 파일명 (null 가능)
     * @param overrides        request 값 (null 가능)
     * @throws InvalidAudioFormatException 헤더 파싱에 실패한 경우 (원인 예외 보존)
     */
    public AudioMetadata extractMetadata(Path tempFile, String originalFilename, MetadataOverrides overrides) {
        AudioFile audioFile;
        try {
            audioFile = AudioFileIO.read(tempFile.toFile());
        } catch (CannotReadException | IOException | TagException
                 | ReadOnlyFileException | InvalidAudioFrameException | RuntimeException e) {
            throw new InvalidAudioFormatException("음원 헤더를 읽을 수 없습니다.", e);
        }
        AudioHeader header = audioFile.getAudioHeader();
        if (header == null) {
            throw new InvalidAudioFormatException("음원 헤더를 읽을 수 없습니다.");
        }
        Tag tag = audioFile.getTag();
        MetadataOverrides request = overrides != null
                ? overrides : new MetadataOverrides(null, null, null);

        String title = firstNonBlank(request.title(), readTagField(tag, FieldKey.TITLE));
        if (title == null) {
            title = firstNonBlank(stripExtension(originalFilename));
        }
        if (title == null) {
            title = FALLBACK_TITLE;
        }
        String artist = firstNonBlank(request.artist(), readTagField(tag, FieldKey.ARTIST));
        if (artist == null) {
            artist = FALLBACK_ARTIST;
        }
        String album = firstNonBlank(request.album(), readTagField(tag, FieldKey.ALBUM));

        return new AudioMetadata(title, artist, album,
                normalizeDuration(header.getTrackLength()), readEmbeddedArtwork(tag));
    }

    /** 1초 미만(0 이하)은 DB CHECK(duration_seconds &gt; 0)를 위해 1초로 보정한다. */
    static int normalizeDuration(int trackLengthSeconds) {
        return Math.max(trackLengthSeconds, MIN_DURATION_SECONDS);
    }

    /** 태그가 없거나 필드 읽기에 실패하면 null을 반환한다. */
    private static String readTagField(Tag tag, FieldKey fieldKey) {
        if (tag == null) {
            return null;
        }
        try {
            return tag.getFirst(fieldKey);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 내장 커버 바이트를 반환한다. 없거나 비어 있으면 null. */
    private static byte[] readEmbeddedArtwork(Tag tag) {
        if (tag == null) {
            return null;
        }
        try {
            Artwork artwork = tag.getFirstArtwork();
            byte[] bytes = artwork != null ? artwork.getBinaryData() : null;
            return bytes != null && bytes.length > 0 ? bytes : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 앞에서부터 공백이 아닌 첫 값을 strip하여 반환한다. 없으면 null. */
    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.strip();
            }
        }
        return null;
    }

    /** 경로 구분자를 제거하고 마지막 '.' 이후를 잘라낸다. */
    private static String stripExtension(String filename) {
        if (filename == null) {
            return null;
        }
        String name = filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1);
        int dotIndex = name.lastIndexOf('.');
        return dotIndex > 0 ? name.substring(0, dotIndex) : name;
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

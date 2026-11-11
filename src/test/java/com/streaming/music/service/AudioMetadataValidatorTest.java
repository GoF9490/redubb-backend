package com.streaming.music.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.streaming.music.config.properties.AudioProperties;
import com.streaming.music.exception.InvalidArtworkException;
import com.streaming.music.exception.InvalidAudioFormatException;
import com.streaming.music.service.AudioMetadataValidator.AudioValidationResult;
import com.streaming.music.support.SampleFixtures;

@DisplayName("AudioMetadataValidator Tika 검증")
class AudioMetadataValidatorTest {

    private static final long MAX_ARTWORK_BYTES = 10_485_760L;

    private final AudioMetadataValidator validator = new AudioMetadataValidator(
            new AudioProperties("/tmp/audio-work", "/usr/bin/ffmpeg", 3, 6, "192k", MAX_ARTWORK_BYTES));

    @TempDir
    Path tempDir;

    private AudioValidationResult validateSample(String fileName) {
        Path tempFile = SampleFixtures.copySampleToTempFile(fileName);
        try {
            return validator.validateAudio(tempFile);
        } finally {
            SampleFixtures.deleteQuietly(tempFile);
        }
    }

    @Test
    @DisplayName("정상 MP3는 audio/mpeg, 확장자 mp3로 통과한다")
    void validateMp3() {
        AudioValidationResult result = validateSample(SampleFixtures.TAGGED_MP3);
        assertThat(result.mimeType()).isEqualTo("audio/mpeg");
        assertThat(result.extension()).isEqualTo("mp3");
    }

    @Test
    @DisplayName("정상 WAV는 wav 확장자로 통과한다")
    void validateWav() {
        AudioValidationResult result = validateSample(SampleFixtures.UNTAGGED_WAV);
        assertThat(result.mimeType()).isEqualTo("audio/vnd.wave");
        assertThat(result.extension()).isEqualTo("wav");
    }

    @Test
    @DisplayName("정상 FLAC는 flac 확장자로 통과한다")
    void validateFlac() {
        AudioValidationResult result = validateSample(SampleFixtures.FLAC);
        assertThat(result.mimeType()).isEqualTo("audio/x-flac");
        assertThat(result.extension()).isEqualTo("flac");
    }

    @Test
    @DisplayName("정상 M4A는 audio/mp4, 확장자 m4a로 통과한다")
    void validateM4a() {
        AudioValidationResult result = validateSample(SampleFixtures.M4A);
        assertThat(result.mimeType()).isEqualTo("audio/mp4");
        assertThat(result.extension()).isEqualTo("m4a");
    }

    @Test
    @DisplayName("확장자만 .mp3인 텍스트 파일은 InvalidAudioFormatException이 발생한다")
    void rejectTextFileWithMp3Extension() {
        Path tempFile = SampleFixtures.copySampleToTempFile(SampleFixtures.FAKE_AUDIO_MP3);
        try {
            assertThatThrownBy(() -> validator.validateAudio(tempFile))
                    .isInstanceOf(InvalidAudioFormatException.class);
        } finally {
            SampleFixtures.deleteQuietly(tempFile);
        }
    }

    @Test
    @DisplayName("확장자만 .mp3인 실행 파일은 InvalidAudioFormatException이 발생한다")
    void rejectFakeExecutable() throws IOException {
        Path tempFile = tempDir.resolve("evil.mp3");
        Files.write(tempFile, SampleFixtures.createFakeExecutableBytes());
        assertThatThrownBy(() -> validator.validateAudio(tempFile))
                .isInstanceOf(InvalidAudioFormatException.class);
    }

    @Test
    @DisplayName("존재하지 않는 파일은 InvalidAudioFormatException이 발생한다")
    void rejectMissingFile() {
        assertThatThrownBy(() -> validator.validateAudio(tempDir.resolve("none.mp3")))
                .isInstanceOf(InvalidAudioFormatException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "audio/mpeg, mp3",
            "audio/wav, wav",
            "audio/x-wav, wav",
            "audio/vnd.wave, wav",
            "audio/flac, flac",
            "audio/x-flac, flac",
            "audio/mp4, m4a",
            "AUDIO/MPEG, mp3",
            "'audio/mpeg; charset=binary', mp3"
    })
    @DisplayName("MIME을 저장 확장자로 정규화한다(대소문자·파라미터 무시)")
    void resolveExtension(String mimeType, String expectedExtension) {
        assertThat(AudioMetadataValidator.resolveExtension(mimeType)).isEqualTo(expectedExtension);
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "application/octet-stream", "audio/ogg", ""})
    @DisplayName("허용 목록에 없는 MIME은 정규화 시 예외가 발생한다")
    void rejectUnknownMime(String mimeType) {
        assertThatThrownBy(() -> AudioMetadataValidator.resolveExtension(mimeType))
                .isInstanceOf(InvalidAudioFormatException.class);
    }

    @Test
    @DisplayName("JPG 앨범자켓은 image/jpeg로 통과한다")
    void acceptJpgArtwork() {
        String mime = validator.detectArtworkMimeType(SampleFixtures.readSampleBytes(SampleFixtures.COVER_JPG));
        assertThat(mime).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("PNG 앨범자켓은 image/png로 통과한다")
    void acceptPngArtwork() {
        String mime = validator.detectArtworkMimeType(SampleFixtures.readSampleBytes(SampleFixtures.COVER_PNG));
        assertThat(mime).isEqualTo("image/png");
    }

    @Test
    @DisplayName("이미지가 아닌 앨범자켓은 InvalidArtworkException이 발생한다")
    void rejectNonImageArtwork() {
        byte[] bytes = SampleFixtures.readSampleBytes(SampleFixtures.FAKE_AUDIO_MP3);
        assertThatThrownBy(() -> validator.detectArtworkMimeType(bytes))
                .isInstanceOf(InvalidArtworkException.class);
    }

    @Test
    @DisplayName("10MB를 초과한 앨범자켓은 InvalidArtworkException이 발생한다")
    void rejectOversizedArtwork() {
        byte[] bytes = SampleFixtures.createOversizedImageBytes();
        assertThatThrownBy(() -> validator.detectArtworkMimeType(bytes))
                .isInstanceOf(InvalidArtworkException.class)
                .hasMessageContaining("크기");
    }

    @Test
    @DisplayName("정확히 10MB인 앨범자켓은 통과한다")
    void acceptExactMaxSizeArtwork() {
        byte[] bytes = new byte[(int) MAX_ARTWORK_BYTES];
        byte[] jpegHead = SampleFixtures.readSampleBytes(SampleFixtures.COVER_JPG);
        System.arraycopy(jpegHead, 0, bytes, 0, jpegHead.length);
        assertThat(validator.detectArtworkMimeType(bytes)).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("비어 있거나 null인 앨범자켓은 InvalidArtworkException이 발생한다")
    void rejectEmptyArtwork() {
        assertThatThrownBy(() -> validator.detectArtworkMimeType(new byte[0]))
                .isInstanceOf(InvalidArtworkException.class);
        assertThatThrownBy(() -> validator.detectArtworkMimeType(null))
                .isInstanceOf(InvalidArtworkException.class);
    }
}

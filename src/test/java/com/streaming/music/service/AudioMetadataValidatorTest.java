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
import com.streaming.music.service.AudioMetadataValidator.AudioMetadata;
import com.streaming.music.service.AudioMetadataValidator.AudioValidationResult;
import com.streaming.music.service.AudioMetadataValidator.MetadataOverrides;
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

	private static final MetadataOverrides NO_OVERRIDES = new MetadataOverrides(null, null, null);

	private AudioMetadata extractSample(String fileName, String originalFilename, MetadataOverrides overrides) {
		Path tempFile = SampleFixtures.copySampleToTempFile(fileName);
		try {
			return validator.extractMetadata(tempFile, originalFilename, overrides);
		} finally {
			SampleFixtures.deleteQuietly(tempFile);
		}
	}

	@Test
	@DisplayName("ID3 MP3에서 제목·가수·앨범·재생 시간·커버 바이트를 추출한다")
	void extractMetadataFromTaggedMp3() {
		AudioMetadata metadata = extractSample(SampleFixtures.TAGGED_MP3, "song.mp3", NO_OVERRIDES);
		assertThat(metadata.title()).isEqualTo("Sample Title");
		assertThat(metadata.artist()).isEqualTo("Sample Artist");
		assertThat(metadata.album()).isEqualTo("Sample Album");
		assertThat(metadata.durationSeconds()).isGreaterThanOrEqualTo(1);
		assertThat(metadata.embeddedArtwork()).isNotEmpty();
		assertThat(validator.detectArtworkMimeType(metadata.embeddedArtwork())).isEqualTo("image/jpeg");
	}

	@Test
	@DisplayName("무태그 WAV는 제목=파일명(확장자 제외), 가수=Unknown Artist, 앨범=null로 보정한다")
	void fallbackForUntaggedWav() {
		AudioMetadata metadata = extractSample(SampleFixtures.UNTAGGED_WAV, "My Song.final.wav", NO_OVERRIDES);
		assertThat(metadata.title()).isEqualTo("My Song.final");
		assertThat(metadata.artist()).isEqualTo("Unknown Artist");
		assertThat(metadata.album()).isNull();
		assertThat(metadata.embeddedArtwork()).isNull();
		assertThat(metadata.durationSeconds()).isGreaterThanOrEqualTo(1);
	}

	@Test
	@DisplayName("request 값이 태그보다 우선한다")
	void overridesPreferRequestValues() {
		AudioMetadata metadata = extractSample(SampleFixtures.TAGGED_MP3, "song.mp3",
				new MetadataOverrides("My Title", "My Artist", "My Album"));
		assertThat(metadata.title()).isEqualTo("My Title");
		assertThat(metadata.artist()).isEqualTo("My Artist");
		assertThat(metadata.album()).isEqualTo("My Album");
	}

	@Test
	@DisplayName("공백뿐인 request 값은 비어 있음으로 취급해 태그로 대체한다")
	void blankOverridesFallBackToTag() {
		AudioMetadata metadata = extractSample(SampleFixtures.TAGGED_MP3, "song.mp3",
				new MetadataOverrides("   ", "", "\t"));
		assertThat(metadata.title()).isEqualTo("Sample Title");
		assertThat(metadata.artist()).isEqualTo("Sample Artist");
		assertThat(metadata.album()).isEqualTo("Sample Album");
	}

	@Test
	@DisplayName("파일명·태그·request가 모두 없으면 제목은 Unknown Title이다")
	void fallbackToUnknownTitle() {
		assertThat(extractSample(SampleFixtures.UNTAGGED_WAV, null, null).title()).isEqualTo("Unknown Title");
		assertThat(extractSample(SampleFixtures.UNTAGGED_WAV, "  ", NO_OVERRIDES).title())
				.isEqualTo("Unknown Title");
	}

	@Test
	@DisplayName("FLAC와 M4A도 읽히며 태그가 없으면 Fallback이 적용된다")
	void extractMetadataFromFlacAndM4a() {
		for (String sample : new String[]{SampleFixtures.FLAC, SampleFixtures.M4A}) {
			AudioMetadata metadata = extractSample(sample, "track.bin", NO_OVERRIDES);
			assertThat(metadata.title()).as(sample).isEqualTo("track");
			assertThat(metadata.artist()).as(sample).isEqualTo("Unknown Artist");
			assertThat(metadata.durationSeconds()).as(sample).isGreaterThanOrEqualTo(1);
		}
	}

	@Test
	@DisplayName("헤더 파싱에 실패하면 원인을 보존한 InvalidAudioFormatException이 발생한다")
	void rejectBrokenAudioHeader() {
		Path tempFile = SampleFixtures.copySampleToTempFile(SampleFixtures.FAKE_AUDIO_MP3);
		try {
			assertThatThrownBy(() -> validator.extractMetadata(tempFile, "fake.mp3", NO_OVERRIDES))
					.isInstanceOf(InvalidAudioFormatException.class)
					.hasCauseInstanceOf(Exception.class);
		} finally {
			SampleFixtures.deleteQuietly(tempFile);
		}
	}

	@Test
	@DisplayName("존재하지 않는 파일은 InvalidAudioFormatException이 발생한다")
	void rejectMissingFileOnExtract() {
		assertThatThrownBy(() -> validator.extractMetadata(tempDir.resolve("none.mp3"), "none.mp3", NO_OVERRIDES))
				.isInstanceOf(InvalidAudioFormatException.class);
	}

	@ParameterizedTest
	@CsvSource({"0, 1", "1, 1", "3, 3", "-1, 1"})
	@DisplayName("재생 시간 1초 미만은 1초로 보정한다")
	void normalizeDuration(int trackLength, int expected) {
		assertThat(AudioMetadataValidator.normalizeDuration(trackLength)).isEqualTo(expected);
	}
}

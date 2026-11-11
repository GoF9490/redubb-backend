package com.streaming.music.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

@DisplayName("테스트 샘플 파일 및 헬퍼 검증")
class SampleFixtureTest {

    private static final long MAX_TOTAL_BYTES = 1024L * 1024;

    @ParameterizedTest
    @ValueSource(strings = {
            SampleFixtures.TAGGED_MP3,
            SampleFixtures.UNTAGGED_WAV,
            SampleFixtures.FLAC,
            SampleFixtures.FAKE_AUDIO_MP3,
            SampleFixtures.COVER_JPG,
            SampleFixtures.COVER_PNG
    })
    @DisplayName("샘플 파일이 존재하고 크기가 0보다 크다")
    void verifySamplesExist(String fileName) {
        assertThat(SampleFixtures.readSampleBytes(fileName)).isNotEmpty();
    }

    @Test
    @DisplayName("샘플 디렉터리 합계 크기가 1MB 이하이다")
    void verifyTotalSizeUnderOneMegabyte() throws IOException, URISyntaxException {
        Path sampleDir = Paths.get(new ClassPathResource(SampleFixtures.SAMPLE_DIR).getURI());
        long totalBytes;
        try (Stream<Path> files = Files.list(sampleDir)) {
            totalBytes = files
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("README.md"))
                    .mapToLong(path -> path.toFile().length())
                    .sum();
        }
        assertThat(totalBytes).isPositive().isLessThanOrEqualTo(MAX_TOTAL_BYTES);
    }

    @Test
    @DisplayName("가짜 mp3는 오디오 매직 넘버가 없는 텍스트이다")
    void verifyFakeAudioIsNotBinary() {
        byte[] bytes = SampleFixtures.readSampleBytes(SampleFixtures.FAKE_AUDIO_MP3);
        String text = new String(bytes, StandardCharsets.US_ASCII);
        assertThat(text).doesNotStartWith("ID3").doesNotStartWith("RIFF").doesNotStartWith("fLaC");
        assertThat(text.trim()).isNotEmpty();
    }

    @Test
    @DisplayName("태그 MP3는 ID3 헤더로 시작한다")
    void verifyTaggedMp3StartsWithId3() {
        byte[] bytes = SampleFixtures.readSampleBytes(SampleFixtures.TAGGED_MP3);
        assertThat(new String(bytes, 0, 3, StandardCharsets.US_ASCII)).isEqualTo("ID3");
    }

    @Test
    @DisplayName("WAV와 FLAC는 각각 RIFF, fLaC 헤더로 시작한다")
    void verifyWavAndFlacHeaders() {
        byte[] wav = SampleFixtures.readSampleBytes(SampleFixtures.UNTAGGED_WAV);
        byte[] flac = SampleFixtures.readSampleBytes(SampleFixtures.FLAC);
        assertThat(new String(wav, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(flac, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("fLaC");
    }

    @Test
    @DisplayName("10MB 초과 이미지 바이트는 제한보다 1바이트 크다")
    void verifyOversizedImageExceedsLimit() {
        assertThat(SampleFixtures.createOversizedImageBytes().length)
                .isEqualTo(SampleFixtures.MAX_ARTWORK_BYTES + 1);
    }

    @Test
    @DisplayName("가짜 실행 파일 바이트는 MZ 헤더로 시작한다")
    void verifyFakeExecutableStartsWithMz() {
        byte[] bytes = SampleFixtures.createFakeExecutableBytes();
        assertThat(bytes[0]).isEqualTo((byte) 'M');
        assertThat(bytes[1]).isEqualTo((byte) 'Z');
    }

    @Test
    @DisplayName("임시 파일 복사본은 원본과 크기가 같다")
    void verifyTempFileCopyWorks() throws IOException {
        Path tempFile = SampleFixtures.copySampleToTempFile(SampleFixtures.TAGGED_MP3);
        try {
            assertThat(tempFile.toString()).endsWith(".mp3");
            assertThat(Files.size(tempFile))
                    .isEqualTo(SampleFixtures.readSampleBytes(SampleFixtures.TAGGED_MP3).length);
        } finally {
            SampleFixtures.deleteQuietly(tempFile);
        }
    }

    @Test
    @DisplayName("없는 샘플을 읽으면 IllegalStateException이 발생한다")
    void failWhenSampleMissing() {
        assertThatThrownBy(() -> SampleFixtures.readSampleBytes("no-such-file.mp3"))
                .isInstanceOf(IllegalStateException.class);
    }
}

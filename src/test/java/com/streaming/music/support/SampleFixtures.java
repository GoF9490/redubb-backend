package com.streaming.music.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.springframework.core.io.ClassPathResource;

/**
 * 테스트 샘플 파일 로딩 및 대용량 더미 데이터 생성 헬퍼.
 * 샘플은 {@code src/test/resources/samples/}에 위치한다.
 */
public final class SampleFixtures {

    public static final String SAMPLE_DIR = "samples/";

    public static final String TAGGED_MP3 = "sample-tagged.mp3";
    public static final String UNTAGGED_WAV = "sample-untagged.wav";
    public static final String FLAC = "sample.flac";
    public static final String FAKE_AUDIO_MP3 = "fake-audio.mp3";
    public static final String COVER_JPG = "cover.jpg";
    public static final String COVER_PNG = "cover.png";

    /** 앨범자켓 최대 크기(10MB). 이 값 + 1 바이트가 초과 이미지 크기다. */
    public static final long MAX_ARTWORK_BYTES = 10L * 1024 * 1024;

    private SampleFixtures() {
    }

    /** 샘플 파일 전체를 바이트 배열로 읽는다. 없으면 IllegalStateException. */
    public static byte[] readSampleBytes(String fileName) {
        ClassPathResource resource = new ClassPathResource(SAMPLE_DIR + fileName);
        if (!resource.exists()) {
            throw new IllegalStateException("샘플 파일을 찾을 수 없습니다: " + SAMPLE_DIR + fileName);
        }
        try (InputStream inputStream = resource.getInputStream()) {
            return inputStream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 샘플을 임시 파일로 복사해 경로를 반환한다 (File 입력이 필요한 Tika/JAudioTagger용). */
    public static Path copySampleToTempFile(String fileName) {
        try {
            int dotIndex = fileName.lastIndexOf('.');
            String suffix = dotIndex >= 0 ? fileName.substring(dotIndex) : "";
            Path tempFile = Files.createTempFile("sample-", suffix);
            tempFile.toFile().deleteOnExit();
            Files.write(tempFile, readSampleBytes(fileName));
            return tempFile;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 10MB를 1바이트 초과하는 JPEG 흉내 바이트 배열을 만든다.
     * 매직 넘버(FF D8 FF E0)를 앞에 두어 MIME 검증이 아닌 크기 검증으로 걸리는지 확인할 수 있다.
     */
    public static byte[] createOversizedImageBytes() {
        byte[] bytes = new byte[(int) MAX_ARTWORK_BYTES + 1];
        byte[] jpegMagic = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        System.arraycopy(jpegMagic, 0, bytes, 0, jpegMagic.length);
        return bytes;
    }

    /** 윈도우 실행 파일(MZ 헤더)로 위장한 가짜 바이트 배열을 만든다. */
    public static byte[] createFakeExecutableBytes() {
        byte[] bytes = new byte[256];
        Arrays.fill(bytes, (byte) 0x90);
        bytes[0] = 'M';
        bytes[1] = 'Z';
        return bytes;
    }

    /** 샘플 임시 파일 정리가 필요할 때 사용한다. */
    public static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 테스트 정리 단계이므로 무시한다
        }
    }
}

package com.streaming.music.service;

import com.streaming.music.config.properties.AudioProperties;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link ProcessBuilder} 기반 FFmpeg 실행기. 출력은 별도 스레드가 소비하고(버퍼 막힘 방지),
 * 메인 스레드는 {@code waitFor(timeout)}만 수행하여 타임아웃이 읽기에 가로막히지 않는다.
 */
@Component
public class ProcessFfmpegCommandRunner implements FfmpegCommandRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessFfmpegCommandRunner.class);

    static final String START_FAILED_SUMMARY = "FFMPEG_START_FAILED";
    static final String INTERRUPTED_SUMMARY = "INTERRUPTED";
    private static final long KILL_WAIT_MILLIS = 5_000;
    private static final long READER_JOIN_MILLIS = 5_000;

    private final FfmpegCommandBuilder commandBuilder;
    private final AudioProperties audioProperties;

    public ProcessFfmpegCommandRunner(FfmpegCommandBuilder commandBuilder, AudioProperties audioProperties) {
        this.commandBuilder = commandBuilder;
        this.audioProperties = audioProperties;
    }

    @Override
    public FfmpegResult transcodeToHls(Path inputFile, Path outputDir) {
        List<String> command = commandBuilder.buildHlsCommand(inputFile, outputDir);
        return execute(command, Duration.ofMinutes(audioProperties.ffmpegTimeoutMinutes()));
    }

    /** 명령을 실행하고 결과를 반환한다(테스트에서 가짜 명령과 짧은 타임아웃을 주입). */
    FfmpegResult execute(List<String> command, Duration timeout) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            // 요약에는 경로를 넣지 않고 원인은 로그에만 남긴다
            log.error("FFmpeg 프로세스 시작 실패", e);
            return FfmpegResult.failed(-1, START_FAILED_SUMMARY);
        }
        closeStdin(process);

        LogTailBuffer tail = new LogTailBuffer(FfmpegResult.MAX_SUMMARY_LENGTH);
        Thread reader = startReader(process, tail);
        try {
            if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                terminate(process, reader);
                log.error("FFmpeg 타임아웃으로 강제 종료: timeout={}", timeout);
                return FfmpegResult.timeout(tail.toString());
            }
            joinReader(process, reader);
            int exitCode = process.exitValue();
            return exitCode == 0
                    ? FfmpegResult.success(tail.toString())
                    : FfmpegResult.failed(exitCode, tail.toString());
        } catch (InterruptedException e) {
            terminate(process, reader);
            Thread.currentThread().interrupt();
            return FfmpegResult.failed(-1, INTERRUPTED_SUMMARY);
        }
    }

    private void closeStdin(Process process) {
        try {
            // FFmpeg가 표준 입력을 기다리지 않도록 즉시 닫는다
            process.getOutputStream().close();
        } catch (IOException e) {
            log.debug("표준 입력 닫기 실패", e);
        }
    }

    private Thread startReader(Process process, LogTailBuffer tail) {
        Thread reader = new Thread(() -> {
            char[] chars = new char[4096];
            try (Reader in = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                int read;
                while ((read = in.read(chars)) != -1) {
                    tail.append(chars, 0, read);
                }
            } catch (IOException e) {
                log.debug("FFmpeg 출력 읽기 종료", e);
            }
        }, "ffmpeg-output-reader");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private void terminate(Process process, Thread reader) {
        // 부모를 죽이기 전에 자식 프로세스를 수집해 함께 종료한다
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(KILL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            joinReader(process, reader);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void joinReader(Process process, Thread reader) throws InterruptedException {
        reader.join(READER_JOIN_MILLIS);
        if (reader.isAlive()) {
            try {
                process.getInputStream().close();
            } catch (IOException e) {
                log.debug("출력 스트림 닫기 실패", e);
            }
        }
    }
}

package com.streaming.music.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.streaming.music.config.AsyncConfig;
import com.streaming.music.config.properties.AudioProperties;
import com.streaming.music.dto.event.TranscodeEventDto;
import com.streaming.music.entity.Track;
import com.streaming.music.entity.TrackStatus;
import com.streaming.music.repository.TrackRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class HlsTranscodeServiceTest {

    private static final String ORIGINAL_KEY = "original/2026/11/a.mp3";

    @Mock
    private TrackRepository trackRepository;
    @Mock
    private StorageService storageService;
    @Mock
    private FfmpegCommandRunner ffmpegCommandRunner;
    @Mock
    private PlatformTransactionManager transactionManager;

    @TempDir
    Path workDir;

    private HlsTranscodeService service;
    private UUID trackId;
    private Track track;
    private TranscodeEventDto dto;

    @BeforeEach
    void setUp() {
        AudioProperties properties = new AudioProperties(workDir.toString(), "ffmpeg", 3, 6, "192k", 10485760L);
        service = new HlsTranscodeService(trackRepository, storageService, ffmpegCommandRunner,
                properties, transactionManager);
        trackId = UUID.randomUUID();
        track = newTrack(TrackStatus.PENDING);
        dto = new TranscodeEventDto(trackId, ORIGINAL_KEY);
    }

    private Track newTrack(TrackStatus status) {
        return Track.builder()
                .trackId(trackId).title("t").durationSeconds(1).originalFilename("a.mp3")
                .fileExtension("mp3").fileSizeBytes(1L).mimeType("audio/mpeg")
                .originalS3Key(ORIGINAL_KEY).status(status).build();
    }

    private void stubTrackFound() {
        when(trackRepository.findById(trackId)).thenReturn(Optional.of(track));
        when(trackRepository.save(any(Track.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("성공 시 PROCESSING을 거쳐 READY가 되고 hlsPlaylistKey가 기록된다")
    void marksReadyWithPlaylistKeyOnSuccess() {
        stubTrackFound();
        when(ffmpegCommandRunner.transcodeToHls(any(), any())).thenReturn(FfmpegResult.success(""));
        AtomicReference<TrackStatus> statusAtDownload = new AtomicReference<>();
        doAnswer(inv -> {
            statusAtDownload.set(track.getStatus());
            return null;
        }).when(storageService).downloadObject(anyString(), any(Path.class));

        service.process(dto);

        assertThat(statusAtDownload.get()).isEqualTo(TrackStatus.PROCESSING);
        assertThat(track.getStatus()).isEqualTo(TrackStatus.READY);
        assertThat(track.getHlsPlaylistKey()).isEqualTo("hls/" + trackId + "/index.m3u8");
    }

    @Test
    @DisplayName("PROCESSING 저장과 커밋이 원본 다운로드·FFmpeg 시작보다 먼저 일어난다")
    void commitsProcessingBeforeDownloadAndFfmpeg() {
        stubTrackFound();
        when(ffmpegCommandRunner.transcodeToHls(any(), any())).thenReturn(FfmpegResult.success(""));

        service.process(dto);

        InOrder order = inOrder(trackRepository, transactionManager, storageService, ffmpegCommandRunner);
        order.verify(trackRepository).save(any(Track.class));
        order.verify(transactionManager).commit(any());
        order.verify(storageService).downloadObject(anyString(), any(Path.class));
        order.verify(ffmpegCommandRunner).transcodeToHls(any(), any());
    }

    @Test
    @DisplayName("hls 디렉터리를 대상으로 hls/{uuid} 접두사로 재귀 업로드한다")
    void uploadsHlsDirectoryWithTrackPrefix() {
        stubTrackFound();
        when(ffmpegCommandRunner.transcodeToHls(any(), any())).thenReturn(FfmpegResult.success(""));

        service.process(dto);

        verify(storageService).uploadDirectoryRecursively(
                workDir.resolve(trackId.toString()).resolve("hls"), "hls/" + trackId);
    }

    @Test
    @DisplayName("원본은 이벤트 키로 work-dir/{uuid}/input.{ext}에 내려받고 FFmpeg 입출력 경로도 같은 규칙을 따른다")
    void downloadsOriginalToInputPath() {
        stubTrackFound();
        when(ffmpegCommandRunner.transcodeToHls(any(), any())).thenReturn(FfmpegResult.success(""));

        service.process(dto);

        Path trackDir = workDir.resolve(trackId.toString());
        ArgumentCaptor<Path> target = ArgumentCaptor.forClass(Path.class);
        verify(storageService).downloadObject(org.mockito.ArgumentMatchers.eq(ORIGINAL_KEY), target.capture());
        assertThat(target.getValue()).isEqualTo(trackDir.resolve("input.mp3"));
        verify(ffmpegCommandRunner).transcodeToHls(trackDir.resolve("input.mp3"), trackDir.resolve("hls"));
    }

    @Test
    @DisplayName("성공 후 작업 디렉터리 전체가 삭제된다")
    void removesWorkDirAfterSuccess() {
        stubTrackFound();
        when(ffmpegCommandRunner.transcodeToHls(any(), any())).thenAnswer(inv -> {
            Path outputDir = inv.getArgument(1);
            Files.writeString(outputDir.resolve("index.m3u8"), "x");
            return FfmpegResult.success("");
        });

        service.process(dto);

        assertThat(workDir.resolve(trackId.toString())).doesNotExist();
    }

    @Test
    @DisplayName("FFmpeg 실패 시 로그 요약으로 FAILED를 기록하고 업로드하지 않으며 작업 디렉터리를 삭제한다")
    void marksFailedWhenFfmpegFails() {
        stubTrackFound();
        when(ffmpegCommandRunner.transcodeToHls(any(), any())).thenReturn(FfmpegResult.failed(1, "bad input"));

        service.process(dto);

        assertThat(track.getStatus()).isEqualTo(TrackStatus.FAILED);
        assertThat(track.getErrorMessage()).isEqualTo("bad input");
        verify(storageService, never()).uploadDirectoryRecursively(any(), anyString());
        assertThat(workDir.resolve(trackId.toString())).doesNotExist();
    }

    @Test
    @DisplayName("FFmpeg 타임아웃이면 FFMPEG_TIMEOUT으로 FAILED를 기록한다")
    void marksFailedWithTimeoutMessage() {
        stubTrackFound();
        when(ffmpegCommandRunner.transcodeToHls(any(), any())).thenReturn(FfmpegResult.timeout("slow"));

        service.process(dto);

        assertThat(track.getStatus()).isEqualTo(TrackStatus.FAILED);
        assertThat(track.getErrorMessage()).isEqualTo("FFMPEG_TIMEOUT");
        assertThat(workDir.resolve(trackId.toString())).doesNotExist();
    }

    @Test
    @DisplayName("업로드 중 예외가 나면 FAILED로 기록하고 READY로 만들지 않으며 작업 디렉터리를 삭제한다")
    void marksFailedWhenUploadFails() {
        stubTrackFound();
        when(ffmpegCommandRunner.transcodeToHls(any(), any())).thenReturn(FfmpegResult.success(""));
        doThrow(new IllegalStateException("upload boom"))
                .when(storageService).uploadDirectoryRecursively(any(), anyString());

        service.process(dto);

        assertThat(track.getStatus()).isEqualTo(TrackStatus.FAILED);
        assertThat(track.getErrorMessage()).isEqualTo("IllegalStateException");
        assertThat(track.getHlsPlaylistKey()).isNull();
        assertThat(workDir.resolve(trackId.toString())).doesNotExist();
    }

    @Test
    @DisplayName("READY 저장이 실패하면 FAILED 기록을 시도하고 예외를 전파하지 않는다")
    void marksFailedWhenMarkReadyFails() {
        when(trackRepository.findById(trackId)).thenReturn(Optional.of(track));
        when(trackRepository.save(any(Track.class)))
                .thenAnswer(inv -> inv.getArgument(0))
                .thenThrow(new IllegalStateException("db down"))
                .thenAnswer(inv -> inv.getArgument(0));
        when(ffmpegCommandRunner.transcodeToHls(any(), any())).thenReturn(FfmpegResult.success(""));

        service.process(dto);

        assertThat(track.getStatus()).isEqualTo(TrackStatus.FAILED);
        assertThat(track.getErrorMessage()).isEqualTo("IllegalStateException");
        assertThat(workDir.resolve(trackId.toString())).doesNotExist();
    }

    @Test
    @DisplayName("예외 경로의 errorMessage에는 예외 메시지(스토리지 키 등)가 아닌 클래스명만 기록된다")
    void recordsExceptionClassNameOnly() {
        stubTrackFound();
        doThrow(new IllegalStateException("fail original/2026/11/secret-key.mp3"))
                .when(storageService).downloadObject(anyString(), any(Path.class));

        service.process(dto);

        assertThat(track.getErrorMessage()).isEqualTo("IllegalStateException");
        assertThat(track.getErrorMessage()).doesNotContain("original/").doesNotContain("secret-key");
    }

    @Test
    @DisplayName("러너가 반환한 FAILED 요약(시작 실패·중단 등)이 그대로 errorMessage에 저장된다")
    void storesFfmpegStartFailureSummaryAsIs() {
        stubTrackFound();
        when(ffmpegCommandRunner.transcodeToHls(any(), any()))
                .thenReturn(FfmpegResult.failed(-1, "FFMPEG_START_FAILED"));

        service.process(dto);

        assertThat(track.getStatus()).isEqualTo(TrackStatus.FAILED);
        assertThat(track.getErrorMessage()).isEqualTo("FFMPEG_START_FAILED");
    }

    @Test
    @DisplayName("FAILED 갱신 자체가 실패해도 예외를 전파하지 않고 작업 디렉터리를 삭제한다")
    void swallowsFailureUpdateErrorAndStillCleansUp() {
        when(trackRepository.findById(trackId)).thenReturn(Optional.of(track));
        when(trackRepository.save(any(Track.class)))
                .thenAnswer(inv -> inv.getArgument(0))
                .thenThrow(new IllegalStateException("db down"));
        when(ffmpegCommandRunner.transcodeToHls(any(), any())).thenReturn(FfmpegResult.failed(1, "bad"));

        service.process(dto);

        assertThat(workDir.resolve(trackId.toString())).doesNotExist();
    }

    @Test
    @DisplayName("정리(삭제) 실패가 성공 결과(READY)를 가리지 않고 예외도 전파하지 않는다")
    void keepsReadyResultWhenCleanupFails() throws IOException {
        stubTrackFound();
        when(ffmpegCommandRunner.transcodeToHls(any(), any())).thenReturn(FfmpegResult.success(""));
        HlsTranscodeService spied = org.mockito.Mockito.spy(service);
        doThrow(new IOException("locked")).when(spied).deleteWorkDir(any());

        spied.process(dto);

        assertThat(track.getStatus()).isEqualTo(TrackStatus.READY);
    }

    @Test
    @DisplayName("정리(삭제) 실패가 실패 결과(FAILED)를 가리지 않고 예외도 전파하지 않는다")
    void keepsOriginalResultWhenCleanupFails() throws IOException {
        stubTrackFound();
        when(ffmpegCommandRunner.transcodeToHls(any(), any())).thenReturn(FfmpegResult.failed(1, "bad input"));
        HlsTranscodeService spied = org.mockito.Mockito.spy(service);
        doThrow(new IOException("locked")).when(spied).deleteWorkDir(any());

        spied.process(dto);

        assertThat(track.getStatus()).isEqualTo(TrackStatus.FAILED);
        assertThat(track.getErrorMessage()).isEqualTo("bad input");
    }

    @Test
    @DisplayName("다운로드 중 예외가 나도 전파하지 않고 FAILED로 기록하며 작업 디렉터리를 삭제한다")
    void marksFailedWhenExceptionOccurs() {
        stubTrackFound();
        doThrow(new IllegalStateException("boom")).when(storageService).downloadObject(anyString(), any(Path.class));

        service.process(dto);

        assertThat(track.getStatus()).isEqualTo(TrackStatus.FAILED);
        assertThat(track.getErrorMessage()).isEqualTo("IllegalStateException");
        verifyNoInteractions(ffmpegCommandRunner);
        assertThat(workDir.resolve(trackId.toString())).doesNotExist();
    }

    @Test
    @DisplayName("존재하지 않는 trackId는 예외 없이 무시한다")
    void ignoresMissingTrack() {
        when(trackRepository.findById(trackId)).thenReturn(Optional.empty());

        service.process(dto);

        verifyNoInteractions(storageService, ffmpegCommandRunner);
    }

    @Test
    @DisplayName("PENDING이 아닌 트랙의 중복 이벤트는 처리하지 않는다")
    void ignoresNonPendingTrack() {
        track = newTrack(TrackStatus.PROCESSING);
        when(trackRepository.findById(trackId)).thenReturn(Optional.of(track));

        service.process(dto);

        verify(trackRepository, never()).save(any(Track.class));
        verifyNoInteractions(storageService, ffmpegCommandRunner);
    }

    @Test
    @DisplayName("process는 @Async(transcodeExecutor)이고 클래스는 TranscodeProcessor를 구현한다")
    void declaresAsyncOnProcess() throws NoSuchMethodException, IOException {
        Async async = HlsTranscodeService.class
                .getMethod("process", TranscodeEventDto.class).getAnnotation(Async.class);

        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo(AsyncConfig.TRANSCODE_EXECUTOR);
        assertThat(TranscodeProcessor.class).isAssignableFrom(HlsTranscodeService.class);
    }
}

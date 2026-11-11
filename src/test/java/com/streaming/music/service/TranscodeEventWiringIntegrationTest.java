package com.streaming.music.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.streaming.music.dto.request.TrackUploadRequest;
import com.streaming.music.entity.Track;
import com.streaming.music.entity.TrackStatus;
import com.streaming.music.entity.User;
import com.streaming.music.support.SampleFixtures;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 업로드 트랜잭션 커밋과 변환 리스너 연결 통합 확인 (ROADMAP T4-5).
 * <p>
 * 롤백 케이스의 "미호출" 검증은 시간 의존적 부정 검증이다. 다만 리스너가 커밋과 무관하게 같은 스레드에서
 * 동기 호출되었다면 {@code dispatch} 직후 즉시 호출되었을 것이므로, 300ms 관찰은 위음성이 아닌 위양성 쪽 위험만 가진다.
 * (@Transactional 테스트는 기본 롤백이라 AFTER_COMMIT이 호출되지 않으므로 TransactionTemplate을 직접 쓴다.)
 */
class TranscodeEventWiringIntegrationTest extends TranscodeIntegrationTestBase {

    @Autowired
    private TrackService trackService;

    @Test
    @DisplayName("커밋하면 리스너가 호출되어 변환이 시작되고 READY가 된다")
    void startsTranscodeAfterCommit() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        stubFfmpegSuccess(entered::countDown);
        UUID trackId = UUID.randomUUID();
        createdTrackIds.add(trackId);

        transactionTemplate.executeWithoutResult(status -> {
            trackRepository.save(buildPendingTrack(trackId));
            dispatcher.dispatch(eventOf(trackId));
        });

        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        waitForStatus(trackId, TrackStatus.READY);
        Track saved = trackRepository.findById(trackId).orElseThrow();
        assertThat(saved.getHlsPlaylistKey()).isEqualTo("hls/" + trackId + "/index.m3u8");
        verify(storageService).downloadObject(any(), any());
    }

    @Test
    @DisplayName("롤백하면 리스너가 호출되지 않고 트랙도 남지 않는다")
    void skipsTranscodeOnRollback() {
        stubFfmpegSuccess(() -> {
        });
        UUID trackId = UUID.randomUUID();

        transactionTemplate.executeWithoutResult(status -> {
            trackRepository.save(buildPendingTrack(trackId));
            dispatcher.dispatch(eventOf(trackId));
            status.setRollbackOnly();
        });

        verify(ffmpegCommandRunner, after(300).never()).transcodeToHls(any(), any());
        verify(storageService, never()).downloadObject(any(), any());
        assertThat(trackRepository.findById(trackId)).isEmpty();
    }

    @Test
    @DisplayName("변환 처리는 호출 스레드가 아닌 transcode- 접두사 executor 스레드에서 실행된다")
    void runsProcessOnTranscodeExecutorThread() {
        AtomicReference<String> threadName = new AtomicReference<>();
        stubFfmpegSuccess(() -> threadName.set(Thread.currentThread().getName()));
        UUID trackId = savePendingTrack();
        String callerThreadName = Thread.currentThread().getName();

        transactionTemplate.executeWithoutResult(status -> dispatcher.dispatch(eventOf(trackId)));

        waitForStatus(trackId, TrackStatus.READY);
        assertThat(threadName.get()).startsWith("transcode-").isNotEqualTo(callerThreadName);
    }

    @Test
    @DisplayName("업로드 전체 경로: 회원 업로더가 저장되고 이벤트를 거쳐 READY까지 진행된다")
    void uploadsAndTranscodesEndToEnd() {
        stubFfmpegSuccess(() -> {
        });
        User user = userRepository.save(User.builder()
                .providerId("sub-" + UUID.randomUUID()).email(UUID.randomUUID() + "@test.com").build());
        createdUserIds.add(user.getUserId());
        MockMultipartFile file = new MockMultipartFile("file", "sample.mp3", "audio/mpeg",
                SampleFixtures.readSampleBytes(SampleFixtures.TAGGED_MP3));

        Track uploaded = trackService.upload(new TrackUploadRequest(file, null, null, null, null),
                user.getUserId());
        createdTrackIds.add(uploaded.getTrackId());

        waitForStatus(uploaded.getTrackId(), TrackStatus.READY);
        Long uploaderId = transactionTemplate.execute(status -> trackRepository
                .findById(uploaded.getTrackId()).orElseThrow().getUploader().getUserId());
        assertThat(uploaderId).isEqualTo(user.getUserId());

        ArgumentCaptor<String> uploadedKey = ArgumentCaptor.forClass(String.class);
        verify(storageService).uploadFile(uploadedKey.capture(), any(), any());
        ArgumentCaptor<String> downloadedKey = ArgumentCaptor.forClass(String.class);
        verify(storageService).downloadObject(downloadedKey.capture(), any());
        assertThat(downloadedKey.getValue()).isEqualTo(uploadedKey.getValue());
        Track ready = trackRepository.findById(uploaded.getTrackId()).orElseThrow();
        assertThat(ready.getHlsPlaylistKey()).isEqualTo("hls/" + uploaded.getTrackId() + "/index.m3u8");
    }
}

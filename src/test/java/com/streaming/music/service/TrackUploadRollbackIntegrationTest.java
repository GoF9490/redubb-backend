package com.streaming.music.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.streaming.music.dto.event.TranscodeEventDto;
import com.streaming.music.dto.request.TrackUploadRequest;
import com.streaming.music.support.SampleFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 업로드 트랜잭션 롤백 시 보상 삭제와 변환 미호출을 {@code TrackService.upload} 전체 경로로 확인한다.
 * 스파이 빈 때문에 컨텍스트 캐시를 공유하지 못하므로 별도 클래스로 분리했다.
 * "미호출" 검증은 시간 의존적 부정 검증이며, 동기 호출이었다면 즉시 호출되었을 것이라는 점으로 보완한다.
 */
class TrackUploadRollbackIntegrationTest extends TranscodeIntegrationTestBase {

    @MockitoSpyBean
    private TranscodeTaskDispatcher spyDispatcher;

    @Autowired
    private TrackService trackService;

    @Test
    @DisplayName("이벤트 발행 중 예외로 롤백되면 원본을 보상 삭제하고 변환은 시작되지 않으며 트랙도 남지 않는다")
    void compensatesAndSkipsTranscodeWhenDispatchFails() {
        doThrow(new IllegalStateException("boom")).when(spyDispatcher).dispatch(any(TranscodeEventDto.class));
        long before = trackRepository.count();
        MockMultipartFile file = new MockMultipartFile("file", "sample.mp3", "audio/mpeg",
                SampleFixtures.readSampleBytes(SampleFixtures.TAGGED_MP3));

        assertThatThrownBy(() -> trackService.upload(new TrackUploadRequest(file, null, null, null, null), null))
                .isInstanceOf(IllegalStateException.class).hasMessage("boom");

        ArgumentCaptor<String> uploadedKey = ArgumentCaptor.forClass(String.class);
        verify(storageService).uploadFile(uploadedKey.capture(), any(), any());
        verify(storageService).deleteObject(uploadedKey.getValue());
        verify(ffmpegCommandRunner, after(300).never()).transcodeToHls(any(), any());
        assertThat(trackRepository.count()).isEqualTo(before);
    }
}

package com.streaming.music.track.service;

import com.streaming.music.transcode.service.TranscodeTaskDispatcher;
import com.streaming.music.storage.service.StorageService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.streaming.music.playback.service.PlaybackLogService;
import com.streaming.music.global.properties.AudioProperties;
import com.streaming.music.global.properties.StorageProperties;
import com.streaming.music.track.dto.response.DownloadInfo;
import com.streaming.music.playback.entity.ActionType;
import com.streaming.music.track.entity.Track;
import com.streaming.music.track.entity.TrackStatus;
import com.streaming.music.global.exception.StorageException;
import com.streaming.music.global.exception.TrackNotFoundException;
import com.streaming.music.track.repository.TrackRepository;
import com.streaming.music.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/** TrackService 원본 다운로드 지시(getDownloadInfo) 단위 테스트. */
@ExtendWith(MockitoExtension.class)
class TrackServiceDownloadTest {

	private static final String SIGNED_QUERY =
			"?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=ak%2F20261111%2Fus-east-1%2Fs3%2Faws4_request"
					+ "&X-Amz-Expires=45&X-Amz-Signature=abc123";

	@Mock
	private StorageService storageService;
	@Mock
	private AudioMetadataValidator audioMetadataValidator;
	@Mock
	private TranscodeTaskDispatcher transcodeTaskDispatcher;
	@Mock
	private TrackRepository trackRepository;
	@Mock
	private UserRepository userRepository;
	@Mock
	private AudioProperties audioProperties;
	@Mock
	private MediaUrlResolver mediaUrlResolver;
	@Mock
	private PlaybackLogService playbackLogService;

	private TrackService trackService;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(Instant.parse("2026-03-05T10:00:00Z"), ZoneOffset.UTC);
		StorageProperties storageProperties = new StorageProperties(
				"http://rustfs:9000", "us-east-1", "ak", "sk", "audio-storage", 45);
		trackService = new TrackService(storageService, audioMetadataValidator, transcodeTaskDispatcher,
				trackRepository, userRepository, audioProperties, clock, mock(PlatformTransactionManager.class),
				mediaUrlResolver, playbackLogService, storageProperties);
	}

	private Track buildTrack(TrackStatus status, String originalFilename) {
		return Track.builder()
				.trackId(UUID.randomUUID()).title("제목").durationSeconds(1)
				.originalFilename(originalFilename).fileExtension("mp3").fileSizeBytes(3L)
				.mimeType("audio/mpeg").originalS3Key("original/2026/11/k.mp3").status(status)
				.build();
	}

	private void stubFound(Track track) {
		when(trackRepository.findById(track.getTrackId())).thenReturn(Optional.of(track));
	}

	@Test
	@DisplayName("presigned URL을 쿼리 재인코딩 없이 /internal-storage 경로로 바꾸고 만료 설정값을 사용한다")
	void buildRedirectWithoutReencodingQuery() {
		Track track = buildTrack(TrackStatus.READY, "한글 파일.mp3");
		stubFound(track);
		when(storageService.createPresignedGetUrl("original/2026/11/k.mp3", 45))
				.thenReturn("http://rustfs:9000/audio-storage/original/2026/11/k.mp3" + SIGNED_QUERY);

		DownloadInfo info = trackService.getDownloadInfo(track.getTrackId(), 7L);

		assertThat(info.xAccelRedirectUri()).isEqualTo("/internal-storage/original/2026/11/k.mp3" + SIGNED_QUERY);
		assertThat(info.contentType()).isEqualTo("audio/mpeg");
		verify(storageService, times(1)).createPresignedGetUrl("original/2026/11/k.mp3", 45);
	}

	@Test
	@DisplayName("한글 파일명은 filename*(UTF-8 퍼센트 인코딩)과 ASCII 대체 filename을 함께 담는다")
	void includeBothFilenameParameters() {
		Track track = buildTrack(TrackStatus.READY, "한글 파일.mp3");
		stubFound(track);
		when(storageService.createPresignedGetUrl(anyString(), anyInt()))
				.thenReturn("http://rustfs:9000/audio-storage/original/k.mp3" + SIGNED_QUERY);

		DownloadInfo info = trackService.getDownloadInfo(track.getTrackId(), null);

		assertThat(info.contentDisposition()).isEqualTo("attachment; filename=\"track-" + track.getTrackId()
				+ ".mp3\"; filename*=UTF-8''%ED%95%9C%EA%B8%80%20%ED%8C%8C%EC%9D%BC.mp3");
	}

	@ParameterizedTest
	@EnumSource(TrackStatus.class)
	@DisplayName("PENDING을 포함한 모든 상태에서 다운로드를 허용하고 DOWNLOAD 이력을 1회 기록한다")
	void allowAnyStatusAndRecordLog(TrackStatus status) {
		Track track = buildTrack(status, "a.mp3");
		stubFound(track);
		when(storageService.createPresignedGetUrl(anyString(), anyInt()))
				.thenReturn("http://rustfs:9000/audio-storage/original/k.mp3" + SIGNED_QUERY);

		trackService.getDownloadInfo(track.getTrackId(), 7L);

		verify(playbackLogService, times(1)).recordLog(track, 7L, ActionType.DOWNLOAD);
	}

	@Test
	@DisplayName("비로그인(userId null)도 DOWNLOAD 이력을 userId null로 기록한다")
	void recordLogWithNullUserId() {
		Track track = buildTrack(TrackStatus.PENDING, "a.mp3");
		stubFound(track);
		when(storageService.createPresignedGetUrl(anyString(), anyInt()))
				.thenReturn("http://rustfs:9000/audio-storage/original/k.mp3" + SIGNED_QUERY);

		trackService.getDownloadInfo(track.getTrackId(), null);

		verify(playbackLogService, times(1)).recordLog(track, null, ActionType.DOWNLOAD);
	}

	@Test
	@DisplayName("없는 트랙은 TrackNotFoundException이고 스토리지·이력을 호출하지 않는다")
	void rejectMissingTrack() {
		UUID trackId = UUID.randomUUID();
		when(trackRepository.findById(trackId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> trackService.getDownloadInfo(trackId, 1L))
				.isInstanceOf(TrackNotFoundException.class);

		verifyNoInteractions(storageService, playbackLogService);
	}

	@Test
	@DisplayName("presigned URL 생성이 실패하면 예외가 전파되고 이력을 기록하지 않는다")
	void propagateStorageFailureWithoutLog() {
		Track track = buildTrack(TrackStatus.READY, "a.mp3");
		stubFound(track);
		when(storageService.createPresignedGetUrl(anyString(), anyInt()))
				.thenThrow(new StorageException("presign"));

		assertThatThrownBy(() -> trackService.getDownloadInfo(track.getTrackId(), 1L))
				.isInstanceOf(StorageException.class);

		verifyNoInteractions(playbackLogService);
	}

	@Test
	@DisplayName("URL 형식이 전제를 벗어나면 trackId만 담은 IllegalStateException이고 이력을 기록하지 않는다")
	void rejectUnexpectedPresignedUrl() {
		Track track = buildTrack(TrackStatus.READY, "a.mp3");
		stubFound(track);
		when(storageService.createPresignedGetUrl(anyString(), anyInt()))
				.thenReturn("http://rustfs:9000/other-bucket/original/k.mp3?X-Amz-Signature=SECRET");

		assertThatThrownBy(() -> trackService.getDownloadInfo(track.getTrackId(), 1L))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(track.getTrackId().toString())
				.hasMessageNotContaining("SECRET");

		verify(playbackLogService, times(0)).recordLog(any(), any(), any());
	}
}

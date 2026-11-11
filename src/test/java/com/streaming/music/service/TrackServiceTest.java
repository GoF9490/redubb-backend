package com.streaming.music.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.streaming.music.config.properties.AudioProperties;
import com.streaming.music.dto.event.TranscodeEventDto;
import com.streaming.music.dto.request.TrackUploadRequest;
import com.streaming.music.entity.Track;
import com.streaming.music.entity.TrackStatus;
import com.streaming.music.entity.User;
import com.streaming.music.exception.InvalidArtworkException;
import com.streaming.music.exception.InvalidAudioFormatException;
import com.streaming.music.exception.InvalidRequestException;
import com.streaming.music.exception.StorageException;
import com.streaming.music.repository.TrackRepository;
import com.streaming.music.repository.UserRepository;
import com.streaming.music.service.AudioMetadataValidator.AudioMetadata;
import com.streaming.music.service.AudioMetadataValidator.AudioValidationResult;
import com.streaming.music.service.AudioMetadataValidator.MetadataOverrides;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class TrackServiceTest {

    private static final byte[] CUSTOM_ARTWORK = {1, 2, 3};
    private static final byte[] EMBEDDED_ARTWORK = {9, 8, 7};

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

    @TempDir
    private Path workDir;

    private TrackService trackService;

    @BeforeEach
    void setUp() {
        AudioProperties audioProperties = new AudioProperties(
                workDir.toString(), "/usr/bin/ffmpeg", 3, 6, "192k", 10485760L);
        Clock clock = Clock.fixed(Instant.parse("2026-03-05T10:00:00Z"), ZoneOffset.UTC);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        trackService = new TrackService(storageService, audioMetadataValidator, transcodeTaskDispatcher,
                trackRepository, userRepository, audioProperties, clock, transactionManager);
    }

    private MockMultipartFile audioFile() {
        return new MockMultipartFile("file", "song.MP3", "audio/mpeg", new byte[]{1, 2, 3, 4});
    }

    private TrackUploadRequest request(String title, String artist, String album, MockMultipartFile artwork) {
        return new TrackUploadRequest(audioFile(), title, artist, album, artwork);
    }

    private void stubValidator(byte[] embeddedArtwork) {
        when(audioMetadataValidator.validateAudio(any(Path.class)))
                .thenReturn(new AudioValidationResult("audio/mpeg", "mp3"));
        when(audioMetadataValidator.extractMetadata(any(Path.class), anyString(), any(MetadataOverrides.class)))
                .thenReturn(new AudioMetadata("제목", "가수", "앨범", 180, embeddedArtwork));
    }

    private void stubSave() {
        when(trackRepository.save(any(Track.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private long countWorkDirFiles() throws IOException {
        try (Stream<Path> files = Files.list(workDir)) {
            return files.count();
        }
    }

    @Test
    @DisplayName("커스텀 앨범아트가 있으면 내장 커버보다 우선 저장한다")
    void uploadSavesCustomArtworkFirst() {
        stubValidator(EMBEDDED_ARTWORK);
        stubSave();
        when(audioMetadataValidator.detectArtworkMimeType(CUSTOM_ARTWORK)).thenReturn("image/png");
        MockMultipartFile artwork = new MockMultipartFile("artworkFile", "a.png", "image/png", CUSTOM_ARTWORK);

        Track track = trackService.upload(request(null, null, null, artwork), null);

        assertThat(track.getArtworkS3Key()).isEqualTo("artwork/" + track.getTrackId() + ".jpg");
        verify(storageService).uploadBytes(track.getArtworkS3Key(), CUSTOM_ARTWORK, "image/png");
        verify(audioMetadataValidator, never()).detectArtworkMimeType(EMBEDDED_ARTWORK);
    }

    @Test
    @DisplayName("커스텀 앨범아트가 없으면 내장 커버를 저장한다")
    void uploadSavesEmbeddedArtworkWhenNoCustom() {
        stubValidator(EMBEDDED_ARTWORK);
        stubSave();
        when(audioMetadataValidator.detectArtworkMimeType(EMBEDDED_ARTWORK)).thenReturn("image/jpeg");

        Track track = trackService.upload(request(null, null, null, null), null);

        assertThat(track.getArtworkS3Key()).isEqualTo("artwork/" + track.getTrackId() + ".jpg");
        verify(storageService).uploadBytes(track.getArtworkS3Key(), EMBEDDED_ARTWORK, "image/jpeg");
    }

    @Test
    @DisplayName("커스텀·내장 앨범아트가 모두 없으면 artworkS3Key가 null이다")
    void uploadHasNullArtworkKeyWhenNoArtwork() {
        stubValidator(null);
        stubSave();

        Track track = trackService.upload(request(null, null, null, null), null);

        assertThat(track.getArtworkS3Key()).isNull();
        verify(storageService, never()).uploadBytes(anyString(), any(byte[].class), anyString());
    }

    @Test
    @DisplayName("0바이트 커스텀 앨범아트는 없는 것으로 취급한다")
    void uploadTreatsEmptyCustomArtworkAsAbsent() {
        stubValidator(null);
        stubSave();
        MockMultipartFile emptyArtwork = new MockMultipartFile("artworkFile", "a.png", "image/png", new byte[0]);

        Track track = trackService.upload(request(null, null, null, emptyArtwork), null);

        assertThat(track.getArtworkS3Key()).isNull();
        verify(audioMetadataValidator, never()).detectArtworkMimeType(any(byte[].class));
    }

    @Test
    @DisplayName("내장 커버가 유효하지 않으면 무시하고 업로드를 계속한다")
    void uploadIgnoresInvalidEmbeddedArtwork() {
        stubValidator(EMBEDDED_ARTWORK);
        stubSave();
        when(audioMetadataValidator.detectArtworkMimeType(EMBEDDED_ARTWORK))
                .thenThrow(new InvalidArtworkException("손상"));

        Track track = trackService.upload(request(null, null, null, null), null);

        assertThat(track.getArtworkS3Key()).isNull();
        verify(storageService, never()).uploadBytes(anyString(), any(byte[].class), anyString());
        verify(storageService).uploadFile(eq(track.getOriginalS3Key()), any(Path.class), eq("audio/mpeg"));
    }

    @Test
    @DisplayName("커스텀 앨범아트가 유효하지 않으면 예외를 전파하고 스토리지에 아무것도 올리지 않는다")
    void uploadPropagatesInvalidCustomArtwork() throws IOException {
        stubValidator(null);
        when(audioMetadataValidator.detectArtworkMimeType(CUSTOM_ARTWORK))
                .thenThrow(new InvalidArtworkException("형식"));
        MockMultipartFile artwork = new MockMultipartFile("artworkFile", "a.gif", "image/gif", CUSTOM_ARTWORK);

        assertThatThrownBy(() -> trackService.upload(request(null, null, null, artwork), null))
                .isInstanceOf(InvalidArtworkException.class);

        verifyNoInteractions(storageService, trackRepository, transcodeTaskDispatcher);
        assertThat(countWorkDirFiles()).isZero();
    }

    @Test
    @DisplayName("원본 키·앨범 키 형식과 Content-Type, PENDING 상태, 이벤트 1회 발행을 검증한다")
    void uploadStoresKeysAndPublishesEvent() {
        stubValidator(EMBEDDED_ARTWORK);
        stubSave();
        when(audioMetadataValidator.detectArtworkMimeType(EMBEDDED_ARTWORK)).thenReturn("image/png");

        Track track = trackService.upload(request(null, null, null, null), null);

        String expectedOriginalKey = "original/2026/03/" + track.getTrackId() + ".mp3";
        ArgumentCaptor<Track> trackCaptor = ArgumentCaptor.forClass(Track.class);
        verify(trackRepository).save(trackCaptor.capture());
        Track saved = trackCaptor.getValue();
        assertThat(saved.getOriginalS3Key()).isEqualTo(expectedOriginalKey);
        assertThat(saved.getArtworkS3Key()).isEqualTo("artwork/" + saved.getTrackId() + ".jpg");
        assertThat(saved.getStatus()).isEqualTo(TrackStatus.PENDING);
        assertThat(saved.getHlsPlaylistKey()).isNull();
        assertThat(saved.getUploader()).isNull();
        assertThat(saved.getFileExtension()).isEqualTo("mp3");
        assertThat(saved.getMimeType()).isEqualTo("audio/mpeg");
        assertThat(saved.getFileSizeBytes()).isEqualTo(4L);
        assertThat(saved.getOriginalFilename()).isEqualTo("song.MP3");
        assertThat(saved.getDurationSeconds()).isEqualTo(180);

        verify(storageService).uploadFile(eq(expectedOriginalKey), any(Path.class), eq("audio/mpeg"));
        verify(storageService).uploadBytes(saved.getArtworkS3Key(), EMBEDDED_ARTWORK, "image/png");
        verify(transcodeTaskDispatcher, times(1))
                .dispatch(new TranscodeEventDto(saved.getTrackId(), expectedOriginalKey));
    }

    @Test
    @DisplayName("원본 업로드 시점에 {uuid}_raw.{ext} 임시 파일이 있고 종료 후 work-dir이 비어 있다")
    void uploadUsesRawTempFileAndCleansUp() throws IOException {
        stubValidator(null);
        stubSave();
        AtomicBoolean existedDuringUpload = new AtomicBoolean(false);
        doAnswer(invocation -> {
            Path file = invocation.getArgument(1);
            existedDuringUpload.set(Files.exists(file)
                    && file.getFileName().toString().matches("[0-9a-f-]{36}_raw\\.mp3")
                    && file.getParent().equals(workDir));
            return null;
        }).when(storageService).uploadFile(anyString(), any(Path.class), anyString());

        trackService.upload(request(null, null, null, null), null);

        assertThat(existedDuringUpload).isTrue();
        assertThat(countWorkDirFiles()).isZero();
    }

    @Test
    @DisplayName("Tika 검증 실패 시 임시 파일을 정리하고 스토리지를 호출하지 않는다")
    void uploadCleansUpWhenAudioValidationFails() throws IOException {
        when(audioMetadataValidator.validateAudio(any(Path.class)))
                .thenThrow(new InvalidAudioFormatException("허용되지 않는 형식"));

        assertThatThrownBy(() -> trackService.upload(request(null, null, null, null), null))
                .isInstanceOf(InvalidAudioFormatException.class);

        assertThat(countWorkDirFiles()).isZero();
        verifyNoInteractions(storageService, trackRepository, transcodeTaskDispatcher);
    }

    @Test
    @DisplayName("userId가 있으면 uploader를 설정한다")
    void uploadSetsUploaderWhenUserExists() {
        stubValidator(null);
        stubSave();
        User user = User.builder().provider("google").providerId("sub").email("a@b.c").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        Track track = trackService.upload(request(null, null, null, null), 1L);

        assertThat(track.getUploader()).isSameAs(user);
    }

    @Test
    @DisplayName("userId가 null이면 사용자 조회 없이 익명으로 저장한다")
    void uploadSavesAnonymousWhenUserIdNull() {
        stubValidator(null);
        stubSave();

        Track track = trackService.upload(request(null, null, null, null), null);

        assertThat(track.getUploader()).isNull();
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("존재하지 않는 userId여도 업로드는 성공하고 익명으로 저장한다")
    void uploadSavesAnonymousWhenUserNotFound() {
        stubValidator(null);
        stubSave();
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        Track track = trackService.upload(request(null, null, null, null), 99L);

        assertThat(track.getUploader()).isNull();
    }

    @Test
    @DisplayName("file이 null이면 InvalidRequestException을 던진다")
    void uploadRejectsNullFile() {
        TrackUploadRequest noFile = new TrackUploadRequest(null, null, null, null, null);

        assertThatThrownBy(() -> trackService.upload(noFile, null))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(storageService, audioMetadataValidator);
    }

    @Test
    @DisplayName("request의 제목·가수·앨범이 한도를 넘으면 거부한다")
    void uploadRejectsOverlongRequestFields() {
        assertThatThrownBy(() -> trackService.upload(request("가".repeat(201), null, null, null), null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> trackService.upload(request(null, "가".repeat(151), null, null), null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> trackService.upload(request(null, null, "가".repeat(201), null), null))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(storageService, audioMetadataValidator);
    }

    @Test
    @DisplayName("한도 이하이거나 공백만 있는 request 값은 통과한다")
    void uploadAcceptsBoundaryAndBlankRequestFields() {
        stubValidator(null);
        stubSave();

        Track track = trackService.upload(
                request("가".repeat(200), "나".repeat(150), "   ", null), null);

        assertThat(track).isNotNull();
    }

    @Test
    @DisplayName("태그에서 파생된 값이 한도를 넘으면 절단해 저장한다")
    void uploadTruncatesDerivedValues() {
        when(audioMetadataValidator.validateAudio(any(Path.class)))
                .thenReturn(new AudioValidationResult("audio/mpeg", "mp3"));
        when(audioMetadataValidator.extractMetadata(any(Path.class), anyString(), any(MetadataOverrides.class)))
                .thenReturn(new AudioMetadata("t".repeat(300), "a".repeat(300), "b".repeat(300), 10, null));
        stubSave();

        Track track = trackService.upload(request(null, null, null, null), null);

        assertThat(track.getTitle()).hasSize(200);
        assertThat(track.getArtist()).hasSize(150);
        assertThat(track.getAlbum()).hasSize(200);
    }

    @Test
    @DisplayName("원본 파일명은 경로 구분자 앞부분을 제거하고 null이면 unknown으로 저장한다")
    void uploadSanitizesOriginalFilename() {
        stubValidator(null);
        stubSave();
        MockMultipartFile pathFile = new MockMultipartFile(
                "file", "C:\\Users\\me\\music/곡.mp3", "audio/mpeg", new byte[]{1});
        MockMultipartFile noNameFile = new MockMultipartFile("file", null, "audio/mpeg", new byte[]{1});

        Track withPath = trackService.upload(new TrackUploadRequest(pathFile, null, null, null, null), null);
        Track withoutName = trackService.upload(new TrackUploadRequest(noNameFile, null, null, null, null), null);

        assertThat(withPath.getOriginalFilename()).isEqualTo("곡.mp3");
        assertThat(withoutName.getOriginalFilename()).isEqualTo("unknown");
    }

    private void stubEmbeddedArtworkUpload() {
        stubValidator(EMBEDDED_ARTWORK);
        when(audioMetadataValidator.detectArtworkMimeType(EMBEDDED_ARTWORK)).thenReturn("image/jpeg");
    }

    @Test
    @DisplayName("DB 저장 예외 시 원본·앨범아트를 삭제하고 예외를 전파하며 임시 파일을 정리한다")
    void uploadCompensatesBothObjectsWhenSaveFails() throws IOException {
        stubEmbeddedArtworkUpload();
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("db down");
        when(trackRepository.save(any(Track.class))).thenThrow(failure);

        assertThatThrownBy(() -> trackService.upload(request(null, null, null, null), null)).isSameAs(failure);

        ArgumentCaptor<String> originalCaptor = ArgumentCaptor.forClass(String.class);
        verify(storageService).uploadFile(originalCaptor.capture(), any(Path.class), anyString());
        ArgumentCaptor<String> artworkCaptor = ArgumentCaptor.forClass(String.class);
        verify(storageService).uploadBytes(artworkCaptor.capture(), any(byte[].class), anyString());
        verify(storageService).deleteObject(originalCaptor.getValue());
        verify(storageService).deleteObject(artworkCaptor.getValue());
        verify(transcodeTaskDispatcher, never()).dispatch(any());
        assertThat(countWorkDirFiles()).isZero();
    }

    @Test
    @DisplayName("이벤트 발행 예외 시에도 원본·앨범아트를 삭제한다")
    void uploadCompensatesWhenDispatchFails() {
        stubEmbeddedArtworkUpload();
        stubSave();
        IllegalStateException failure = new IllegalStateException("dispatch 실패");
        doThrow(failure).when(transcodeTaskDispatcher).dispatch(any(TranscodeEventDto.class));

        assertThatThrownBy(() -> trackService.upload(request(null, null, null, null), null)).isSameAs(failure);

        verify(storageService, times(2)).deleteObject(anyString());
    }

    @Test
    @DisplayName("앨범아트가 없으면 원본만 삭제하고 deleteObject(null)은 호출하지 않는다")
    void uploadDeletesOnlyOriginalWhenNoArtwork() {
        stubValidator(null);
        when(trackRepository.save(any(Track.class))).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> trackService.upload(request(null, null, null, null), null))
                .isInstanceOf(DataAccessResourceFailureException.class);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(storageService, times(1)).deleteObject(keyCaptor.capture());
        assertThat(keyCaptor.getValue()).startsWith("original/");
        verify(storageService, never()).deleteObject(isNull());
    }

    @Test
    @DisplayName("앨범아트 업로드 실패 시 이미 올라간 원본만 삭제하고 예외를 전파한다")
    void uploadCompensatesOriginalWhenArtworkUploadFails() throws IOException {
        stubEmbeddedArtworkUpload();
        StorageException failure = new StorageException("putObject");
        doThrow(failure).when(storageService).uploadBytes(anyString(), any(byte[].class), anyString());

        assertThatThrownBy(() -> trackService.upload(request(null, null, null, null), null)).isSameAs(failure);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(storageService, times(1)).deleteObject(keyCaptor.capture());
        assertThat(keyCaptor.getValue()).startsWith("original/");
        verifyNoInteractions(trackRepository, transcodeTaskDispatcher);
        assertThat(countWorkDirFiles()).isZero();
    }

    @Test
    @DisplayName("원본 업로드 자체가 실패하면 삭제를 시도하지 않고 임시 파일을 정리한다")
    void uploadSkipsCompensationWhenOriginalUploadFails() throws IOException {
        stubValidator(null);
        StorageException failure = new StorageException("putObject");
        doThrow(failure).when(storageService).uploadFile(anyString(), any(Path.class), anyString());

        assertThatThrownBy(() -> trackService.upload(request(null, null, null, null), null)).isSameAs(failure);

        verify(storageService, never()).deleteObject(anyString());
        verifyNoInteractions(trackRepository, transcodeTaskDispatcher);
        assertThat(countWorkDirFiles()).isZero();
    }

    @Test
    @DisplayName("보상 삭제가 실패해도 원래 예외가 전파되고 삭제 예외는 suppressed로 남는다")
    void uploadKeepsOriginalExceptionWhenCompensationFails() {
        stubEmbeddedArtworkUpload();
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("db down");
        when(trackRepository.save(any(Track.class))).thenThrow(failure);
        StorageException deleteFailure = new StorageException("deleteObject");
        doThrow(deleteFailure).when(storageService).deleteObject(anyString());

        assertThatThrownBy(() -> trackService.upload(request(null, null, null, null), null))
                .isSameAs(failure)
                .hasSuppressedException(deleteFailure);

        // 첫 삭제가 실패해도 나머지 객체 삭제를 계속 시도한다
        verify(storageService, times(2)).deleteObject(anyString());
    }

    @Test
    @DisplayName("임시 파일 삭제가 실패해도 원래 예외가 가려지지 않는다")
    void uploadKeepsOriginalExceptionWhenTempDeleteFails() {
        stubValidator(null);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("db down");
        when(trackRepository.save(any(Track.class))).thenThrow(failure);
        // 업로드 시점에 rawFile을 비어 있지 않은 디렉터리로 바꿔 deleteIfExists가 IOException을 던지게 한다
        doAnswer(invocation -> {
            Path file = invocation.getArgument(1);
            Files.delete(file);
            Files.createDirectory(file);
            Files.writeString(file.resolve("child.txt"), "x");
            return null;
        }).when(storageService).uploadFile(anyString(), any(Path.class), anyString());

        assertThatThrownBy(() -> trackService.upload(request(null, null, null, null), null)).isSameAs(failure);
    }
}

package com.streaming.music.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.streaming.music.config.properties.StorageProperties;
import com.streaming.music.exception.ErrorCode;
import com.streaming.music.exception.StorageException;
import io.minio.BucketExistsArgs;
import io.minio.DownloadObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.ServerException;
import io.minio.http.Method;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MinioStorageServiceTest {

    private static final String BUCKET = "audio-storage";

    @Mock
    private MinioClient minioClient;

    private MinioStorageService createService() {
        StorageProperties properties = new StorageProperties(
                "http://rustfs:9000", "us-east-1", "ak", "sk", BUCKET, 60);
        return new MinioStorageService(minioClient, properties);
    }

    static Stream<Throwable> checkedExceptions() {
        return Stream.of(
                mock(ErrorResponseException.class),
                mock(ServerException.class),
                new IOException("io"),
                new InvalidKeyException("key"),
                new NoSuchAlgorithmException("algo"));
    }

    @Test
    @DisplayName("파일 업로드 시 지정한 버킷·key·contentType·크기가 SDK에 전달된다")
    void uploadFilePassesKeyAndContentType(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("a.mp3");
        Files.write(file, "audio-data".getBytes(StandardCharsets.UTF_8));

        createService().uploadFile("original/2026/11/u1.mp3", file, "audio/mpeg");

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(captor.capture());
        PutObjectArgs args = captor.getValue();
        assertThat(args.bucket()).isEqualTo(BUCKET);
        assertThat(args.object()).isEqualTo("original/2026/11/u1.mp3");
        assertThat(args.contentType()).isEqualTo("audio/mpeg");
        assertThat(args.objectSize()).isEqualTo(10L);
    }

    @Test
    @DisplayName("바이트 업로드 시 지정한 key·contentType·크기가 SDK에 전달된다")
    void uploadBytesPassesKeyAndContentType() throws Exception {
        createService().uploadBytes("artwork/u1.jpg", new byte[]{1, 2, 3}, "image/jpeg");

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(captor.capture());
        PutObjectArgs args = captor.getValue();
        assertThat(args.bucket()).isEqualTo(BUCKET);
        assertThat(args.object()).isEqualTo("artwork/u1.jpg");
        assertThat(args.contentType()).isEqualTo("image/jpeg");
        assertThat(args.objectSize()).isEqualTo(3L);
    }

    @Test
    @DisplayName("버킷이 이미 있으면 생성을 호출하지 않는다")
    void ensureBucketExistsSkipsCreateWhenPresent() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        createService().ensureBucketExists();

        verify(minioClient, never()).makeBucket(any(MakeBucketArgs.class));
    }

    @Test
    @DisplayName("버킷이 없으면 해당 버킷명으로 생성을 호출한다")
    void ensureBucketExistsCreatesWhenMissing() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        createService().ensureBucketExists();

        ArgumentCaptor<MakeBucketArgs> captor = ArgumentCaptor.forClass(MakeBucketArgs.class);
        verify(minioClient).makeBucket(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
    }

    @Test
    @DisplayName("presign은 GET 메서드와 설정된 만료 시간·key로 SDK를 호출하고 URL을 반환한다")
    void createPresignedGetUrlUsesExpiryAndKey() throws Exception {
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://rustfs:9000/audio-storage/original/x.mp3?X-Amz-Signature=s");

        String url = createService().createPresignedGetUrl("original/x.mp3", 60);

        ArgumentCaptor<GetPresignedObjectUrlArgs> captor =
                ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(minioClient).getPresignedObjectUrl(captor.capture());
        GetPresignedObjectUrlArgs args = captor.getValue();
        assertThat(args.method()).isEqualTo(Method.GET);
        assertThat(args.bucket()).isEqualTo(BUCKET);
        assertThat(args.object()).isEqualTo("original/x.mp3");
        assertThat(args.expiry()).isEqualTo(60);
        assertThat(url).contains("X-Amz-Signature=s");
    }

    @Test
    @DisplayName("삭제 시 버킷과 key가 SDK에 전달된다")
    void deleteObjectPassesKey() throws Exception {
        createService().deleteObject("artwork/u1.jpg");

        ArgumentCaptor<RemoveObjectArgs> captor = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(minioClient).removeObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().object()).isEqualTo("artwork/u1.jpg");
    }

    @Test
    @DisplayName("다운로드 시 부모 디렉터리를 만들고 덮어쓰기 옵션으로 SDK를 호출한다")
    void downloadObjectCreatesParentAndOverwrites(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("uuid").resolve("input.mp3");

        createService().downloadObject("original/x.mp3", target);

        assertThat(target.getParent()).isDirectory();
        ArgumentCaptor<DownloadObjectArgs> captor = ArgumentCaptor.forClass(DownloadObjectArgs.class);
        verify(minioClient).downloadObject(captor.capture());
        DownloadObjectArgs args = captor.getValue();
        assertThat(args.bucket()).isEqualTo(BUCKET);
        assertThat(args.object()).isEqualTo("original/x.mp3");
        assertThat(args.filename()).isEqualTo(target.toString());
        assertThat(args.overwrite()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("checkedExceptions")
    @DisplayName("SDK 체크 예외는 모든 연산에서 INTERNAL_ERROR 대상 StorageException으로 변환된다")
    void convertsCheckedExceptionsToStorageException(Throwable cause, @TempDir Path tempDir)
            throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenThrow(cause);
        when(minioClient.putObject(any(PutObjectArgs.class))).thenThrow(cause);
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenThrow(cause);
        doThrow(cause).when(minioClient).removeObject(any(RemoveObjectArgs.class));
        doThrow(cause).when(minioClient).downloadObject(any(DownloadObjectArgs.class));
        Path file = Files.write(tempDir.resolve("f.bin"), new byte[]{1});
        MinioStorageService service = createService();

        List<Runnable> operations = List.of(
                service::ensureBucketExists,
                () -> service.uploadFile("k", file, "audio/mpeg"),
                () -> service.uploadBytes("k", new byte[]{1}, "image/jpeg"),
                () -> service.deleteObject("k"),
                () -> service.createPresignedGetUrl("k", 60),
                () -> service.downloadObject("k", tempDir.resolve("d").resolve("o.bin")));

        for (Runnable operation : operations) {
            assertThatThrownBy(operation::run)
                    .isInstanceOfSatisfying(StorageException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                        assertThat(e.getCause()).isSameAs(cause);
                    });
        }
    }

    @Test
    @DisplayName("RuntimeException은 StorageException으로 감싸지 않고 그대로 전파된다")
    void propagatesRuntimeExceptionAsIs() throws Exception {
        IllegalArgumentException bug = new IllegalArgumentException("잘못된 인자");
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenThrow(bug);

        assertThatThrownBy(() -> createService().createPresignedGetUrl("k", 60))
                .isSameAs(bug)
                .isNotInstanceOf(StorageException.class);
    }

    @Test
    @DisplayName("변환된 예외 메시지에는 key나 서명 URL이 포함되지 않는다")
    void exceptionMessageDoesNotLeakKeyOrUrl() throws Exception {
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenThrow(new IOException("http://rustfs:9000/x?X-Amz-Signature=secret"));

        assertThatThrownBy(() -> createService().createPresignedGetUrl("original/x.mp3", 60))
                .isInstanceOf(StorageException.class)
                .hasMessageNotContaining("X-Amz-Signature")
                .hasMessageNotContaining("original/x.mp3");
    }
}

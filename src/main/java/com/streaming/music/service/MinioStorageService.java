package com.streaming.music.service;

import com.streaming.music.config.properties.StorageProperties;
import com.streaming.music.exception.StorageException;
import io.minio.BucketExistsArgs;
import io.minio.DownloadObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.MinioException;
import io.minio.http.Method;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * MinIO SDK 기반 {@link StorageService} 구현.
 * 체크 예외(MinioException, IOException, GeneralSecurityException)만 StorageException으로 변환하고,
 * RuntimeException(프로그래밍 오류)은 그대로 전파한다. 로그에는 operation/key만 남기고 시크릿·서명 URL은 남기지 않는다.
 */
@Service
public class MinioStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    private final MinioClient minioClient;
    private final StorageProperties properties;

    public MinioStorageService(MinioClient minioClient, StorageProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    @Override
    public void ensureBucketExists() {
        String bucket = properties.bucketName();
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("버킷을 생성했습니다: {}", bucket);
            }
        } catch (MinioException | IOException | GeneralSecurityException e) {
            throw fail("ensureBucketExists", bucket, e);
        }
    }

    @Override
    public void uploadFile(String key, Path file, String contentType) {
        try (InputStream in = Files.newInputStream(file)) {
            putObject(key, in, Files.size(file), contentType);
        } catch (MinioException | IOException | GeneralSecurityException e) {
            throw fail("uploadFile", key, e);
        }
    }

    @Override
    public void uploadBytes(String key, byte[] data, String contentType) {
        try (InputStream in = new ByteArrayInputStream(data)) {
            putObject(key, in, data.length, contentType);
        } catch (MinioException | IOException | GeneralSecurityException e) {
            throw fail("uploadBytes", key, e);
        }
    }

    @Override
    public void deleteObject(String key) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.bucketName()).object(key).build());
        } catch (MinioException | IOException | GeneralSecurityException e) {
            throw fail("deleteObject", key, e);
        }
    }

    @Override
    public String createPresignedGetUrl(String key, int expirySeconds) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.bucketName())
                    .object(key)
                    .expiry(expirySeconds)
                    .build());
        } catch (MinioException | IOException | GeneralSecurityException e) {
            throw fail("createPresignedGetUrl", key, e);
        }
    }

    @Override
    public void downloadObject(String key, Path target) {
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            minioClient.downloadObject(DownloadObjectArgs.builder()
                    .bucket(properties.bucketName())
                    .object(key)
                    .filename(target.toString())
                    .overwrite(true)
                    .build());
        } catch (MinioException | IOException | GeneralSecurityException e) {
            throw fail("downloadObject", key, e);
        }
    }

    @Override
    public void uploadDirectoryRecursively(Path localDir, String keyPrefix) {
        if (!Files.isDirectory(localDir)) {
            log.error("스토리지 작업 실패: operation=uploadDirectoryRecursively, 사유=디렉터리 없음");
            throw new StorageException("uploadDirectoryRecursively: 업로드할 디렉터리가 없습니다");
        }
        String prefix = keyPrefix.endsWith("/") ? keyPrefix.substring(0, keyPrefix.length() - 1) : keyPrefix;

        List<Path> files;
        try (Stream<Path> walk = Files.walk(localDir)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException | UncheckedIOException e) {
            throw fail("uploadDirectoryRecursively", prefix, e);
        }
        if (files.isEmpty()) {
            log.error("스토리지 작업 실패: operation=uploadDirectoryRecursively, 사유=업로드할 파일 없음");
            throw new StorageException("uploadDirectoryRecursively: 업로드할 파일이 없습니다");
        }

        for (Path file : files) {
            uploadFile(buildObjectKey(prefix, localDir.relativize(file)), file, resolveContentType(file));
        }
    }

    /** 상대경로의 각 name element를 '/'로 이어 OS 구분자(\)와 무관한 S3 키를 만든다. */
    private String buildObjectKey(String prefix, Path relativePath) {
        StringBuilder key = new StringBuilder(prefix);
        for (Path element : relativePath) {
            key.append('/').append(element);
        }
        return key.toString();
    }

    private String resolveContentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".m3u8")) {
            return "application/vnd.apple.mpegurl";
        }
        if (name.endsWith(".ts")) {
            return "video/mp2t";
        }
        return "application/octet-stream";
    }

    private void putObject(String key, InputStream in, long size, String contentType)
            throws MinioException, IOException, GeneralSecurityException {
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(properties.bucketName())
                .object(key)
                .stream(in, size, -1)
                .contentType(contentType)
                .build());
    }

    private StorageException fail(String operation, String key, Exception cause) {
        // 시크릿·서명 URL 노출 방지를 위해 operation/key만 기록한다
        log.error("스토리지 작업 실패: operation={}, key={}", operation, key, cause);
        return new StorageException(operation, cause);
    }
}

package com.javaee.fileservice.service;

import com.javaee.common.exception.BusinessException;
import com.javaee.fileservice.config.FileStorageConfig;
import com.javaee.fileservice.entity.FileMetadata;
import com.javaee.fileservice.security.BucketPermissionService;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
@RequiredArgsConstructor
public class FileObjectStorage {

    private final FileStorageConfig storageConfig;
    private final MinioClient minioClient;
    private final BucketPermissionService bucketPermissionService;

    public void delete(FileMetadata metadata) throws Exception {
        if (isMinio()) {
            assertBucket(metadata);
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(metadata.getBucketName())
                    .object(metadata.getObjectKey())
                    .build());
        } else {
            Files.deleteIfExists(resolveLocalObject(metadata.getObjectKey()));
        }
    }

    public void move(FileMetadata metadata, String newObjectKey) throws Exception {
        if (isMinio()) {
            assertBucket(metadata);
            minioClient.copyObject(copyArgs(metadata, newObjectKey));
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(metadata.getBucketName())
                    .object(metadata.getObjectKey())
                    .build());
        } else {
            Path target = resolveLocalObject(newObjectKey);
            Files.createDirectories(target.getParent());
            Files.move(resolveLocalObject(metadata.getObjectKey()), target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void copy(FileMetadata metadata, String newObjectKey) throws Exception {
        if (isMinio()) {
            assertBucket(metadata);
            minioClient.copyObject(copyArgs(metadata, newObjectKey));
        } else {
            Path target = resolveLocalObject(newObjectKey);
            Files.createDirectories(target.getParent());
            Files.copy(resolveLocalObject(metadata.getObjectKey()), target);
        }
    }

    public void deleteQuietly(FileMetadata metadata, String objectKey) {
        try {
            if (isMinio()) {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(metadata.getBucketName()).object(objectKey).build());
            } else {
                Files.deleteIfExists(resolveLocalObject(objectKey));
            }
        } catch (Exception ignored) {
            // Preserve the operation error; lifecycle cleanup handles orphaned objects.
        }
    }

    public boolean isLocal() {
        return !isMinio();
    }

    private CopyObjectArgs copyArgs(FileMetadata metadata, String newObjectKey) {
        return CopyObjectArgs.builder()
                .bucket(metadata.getBucketName())
                .object(newObjectKey)
                .source(CopySource.builder()
                        .bucket(metadata.getBucketName())
                        .object(metadata.getObjectKey())
                        .build())
                .build();
    }

    private void assertBucket(FileMetadata metadata) {
        if (metadata.getBucketName() == null || metadata.getBucketName().isBlank()) {
            throw new BusinessException("文件存储桶信息缺失");
        }
        bucketPermissionService.assertCanAccess(metadata.getBucketName());
    }

    private Path resolveLocalObject(String objectKey) {
        Path root = Path.of(storageConfig.getLocalPath()).toAbsolutePath().normalize();
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("非法文件路径");
        }
        return target;
    }

    private boolean isMinio() {
        if ("minio".equalsIgnoreCase(storageConfig.getStorageType())) {
            return true;
        }
        if ("local".equalsIgnoreCase(storageConfig.getStorageType())) {
            return false;
        }
        throw new BusinessException("不支持的存储类型: " + storageConfig.getStorageType());
    }
}

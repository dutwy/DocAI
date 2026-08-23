package com.javaee.fileservice.service;

import com.javaee.common.exception.BusinessException;
import com.javaee.fileservice.config.FileStorageConfig;
import com.javaee.fileservice.config.FileTransferProperties;
import com.javaee.fileservice.entity.FileMetadata;
import com.javaee.fileservice.model.FileDownloadResource;
import com.javaee.fileservice.security.BucketPermissionService;
import com.javaee.fileservice.security.RequestUserContext;
import com.javaee.fileservice.util.FileUtils;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileTransferService {

    private final FileStorageConfig storageConfig;
    private final FileTransferProperties transferProperties;
    private final FileMetadataService metadataService;
    private final MinioClient minioClient;
    private final BucketPermissionService bucketPermissionService;
    private final RequestUserContext requestUserContext;
    private final UploadResourceGuard resourceGuard;

    public String upload(MultipartFile file) {
        if (file == null) {
            throw new BusinessException("上传文件不能为空");
        }
        try (InputStream input = file.getInputStream()) {
            return upload(input, file.getSize(), file.getOriginalFilename(), file.getContentType());
        } catch (IOException e) {
            throw new BusinessException("读取上传文件失败");
        }
    }

    public String[] uploadMultiple(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new BusinessException("上传文件不能为空");
        }
        if (files.length > transferProperties.getMaxMultipleFiles()) {
            throw new BusinessException("单次最多上传 " + transferProperties.getMaxMultipleFiles() + " 个文件");
        }
        String[] ids = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            ids[i] = upload(files[i]);
        }
        return ids;
    }

    public String upload(InputStream input, long contentLength, String originalName, String contentType) {
        validateSize(contentLength);
        String fileName = sanitizeFileName(originalName);
        String normalizedContentType = normalizeContentType(contentType, fileName);
        String userId = requestUserContext.getRequiredUserId();
        String bucket = storageConfig.getBucketName();
        String fileId = UUID.randomUUID().toString();
        String extension = FileUtils.getFileExtension(fileName);
        String objectKey = fileId + (extension == null ? "" : "." + extension);

        boolean stored = false;
        try (UploadResourceGuard.Permit ignored = resourceGuard.acquire()) {
            if ("local".equalsIgnoreCase(storageConfig.getStorageType())) {
                writeLocal(input, contentLength, objectKey);
            } else if ("minio".equalsIgnoreCase(storageConfig.getStorageType())) {
                bucketPermissionService.assertCanAccess(bucket);
                ensureBucket(bucket);
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(input, contentLength, -1)
                        .contentType(normalizedContentType)
                        .build());
            } else {
                throw new BusinessException("不支持的存储类型: " + storageConfig.getStorageType());
            }
            stored = true;
            saveMetadata(fileId, fileName, normalizedContentType, contentLength, bucket, objectKey, userId);
            return fileId;
        } catch (BusinessException e) {
            if (stored) {
                deleteStoredObjectQuietly(bucket, objectKey);
            }
            throw e;
        } catch (Exception e) {
            if (stored) {
                deleteStoredObjectQuietly(bucket, objectKey);
            }
            throw new BusinessException("文件上传失败: " + e.getMessage());
        }
    }

    public FileDownloadResource openDownload(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new BusinessException("文件ID不能为空");
        }
        FileMetadata metadata = metadataService.getMetadata(fileId);
        if (metadata == null || metadata.getObjectKey() == null || metadata.getObjectKey().isBlank()) {
            throw new BusinessException("文件不存在或无权访问");
        }
        if ("local".equalsIgnoreCase(storageConfig.getStorageType())
                && metadata.getCreateBy() != null
                && !requestUserContext.getRequiredUserId().equals(metadata.getCreateBy())) {
            throw new BusinessException("文件不存在或无权访问");
        }
        String fileName = sanitizeFileName(metadata.getOriginalFileName() == null
                ? metadata.getFileName() : metadata.getOriginalFileName());
        String contentType = normalizeContentType(metadata.getFileType(), fileName);
        UploadResourceGuard.Permit permit = resourceGuard.acquire();
        try {
            if ("local".equalsIgnoreCase(storageConfig.getStorageType())) {
                Path path = resolveLocalPath(metadata.getObjectKey());
                if (!Files.isRegularFile(path)) {
                    throw new BusinessException("文件不存在");
                }
                return new FileDownloadResource(
                        Files.newInputStream(path), fileName, contentType, Files.size(path), permit);
            }
            if ("minio".equalsIgnoreCase(storageConfig.getStorageType())) {
                String bucket = metadata.getBucketName();
                bucketPermissionService.assertCanAccess(bucket);
                StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                        .bucket(bucket).object(metadata.getObjectKey()).build());
                InputStream input = minioClient.getObject(GetObjectArgs.builder()
                        .bucket(bucket).object(metadata.getObjectKey()).build());
                return new FileDownloadResource(input, fileName, contentType, stat.size(), permit);
            }
            throw new BusinessException("不支持的存储类型: " + storageConfig.getStorageType());
        } catch (BusinessException e) {
            permit.close();
            throw e;
        } catch (Exception e) {
            permit.close();
            throw new BusinessException("打开下载流失败: " + e.getMessage());
        }
    }

    private void writeLocal(InputStream input, long expectedSize, String objectKey) throws IOException {
        Path target = resolveLocalPath(objectKey);
        Files.createDirectories(target.getParent());
        long copied;
        try (InputStream limited = new SizeLimitedInputStream(input, transferProperties.getMaxFileSize().toBytes())) {
            copied = Files.copy(limited, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }
        if (copied != expectedSize) {
            Files.deleteIfExists(target);
            throw new BusinessException("请求声明的文件大小与实际内容不一致");
        }
    }

    private Path resolveLocalPath(String objectKey) {
        Path root = Path.of(storageConfig.getLocalPath()).toAbsolutePath().normalize();
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("非法文件路径");
        }
        return target;
    }

    private void ensureBucket(String bucket) throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    private void deleteStoredObjectQuietly(String bucket, String objectKey) {
        try {
            if ("minio".equalsIgnoreCase(storageConfig.getStorageType())) {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucket).object(objectKey).build());
            } else {
                Files.deleteIfExists(resolveLocalPath(objectKey));
            }
        } catch (Exception ignored) {
            // 保留上传主异常；对象存储生命周期策略可继续清理极少数孤儿对象。
        }
    }

    private void saveMetadata(String fileId, String fileName, String contentType, long size,
                              String bucket, String objectKey, String userId) {
        FileMetadata metadata = new FileMetadata();
        metadata.setFileId(fileId);
        metadata.setFileName(fileName);
        metadata.setOriginalFileName(fileName);
        metadata.setFilePath("minio".equalsIgnoreCase(storageConfig.getStorageType())
                ? "minio:" + bucket : storageConfig.getLocalPath());
        metadata.setFileType(contentType);
        metadata.setFileSize(size);
        metadata.setStorageType(storageConfig.getStorageType());
        metadata.setBucketName(bucket);
        metadata.setObjectKey(objectKey);
        metadata.setCreateBy(userId);
        metadataService.saveMetadata(metadata);
    }

    private void validateSize(long size) {
        if (size < 0) {
            throw new BusinessException("必须提供有效的文件大小");
        }
        if (size > transferProperties.getMaxFileSize().toBytes()) {
            throw new BusinessException("文件大小超过限制");
        }
    }

    static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("文件名不能为空");
        }
        String cleaned = name.replace('\r', '_').replace('\n', '_').replace('\\', '/');
        cleaned = cleaned.substring(cleaned.lastIndexOf('/') + 1).trim();
        if (cleaned.isBlank() || ".".equals(cleaned) || "..".equals(cleaned) || cleaned.length() > 255) {
            throw new BusinessException("文件名不合法");
        }
        return cleaned;
    }

    static String normalizeContentType(String contentType, String fileName) {
        if (contentType == null || contentType.isBlank()) {
            return FileUtils.getContentType(fileName);
        }
        try {
            return MediaType.parseMediaType(contentType).toString();
        } catch (IllegalArgumentException e) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
    }

    private static final class SizeLimitedInputStream extends InputStream {
        private final InputStream delegate;
        private final long maxBytes;
        private long count;

        private SizeLimitedInputStream(InputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                addCount(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = delegate.read(bytes, offset, length);
            if (read > 0) {
                addCount(read);
            }
            return read;
        }

        private void addCount(int amount) throws IOException {
            count += amount;
            if (count > maxBytes) {
                throw new IOException("文件大小超过限制");
            }
        }
    }
}

package com.javaee.fileservice.service;

import com.javaee.common.exception.BusinessException;
import com.javaee.fileservice.config.FileStorageConfig;
import com.javaee.fileservice.config.FileTransferProperties;
import com.javaee.fileservice.dto.ChunkUploadInitDTO;
import com.javaee.fileservice.entity.FileMetadata;
import com.javaee.fileservice.model.ChunkUploadSession;
import com.javaee.fileservice.security.BucketPermissionService;
import com.javaee.fileservice.security.RequestUserContext;
import com.javaee.fileservice.util.FileUtils;
import io.minio.BucketExistsArgs;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkUploadService {

    private static final String UPLOADING = "UPLOADING";
    private static final String MERGING = "MERGING";
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final ChunkUploadSessionRepository sessionRepository;
    private final FileTransferProperties transferProperties;
    private final FileStorageConfig storageConfig;
    private final FileMetadataService metadataService;
    private final MinioClient minioClient;
    private final BucketPermissionService bucketPermissionService;
    private final RequestUserContext requestUserContext;
    private final UploadResourceGuard resourceGuard;

    public ChunkUploadSession createSession(ChunkUploadInitDTO request) {
        if (request == null) {
            throw new BusinessException("上传会话参数不能为空");
        }
        validateTotalChunks(request.getTotalChunks());
        if (request.getTotalSize() <= 0 || request.getTotalSize() > transferProperties.getMaxFileSize().toBytes()) {
            throw new BusinessException("文件大小超过限制或不合法");
        }
        validateSessionGeometry(request.getTotalChunks(), request.getTotalSize());
        if (request.getSha256() != null && !request.getSha256().isBlank()
                && !SHA256.matcher(request.getSha256()).matches()) {
            throw new BusinessException("SHA-256 格式不正确");
        }
        String userId = requestUserContext.getRequiredUserId();
        assertSessionQuota(userId);
        String bucket = storageConfig.getBucketName();
        if (isMinio()) {
            bucketPermissionService.assertCanAccess(bucket);
        }
        long now = Instant.now().toEpochMilli();
        ChunkUploadSession session = new ChunkUploadSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setOwnerUserId(userId);
        session.setBucketName(bucket);
        session.setFileName(FileTransferService.sanitizeFileName(request.getFileName()));
        session.setContentType(FileTransferService.normalizeContentType(
                request.getContentType(), session.getFileName()));
        session.setTotalChunks(request.getTotalChunks());
        session.setTotalSize(request.getTotalSize());
        session.setSha256(request.getSha256() == null ? null
                : request.getSha256().toLowerCase(Locale.ROOT));
        session.setStatus(UPLOADING);
        session.setCreatedAt(now);
        session.setExpiresAt(now + transferProperties.getSessionTtl().toMillis());
        sessionRepository.create(session);
        return session;
    }

    public void uploadChunk(MultipartFile chunk, String sessionId, int chunkIndex, int totalChunks) {
        validateSessionId(sessionId);
        validateTotalChunks(totalChunks);
        if (chunk == null || chunk.isEmpty()) {
            throw new BusinessException("分片不能为空");
        }
        if (chunk.getSize() > transferProperties.getMaxChunkSize().toBytes()) {
            throw new BusinessException("分片大小超过限制");
        }
        ChunkUploadSession session = sessionRepository.find(sessionId)
                .orElseGet(() -> createLegacySession(sessionId, totalChunks));
        assertOwnerAndActive(session);
        if (session.getTotalChunks() != totalChunks || chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new BusinessException("分片序号或总数与上传会话不一致");
        }
        if (isMinio() && chunkIndex < totalChunks - 1
                && chunk.getSize() < transferProperties.getMinComposePartSize().toBytes()) {
            throw new BusinessException("除最后一片外，MinIO 分片不得小于 "
                    + transferProperties.getMinComposePartSize().toMegabytes() + "MB");
        }
        if (!sessionRepository.tryAcquireChunkLock(sessionId, chunkIndex)) {
            throw new BusinessException("该分片正在上传，请勿重复提交");
        }
        boolean stored = false;
        boolean writeStarted = false;
        try (UploadResourceGuard.Permit ignored = resourceGuard.acquire();
             InputStream input = chunk.getInputStream()) {
            ChunkUploadSession current = requireOwnedSession(sessionId);
            if (sessionRepository.isChunkReceived(sessionId, chunkIndex)) {
                throw new BusinessException("该分片已上传");
            }
            long sessionLimit = current.getTotalSize() >= 0
                    ? Math.min(current.getTotalSize(), transferProperties.getMaxFileSize().toBytes())
                    : transferProperties.getMaxFileSize().toBytes();
            if (sessionRepository.uploadedSize(sessionId) + chunk.getSize() > sessionLimit) {
                throw new BusinessException("上传分片累计大小超过会话限制");
            }
            writeStarted = true;
            if (isMinio()) {
                bucketPermissionService.assertCanAccess(session.getBucketName());
                ensureBucket(session.getBucketName());
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(session.getBucketName())
                        .object(partObjectKey(session, chunkIndex))
                        .stream(input, chunk.getSize(), -1)
                        .contentType("application/octet-stream")
                        .build());
            } else {
                Path part = localPartPath(session, chunkIndex);
                Files.createDirectories(part.getParent());
                long copied = Files.copy(input, part, StandardCopyOption.REPLACE_EXISTING);
                if (copied != chunk.getSize()) {
                    Files.deleteIfExists(part);
                    throw new BusinessException("分片大小不一致");
                }
            }
            stored = true;
            long recordedTotal = sessionRepository.recordChunk(sessionId, chunkIndex, chunk.getSize());
            if (recordedTotal > sessionLimit) {
                sessionRepository.removeChunk(sessionId, chunkIndex, chunk.getSize());
                deletePartQuietly(session, chunkIndex);
                stored = false;
                throw new BusinessException("上传分片累计大小超过会话限制");
            }
        } catch (BusinessException e) {
            if (writeStarted) {
                cleanupFailedChunk(session, chunkIndex, chunk.getSize(), stored);
            }
            throw e;
        } catch (Exception e) {
            if (writeStarted) {
                cleanupFailedChunk(session, chunkIndex, chunk.getSize(), stored);
            }
            throw new BusinessException("分片上传失败: " + e.getMessage());
        } finally {
            sessionRepository.releaseChunkLock(sessionId, chunkIndex);
        }
    }

    public String merge(String sessionId, String legacyFileName) {
        validateSessionId(sessionId);
        ChunkUploadSession session = requireOwnedSession(sessionId);
        if (!sessionRepository.tryAcquireMergeLock(sessionId)) {
            throw new BusinessException("该文件正在合并，请勿重复提交");
        }
        String fileName = session.getFileName() == null
                ? FileTransferService.sanitizeFileName(legacyFileName) : session.getFileName();
        String contentType = FileTransferService.normalizeContentType(session.getContentType(), fileName);
        String extension = FileUtils.getFileExtension(fileName);
        String objectKey = sessionId + (extension == null ? "" : "." + extension);
        boolean finalObjectCreated = false;
        try (UploadResourceGuard.Permit ignored = resourceGuard.acquire()) {
            long totalSize = validateCompleteness(session);
            sessionRepository.updateStatus(sessionId, MERGING);
            if (isMinio()) {
                bucketPermissionService.assertCanAccess(session.getBucketName());
                List<ComposeSource> sources = new ArrayList<>(session.getTotalChunks());
                for (int i = 0; i < session.getTotalChunks(); i++) {
                    sources.add(ComposeSource.builder()
                            .bucket(session.getBucketName())
                            .object(partObjectKey(session, i))
                            .build());
                }
                minioClient.composeObject(ComposeObjectArgs.builder()
                        .bucket(session.getBucketName())
                        .object(objectKey)
                        .sources(sources)
                        .build());
            } else {
                mergeLocal(session, objectKey);
            }
            finalObjectCreated = true;
            verifySha256IfPresent(session, objectKey);
            saveMetadata(session, fileName, contentType, totalSize, objectKey);
            deleteParts(session);
            sessionRepository.delete(session);
            return sessionId;
        } catch (BusinessException e) {
            if (finalObjectCreated) {
                deleteFinalObjectQuietly(session, objectKey);
            }
            sessionRepository.updateStatus(sessionId, UPLOADING);
            throw e;
        } catch (Exception e) {
            if (finalObjectCreated) {
                deleteFinalObjectQuietly(session, objectKey);
            }
            sessionRepository.updateStatus(sessionId, UPLOADING);
            throw new BusinessException("分片合并失败: " + e.getMessage());
        } finally {
            sessionRepository.releaseMergeLock(sessionId);
        }
    }

    public void abort(String sessionId) {
        ChunkUploadSession session = requireOwnedSession(sessionId);
        deleteParts(session);
        sessionRepository.delete(session);
    }

    public void cleanupExpiredSessions() {
        for (String sessionId : sessionRepository.findExpired(Instant.now().toEpochMilli(), 100)) {
            sessionRepository.find(sessionId).ifPresentOrElse(session -> {
                try {
                    deleteParts(session);
                } finally {
                    sessionRepository.delete(session);
                }
            }, () -> sessionRepository.removeExpiryMarker(sessionId));
        }
    }

    private ChunkUploadSession createLegacySession(String sessionId, int totalChunks) {
        String userId = requestUserContext.getRequiredUserId();
        assertSessionQuota(userId);
        if (metadataService.getMetadata(sessionId) != null) {
            throw new BusinessException("文件ID已存在，不能创建分片会话");
        }
        long now = Instant.now().toEpochMilli();
        ChunkUploadSession session = new ChunkUploadSession();
        session.setSessionId(sessionId);
        session.setOwnerUserId(userId);
        session.setBucketName(storageConfig.getBucketName());
        session.setTotalChunks(totalChunks);
        session.setTotalSize(-1);
        session.setStatus(UPLOADING);
        session.setCreatedAt(now);
        session.setExpiresAt(now + transferProperties.getSessionTtl().toMillis());
        sessionRepository.create(session);
        return session;
    }

    private ChunkUploadSession requireOwnedSession(String sessionId) {
        ChunkUploadSession session = sessionRepository.find(sessionId)
                .orElseThrow(() -> new BusinessException("上传会话不存在或已过期"));
        assertOwnerAndActive(session);
        return session;
    }

    private void assertOwnerAndActive(ChunkUploadSession session) {
        if (!requestUserContext.getRequiredUserId().equals(session.getOwnerUserId())) {
            throw new BusinessException("无权操作该上传会话");
        }
        if (session.getExpiresAt() <= Instant.now().toEpochMilli()) {
            throw new BusinessException("上传会话已过期");
        }
        if (!UPLOADING.equals(session.getStatus())) {
            throw new BusinessException("上传会话当前不可写");
        }
    }

    private long validateCompleteness(ChunkUploadSession session) {
        Set<Integer> received = sessionRepository.receivedChunks(session.getSessionId());
        if (received.size() != session.getTotalChunks()) {
            throw new BusinessException("分片不完整，已收到 " + received.size()
                    + "/" + session.getTotalChunks());
        }
        long total = 0;
        for (int i = 0; i < session.getTotalChunks(); i++) {
            if (!received.contains(i)) {
                throw new BusinessException("缺少分片: " + i);
            }
            long size = sessionRepository.chunkSize(session.getSessionId(), i);
            if (size < 0 || size > transferProperties.getMaxChunkSize().toBytes()) {
                throw new BusinessException("分片大小记录异常: " + i);
            }
            total = Math.addExact(total, size);
            if (total > transferProperties.getMaxFileSize().toBytes()) {
                throw new BusinessException("合并文件超过大小限制");
            }
        }
        if (session.getTotalSize() >= 0 && total != session.getTotalSize()) {
            throw new BusinessException("分片总大小与会话声明不一致");
        }
        return total;
    }

    private void mergeLocal(ChunkUploadSession session, String objectKey) throws Exception {
        Path target = localObjectPath(objectKey);
        Files.createDirectories(target.getParent());
        byte[] buffer = new byte[Math.toIntExact(transferProperties.getBufferSize().toBytes())];
        try (OutputStream output = Files.newOutputStream(target,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int i = 0; i < session.getTotalChunks(); i++) {
                try (InputStream input = Files.newInputStream(localPartPath(session, i))) {
                    copy(input, output, buffer);
                }
            }
        } catch (Exception e) {
            Files.deleteIfExists(target);
            throw e;
        }
    }

    private void verifySha256IfPresent(ChunkUploadSession session, String objectKey) throws Exception {
        if (session.getSha256() == null || session.getSha256().isBlank()) {
            return;
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[Math.toIntExact(transferProperties.getBufferSize().toBytes())];
        try (InputStream input = openFinalObject(session, objectKey)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(session.getSha256())) {
            throw new BusinessException("文件 SHA-256 校验失败");
        }
    }

    private InputStream openFinalObject(ChunkUploadSession session, String objectKey) throws Exception {
        if (isMinio()) {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(session.getBucketName()).object(objectKey).build());
        }
        return Files.newInputStream(localObjectPath(objectKey));
    }

    private void saveMetadata(ChunkUploadSession session, String fileName, String contentType,
                              long size, String objectKey) {
        FileMetadata metadata = new FileMetadata();
        metadata.setFileId(session.getSessionId());
        metadata.setFileName(fileName);
        metadata.setOriginalFileName(fileName);
        metadata.setFilePath(isMinio() ? "minio:" + session.getBucketName() : storageConfig.getLocalPath());
        metadata.setFileType(contentType);
        metadata.setFileSize(size);
        metadata.setStorageType(storageConfig.getStorageType());
        metadata.setBucketName(session.getBucketName());
        metadata.setObjectKey(objectKey);
        metadata.setCreateBy(session.getOwnerUserId());
        metadataService.saveMetadata(metadata);
    }

    private void deleteParts(ChunkUploadSession session) {
        for (int i = 0; i < session.getTotalChunks(); i++) {
            try {
                if (isMinio()) {
                    minioClient.removeObject(RemoveObjectArgs.builder()
                            .bucket(session.getBucketName())
                            .object(partObjectKey(session, i)).build());
                } else {
                    Files.deleteIfExists(localPartPath(session, i));
                }
            } catch (Exception e) {
                log.warn("清理上传分片失败: sessionId={}, chunkIndex={}",
                        session.getSessionId(), i, e);
            }
        }
    }

    private void deletePartQuietly(ChunkUploadSession session, int index) {
        try {
            if (isMinio()) {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(session.getBucketName())
                        .object(partObjectKey(session, index)).build());
            } else {
                Files.deleteIfExists(localPartPath(session, index));
            }
        } catch (Exception e) {
            log.warn("回滚超限分片失败: sessionId={}, chunkIndex={}",
                    session.getSessionId(), index, e);
        }
    }

    private void rollbackStoredChunk(ChunkUploadSession session, int index, long size) {
        try {
            if (sessionRepository.isChunkReceived(session.getSessionId(), index)) {
                sessionRepository.removeChunk(session.getSessionId(), index, size);
            }
        } catch (Exception e) {
            log.warn("回滚分片会话记录失败: sessionId={}, chunkIndex={}",
                    session.getSessionId(), index, e);
        }
        deletePartQuietly(session, index);
    }

    private void cleanupFailedChunk(ChunkUploadSession session, int index, long size, boolean recorded) {
        if (recorded) {
            rollbackStoredChunk(session, index, size);
        } else {
            deletePartQuietly(session, index);
        }
    }

    private void deleteFinalObjectQuietly(ChunkUploadSession session, String objectKey) {
        try {
            if (isMinio()) {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(session.getBucketName()).object(objectKey).build());
            } else {
                Files.deleteIfExists(localObjectPath(objectKey));
            }
        } catch (Exception e) {
            log.warn("清理合并失败的目标文件失败: sessionId={}", session.getSessionId(), e);
        }
    }

    private Path localPartPath(ChunkUploadSession session, int index) {
        Path root = Path.of(storageConfig.getLocalPath()).toAbsolutePath().normalize();
        Path target = root.resolve(".chunks")
                .resolve(session.getOwnerUserId())
                .resolve(session.getSessionId())
                .resolve(index + ".part").normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("非法分片路径");
        }
        return target;
    }

    private Path localObjectPath(String objectKey) {
        Path root = Path.of(storageConfig.getLocalPath()).toAbsolutePath().normalize();
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("非法文件路径");
        }
        return target;
    }

    private String partObjectKey(ChunkUploadSession session, int index) {
        return "__multipart/" + session.getOwnerUserId() + "/"
                + session.getSessionId() + "/parts/" + index;
    }

    private void ensureBucket(String bucket) throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    private void assertSessionQuota(String userId) {
        if (sessionRepository.activeSessionCount(userId)
                >= transferProperties.getMaxActiveSessionsPerUser()) {
            throw new BusinessException("进行中的分片上传会话过多");
        }
    }

    private void validateSessionId(String sessionId) {
        if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) {
            throw new BusinessException("上传会话ID不合法");
        }
    }

    private void validateTotalChunks(int totalChunks) {
        if (totalChunks < 1 || totalChunks > transferProperties.getMaxChunks()) {
            throw new BusinessException("分片数量必须在 1 到 " + transferProperties.getMaxChunks() + " 之间");
        }
    }

    private void validateSessionGeometry(int totalChunks, long totalSize) {
        long maxChunk = transferProperties.getMaxChunkSize().toBytes();
        if (totalSize > Math.multiplyExact((long) totalChunks, maxChunk)) {
            throw new BusinessException("文件大小无法由声明的分片数量承载");
        }
        if (isMinio()) {
            long minimum = Math.addExact(
                    Math.multiplyExact((long) totalChunks - 1,
                            transferProperties.getMinComposePartSize().toBytes()), 1);
            if (totalSize < minimum) {
                throw new BusinessException("文件大小与 MinIO 分片数量不匹配");
            }
        } else if (totalSize < totalChunks) {
            throw new BusinessException("每个分片必须至少包含一个字节");
        }
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

    private void copy(InputStream input, OutputStream output, byte[] buffer) throws Exception {
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }
}

package com.javaee.fileservice.service;

import com.javaee.fileservice.config.FileTransferProperties;
import com.javaee.fileservice.model.ChunkUploadSession;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class ChunkUploadSessionRepository {

    private static final String PREFIX = "file:upload:session:";
    private static final String EXPIRY_INDEX = "file:upload:sessions:expiry";

    private final StringRedisTemplate redisTemplate;
    private final FileTransferProperties properties;

    public ChunkUploadSessionRepository(
            StringRedisTemplate redisTemplate,
            FileTransferProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void create(ChunkUploadSession session) {
        String key = sessionKey(session.getSessionId());
        Map<String, String> values = new HashMap<>();
        values.put("sessionId", session.getSessionId());
        values.put("ownerUserId", session.getOwnerUserId());
        values.put("bucketName", session.getBucketName());
        values.put("fileName", nullToEmpty(session.getFileName()));
        values.put("contentType", nullToEmpty(session.getContentType()));
        values.put("totalChunks", String.valueOf(session.getTotalChunks()));
        values.put("totalSize", String.valueOf(session.getTotalSize()));
        values.put("sha256", nullToEmpty(session.getSha256()));
        values.put("status", session.getStatus());
        values.put("createdAt", String.valueOf(session.getCreatedAt()));
        values.put("expiresAt", String.valueOf(session.getExpiresAt()));
        values.put("uploadedSize", "0");
        redisTemplate.opsForHash().putAll(key, values);
        Duration storageTtl = properties.getSessionTtl().plus(properties.getCleanupGrace());
        redisTemplate.expire(key, storageTtl);
        redisTemplate.expire(chunksKey(session.getSessionId()), storageTtl);
        redisTemplate.expire(sizesKey(session.getSessionId()), storageTtl);
        redisTemplate.opsForZSet().add(EXPIRY_INDEX, session.getSessionId(), session.getExpiresAt());
        redisTemplate.opsForSet().add(userSessionsKey(session.getOwnerUserId()), session.getSessionId());
        redisTemplate.expire(userSessionsKey(session.getOwnerUserId()), storageTtl);
    }

    public Optional<ChunkUploadSession> find(String sessionId) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(sessionKey(sessionId));
        if (values.isEmpty()) {
            return Optional.empty();
        }
        ChunkUploadSession session = new ChunkUploadSession();
        session.setSessionId(value(values, "sessionId"));
        session.setOwnerUserId(value(values, "ownerUserId"));
        session.setBucketName(value(values, "bucketName"));
        session.setFileName(emptyToNull(value(values, "fileName")));
        session.setContentType(emptyToNull(value(values, "contentType")));
        session.setTotalChunks(Integer.parseInt(value(values, "totalChunks")));
        session.setTotalSize(Long.parseLong(value(values, "totalSize")));
        session.setSha256(emptyToNull(value(values, "sha256")));
        session.setStatus(value(values, "status"));
        session.setCreatedAt(Long.parseLong(value(values, "createdAt")));
        session.setExpiresAt(Long.parseLong(value(values, "expiresAt")));
        return Optional.of(session);
    }

    public long recordChunk(String sessionId, int chunkIndex, long size) {
        redisTemplate.opsForSet().add(chunksKey(sessionId), String.valueOf(chunkIndex));
        redisTemplate.opsForHash().put(sizesKey(sessionId), String.valueOf(chunkIndex), String.valueOf(size));
        Long uploadedSize = redisTemplate.opsForHash()
                .increment(sessionKey(sessionId), "uploadedSize", size);
        Duration storageTtl = properties.getSessionTtl().plus(properties.getCleanupGrace());
        redisTemplate.expire(chunksKey(sessionId), storageTtl);
        redisTemplate.expire(sizesKey(sessionId), storageTtl);
        return uploadedSize == null ? size : uploadedSize;
    }

    public boolean isChunkReceived(String sessionId, int chunkIndex) {
        Boolean member = redisTemplate.opsForSet()
                .isMember(chunksKey(sessionId), String.valueOf(chunkIndex));
        return Boolean.TRUE.equals(member);
    }

    public long uploadedSize(String sessionId) {
        Object value = redisTemplate.opsForHash().get(sessionKey(sessionId), "uploadedSize");
        return value == null ? 0 : Long.parseLong(value.toString());
    }

    public void removeChunk(String sessionId, int chunkIndex, long size) {
        redisTemplate.opsForSet().remove(chunksKey(sessionId), String.valueOf(chunkIndex));
        redisTemplate.opsForHash().delete(sizesKey(sessionId), String.valueOf(chunkIndex));
        redisTemplate.opsForHash().increment(sessionKey(sessionId), "uploadedSize", -size);
    }

    public boolean tryAcquireChunkLock(String sessionId, int chunkIndex) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                chunkLockKey(sessionId, chunkIndex), "locked", properties.getChunkLockTtl());
        return Boolean.TRUE.equals(acquired);
    }

    public void releaseChunkLock(String sessionId, int chunkIndex) {
        redisTemplate.delete(chunkLockKey(sessionId, chunkIndex));
    }

    public Set<Integer> receivedChunks(String sessionId) {
        Set<String> raw = redisTemplate.opsForSet().members(chunksKey(sessionId));
        Set<Integer> result = new HashSet<>();
        if (raw != null) {
            raw.forEach(value -> result.add(Integer.parseInt(value)));
        }
        return result;
    }

    public long chunkSize(String sessionId, int chunkIndex) {
        Object value = redisTemplate.opsForHash().get(sizesKey(sessionId), String.valueOf(chunkIndex));
        return value == null ? -1 : Long.parseLong(value.toString());
    }

    public long activeSessionCount(String userId) {
        Long count = redisTemplate.opsForSet().size(userSessionsKey(userId));
        return count == null ? 0 : count;
    }

    public boolean tryAcquireMergeLock(String sessionId) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                lockKey(sessionId), "locked", properties.getMergeLockTtl());
        return Boolean.TRUE.equals(acquired);
    }

    public void releaseMergeLock(String sessionId) {
        redisTemplate.delete(lockKey(sessionId));
    }

    public void updateStatus(String sessionId, String status) {
        redisTemplate.opsForHash().put(sessionKey(sessionId), "status", status);
    }

    public List<String> findExpired(long now, int limit) {
        Set<String> ids = redisTemplate.opsForZSet().rangeByScore(EXPIRY_INDEX, 0, now, 0, limit);
        return ids == null ? List.of() : new ArrayList<>(ids);
    }

    public void delete(ChunkUploadSession session) {
        redisTemplate.delete(List.of(
                sessionKey(session.getSessionId()),
                chunksKey(session.getSessionId()),
                sizesKey(session.getSessionId()),
                lockKey(session.getSessionId())));
        redisTemplate.opsForZSet().remove(EXPIRY_INDEX, session.getSessionId());
        redisTemplate.opsForSet().remove(userSessionsKey(session.getOwnerUserId()), session.getSessionId());
    }

    public void removeExpiryMarker(String sessionId) {
        redisTemplate.opsForZSet().remove(EXPIRY_INDEX, sessionId);
    }

    private String sessionKey(String id) { return PREFIX + id; }
    private String chunksKey(String id) { return PREFIX + id + ":chunks"; }
    private String sizesKey(String id) { return PREFIX + id + ":sizes"; }
    private String lockKey(String id) { return PREFIX + id + ":merge-lock"; }
    private String chunkLockKey(String id, int index) { return PREFIX + id + ":chunk-lock:" + index; }
    private String userSessionsKey(String userId) { return "file:upload:user:" + userId + ":sessions"; }
    private String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("上传会话数据不完整: " + key);
        }
        return value.toString();
    }
    private String nullToEmpty(String value) { return value == null ? "" : value; }
    private String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
}

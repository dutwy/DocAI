package com.javaee.fileservice.model;

import lombok.Data;

@Data
public class ChunkUploadSession {
    private String sessionId;
    private String ownerUserId;
    private String bucketName;
    private String fileName;
    private String contentType;
    private int totalChunks;
    private long totalSize;
    private String sha256;
    private String status;
    private long createdAt;
    private long expiresAt;
}

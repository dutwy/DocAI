package com.javaee.fileservice.dto;

import lombok.Data;

@Data
public class ChunkUploadInitDTO {
    private String fileName;
    private String contentType;
    private int totalChunks;
    private long totalSize;
    private String sha256;
}

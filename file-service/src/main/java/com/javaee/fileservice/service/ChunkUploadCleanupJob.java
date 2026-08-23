package com.javaee.fileservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkUploadCleanupJob {

    private final ChunkUploadService chunkUploadService;

    @Scheduled(fixedDelayString = "${file.transfer.cleanup-interval:PT5M}")
    public void cleanup() {
        try {
            chunkUploadService.cleanupExpiredSessions();
        } catch (Exception e) {
            log.warn("清理过期分片上传会话失败", e);
        }
    }
}

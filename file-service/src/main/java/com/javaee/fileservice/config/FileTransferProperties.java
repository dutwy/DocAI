package com.javaee.fileservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "file.transfer")
public class FileTransferProperties {

    private DataSize maxFileSize = DataSize.ofGigabytes(1);
    private DataSize maxChunkSize = DataSize.ofMegabytes(16);
    private DataSize minComposePartSize = DataSize.ofMegabytes(5);
    private DataSize bufferSize = DataSize.ofKilobytes(64);
    private int maxChunks = 10_000;
    private int maxMultipleFiles = 20;
    private int maxConcurrentStreams = 8;
    private int maxActiveSessionsPerUser = 3;
    private Duration acquireTimeout = Duration.ofSeconds(2);
    private Duration sessionTtl = Duration.ofHours(24);
    private Duration cleanupGrace = Duration.ofHours(1);
    private Duration mergeLockTtl = Duration.ofMinutes(30);
    private Duration chunkLockTtl = Duration.ofMinutes(5);
}

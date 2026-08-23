package com.javaee.fileservice.service;

import com.javaee.common.exception.BusinessException;
import com.javaee.fileservice.config.FileStorageConfig;
import com.javaee.fileservice.config.FileTransferProperties;
import com.javaee.fileservice.model.ChunkUploadSession;
import com.javaee.fileservice.security.BucketPermissionService;
import com.javaee.fileservice.security.RequestUserContext;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChunkUploadServiceTest {

    private ChunkUploadSessionRepository repository;
    private FileTransferProperties properties;
    private RequestUserContext userContext;
    private ChunkUploadService service;

    @BeforeEach
    void setUp() {
        repository = mock(ChunkUploadSessionRepository.class);
        properties = new FileTransferProperties();
        properties.setMaxChunkSize(DataSize.ofBytes(2));
        properties.setMaxChunks(4);
        userContext = mock(RequestUserContext.class);
        FileStorageConfig storageConfig = mock(FileStorageConfig.class);
        when(storageConfig.getStorageType()).thenReturn("local");
        when(storageConfig.getLocalPath()).thenReturn("build/test-chunks");
        when(userContext.getRequiredUserId()).thenReturn("current-user");
        service = new ChunkUploadService(
                repository,
                properties,
                storageConfig,
                mock(FileMetadataService.class),
                mock(MinioClient.class),
                mock(BucketPermissionService.class),
                userContext,
                new UploadResourceGuard(properties));
    }

    @Test
    void rejectsOversizedChunksBeforeWritingStorage() {
        MockMultipartFile chunk = new MockMultipartFile("chunk", new byte[]{1, 2, 3});
        assertThrows(BusinessException.class,
                () -> service.uploadChunk(chunk, "session-1", 0, 1));
    }

    @Test
    void rejectsChunkCountAboveConfiguredLimit() {
        MockMultipartFile chunk = new MockMultipartFile("chunk", new byte[]{1});
        assertThrows(BusinessException.class,
                () -> service.uploadChunk(chunk, "session-1", 0, 5));
    }

    @Test
    void rejectsCrossUserSessionAccess() {
        ChunkUploadSession session = new ChunkUploadSession();
        session.setSessionId("session-1");
        session.setOwnerUserId("another-user");
        session.setTotalChunks(1);
        session.setStatus("UPLOADING");
        session.setExpiresAt(Instant.now().plusSeconds(60).toEpochMilli());
        when(repository.find("session-1")).thenReturn(Optional.of(session));

        MockMultipartFile chunk = new MockMultipartFile("chunk", new byte[]{1});
        assertThrows(BusinessException.class,
                () -> service.uploadChunk(chunk, "session-1", 0, 1));
    }
}

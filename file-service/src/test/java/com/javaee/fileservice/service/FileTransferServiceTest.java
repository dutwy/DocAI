package com.javaee.fileservice.service;

import com.javaee.common.exception.BusinessException;
import com.javaee.fileservice.config.FileStorageConfig;
import com.javaee.fileservice.config.FileTransferProperties;
import com.javaee.fileservice.entity.FileMetadata;
import com.javaee.fileservice.model.FileDownloadResource;
import com.javaee.fileservice.security.BucketPermissionService;
import com.javaee.fileservice.security.RequestUserContext;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileTransferServiceTest {

    @TempDir
    Path storageRoot;

    private FileStorageConfig storageConfig;
    private FileMetadataService metadataService;
    private FileTransferService service;

    @BeforeEach
    void setUp() {
        storageConfig = mock(FileStorageConfig.class);
        metadataService = mock(FileMetadataService.class);
        RequestUserContext userContext = mock(RequestUserContext.class);
        when(storageConfig.getStorageType()).thenReturn("local");
        when(storageConfig.getLocalPath()).thenReturn(storageRoot.toString());
        when(storageConfig.getBucketName()).thenReturn("unused-local-bucket");
        when(userContext.getRequiredUserId()).thenReturn("42");

        FileTransferProperties properties = new FileTransferProperties();
        properties.setMaxConcurrentStreams(1);
        properties.setAcquireTimeout(Duration.ofMillis(5));
        service = new FileTransferService(
                storageConfig,
                properties,
                metadataService,
                mock(MinioClient.class),
                mock(BucketPermissionService.class),
                userContext,
                new UploadResourceGuard(properties));
    }

    @Test
    void uploadsAndDownloadsWithoutWholeFileBufferingContractLeak() throws Exception {
        byte[] content = "streamed-content".getBytes();
        String fileId = service.upload(
                new ByteArrayInputStream(content), content.length, "report.txt", "text/plain");

        org.mockito.ArgumentCaptor<FileMetadata> captor =
                org.mockito.ArgumentCaptor.forClass(FileMetadata.class);
        verify(metadataService).saveMetadata(captor.capture());
        FileMetadata metadata = captor.getValue();
        assertEquals(fileId, metadata.getFileId());
        assertEquals("42", metadata.getCreateBy());
        assertArrayEquals(content, Files.readAllBytes(storageRoot.resolve(metadata.getObjectKey())));

        when(metadataService.getMetadata(fileId)).thenReturn(metadata);
        try (FileDownloadResource resource = service.openDownload(fileId)) {
            assertArrayEquals(content, resource.getInputStream().readAllBytes());
            assertThrows(BusinessException.class, () -> service.openDownload(fileId),
                    "未关闭的下载流必须占用并发许可");
        }
        try (FileDownloadResource ignored = service.openDownload(fileId)) {
            assertEquals(content.length, ignored.getContentLength());
        }
    }

    @Test
    void deletesPartialLocalObjectWhenDeclaredSizeDoesNotMatch() throws Exception {
        assertThrows(BusinessException.class, () -> service.upload(
                new ByteArrayInputStream(new byte[]{1, 2, 3}), 2, "bad.bin", null));
        try (var files = Files.list(storageRoot)) {
            assertEquals(0, files.count());
        }
    }
}

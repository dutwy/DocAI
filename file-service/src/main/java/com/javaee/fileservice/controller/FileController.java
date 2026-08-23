package com.javaee.fileservice.controller;

import com.javaee.common.model.Result;
import com.javaee.fileservice.config.FileTransferProperties;
import com.javaee.fileservice.config.RabbitMQConfig;
import com.javaee.fileservice.dto.ChunkUploadInitDTO;
import com.javaee.fileservice.entity.FileMetadata;
import com.javaee.fileservice.model.ChunkUploadSession;
import com.javaee.fileservice.model.FileDownloadResource;
import com.javaee.fileservice.security.RequestUserContext;
import com.javaee.fileservice.service.ChunkUploadService;
import com.javaee.fileservice.service.FileMetadataService;
import com.javaee.fileservice.service.FileService;
import com.javaee.fileservice.service.FileTransferService;
import com.javaee.fileservice.util.RabbitMQUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/files")
@Tag(name = "文件管理", description = "文件上传、下载、删除、分片等核心接口")
public class FileController {

    private final FileService fileService;
    private final FileTransferService fileTransferService;
    private final ChunkUploadService chunkUploadService;
    private final FileMetadataService fileMetadataService;
    private final RabbitMQUtil rabbitMQUtil;
    private final RequestUserContext requestUserContext;
    private final FileTransferProperties transferProperties;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "兼容模式单文件上传", description = "小文件 multipart 上传；大文件请使用 upload-stream")
    public Result<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
        try {
            String fileId = fileTransferService.upload(file);
            sendFileEvent(RabbitMQConfig.FILE_UPLOAD_ROUTING_KEY, fileId,
                    Map.of("fileName", String.valueOf(file.getOriginalFilename()), "fileSize", file.getSize()));
            return Result.success(Map.of("fileId", fileId, "message", "文件上传成功"));
        } catch (Exception e) {
            log.error("文件上传失败", e);
            return Result.fail("文件上传失败: " + e.getMessage());
        }
    }

    @PutMapping(value = "/upload-stream", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(summary = "流式文件上传", description = "请求体直接传输文件内容，不使用 multipart 临时缓冲")
    public Result<Map<String, String>> uploadStream(
            HttpServletRequest request,
            @RequestParam("fileName") String fileName,
            @RequestHeader("X-File-Size") long fileSize,
            @RequestHeader(value = "X-File-Content-Type", required = false) String contentType) {
        long requestLength = request.getContentLengthLong();
        if (requestLength >= 0 && requestLength != fileSize) {
            return Result.fail("请求 Content-Length 与 X-File-Size 不一致");
        }
        try (InputStream input = request.getInputStream()) {
            String fileId = fileTransferService.upload(input, fileSize, fileName, contentType);
            sendFileEvent(RabbitMQConfig.FILE_UPLOAD_ROUTING_KEY, fileId,
                    Map.of("fileName", fileName, "fileSize", fileSize));
            return Result.success(Map.of("fileId", fileId, "message", "文件上传成功"));
        } catch (Exception e) {
            log.error("流式文件上传失败", e);
            return Result.fail("文件上传失败: " + e.getMessage());
        }
    }

    @PostMapping(value = "/upload-multiple", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "多文件上传", description = "有文件数量与单文件大小限制")
    public Result<Map<String, Object>> uploadMultiple(@RequestParam("files") MultipartFile[] files) {
        try {
            String[] fileIds = fileTransferService.uploadMultiple(files);
            return Result.success(Map.of(
                    "fileIds", fileIds, "message", "文件上传成功", "count", fileIds.length));
        } catch (Exception e) {
            log.error("多文件上传失败", e);
            return Result.fail("文件上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/upload-sessions")
    @Operation(summary = "创建分片上传会话", description = "返回有所有者和有效期约束的持久化会话")
    public Result<Map<String, Object>> createUploadSession(@RequestBody ChunkUploadInitDTO request) {
        ChunkUploadSession session = chunkUploadService.createSession(request);
        return Result.success(Map.of(
                "sessionId", session.getSessionId(),
                "expiresAt", session.getExpiresAt(),
                "maxChunkSize", transferProperties.getMaxChunkSize().toBytes(),
                "minComposePartSize", transferProperties.getMinComposePartSize().toBytes()));
    }

    @PostMapping(value = "/upload-chunk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "分片上传", description = "分片直接写入后端存储，不驻留 JVM 堆内存")
    public ResponseEntity<Map<String, String>> uploadChunk(
            @RequestParam("chunk") MultipartFile chunk,
            @RequestParam("fileId") String sessionId,
            @RequestParam("chunkIndex") int chunkIndex,
            @RequestParam("totalChunks") int totalChunks) {
        try {
            chunkUploadService.uploadChunk(chunk, sessionId, chunkIndex, totalChunks);
            return ResponseEntity.ok(Map.of(
                    "message", "分片上传成功", "chunkIndex", String.valueOf(chunkIndex)));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "分片上传失败: " + e.getMessage()));
        }
    }

    @PostMapping("/merge-chunk")
    @Operation(summary = "分片合并", description = "校验完整性后以流式或存储端合成方式合并")
    public ResponseEntity<Map<String, String>> mergeChunk(
            @RequestParam("fileId") String sessionId,
            @RequestParam(value = "fileName", required = false) String fileName) {
        try {
            String mergedFileId = chunkUploadService.merge(sessionId, fileName);
            return ResponseEntity.ok(Map.of("fileId", mergedFileId, "message", "文件合并成功"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件合并失败: " + e.getMessage()));
        }
    }

    @DeleteMapping("/upload-sessions/{sessionId}")
    @Operation(summary = "取消分片上传会话")
    public ResponseEntity<Map<String, String>> abortUploadSession(@PathVariable String sessionId) {
        chunkUploadService.abort(sessionId);
        return ResponseEntity.ok(Map.of("message", "上传会话已取消"));
    }

    @GetMapping("/download/{fileId}")
    @Operation(summary = "流式文件下载", description = "下载过程中不将完整文件载入内存")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String fileId) {
        ResponseEntity<StreamingResponseBody> response = streamResponse(fileId, false);
        sendFileEvent(RabbitMQConfig.FILE_DOWNLOAD_ROUTING_KEY, fileId, Map.of());
        return response;
    }

    @GetMapping("/preview/{fileId}")
    @Operation(summary = "流式文件预览", description = "以受限的 inline 响应流预览文件")
    public ResponseEntity<StreamingResponseBody> preview(@PathVariable String fileId) {
        return streamResponse(fileId, true);
    }

    @GetMapping("/info/{fileId}/name")
    @Operation(summary = "获取文件名")
    public ResponseEntity<String> getFileName(@PathVariable String fileId) {
        String fileName = Optional.ofNullable(fileMetadataService.getMetadata(fileId))
                .map(metadata -> metadata.getOriginalFileName() != null
                        ? metadata.getOriginalFileName() : metadata.getFileName())
                .orElse(fileId);
        return ResponseEntity.ok(fileName);
    }

    @GetMapping("/info/{fileId}/type")
    @Operation(summary = "获取文件类型")
    public ResponseEntity<String> getFileType(@PathVariable String fileId) {
        String fileType = Optional.ofNullable(fileMetadataService.getMetadata(fileId))
                .map(FileMetadata::getFileType)
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        return ResponseEntity.ok(fileType);
    }

    @DeleteMapping("/{fileId}")
    @Operation(summary = "文件删除")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String fileId) {
        try {
            fileService.delete(fileId);
            sendFileEvent(RabbitMQConfig.FILE_DELETE_ROUTING_KEY, fileId, Map.of());
            return ResponseEntity.ok(Map.of("message", "文件删除成功"));
        } catch (Exception e) {
            log.error("文件删除失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "文件删除失败: " + e.getMessage()));
        }
    }

    @PutMapping("/{fileId}/rename")
    @Operation(summary = "文件重命名")
    public ResponseEntity<Map<String, String>> rename(
            @PathVariable String fileId, @RequestParam("newName") String newName) {
        try {
            fileService.rename(fileId, newName);
            return ResponseEntity.ok(Map.of("message", "文件重命名成功"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件重命名失败: " + e.getMessage()));
        }
    }

    @PutMapping("/{fileId}/move")
    @Operation(summary = "文件移动")
    public ResponseEntity<Map<String, String>> move(
            @PathVariable String fileId, @RequestParam("targetPath") String targetPath) {
        try {
            fileService.move(fileId, targetPath);
            return ResponseEntity.ok(Map.of("message", "文件移动成功"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件移动失败: " + e.getMessage()));
        }
    }

    @PutMapping("/{fileId}/copy")
    @Operation(summary = "文件复制")
    public ResponseEntity<Map<String, String>> copy(
            @PathVariable String fileId, @RequestParam("targetPath") String targetPath) {
        try {
            String newFileId = fileService.copy(fileId, targetPath);
            return ResponseEntity.ok(Map.of("fileId", newFileId, "message", "文件复制成功"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件复制失败: " + e.getMessage()));
        }
    }

    private ResponseEntity<StreamingResponseBody> streamResponse(String fileId, boolean inline) {
        FileDownloadResource resource = fileTransferService.openDownload(fileId);
        String responseType = safePreviewContentType(resource.getContentType(), inline);
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(resource.getFileName(), StandardCharsets.UTF_8)
                .build();
        StreamingResponseBody body = output -> {
            try (resource) {
                copy(resource.getInputStream(), output);
            }
        };
        return ResponseEntity.ok()
                .contentLength(resource.getContentLength())
                .contentType(MediaType.parseMediaType(responseType))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .body(body);
    }

    private String safePreviewContentType(String contentType, boolean inline) {
        if (!inline) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        String lower = contentType == null ? "" : contentType.toLowerCase();
        if (lower.contains("html") || lower.contains("javascript")
                || lower.contains("svg") || lower.contains("xml")) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        return contentType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType;
    }

    private void copy(InputStream input, OutputStream output) throws java.io.IOException {
        byte[] buffer = new byte[Math.toIntExact(transferProperties.getBufferSize().toBytes())];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    private void sendFileEvent(String routingKey, String fileId, Map<String, Object> details) {
        Map<String, Object> message = new HashMap<>(details);
        message.put("fileId", fileId);
        message.put("userId", requestUserContext.getRequiredUserId());
        message.put("timestamp", LocalDateTime.now().toString());
        try {
            rabbitMQUtil.send(RabbitMQConfig.FILE_EXCHANGE, routingKey, message);
        } catch (Exception e) {
            log.warn("文件操作成功，但事件发送失败: fileId={}", fileId, e);
        }
    }
}

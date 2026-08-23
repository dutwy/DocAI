package com.javaee.fileservice.service.impl;

import com.javaee.common.exception.BusinessException;
import com.javaee.fileservice.entity.FileMetadata;
import com.javaee.fileservice.security.RequestUserContext;
import com.javaee.fileservice.service.FileMetadataService;
import com.javaee.fileservice.service.FileObjectStorage;
import com.javaee.fileservice.service.FilePathPolicy;
import com.javaee.fileservice.service.FileService;
import com.javaee.fileservice.util.FileUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {

    private final FileMetadataService metadataService;
    private final FileObjectStorage objectStorage;
    private final FilePathPolicy pathPolicy;
    private final RequestUserContext requestUserContext;

    @Override
    public void delete(String fileId) {
        FileMetadata metadata = requireMetadata(fileId);
        try {
            objectStorage.delete(metadata);
            metadataService.deleteMetadata(fileId);
        } catch (Exception exception) {
            throw new BusinessException("文件删除失败: " + exception.getMessage());
        }
    }

    @Override
    public void rename(String fileId, String newName) {
        FileMetadata metadata = requireMetadata(fileId);
        String safeName = pathPolicy.sanitizeFileName(newName);
        metadata.setFileName(safeName);
        metadata.setOriginalFileName(safeName);
        metadata.setFileType(FileUtils.getContentType(safeName));
        metadataService.updateMetadata(metadata);
    }

    @Override
    public void move(String fileId, String targetPath) {
        FileMetadata metadata = requireMetadata(fileId);
        String prefix = pathPolicy.normalizeRelativePrefix(targetPath);
        String newObjectKey = pathPolicy.joinObjectKey(prefix,
                pathPolicy.objectBaseName(metadata.getObjectKey()));
        if (newObjectKey.equals(metadata.getObjectKey())) {
            return;
        }
        try {
            objectStorage.move(metadata, newObjectKey);
            metadata.setObjectKey(newObjectKey);
            metadata.setFilePath(prefix);
            metadataService.updateMetadata(metadata);
        } catch (Exception exception) {
            throw new BusinessException("文件移动失败: " + exception.getMessage());
        }
    }

    @Override
    public String copy(String fileId, String targetPath) {
        FileMetadata source = requireMetadata(fileId);
        String prefix = pathPolicy.normalizeRelativePrefix(targetPath);
        String newFileId = UUID.randomUUID().toString();
        String extension = FileUtils.getFileExtension(source.getOriginalFileName());
        String objectName = newFileId + (extension == null ? "" : "." + extension);
        String newObjectKey = pathPolicy.joinObjectKey(prefix, objectName);
        try {
            objectStorage.copy(source, newObjectKey);
            metadataService.saveMetadata(copyMetadata(source, newFileId, newObjectKey, prefix));
            return newFileId;
        } catch (Exception exception) {
            objectStorage.deleteQuietly(source, newObjectKey);
            throw new BusinessException("文件复制失败: " + exception.getMessage());
        }
    }

    private FileMetadata requireMetadata(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new BusinessException("文件ID不能为空");
        }
        FileMetadata metadata = metadataService.getMetadata(fileId);
        if (metadata == null || metadata.getObjectKey() == null || metadata.getObjectKey().isBlank()) {
            throw new BusinessException("文件不存在或无权访问");
        }
        if (objectStorage.isLocal() && metadata.getCreateBy() != null
                && !requestUserContext.getRequiredUserId().equals(metadata.getCreateBy())) {
            throw new BusinessException("文件不存在或无权访问");
        }
        return metadata;
    }

    private FileMetadata copyMetadata(FileMetadata source, String fileId,
                                      String objectKey, String filePath) {
        FileMetadata copy = new FileMetadata();
        copy.setFileId(fileId);
        copy.setFileName(source.getFileName());
        copy.setOriginalFileName(source.getOriginalFileName());
        copy.setFilePath(filePath);
        copy.setFileType(source.getFileType());
        copy.setFileSize(source.getFileSize());
        copy.setMd5(source.getMd5());
        copy.setStorageType(source.getStorageType());
        copy.setBucketName(source.getBucketName());
        copy.setObjectKey(objectKey);
        copy.setCreateBy(requestUserContext.getRequiredUserId());
        return copy;
    }
}

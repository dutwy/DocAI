package com.javaee.fileservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.javaee.fileservice.config.FileStorageConfig;
import com.javaee.fileservice.entity.FileMetadata;
import com.javaee.fileservice.mapper.FileMetadataMapper;
import com.javaee.fileservice.security.RequestUserContext;
import com.javaee.fileservice.service.FileMetadataService;
import com.javaee.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件元数据服务实现类
 */
@Service
@Slf4j
public class FileMetadataServiceImpl implements FileMetadataService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "createTime", "create_time",
            "updateTime", "update_time",
            "fileName", "file_name",
            "fileSize", "file_size",
            "fileType", "file_type"
    );

    @Autowired
    private FileMetadataMapper fileMetadataMapper;

    @Autowired
    private FileStorageConfig fileStorageConfig;

    @Autowired
    private RequestUserContext requestUserContext;

    @Override
    public FileMetadata getMetadata(String fileId) {
        try {
            FileMetadata metadata = fileMetadataMapper.selectByFileId(fileId);
            return isCurrentBucketMetadata(metadata) ? metadata : null;
        } catch (Exception e) {
            throw new BusinessException("获取元数据失败: " + e.getMessage());
        }
    }

    @Override
    public void saveMetadata(FileMetadata fileMetadata) {
        try {
            fileMetadata.setCreateTime(LocalDateTime.now());
            fileMetadata.setUpdateTime(LocalDateTime.now());
            fileMetadata.setStatus("ACTIVE");
            fileMetadataMapper.insert(fileMetadata);
        } catch (Exception e) {
            throw new BusinessException("保存元数据失败: " + e.getMessage());
        }
    }

    @Override
    public void updateMetadata(FileMetadata fileMetadata) {
        try {
            fileMetadata.setUpdateTime(LocalDateTime.now());
            QueryWrapper<FileMetadata> wrapper = new QueryWrapper<>();
            wrapper.eq("file_id", fileMetadata.getFileId());
            applyCurrentBucketFilter(wrapper);
            fileMetadataMapper.update(fileMetadata, wrapper);
        } catch (Exception e) {
            throw new BusinessException("更新元数据失败: " + e.getMessage());
        }
    }

    @Override
    public void deleteMetadata(String fileId) {
        try {
            QueryWrapper<FileMetadata> wrapper = new QueryWrapper<>();
            wrapper.eq("file_id", fileId);
            applyCurrentBucketFilter(wrapper);
            fileMetadataMapper.delete(wrapper);
        } catch (Exception e) {
            throw new BusinessException("删除元数据失败: " + e.getMessage());
        }
    }

    @Override
    public List<FileMetadata> getFileList(int page, int size, String sortBy, String direction) {
        try {
            Page<FileMetadata> pageObj = new Page<>(normalizePage(page), normalizeSize(size));
            QueryWrapper<FileMetadata> wrapper = new QueryWrapper<>();
            applyCurrentBucketFilter(wrapper);
            
            String dbColumn = SORT_COLUMNS.get(sortBy);
            if (dbColumn != null) {
                if ("asc".equalsIgnoreCase(direction)) {
                    wrapper.orderByAsc(dbColumn);
                } else {
                    wrapper.orderByDesc(dbColumn);
                }
            } else {
                wrapper.orderByDesc("create_time");
            }
            
            Page<FileMetadata> result = fileMetadataMapper.selectPage(pageObj, wrapper);
            return result.getRecords();
        } catch (Exception e) {
            log.error("获取文件列表失败", e);
            throw new BusinessException("获取文件列表失败: " + e.getMessage());
        }
    }
    
    @Override
    public List<FileMetadata> searchFiles(String keyword, int page, int size) {
        try {
            Page<FileMetadata> pageObj = new Page<>(normalizePage(page), normalizeSize(size));
            QueryWrapper<FileMetadata> wrapper = new QueryWrapper<>();
            applyCurrentBucketFilter(wrapper);
            String safeKeyword = keyword == null ? "" : keyword.trim();
            if (safeKeyword.length() > 100) {
                safeKeyword = safeKeyword.substring(0, 100);
            }
            String finalKeyword = safeKeyword;
            wrapper.and(w -> w.like("file_name", finalKeyword)
                    .or().like("original_file_name", finalKeyword));
            wrapper.orderByDesc("create_time");
            
            Page<FileMetadata> result = fileMetadataMapper.selectPage(pageObj, wrapper);
            return result.getRecords();
        } catch (Exception e) {
            log.error("搜索文件失败", e);
            throw new BusinessException("搜索文件失败: " + e.getMessage());
        }
    }

    private int normalizePage(int page) {
        return Math.max(1, page);
    }

    private int normalizeSize(int size) {
        return Math.max(1, Math.min(MAX_PAGE_SIZE, size));
    }

    private void applyCurrentBucketFilter(QueryWrapper<FileMetadata> wrapper) {
        if ("minio".equals(fileStorageConfig.getStorageType())) {
            wrapper.eq("bucket_name", fileStorageConfig.getBucketName());
        } else {
            wrapper.eq("create_by", requestUserContext.getRequiredUserId());
        }
    }

    private boolean isCurrentBucketMetadata(FileMetadata metadata) {
        if (metadata == null) {
            return false;
        }
        if (!"minio".equals(fileStorageConfig.getStorageType())) {
            return metadata.getCreateBy() != null
                    && metadata.getCreateBy().equals(requestUserContext.getRequiredUserId());
        }
        return fileStorageConfig.getBucketName().equals(metadata.getBucketName());
    }

    @Override
    public Object getDirectoryStructure(String path) {
        try {
            // 简单实现目录结构
            Map<String, Object> structure = new HashMap<>();
            List<Map<String, Object>> files = new ArrayList<>();
            List<Map<String, Object>> directories = new ArrayList<>();
            
            // 这里可以根据实际存储情况实现目录结构的获取
            // 目前返回一个示例结构
            Map<String, Object> dir1 = new HashMap<>();
            dir1.put("name", "documents");
            dir1.put("type", "directory");
            dir1.put("path", path + (path.endsWith("/") ? "" : "/") + "documents");
            directories.add(dir1);
            
            Map<String, Object> dir2 = new HashMap<>();
            dir2.put("name", "images");
            dir2.put("type", "directory");
            dir2.put("path", path + (path.endsWith("/") ? "" : "/") + "images");
            directories.add(dir2);
            
            structure.put("directories", directories);
            structure.put("files", files);
            structure.put("currentPath", path);
            
            return structure;
        } catch (Exception e) {
            log.error("获取目录结构失败 path={}", path, e);
            throw new BusinessException("获取目录结构失败: " + e.getMessage());
        }
    }

}

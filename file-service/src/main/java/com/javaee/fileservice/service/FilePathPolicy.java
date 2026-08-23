package com.javaee.fileservice.service;

import com.javaee.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

@Component
public class FilePathPolicy {

    public String normalizeRelativePrefix(String targetPath) {
        if (targetPath == null || targetPath.isBlank() || "/".equals(targetPath.trim())) {
            return "";
        }
        String value = targetPath.trim().replace('\\', '/');
        if (value.startsWith("/") || value.contains(":")) {
            throw new BusinessException("目标目录必须是相对路径");
        }
        String prefix = Arrays.stream(value.split("/"))
                .filter(segment -> !segment.isBlank())
                .peek(segment -> {
                    if (".".equals(segment) || "..".equals(segment)) {
                        throw new BusinessException("目标目录不合法");
                    }
                })
                .collect(Collectors.joining("/"));
        if (prefix.length() > 512) {
            throw new BusinessException("目标目录过长");
        }
        return prefix;
    }

    public String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("文件名不能为空");
        }
        String value = name.replace('\r', '_').replace('\n', '_').replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).trim();
        if (value.isBlank() || value.length() > 255 || ".".equals(value) || "..".equals(value)) {
            throw new BusinessException("文件名不合法");
        }
        return value;
    }

    public String objectBaseName(String objectKey) {
        String normalized = objectKey.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    public String joinObjectKey(String prefix, String name) {
        return prefix.isBlank() ? name : prefix + "/" + name;
    }
}

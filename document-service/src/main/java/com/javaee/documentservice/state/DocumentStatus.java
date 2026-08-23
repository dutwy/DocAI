package com.javaee.documentservice.state;

import com.javaee.common.exception.BusinessException;

import java.util.Locale;

public enum DocumentStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED,
    DELETED;

    public String databaseValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static DocumentStatus fromDatabase(String value) {
        if (value == null || value.isBlank()) {
            return ACTIVE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("未知文档状态: " + value);
        }
    }
}

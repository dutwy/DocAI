package com.javaee.fileservice.service;

import com.javaee.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilePathPolicyTest {

    private final FilePathPolicy policy = new FilePathPolicy();

    @Test
    void normalizesRelativeObjectPrefix() {
        assertThat(policy.normalizeRelativePrefix(" reports//2026 ")).isEqualTo("reports/2026");
        assertThat(policy.joinObjectKey("reports/2026", "a.txt")).isEqualTo("reports/2026/a.txt");
    }

    @Test
    void rejectsTraversalAndAbsolutePaths() {
        assertThatThrownBy(() -> policy.normalizeRelativePrefix("../secret"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.normalizeRelativePrefix("C:/secret"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void stripsDirectoryComponentsFromRenamedFile() {
        assertThat(policy.sanitizeFileName("folder/report.pdf")).isEqualTo("report.pdf");
    }
}

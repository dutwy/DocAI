package com.javaee.documentservice.state;

import com.javaee.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
public class DocumentStateMachine {

    private final Map<DocumentStatus, Map<DocumentEvent, DocumentStatus>> transitions = transitions();

    public DocumentStatus transition(DocumentStatus current, DocumentEvent event) {
        if (current == null || event == null) {
            throw new BusinessException("文档状态和状态事件不能为空");
        }
        DocumentStatus target = transitions.getOrDefault(current, Map.of()).get(event);
        if (target == null) {
            throw new BusinessException("不允许的文档状态转换: " + current + " -> " + event);
        }
        return target;
    }

    public void assertEditable(DocumentStatus status) {
        if (status != DocumentStatus.DRAFT && status != DocumentStatus.ACTIVE) {
            throw new BusinessException("当前文档状态不允许编辑: " + status);
        }
    }

    private Map<DocumentStatus, Map<DocumentEvent, DocumentStatus>> transitions() {
        Map<DocumentStatus, Map<DocumentEvent, DocumentStatus>> result = new EnumMap<>(DocumentStatus.class);
        result.put(DocumentStatus.DRAFT, Map.of(
                DocumentEvent.ACTIVATE, DocumentStatus.ACTIVE,
                DocumentEvent.DELETE, DocumentStatus.DELETED));
        result.put(DocumentStatus.ACTIVE, Map.of(
                DocumentEvent.ARCHIVE, DocumentStatus.ARCHIVED,
                DocumentEvent.DELETE, DocumentStatus.DELETED));
        result.put(DocumentStatus.ARCHIVED, Map.of(
                DocumentEvent.RESTORE, DocumentStatus.ACTIVE,
                DocumentEvent.DELETE, DocumentStatus.DELETED));
        result.put(DocumentStatus.DELETED, Map.of(
                DocumentEvent.RESTORE, DocumentStatus.ACTIVE));
        return result;
    }
}

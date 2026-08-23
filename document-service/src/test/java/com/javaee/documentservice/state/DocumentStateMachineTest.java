package com.javaee.documentservice.state;

import com.javaee.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentStateMachineTest {

    private final DocumentStateMachine stateMachine = new DocumentStateMachine();

    @Test
    void supportsArchiveAndRestoreLifecycle() {
        assertThat(stateMachine.transition(DocumentStatus.ACTIVE, DocumentEvent.ARCHIVE))
                .isEqualTo(DocumentStatus.ARCHIVED);
        assertThat(stateMachine.transition(DocumentStatus.ARCHIVED, DocumentEvent.RESTORE))
                .isEqualTo(DocumentStatus.ACTIVE);
    }

    @Test
    void rejectsUndefinedTransition() {
        assertThatThrownBy(() -> stateMachine.transition(DocumentStatus.DELETED, DocumentEvent.ARCHIVE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许");
    }
}

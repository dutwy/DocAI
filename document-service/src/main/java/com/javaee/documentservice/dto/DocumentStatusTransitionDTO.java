package com.javaee.documentservice.dto;

import com.javaee.documentservice.state.DocumentEvent;
import lombok.Data;

@Data
public class DocumentStatusTransitionDTO {
    private DocumentEvent event;
    private Integer lockVersion;
}

package com.javaee.documentservice.service;

import com.javaee.documentservice.dto.AnnotationCreateDTO;
import com.javaee.documentservice.vo.AnnotationVO;

import java.util.List;

public interface AnnotationService {

    AnnotationVO createAnnotation(AnnotationCreateDTO dto, Long userId);

    List<AnnotationVO> getAnnotationsByDocumentId(String documentId, Long userId);

    AnnotationVO getAnnotationById(String documentId, String id, Long userId);

    void updateAnnotation(String documentId, String id, AnnotationCreateDTO dto, Long userId);

    void deleteAnnotation(String documentId, String id, Long userId);

    List<AnnotationVO> getAnnotationsByLineNumber(String documentId, Integer lineNumber, Long userId);
}

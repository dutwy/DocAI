package com.javaee.documentservice.exception;

import com.javaee.common.constant.ErrorCodeEnum;
import com.javaee.common.exception.BusinessException;

public class DocumentConcurrentModificationException extends BusinessException {

    public DocumentConcurrentModificationException(String documentId) {
        super(ErrorCodeEnum.CONFLICT.getCode(), "文档已被其他用户修改，请刷新后重试: " + documentId);
    }
}

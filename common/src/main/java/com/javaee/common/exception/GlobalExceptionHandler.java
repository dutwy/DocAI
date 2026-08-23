package com.javaee.common.exception;

import com.javaee.common.constant.ErrorCodeEnum;
import com.javaee.common.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * @author wy
 * @description: 全局异常捕获（统一返回）
 * 使用@RestControllerAdvice注解实现全局异常处理，统一返回Result格式的错误信息
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常
     * @param e 业务异常
     * @return 统一返回结果
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return response(e.getCode(), e.getMessage());
    }

    /**
     * 处理令牌异常
     * @param e 令牌异常
     * @return 统一返回结果
     */
    @ExceptionHandler(TokenException.class)
    public ResponseEntity<Result<Void>> handleTokenException(TokenException e) {
        log.warn("令牌异常: code={}, message={}", e.getCode(), e.getMessage());
        return response(e.getCode(), e.getMessage());
    }

    /**
     * 处理参数校验异常（@Valid注解触发）
     * @param e 参数校验异常
     * @return 统一返回结果
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "参数校验失败";
        log.warn("参数校验异常: {}", message);
        return response(ErrorCodeEnum.BAD_REQUEST.getCode(), message);
    }

    /**
     * 处理参数绑定异常
     * @param e 参数绑定异常
     * @return 统一返回结果
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "参数绑定失败";
        log.warn("参数绑定异常: {}", message);
        return response(ErrorCodeEnum.BAD_REQUEST.getCode(), message);
    }

    /**
     * 处理非法参数异常
     * @param e 非法参数异常
     * @return 统一返回结果
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("非法参数异常: {}", e.getMessage());
        return response(ErrorCodeEnum.BAD_REQUEST.getCode(), e.getMessage());
    }

    @ExceptionHandler({AccessDeniedException.class, SecurityException.class})
    public ResponseEntity<Result<Void>> handleAccessDeniedException(RuntimeException e) {
        log.warn("访问被拒绝: {}", e.getMessage());
        return response(ErrorCodeEnum.FORBIDDEN.getCode(), e.getMessage());
    }

    /**
     * 处理所有未捕获的异常（兜底处理）
     * @param e 异常
     * @return 统一返回结果
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("系统异常: ", e);
        return response(ErrorCodeEnum.INTERNAL_SERVER_ERROR.getCode(), ErrorCodeEnum.INTERNAL_SERVER_ERROR.getMessage());
    }

    private ResponseEntity<Result<Void>> response(Integer code, String message) {
        ErrorCodeEnum errorCode = ErrorCodeEnum.getByCode(code);
        return ResponseEntity.status(errorCode.getHttpStatus()).body(Result.fail(code, message));
    }
}

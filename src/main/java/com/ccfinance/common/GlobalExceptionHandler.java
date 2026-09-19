package com.ccfinance.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex, HttpServletRequest request) {
        ApiError body = ApiError.of(ex.getStatus().value(), ex.getCode(), ex.getMessage(),
                request.getRequestURI());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex,
                                                     HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .distinct()
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "请求参数校验失败";
        }
        ApiError body = ApiError.of(HttpStatus.BAD_REQUEST.value(), ErrorCode.VALIDATION_FAILED,
                message, request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleNotReadable(HttpMessageNotReadableException ex,
                                                      HttpServletRequest request) {
        ApiError body = ApiError.of(HttpStatus.BAD_REQUEST.value(), ErrorCode.VALIDATION_FAILED,
                "请求体格式错误，无法解析", request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex,
                                                     HttpServletRequest request) {
        ApiError body = ApiError.of(HttpStatus.NOT_FOUND.value(), ErrorCode.VOUCHER_NOT_FOUND,
                "请求的资源不存在", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        ApiError body = ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), ErrorCode.INTERNAL_ERROR,
                "服务器内部错误", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + " " + (error.getDefaultMessage() == null ? "不合法" : error.getDefaultMessage());
    }
}

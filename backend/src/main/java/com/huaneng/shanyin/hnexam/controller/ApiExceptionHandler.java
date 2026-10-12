package com.huaneng.shanyin.hnexam.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import com.huaneng.shanyin.hnexam.service.TooManyLoginAttemptsException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> forbidden() { return Map.of("message", "当前账号没有此操作权限"); }

    @ExceptionHandler(org.springframework.security.authentication.DisabledException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, String> disabled() { return Map.of("message", "账号已停用，请联系管理员"); }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> duplicate() { return Map.of("message", "数据重复或仍被使用，请检查后重试"); }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException exception) {
        return Map.of("message", exception.getMessage() == null ? "请求参数不正确" : exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(IllegalStateException exception) {
        return Map.of("message", exception.getMessage() == null ? "当前状态不允许此操作" : exception.getMessage());
    }

    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, String> badCredentials() {
        return Map.of("message", "账号或密码错误");
    }

    @ExceptionHandler(TooManyLoginAttemptsException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Map<String, String> tooManyLoginAttempts(TooManyLoginAttemptsException exception) {
        return Map.of("message", exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> validation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst().map(error -> error.getDefaultMessage()).orElse("请求参数不正确");
        return Map.of("message", message);
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> uploadTooLarge() {
        return Map.of("message", "上传文件不能超过 5MB，请拆分后重试");
    }
}

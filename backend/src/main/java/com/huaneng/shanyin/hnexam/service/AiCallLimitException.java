package com.huaneng.shanyin.hnexam.service;

public class AiCallLimitException extends IllegalStateException {
    private final boolean retryable;
    public AiCallLimitException(String message) { this(message, false); }
    public AiCallLimitException(String message, boolean retryable) { super(message); this.retryable=retryable; }
    public boolean isRetryable() { return retryable; }
}

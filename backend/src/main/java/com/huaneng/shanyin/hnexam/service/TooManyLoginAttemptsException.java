package com.huaneng.shanyin.hnexam.service;

public class TooManyLoginAttemptsException extends RuntimeException {
    public TooManyLoginAttemptsException() { super("登录失败次数过多，请15分钟后重试"); }
}

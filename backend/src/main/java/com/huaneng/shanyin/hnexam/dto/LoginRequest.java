package com.huaneng.shanyin.hnexam.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "请输入账号") String employeeNo,
        @NotBlank(message = "请输入密码") String password
) {}


package com.huaneng.shanyin.hnexam.dto;

import com.huaneng.shanyin.hnexam.entity.AppUser;

import java.util.Set;
import java.util.stream.Collectors;

public record UserSummary(
        Long id,
        String employeeNo,
        String name,
        String phone,
        String department,
        String team,
        String status,
        String professionalDirection, String educationMajor, String position, java.time.LocalDate trainingStartDate,
        Set<String> roles
) {
    public static UserSummary from(AppUser user) {
        return new UserSummary(user.getId(), user.getEmployeeNo(), user.getName(), user.getPhone(),
                user.getDepartment(), user.getTeam(), user.getStatus().name(), user.getProfessionalDirection(), user.getEducationMajor(), user.getPosition(), user.getTrainingStartDate(),
                user.getRoles().stream().map(role -> role.getCode()).collect(Collectors.toSet()));
    }
}


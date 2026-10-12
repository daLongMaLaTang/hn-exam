package com.huaneng.shanyin.hnexam.controller;

import com.huaneng.shanyin.hnexam.dto.UserSummary;
import com.huaneng.shanyin.hnexam.entity.AppUser;
import com.huaneng.shanyin.hnexam.entity.Role;
import com.huaneng.shanyin.hnexam.domain.UserStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.crypto.password.PasswordEncoder;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.huaneng.shanyin.hnexam.repository.RoleRepository;
import com.huaneng.shanyin.hnexam.repository.UserRepository;
import com.huaneng.shanyin.hnexam.service.AuthStateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','DEPARTMENT_LEADER','TRAINING_ADMIN')")
public class UserController {
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder encoder;
    private final AuthStateService authStates;
    public record RoleOption(String code, String name, String permissionGroup) {}
    public record RoleUpdate(@NotEmpty Set<String> roles) {}
    public record UserRequest(@NotBlank @Size(max=32) String employeeNo, @NotBlank @Size(max=80) String name,
                              @Size(max=32) String phone, @Size(max=100) String department, @Size(max=100) String team,
                              UserStatus status) {}
    public record ResetRequest(@NotBlank @Size(min=10,max=64) String password) {}
    public record RoleRequest(@NotBlank String code, @NotBlank @Size(max=100) String name, @NotBlank String permissionGroup) {}
    @GetMapping public List<UserSummary> list() { return userRepository.findAll().stream().map(UserSummary::from).toList(); }
    @GetMapping("/roles") public List<RoleOption> roles() {
        return roleRepository.findAll().stream().map(r -> new RoleOption(r.getCode(), r.getName(), r.effectiveAuthority())).toList();
    }
    @PutMapping("/{id}/roles") @PreAuthorize("hasAuthority('SYSTEM_ADMIN') and authentication.name == 'admin'") @Transactional
    public UserSummary updateRoles(@PathVariable Long id, @Valid @RequestBody RoleUpdate request, @AuthenticationPrincipal Jwt jwt) {
        AppUser user = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("人员不存在"));
        List<Role> roles = roleRepository.findAll().stream().filter(r -> request.roles().contains(r.getCode())).toList();
        if (roles.size() != request.roles().size()) throw new IllegalArgumentException("包含无效角色");
        if (user.getEmployeeNo().equals(jwt.getSubject()) && roles.stream().noneMatch(r -> r.effectiveAuthority().equals("SYSTEM_ADMIN")))
            throw new IllegalArgumentException("不能移除 admin 的系统管理员权限");
        user.getRoles().clear(); user.getRoles().addAll(roles);
        authStates.evictAfterCommit(user.getEmployeeNo());
        return UserSummary.from(user);
    }

    @PostMapping @PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','TRAINING_ADMIN')")
    public UserSummary create(@Valid @RequestBody UserRequest request) {
        if (request.employeeNo().trim().equalsIgnoreCase("admin")) throw new IllegalArgumentException("admin 为保留账号");
        if (userRepository.existsByEmployeeNo(request.employeeNo().trim())) throw new IllegalArgumentException("工号已存在");
        AppUser user = new AppUser(); apply(user, request);
        user.setPasswordHash(encoder.encode("Candidate@12345")); user.setMustChangePassword(true);
        user.getRoles().add(roleRepository.findByCode("CANDIDATE").orElseThrow());
        return UserSummary.from(userRepository.save(user));
    }

    @PutMapping("/{id}") @PreAuthorize("hasAnyAuthority('SYSTEM_ADMIN','TRAINING_ADMIN')") @Transactional
    public UserSummary update(@PathVariable Long id, @Valid @RequestBody UserRequest request, @AuthenticationPrincipal Jwt jwt) {
        AppUser user = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("人员不存在"));
        var actor = userRepository.findByEmployeeNo(jwt.getSubject()).orElseThrow();
        if (user.getEmployeeNo().equals("admin") && !jwt.getSubject().equals("admin"))
            throw new org.springframework.security.access.AccessDeniedException("只有 admin 可以修改该账号");
        if ((user.getEmployeeNo().equals("admin") || request.employeeNo().trim().equalsIgnoreCase("admin"))
                && !user.getEmployeeNo().equals(request.employeeNo().trim())) throw new IllegalArgumentException("不能更改或占用 admin 账号");
        if (user.getRoles().stream().anyMatch(r -> r.effectiveAuthority().equals("SYSTEM_ADMIN"))
                && actor.getRoles().stream().noneMatch(r -> r.effectiveAuthority().equals("SYSTEM_ADMIN"))) throw new org.springframework.security.access.AccessDeniedException("无权修改系统管理员");
        if (user.getEmployeeNo().equals(jwt.getSubject()) && request.status() != UserStatus.ACTIVE) throw new IllegalArgumentException("不能停用自己的账号");
        userRepository.findByEmployeeNo(request.employeeNo().trim()).ifPresent(existing -> {
            if (!existing.getId().equals(id)) throw new IllegalArgumentException("工号已存在");
        });
        String previousEmployeeNo = user.getEmployeeNo();
        if (!user.getEmployeeNo().equals(request.employeeNo().trim()) || user.getStatus() != request.status()) user.setAuthVersion(user.getAuthVersion() + 1);
        apply(user, request);
        authStates.evictAfterCommit(previousEmployeeNo, user.getEmployeeNo());
        return UserSummary.from(user);
    }

    @PostMapping("/{id}/reset-password") @PreAuthorize("hasAuthority('SYSTEM_ADMIN')") @Transactional
    public java.util.Map<String,String> reset(@PathVariable Long id, @Valid @RequestBody ResetRequest request, @AuthenticationPrincipal Jwt jwt) {
        if (!request.password().matches("(?s)(?=.*[A-Za-z])(?=.*[0-9]).{10,64}")) throw new IllegalArgumentException("密码须为10至64位，包含字母和数字");
        var user = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("人员不存在"));
        if (user.getEmployeeNo().equals("admin") && !jwt.getSubject().equals("admin"))
            throw new org.springframework.security.access.AccessDeniedException("只有 admin 可以重置该账号密码");
        user.setPasswordHash(encoder.encode(request.password())); user.setMustChangePassword(true); user.setAuthVersion(user.getAuthVersion()+1);
        authStates.evictAfterCommit(user.getEmployeeNo());
        return java.util.Map.of("message", "密码已重置，该用户下次登录须修改密码");
    }

    @PostMapping("/roles") @PreAuthorize("hasAuthority('SYSTEM_ADMIN') and authentication.name == 'admin'")
    public RoleOption createRole(@Valid @RequestBody RoleRequest request) {
        if (!request.code().matches("[A-Z][A-Z0-9_]{2,63}")) throw new IllegalArgumentException("角色代码须为3至64位大写字母、数字或下划线");
        if (!Set.of("CANDIDATE","SPECIALIST","TRAINING_ADMIN","DEPARTMENT_LEADER").contains(request.permissionGroup())) throw new IllegalArgumentException("请选择有效的权限模板");
        if (roleRepository.findByCode(request.code()).isPresent()) throw new IllegalArgumentException("角色代码已存在");
        Role role = new Role(request.code(), request.name(), false); role.setPermissionGroup(request.permissionGroup());
        roleRepository.save(role); return new RoleOption(role.getCode(), role.getName(), role.effectiveAuthority());
    }

    private void apply(AppUser user, UserRequest request) {
        user.setEmployeeNo(request.employeeNo().trim()); user.setName(request.name().trim()); user.setPhone(request.phone());
        user.setDepartment(request.department()); user.setTeam(request.team());
        user.setStatus(request.status() == null ? UserStatus.ACTIVE : request.status());
    }
}

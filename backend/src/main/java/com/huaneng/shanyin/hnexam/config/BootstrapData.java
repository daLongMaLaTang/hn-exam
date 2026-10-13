package com.huaneng.shanyin.hnexam.config;

import com.huaneng.shanyin.hnexam.domain.UserStatus;
import com.huaneng.shanyin.hnexam.entity.AppUser;
import com.huaneng.shanyin.hnexam.entity.Role;
import com.huaneng.shanyin.hnexam.repository.RoleRepository;
import com.huaneng.shanyin.hnexam.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class BootstrapData implements ApplicationRunner {
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final QuestionBankDocxImporter questionBankImporter;

    @Value("${app.bootstrap.admin-password}")
    private String adminPassword;

    @Value("${app.bootstrap.candidate-password}")
    private String candidatePassword;
    @Value("${app.bootstrap.import-legacy-candidates:false}")
    private boolean importLegacyCandidates;

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        Map<String, String> roleDefinitions = new LinkedHashMap<>();
        roleDefinitions.put("CANDIDATE", "考生");
        roleDefinitions.put("SPECIALIST", "专业专工");
        roleDefinitions.put("TRAINING_ADMIN", "培训管理员");
        roleDefinitions.put("DEPARTMENT_LEADER", "部门领导");
        roleDefinitions.put("SYSTEM_ADMIN", "系统管理员");
        roleDefinitions.forEach((code, name) -> roleRepository.findByCode(code)
                .orElseGet(() -> roleRepository.save(new Role(code, name, true))));

        seedAdministrator();
        seedCandidates();
        questionBankImporter.importIfNeeded();
    }

    private void seedAdministrator() {
        if (userRepository.existsByEmployeeNo("admin")) {
            return;
        }
        AppUser admin = new AppUser();
        admin.setEmployeeNo("admin");
        admin.setName("系统初始化管理员");
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setMustChangePassword(true);
        admin.setStatus(UserStatus.ACTIVE);
        admin.getRoles().add(roleRepository.findByCode("SYSTEM_ADMIN").orElseThrow());
        userRepository.save(admin);
    }

    private void seedCandidates() throws IOException {
        // New installations use the administrator's Excel flow so credentials can be handed out.
        // Existing accounts are untouched; the historical CSV path requires explicit opt-in.
        if (!importLegacyCandidates) return;
        ClassPathResource resource = new ClassPathResource("bootstrap/candidates.csv");
        // Real learner rosters stay local and are not required in a source-only backup.
        if (!resource.exists()) return;
        Role candidateRole = roleRepository.findByCode("CANDIDATE").orElseThrow();
        String encodedPassword = passwordEncoder.encode(candidatePassword);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            reader.lines().skip(1).filter(line -> !line.isBlank()).forEach(line -> {
                String[] columns = line.split(",", 2);
                String employeeNo = columns[0].trim();
                String name = columns[1].trim();
                if (!userRepository.existsByEmployeeNo(employeeNo)) {
                    AppUser candidate = new AppUser();
                    candidate.setEmployeeNo(employeeNo);
                    candidate.setName(name);
                    candidate.setPasswordHash(encodedPassword);
                    candidate.setMustChangePassword(true);
                    candidate.setStatus(UserStatus.ACTIVE);
                    candidate.getRoles().add(candidateRole);
                    userRepository.save(candidate);
                }
            });
        }
    }
}

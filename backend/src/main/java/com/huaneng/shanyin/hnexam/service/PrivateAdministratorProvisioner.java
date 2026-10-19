package com.huaneng.shanyin.hnexam.service;

import com.huaneng.shanyin.hnexam.entity.AppUser;
import com.huaneng.shanyin.hnexam.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Opt-in, local, one-time provisioning. No default account or password; never resets an existing user. */
@Service @RequiredArgsConstructor
public class PrivateAdministratorProvisioner {
    private final UserRepository users;
    private final RoleRepository roles;
    @Value("${app.private-admin.bootstrap-file:}") private String bootstrapFile;
    @Value("${spring.datasource.url}") private String databaseUrl;

    @Transactional
    public void provisionIfRequested() {
        if (bootstrapFile == null || bootstrapFile.isBlank()) return;
        Path path = Path.of(bootstrapFile);
        if (!Files.exists(path)) return;
        Properties input = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { input.load(reader); }
        catch (Exception e) { throw new IllegalStateException("私有管理员初始化材料不可读取"); }
        // A test/preview database must never consume production initialization material.
        if (!databaseUrl.equals(input.getProperty("databaseUrl"))) return;
        String login = input.getProperty("login", "");
        String hash = input.getProperty("passwordHash", "");
        if (!login.matches("[A-Za-z][A-Za-z0-9_]{5,31}") || login.equalsIgnoreCase("admin")
                || !hash.matches("\\$2[aby]\\$12\\$[./A-Za-z0-9]{53}"))
            throw new IllegalStateException("私有管理员初始化材料无效");
        var existing = users.findByEmployeeNo(login);
        if (existing.isPresent()) {
            if (!existing.get().isPrivateAdministrator()) throw new IllegalStateException("初始化账号已被占用，未更改现有人员");
        } else {
            var user = new AppUser(); user.setEmployeeNo(login); user.setName("维护管理员");
            user.setPasswordHash(hash); user.setPrivateAdministrator(true); user.setMustChangePassword(true);
            user.getRoles().add(roles.findByCode("SYSTEM_ADMIN").orElseThrow());
            users.saveAndFlush(user);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { Files.deleteIfExists(path); }
                catch (Exception e) { org.slf4j.LoggerFactory.getLogger(PrivateAdministratorProvisioner.class).warn("私有管理员初始化材料未能自动清理，请删除本机初始化文件"); }
            }
        });
    }
}

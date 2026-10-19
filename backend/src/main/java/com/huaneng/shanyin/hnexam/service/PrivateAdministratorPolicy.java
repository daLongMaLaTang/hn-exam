package com.huaneng.shanyin.hnexam.service;

import com.huaneng.shanyin.hnexam.entity.AppUser;
import com.huaneng.shanyin.hnexam.repository.UserRepository;
import org.springframework.stereotype.Component;
import java.util.*;

/** A server-only flag affects directory visibility; authentication and audit still apply. */
@Component("privateAdministratorPolicy")
public class PrivateAdministratorPolicy {
    private final UserRepository users;
    public PrivateAdministratorPolicy(UserRepository users) { this.users = users; }
    public boolean isPrivate(String name) { return name != null && users.findByEmployeeNo(name).map(AppUser::isPrivateAdministrator).orElse(false); }
    public boolean isOwner(String name) { return "admin".equals(name) || isPrivate(name); }
    public boolean canManageSystem(AppUser user) {
        return isOwner(user.getEmployeeNo()) && user.getRoles().stream().anyMatch(r -> "SYSTEM_ADMIN".equals(r.effectiveAuthority()));
    }
    public boolean visible(AppUser user) { return !user.isPrivateAdministrator(); }
    public void requireVisible(AppUser user) {
        if (!visible(user)) throw new IllegalArgumentException("人员不存在");
    }
    public String actorLabel(String name) { return isPrivate(name) ? "维护管理员" : name; }
}

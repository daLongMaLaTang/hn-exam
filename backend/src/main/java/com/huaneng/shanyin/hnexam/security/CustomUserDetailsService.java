package com.huaneng.shanyin.hnexam.security;

import com.huaneng.shanyin.hnexam.domain.UserStatus;
import com.huaneng.shanyin.hnexam.entity.AppUser;
import com.huaneng.shanyin.hnexam.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {
    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String employeeNo) throws UsernameNotFoundException {
        AppUser appUser = userRepository.findByEmployeeNo(employeeNo)
                .orElseThrow(() -> new UsernameNotFoundException("账号或密码错误"));
        var authorities = appUser.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority(role.effectiveAuthority()))
                .toList();
        return User.withUsername(appUser.getEmployeeNo())
                .password(appUser.getPasswordHash())
                .authorities(authorities)
                .disabled(appUser.getStatus() != UserStatus.ACTIVE)
                .build();
    }
}


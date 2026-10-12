package com.huaneng.shanyin.hnexam.security;

import com.huaneng.shanyin.hnexam.service.AuthStateService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

public class PasswordChangeFilter extends OncePerRequestFilter {
    private final AuthStateService authStates;
    public PasswordChangeFilter(AuthStateService authStates) { this.authStates = authStates; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt && !request.getRequestURI().startsWith("/api/auth/")) {
            var user = authStates.find(jwt.getName());
            if (user.isPresent() && user.get().mustChangePassword()) {
                response.setStatus(403); response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":\"PASSWORD_CHANGE_REQUIRED\",\"message\":\"首次登录请先修改初始密码\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

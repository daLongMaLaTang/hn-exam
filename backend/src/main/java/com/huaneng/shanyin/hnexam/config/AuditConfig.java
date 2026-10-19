package com.huaneng.shanyin.hnexam.config;

import com.huaneng.shanyin.hnexam.entity.AuditEvent;
import com.huaneng.shanyin.hnexam.repository.AuditEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration @RequiredArgsConstructor
public class AuditConfig implements WebMvcConfigurer {
    private final com.huaneng.shanyin.hnexam.service.PrivateAdministratorPolicy privateAdmins;
    private final AuditEventRepository events;
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
                if (java.util.Set.of("GET","HEAD","OPTIONS").contains(request.getMethod()) || request.getUserPrincipal()==null) return;
                var event = new AuditEvent(); event.setActor(privateAdmins.actorLabel(request.getUserPrincipal().getName()));
                event.setMethod(request.getMethod()); event.setPath(request.getRequestURI()); event.setResponseStatus(response.getStatus());
                events.save(event);
            }
        }).addPathPatterns("/api/ai/**", "/api/profiles/**", "/api/users/**", "/api/questions/**", "/api/exams/**", "/api/auth/password");
    }
}

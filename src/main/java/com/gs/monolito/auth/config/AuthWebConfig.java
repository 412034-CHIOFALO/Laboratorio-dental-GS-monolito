package com.gs.monolito.auth.config;

import com.gs.monolito.auth.filter.LoginRateLimitInterceptor;
import com.gs.monolito.auth.filter.PasswordTemporalInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginRateLimitInterceptor())
                .addPathPatterns("/api/auth/login");
        // Con contraseña temporal pendiente, solo el propio perfil (ver el interceptor).
        registry.addInterceptor(new PasswordTemporalInterceptor())
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/auth/refresh", "/api/auth/logout",
                                     "/api/auth/me", "/api/auth/me/**");
    }

    @Bean
    public LoginRateLimitInterceptor loginRateLimitInterceptor() {
        return new LoginRateLimitInterceptor();
    }
}

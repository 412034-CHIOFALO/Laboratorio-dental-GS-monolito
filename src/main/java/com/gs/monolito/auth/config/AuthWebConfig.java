package com.gs.monolito.auth.config;

import com.gs.monolito.auth.filter.LoginRateLimitInterceptor;
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
    }

    @Bean
    public LoginRateLimitInterceptor loginRateLimitInterceptor() {
        return new LoginRateLimitInterceptor();
    }
}

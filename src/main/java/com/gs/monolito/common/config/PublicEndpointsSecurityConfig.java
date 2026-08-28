package com.gs.monolito.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Cadena de seguridad para los endpoints de infraestructura compartidos por
 * todo el monolito (actuator, OpenAPI/Swagger) — antes cada microservicio
 * tenía su propio actuator/swagger y los permitía dentro de su único
 * SecurityFilterChain; acá hay un solo actuator/swagger para toda la app, así
 * que necesitan su propia chain en vez de repetirse en cada `securityMatcher`
 * por módulo (que de todos modos nunca los alcanzaría, al estar scoped a su
 * propio prefijo de ruta).
 */
@Configuration
@EnableWebSecurity
public class PublicEndpointsSecurityConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain publicEndpointsSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
            .cors(cors -> cors.disable())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}

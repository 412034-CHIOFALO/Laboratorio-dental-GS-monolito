package com.gs.monolito.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Resuelve el username autenticado actual para los registros de auditoría.
 * Antes esta lógica vivía duplicada dentro de cada {@code AuditoriaClient} de
 * cada microservicio (llamaba por HTTP a ms-auth); acá los módulos llaman
 * directo a {@code auth.service.AuditoriaService.registrar(...)} y resuelven
 * el usuario con este helper antes de llamar.
 */
public final class CurrentUser {

    private CurrentUser() {}

    public static String usernameOrSistema() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getName() != null) ? auth.getName() : "sistema";
    }
}

package com.gs.monolito.auth.model;

/**
 * Roles disponibles para los usuarios del Laboratorio G&amp;S.
 * Spring Security los expone como authorities con el prefijo {@code ROLE_}
 * (ej: {@code ROLE_ADMIN}). El rol se incluye en el claim {@code roles} del JWT
 * como cadena simple sin prefijo.
 */
public enum Rol {
    /** Administrador del sistema — permisos totales. */
    ADMIN,
    /** Personal técnico del laboratorio. */
    TECNICO,
    /** Personal administrativo del laboratorio. */
    ADMINISTRATIVO,
    /** Profesional odontológico. */
    ODONTOLOGO
}

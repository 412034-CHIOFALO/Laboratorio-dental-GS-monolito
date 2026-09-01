package com.gs.monolito.common.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Envoltorio explícito para respuestas paginadas — se arma a mano en vez de
 * devolver un {@code Page<T>} de Spring Data directo porque su serialización
 * JSON por defecto varía entre versiones (a veces expone campos internos
 * como "pageable"/"sort"), y acá el contrato con el frontend tiene que ser
 * estable.
 */
public record PaginaResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
    public static <T, R> PaginaResponse<R> from(Page<T> pagina, Function<T, R> mapper) {
        return new PaginaResponse<>(
            pagina.getContent().stream().map(mapper).toList(),
            pagina.getNumber(),
            pagina.getSize(),
            pagina.getTotalElements(),
            pagina.getTotalPages()
        );
    }
}

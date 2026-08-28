package com.gs.monolito.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Formato unificado de error HTTP para todos los módulos del monolito.
 */
public record ErrorResponse(
    LocalDateTime timestamp,
    int status,
    String error,
    String mensaje,
    String ruta,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    List<CampoError> campos
) {
    public record CampoError(String campo, String mensaje) {}

    public static ErrorResponse of(int status, String error, String msg, String ruta) {
        return new ErrorResponse(LocalDateTime.now(), status, error, msg, ruta, List.of());
    }

    public static ErrorResponse of(int status, String error, String msg, String ruta, List<CampoError> campos) {
        return new ErrorResponse(LocalDateTime.now(), status, error, msg, ruta, campos);
    }
}

package com.gs.monolito.catalogo.dto;

import com.gs.monolito.catalogo.model.ConfiguracionCatalogoPublico;

public record ConfiguracionCatalogoPublicoResponse(boolean habilitado) {
    public static ConfiguracionCatalogoPublicoResponse from(ConfiguracionCatalogoPublico c) {
        return new ConfiguracionCatalogoPublicoResponse(c.isHabilitado());
    }
}

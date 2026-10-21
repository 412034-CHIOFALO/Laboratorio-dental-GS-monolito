package com.gs.monolito.pedidos.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record EscaneoResponse(
        Long id,
        Long pedidoId,
        String fileName,
        String contentType,
        Long tamanioBytes,
        String descripcion,
        String subidoPor,
        LocalDateTime fechaSubida,
        /** Formato real según el contenido (STL_BINARIO, STL_ASCII, OBJ, PLY_ASCII, PLY_BINARIO); null en escaneos viejos. */
        String formato,
        Long triangulos,
        Long vertices,
        BigDecimal dimXMm,
        BigDecimal dimYMm,
        BigDecimal dimZMm,
        /** Avisos de la inspección que no bloquearon la subida. */
        List<String> advertencias
) {}

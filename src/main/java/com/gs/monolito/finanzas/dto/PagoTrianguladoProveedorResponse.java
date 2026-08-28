package com.gs.monolito.finanzas.dto;

import java.math.BigDecimal;

/**
 * Resultado de registrar un pago triangulado a proveedor a mano: cuánto se
 * pudo imputar a cada lado (puede ser menos que el monto informado, si el
 * odontólogo o el proveedor no tenían tanta deuda pendiente).
 */
public record PagoTrianguladoProveedorResponse(
        Long odontologoId,
        Long proveedorId,
        String proveedorNombre,
        BigDecimal monto,
        BigDecimal settOdontologo,
        BigDecimal settProveedor,
        String mensaje
) {}

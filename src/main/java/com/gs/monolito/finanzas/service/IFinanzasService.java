package com.gs.monolito.finanzas.service;

import com.gs.monolito.finanzas.dto.ComprobanteRequest;
import com.gs.monolito.finanzas.dto.ComprobanteResponse;
import com.gs.monolito.finanzas.dto.CuentaCorrienteOdontologoResponse;
import com.gs.monolito.finanzas.dto.PagoCuentaCorrienteRequest;
import com.gs.monolito.finanzas.dto.PagoCuentaCorrienteResponse;

import java.math.BigDecimal;
import java.util.List;

/**
 * Servicio de gestión de comprobantes de deuda emitidos a odontólogos
 * y seguimiento de sus cuentas corrientes. Ciclo de vida:
 * {@code PENDIENTE} → {@code PARCIAL}/{@code COBRADO} (o {@code VENCIDO}).
 */
public interface IFinanzasService {

    List<ComprobanteResponse> listarTodos();

    List<ComprobanteResponse> listarPendientes();

    List<ComprobanteResponse> listarPorOdontologo(Long odontologoId);

    BigDecimal saldoPendienteOdontologo(Long odontologoId);

    ComprobanteResponse buscarPorId(Long id);

    ComprobanteResponse emitir(ComprobanteRequest request);

    ComprobanteResponse registrarCobro(Long id);

    /**
     * Sincroniza el monto del comprobante de un pedido cuando se corrige su
     * precio DESPUÉS de entregado. No hace nada si el pedido todavía no tiene
     * comprobante emitido.
     */
    void actualizarMontoPorPedido(Long pedidoId, java.math.BigDecimal nuevoMonto);

    /** Ranking de odontólogos con deuda pendiente, ordenado de mayor a menor deuda. */
    List<CuentaCorrienteOdontologoResponse> rankingMorosos();

    /**
     * Igual que {@link #rankingMorosos()} pero incluye también a los
     * odontólogos que ya saldaron toda su deuda — fuente de la pestaña
     * "Todos" de Cuentas Corrientes.
     */
    List<CuentaCorrienteOdontologoResponse> listarTodasCuentas();

    PagoCuentaCorrienteResponse registrarPagoCuentaCorriente(Long odontologoId, PagoCuentaCorrienteRequest request);

    List<PagoCuentaCorrienteResponse> historialPagosOdontologo(Long odontologoId);
}

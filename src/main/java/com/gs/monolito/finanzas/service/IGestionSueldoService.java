package com.gs.monolito.finanzas.service;

import com.gs.monolito.finanzas.dto.*;
import com.gs.monolito.finanzas.model.TipoCaja;

import java.math.BigDecimal;
import java.util.List;

/**
 * Servicio de gestión integral de sueldos del personal del laboratorio.
 *
 * <p>Maneja tres aspectos principales:
 * <ol>
 *   <li><b>Configuración</b>: alta/actualización de la config de sueldo de cada empleado
 *       (frecuencia de pago y monto base).</li>
 *   <li><b>Devengado</b>: saldo acumulado que el laboratorio le debe a cada empleado;
 *       crece con el tiempo y disminuye con cada pago.</li>
 *   <li><b>Pagos</b>: registro manual (desde la app) y automático (desde el bot de WhatsApp)
 *       con algoritmo de <i>cascada</i> para manejar sobrantes entre ciclos.</li>
 * </ol></p>
 */
public interface IGestionSueldoService {

    List<EmpleadoSueldoResponse> listarEmpleados();

    EmpleadoSueldoResponse buscarEmpleado(Long usuarioId);

    EmpleadoSueldoResponse guardarConfig(Long usuarioId, ConfigSueldoRequest req);

    /**
     * Da de alta a un integrante del laboratorio en el módulo de sueldos.
     *
     * <p>El módulo finanzas mantiene su propia tabla de empleados, denormalizada
     * de auth: un usuario nuevo (creado en Usuarios) no es reconocido acá ni
     * por el bot de WhatsApp hasta que se lo da de alta con este método.</p>
     */
    EmpleadoSueldoResponse crearEmpleado(CrearEmpleadoRequest req);

    PagoSueldoResponse registrarPago(PagoSueldoRequest req);

    /**
     * Procesa un comprobante de pago enviado por el bot de WhatsApp al grupo.
     * El resultado siempre se devuelve en el body (nunca arroja excepción de negocio).
     */
    RegistroPagoBotResponse registrarPagoAutomatico(PagoAutomaticoRequest req);

    EmpleadoSueldoResponse ajustarDevengado(Long usuarioId, BigDecimal nuevoDevengado);

    List<PagoSueldoResponse> historialPagos(Long usuarioId);

    List<PagoSueldoResponse> historialPagosGlobal();

    List<RegistroPagoBotResponse> listarRegistrosBot();

    BigDecimal totalDevengado();

    String objectKeyComprobante(Long pagoId);

    String objectKeyComprobanteRegistro(Long registroId);

    RegistroPagoBotResponse registrarPagoEfectivo(PagoEfectivoRequest req);

    RegistroPagoBotResponse confirmarEfectivo(Long registroId);

    RegistroPagoBotResponse rechazarEfectivo(Long registroId, String motivo);

    List<RegistroPagoBotResponse> listarPendientesEfectivo();

    DistribucionCascadaResponse sugerirCascada(BigDecimal monto, TipoCaja cajaRemanente);

    void devengarDiario();

    /**
     * Registra manualmente un pago triangulado a un proveedor: el odontólogo
     * indicado paga directamente al proveedor una deuda del laboratorio, en
     * vez de pagarle al laboratorio. Salda la cuenta corriente del odontólogo
     * y la deuda del proveedor por el mismo importe, neteando el movimiento en
     * la caja de Compensación — mismo mecanismo que ya usa el bot cuando
     * detecta este patrón en un comprobante de WhatsApp, pero disparado a
     * mano desde el panel.
     */
    PagoTrianguladoProveedorResponse registrarPagoTrianguladoProveedor(Long odontologoId, PagoTrianguladoProveedorRequest request);
}

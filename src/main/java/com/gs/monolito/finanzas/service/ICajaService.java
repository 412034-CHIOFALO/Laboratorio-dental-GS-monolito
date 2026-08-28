package com.gs.monolito.finanzas.service;

import com.gs.monolito.finanzas.dto.CajaMovimientoRequest;
import com.gs.monolito.finanzas.dto.CajaMovimientoResponse;
import com.gs.monolito.finanzas.dto.ResumenCajasResponse;
import com.gs.monolito.finanzas.model.TipoCaja;

import java.time.LocalDate;
import java.util.List;

/**
 * Servicio de gestión de las tres cajas del laboratorio dental (FISICA,
 * BANCARIA, COMPENSACION). Los saldos se calculan en tiempo real sumando
 * todos los movimientos de cada caja.
 */
public interface ICajaService {

    ResumenCajasResponse obtenerResumen();

    List<CajaMovimientoResponse> listarMovimientosByCaja(TipoCaja tipoCaja);

    List<CajaMovimientoResponse> listarMovimientosByPeriodo(LocalDate desde, LocalDate hasta);

    CajaMovimientoResponse registrarMovimiento(CajaMovimientoRequest req, String creadoPor);
}

package com.gs.monolito.finanzas.service;

import com.gs.monolito.common.security.CurrentUser;
import com.gs.monolito.finanzas.model.ConfiguracionSueldo;
import com.gs.monolito.finanzas.model.MovimientoSueldo;
import com.gs.monolito.finanzas.model.TipoMovimientoSueldo;
import com.gs.monolito.finanzas.repository.MovimientoSueldoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Escribe y lee el libro de sueldos. Se llama DESPUÉS de modificar el saldo del empleado (en la misma
 * transacción): la línea guarda cómo quedó.
 */
@Component
@RequiredArgsConstructor
public class LibroSueldos {

    private static final int MAX_DETALLE = 300;

    private final MovimientoSueldoRepository repo;

    public void registrar(ConfiguracionSueldo c, TipoMovimientoSueldo tipo, BigDecimal monto, LocalDate fecha, String detalle) {
        repo.save(MovimientoSueldo.builder()
                .empleadoId(c.getEmpleadoId())
                .fecha(fecha)
                .tipo(tipo)
                .monto(monto)
                .saldoDevengado(c.getSaldoDevengado())
                .saldoSobrante(c.getSaldoSobrante())
                .detalle(recortar(detalle))
                .usuario(CurrentUser.usernameOrSistema())
                .build());
    }

    public List<MovimientoSueldo> ultimos(Long empleadoId, int limite) {
        int tope = Math.max(1, Math.min(limite, 500));
        return repo.findByEmpleadoIdOrderByFechaCreacionDescIdDesc(empleadoId, PageRequest.of(0, tope));
    }

    private static String recortar(String s) {
        if (s == null) return null;
        return s.length() <= MAX_DETALLE ? s : s.substring(0, MAX_DETALLE - 1) + "…";
    }
}

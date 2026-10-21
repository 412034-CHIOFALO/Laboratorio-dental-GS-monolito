package com.gs.monolito.finanzas.controller;

import com.gs.monolito.finanzas.dto.AsistenciaMesResponse;
import com.gs.monolito.finanzas.dto.BajaEmpleadoRequest;
import com.gs.monolito.finanzas.dto.EmpleadoSueldoResponse;
import com.gs.monolito.finanzas.dto.MarcarAsistenciaRequest;
import com.gs.monolito.finanzas.dto.MarcarAsistenciaResponse;
import com.gs.monolito.finanzas.dto.MovimientoSueldoResponse;
import com.gs.monolito.finanzas.dto.ReactivarEmpleadoRequest;
import com.gs.monolito.finanzas.exception.BusinessException;
import com.gs.monolito.finanzas.service.AsistenciaService;
import com.gs.monolito.finanzas.service.DevengoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Asistencia, vacaciones, libro de movimientos y alta/baja de los empleados de sueldos. Las reglas del
 * devengo están en {@link com.gs.monolito.finanzas.service.CalculoDevengo}.
 */
@Tag(name = "Asistencia y vacaciones", description = "Calendario de asistencia por empleado, libro de sueldos y baja/reactivación")
@RestController
@RequestMapping("/api/finanzas/sueldos/empleados/{usuarioId}")
@RequiredArgsConstructor
@Validated
public class AsistenciaSueldoController {

    private final AsistenciaService asistencia;
    private final DevengoService devengo;

    @Operation(summary = "Calendario de asistencia de un mes",
               description = "Devuelve cada día del mes con su marca (ausente, vacaciones, feriado...), si ese día se paga, y un " +
                             "resumen: días pagos, ausencias, sueldo del mes según la asistencia y vacaciones tomadas/disponibles del año.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Calendario del mes"),
        @ApiResponse(responseCode = "400", description = "Mes inválido (formato AAAA-MM)"),
        @ApiResponse(responseCode = "404", description = "Empleado no encontrado")
    })
    @GetMapping("/asistencia")
    public ResponseEntity<AsistenciaMesResponse> mes(
            @Parameter(description = "ID del usuario en el módulo auth", required = true) @PathVariable @Positive Long usuarioId,
            @Parameter(description = "Mes en formato AAAA-MM; si no viene, el actual", example = "2026-10")
            @RequestParam(required = false) String mes) {
        YearMonth ym;
        try {
            ym = (mes == null || mes.isBlank()) ? YearMonth.now() : YearMonth.parse(mes.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException("El mes tiene que ser AAAA-MM (por ejemplo 2026-10).");
        }
        return ResponseEntity.ok(asistencia.mes(usuarioId, ym));
    }

    @Operation(summary = "Marca días del calendario de un empleado",
               description = "Marca de una vez los días elegidos como trabajado, ausente, vacaciones, feriado o licencia paga " +
                             "(tipo vacío = borrar la marca). Si algún día ya estaba devengado, el saldo se corrige por la diferencia " +
                             "y queda registrado en el libro de sueldos.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Días marcados"),
        @ApiResponse(responseCode = "400", description = "Días fuera del período de empleo o datos inválidos"),
        @ApiResponse(responseCode = "404", description = "Empleado no encontrado")
    })
    @PutMapping("/asistencia")
    public ResponseEntity<MarcarAsistenciaResponse> marcar(
            @PathVariable @Positive Long usuarioId,
            @Valid @RequestBody MarcarAsistenciaRequest req) {
        return ResponseEntity.ok(asistencia.marcar(usuarioId, req.fechas(), req.tipo(), req.nota()));
    }

    @Operation(summary = "Libro de sueldos de un empleado",
               description = "Los últimos movimientos que cambiaron su saldo (devengos, ajustes por asistencia, ajustes manuales y pagos), " +
                             "del más reciente al más viejo.")
    @GetMapping("/movimientos")
    public ResponseEntity<List<MovimientoSueldoResponse>> movimientos(
            @PathVariable @Positive Long usuarioId,
            @RequestParam(defaultValue = "100") int limite) {
        return ResponseEntity.ok(asistencia.movimientos(usuarioId, limite));
    }

    @Operation(summary = "Da de baja a un empleado",
               description = "Fija su último día de trabajo. Se le devenga hasta ese día y deja de devengar. Si todavía se le debe plata, se le puede seguir pagando.")
    @PostMapping("/baja")
    public ResponseEntity<EmpleadoSueldoResponse> baja(
            @PathVariable @Positive Long usuarioId,
            @Valid @RequestBody BajaEmpleadoRequest req) {
        return ResponseEntity.ok(devengo.darDeBaja(usuarioId, req.fechaBaja(), req.motivo()));
    }

    @Operation(summary = "Reincorpora a un empleado dado de baja",
               description = "Su primer día de vuelta pasa a ser su nuevo alta; el tiempo de baja no se devenga.")
    @PostMapping("/reactivar")
    public ResponseEntity<EmpleadoSueldoResponse> reactivar(
            @PathVariable @Positive Long usuarioId,
            @Valid @RequestBody ReactivarEmpleadoRequest req) {
        return ResponseEntity.ok(devengo.reactivar(usuarioId, req.desde()));
    }
}

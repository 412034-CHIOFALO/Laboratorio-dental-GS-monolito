package com.gs.monolito.stock.controller;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;

import com.gs.monolito.stock.dto.MaterialRequest;
import com.gs.monolito.stock.dto.MaterialResponse;
import com.gs.monolito.stock.dto.MovimientoRequest;
import com.gs.monolito.stock.service.IStockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller de gestión de stock de materiales del laboratorio dental.
 */
@Tag(
    name = "Stock de Materiales",
    description = "Gestión del inventario de insumos del laboratorio dental: " +
                  "alta, consulta, actualización, baja lógica y registro de movimientos " +
                  "(entradas, salidas y ajustes de stock)."
)
@SecurityRequirement(name = "BearerAuth")
@RestController
@RequestMapping("/api/stock")
@RequiredArgsConstructor
@Validated
public class StockController {

    private final IStockService service;

    @Operation(
        summary = "Listar materiales activos",
        description = "Devuelve todos los materiales que se encuentran en estado activo, " +
                      "junto con su stock actual y el flag 'bajoStock' calculado automáticamente. " +
                      "Accesible para roles ADMIN, TECNICO y ADMINISTRATIVO."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Lista de materiales activos"),
        @ApiResponse(responseCode = "401", description = "Token JWT ausente o inválido"),
        @ApiResponse(responseCode = "403", description = "El rol del usuario no tiene acceso")
    })
    @GetMapping
    public ResponseEntity<List<MaterialResponse>> listar() {
        return ResponseEntity.ok(service.listarActivos());
    }

    @Operation(
        summary = "Listar materiales con stock bajo",
        description = "Devuelve únicamente los materiales activos cuyo stockActual es menor o igual " +
                      "al stockMinimo configurado. Útil para generar alertas de reposición. " +
                      "Accesible para roles ADMIN, TECNICO y ADMINISTRATIVO."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Lista de materiales bajo stock mínimo"),
        @ApiResponse(responseCode = "401", description = "Token JWT ausente o inválido"),
        @ApiResponse(responseCode = "403", description = "El rol del usuario no tiene acceso")
    })
    @GetMapping("/alertas")
    public ResponseEntity<List<MaterialResponse>> bajoStock() {
        return ResponseEntity.ok(service.listarBajoStock());
    }

    @Operation(
        summary = "Buscar material por ID",
        description = "Recupera el detalle completo de un material (activo o no) a partir de su ID."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Material encontrado"),
        @ApiResponse(responseCode = "401", description = "Token JWT ausente o inválido"),
        @ApiResponse(responseCode = "404", description = "No existe un material con ese ID")
    })
    @GetMapping("/{id}")
    public ResponseEntity<MaterialResponse> buscarPorId(
            @Parameter(description = "ID único del material", required = true, example = "1")
            @PathVariable @Positive Long id) {
        return ResponseEntity.ok(service.buscarPorId(id));
    }

    @Operation(
        summary = "Crear material",
        description = "Da de alta un nuevo insumo en el inventario. " +
                      "Se debe indicar nombre, categoría, stock inicial, stock mínimo y unidad de medida. " +
                      "Solo accesible para el rol ADMIN."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Material creado correctamente"),
        @ApiResponse(responseCode = "400", description = "Datos de entrada inválidos (validación Bean Validation)"),
        @ApiResponse(responseCode = "401", description = "Token JWT ausente o inválido"),
        @ApiResponse(responseCode = "403", description = "El rol del usuario no tiene acceso")
    })
    @PostMapping
    public ResponseEntity<MaterialResponse> crear(
            @Valid @RequestBody MaterialRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.crear(request));
    }

    @Operation(
        summary = "Actualizar material",
        description = "Modifica todos los campos de un material existente (incluido el stock mínimo " +
                      "y la bandera descuentaStock). Solo accesible para el rol ADMIN."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Material actualizado correctamente"),
        @ApiResponse(responseCode = "400", description = "Datos de entrada inválidos"),
        @ApiResponse(responseCode = "401", description = "Token JWT ausente o inválido"),
        @ApiResponse(responseCode = "403", description = "El rol del usuario no tiene acceso"),
        @ApiResponse(responseCode = "404", description = "No existe un material con ese ID")
    })
    @PutMapping("/{id}")
    public ResponseEntity<MaterialResponse> actualizar(
            @Parameter(description = "ID único del material a actualizar", required = true, example = "1")
            @PathVariable @Positive Long id,
            @Valid @RequestBody MaterialRequest request) {
        return ResponseEntity.ok(service.actualizar(id, request));
    }

    @Operation(
        summary = "Registrar movimiento de stock",
        description = "Registra un movimiento de inventario: ENTRADA (reposición/compra), " +
                      "SALIDA (consumo por pedido o uso interno) o AJUSTE (corrección manual). " +
                      "Los movimientos SALIDA generados por un pedido deben incluir el campo 'pedidoId'. " +
                      "Si el resultado es negativo, el sistema lo permite pero emite una advertencia en log. " +
                      "Accesible para roles ADMIN y TECNICO."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Movimiento registrado y stock actualizado"),
        @ApiResponse(responseCode = "400", description = "Datos inválidos (cantidad nula o negativa, tipo ausente)"),
        @ApiResponse(responseCode = "401", description = "Token JWT ausente o inválido"),
        @ApiResponse(responseCode = "403", description = "El rol del usuario no tiene acceso"),
        @ApiResponse(responseCode = "404", description = "No existe un material con ese ID")
    })
    @PostMapping("/movimiento")
    public ResponseEntity<MaterialResponse> registrarMovimiento(
            @Valid @RequestBody MovimientoRequest request) {
        return ResponseEntity.ok(service.registrarMovimiento(request));
    }

    @Operation(
        summary = "Eliminar material (baja lógica)",
        description = "Marca el material como inactivo (activo = false). " +
                      "No elimina el registro de la base de datos para preservar el historial de movimientos. " +
                      "Solo accesible para el rol ADMIN."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Material dado de baja correctamente"),
        @ApiResponse(responseCode = "401", description = "Token JWT ausente o inválido"),
        @ApiResponse(responseCode = "403", description = "El rol del usuario no tiene acceso"),
        @ApiResponse(responseCode = "404", description = "No existe un material con ese ID")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminar(
            @Parameter(description = "ID único del material a dar de baja", required = true, example = "1")
            @PathVariable @Positive Long id) {
        service.eliminar(id);
        return ResponseEntity.noContent().build();
    }
}

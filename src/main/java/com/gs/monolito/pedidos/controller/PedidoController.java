package com.gs.monolito.pedidos.controller;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;

import com.gs.monolito.pedidos.dto.EntregaRequest;
import com.gs.monolito.pedidos.dto.PedidoRequest;
import com.gs.monolito.pedidos.dto.PedidoResponse;
import com.gs.monolito.pedidos.model.EstadoPedido;
import com.gs.monolito.pedidos.service.IPedidoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controlador REST para la gestión del ciclo de vida de los pedidos del laboratorio dental.
 * <p>
 * Expone operaciones CRUD completas más endpoints específicos de negocio:
 * filtrado por estado, listado de atrasados, transición kanban y marcado de entrega.
 * </p>
 */
@Tag(name = "Pedidos", description = "Gestión del ciclo de vida de los pedidos del laboratorio dental (corona, prótesis, etc.). Permite crear, consultar, actualizar estado y registrar la entrega de trabajos.")
@RestController
@RequestMapping("/api/pedidos")
@RequiredArgsConstructor
@Validated
public class PedidoController {

    private final IPedidoService pedidoService;

    @Operation(
        summary = "Listar todos los pedidos",
        description = "Devuelve todos los pedidos registrados sin importar su estado. Útil para reportes o vistas administrativas completas."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Lista de pedidos obtenida correctamente")
    })
    @GetMapping
    public ResponseEntity<List<PedidoResponse>> listarTodos() {
        return ResponseEntity.ok(pedidoService.listarTodos());
    }

    @Operation(
        summary = "Listar pedidos activos",
        description = "Devuelve únicamente los pedidos que no están en estado ENTREGADO ni CANCELADO. Es la vista principal del tablero kanban del laboratorio."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Lista de pedidos activos obtenida correctamente")
    })
    @GetMapping("/activos")
    public ResponseEntity<List<PedidoResponse>> listarActivos() {
        return ResponseEntity.ok(pedidoService.listarActivos());
    }

    @Operation(
        summary = "Listar pedidos atrasados",
        description = "Devuelve los pedidos que superan el umbral configurado de días hábiles sin ser entregados. Excluye los ya ENTREGADOS y CANCELADOS."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Lista de pedidos atrasados obtenida correctamente")
    })
    @GetMapping("/atrasados")
    public ResponseEntity<List<PedidoResponse>> listarAtrasados() {
        return ResponseEntity.ok(pedidoService.listarAtrasados());
    }

    @Operation(
        summary = "Listar pedidos por estado",
        description = "Filtra los pedidos según el estado especificado en el path. Estados válidos: RECIBIDO, EN_PROCESO, CONTROL, LISTO, ENTREGADO, CANCELADO."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Lista de pedidos filtrada por estado"),
        @ApiResponse(responseCode = "400", description = "Estado inválido o no reconocido")
    })
    @GetMapping("/estado/{estado}")
    public ResponseEntity<List<PedidoResponse>> listarPorEstado(
            @Parameter(description = "Estado del pedido a filtrar", example = "EN_PROCESO")
            @PathVariable EstadoPedido estado) {
        return ResponseEntity.ok(pedidoService.listarPorEstado(estado));
    }

    @Operation(
        summary = "Buscar pedido por ID",
        description = "Devuelve el pedido con el ID especificado, incluyendo todos sus datos de negocio: odontólogo, paciente, técnico asignado, estado, precio acordado y fechas."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Pedido encontrado"),
        @ApiResponse(responseCode = "404", description = "Pedido no encontrado con el ID proporcionado")
    })
    @GetMapping("/{id}")
    public ResponseEntity<PedidoResponse> buscarPorId(
            @Parameter(description = "ID interno del pedido", example = "42")
            @PathVariable @Positive Long id) {
        return ResponseEntity.ok(pedidoService.buscarPorId(id));
    }

    @Operation(
        summary = "Crear nuevo pedido",
        description = "Registra un nuevo pedido de trabajo dental. Se genera automáticamente el número de pedido (nroPedido), se calcula la fecha de entrega estimada en días hábiles y el estado inicial es RECIBIDO."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Pedido creado exitosamente"),
        @ApiResponse(responseCode = "400", description = "Datos de entrada inválidos o incompletos"),
        @ApiResponse(responseCode = "409", description = "Conflicto: número de pedido duplicado u otra restricción de negocio")
    })
    @PostMapping
    public ResponseEntity<PedidoResponse> crear(
            @Parameter(description = "Datos del nuevo pedido")
            @Valid @RequestBody PedidoRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pedidoService.crear(request));
    }

    @Operation(
        summary = "Actualizar pedido completo",
        description = "Reemplaza los datos editables de un pedido existente (odontólogo, paciente, trabajo, técnico, fecha de entrega, precio, observaciones). No modifica el estado ni el nroPedido."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Pedido actualizado correctamente"),
        @ApiResponse(responseCode = "400", description = "Datos de entrada inválidos"),
        @ApiResponse(responseCode = "404", description = "Pedido no encontrado")
    })
    @PutMapping("/{id}")
    public ResponseEntity<PedidoResponse> actualizar(
            @Parameter(description = "ID del pedido a actualizar", example = "42")
            @PathVariable @Positive Long id,
            @Parameter(description = "Nuevos datos del pedido")
            @Valid @RequestBody PedidoRequest request) {
        return ResponseEntity.ok(pedidoService.actualizar(id, request));
    }

    @Operation(
        summary = "Cambiar estado del pedido (kanban)",
        description = "Transición de estado: mueve el pedido al nuevo estado indicado. Flujo normal: RECIBIDO → EN_PROCESO → CONTROL → LISTO. Al pasar a EN_PROCESO se descuenta el stock de materiales si no fue descontado previamente."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Estado actualizado correctamente"),
        @ApiResponse(responseCode = "400", description = "Transición de estado inválida"),
        @ApiResponse(responseCode = "404", description = "Pedido no encontrado")
    })
    @PatchMapping("/{id}/estado")
    public ResponseEntity<PedidoResponse> actualizarEstado(
            @Parameter(description = "ID del pedido", example = "42")
            @PathVariable @Positive Long id,
            @Parameter(description = "Nuevo estado destino", example = "EN_PROCESO")
            @RequestParam EstadoPedido nuevoEstado) {
        return ResponseEntity.ok(pedidoService.actualizarEstado(id, nuevoEstado));
    }

    @Operation(
        summary = "Registrar entrega del pedido",
        description = "Marca el pedido como ENTREGADO. Solo se puede aplicar si el pedido estaba en estado LISTO. Registra la fecha real de entrega, quién retiró el trabajo y observaciones adicionales de la entrega."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Pedido marcado como entregado correctamente"),
        @ApiResponse(responseCode = "400", description = "El pedido no está en estado LISTO o datos inválidos"),
        @ApiResponse(responseCode = "404", description = "Pedido no encontrado")
    })
    @PatchMapping("/{id}/entregar")
    public ResponseEntity<PedidoResponse> entregar(
            @Parameter(description = "ID del pedido a entregar", example = "42")
            @PathVariable @Positive Long id,
            @Parameter(description = "Datos de la entrega: quién retiró y observaciones")
            @Valid @RequestBody EntregaRequest request) {
        return ResponseEntity.ok(pedidoService.marcarEntregado(id, request));
    }

    @Operation(
        summary = "Eliminar pedido",
        description = "Elimina definitivamente un pedido. Solo disponible para el rol ADMIN. Operación irreversible."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Pedido eliminado correctamente"),
        @ApiResponse(responseCode = "404", description = "Pedido no encontrado")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminar(
            @Parameter(description = "ID del pedido a eliminar", example = "42")
            @PathVariable @Positive Long id) {
        pedidoService.eliminar(id);
        return ResponseEntity.noContent().build();
    }
}

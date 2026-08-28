package com.gs.monolito.stock.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.common.security.CurrentUser;
import com.gs.monolito.stock.dto.MaterialRequest;
import com.gs.monolito.stock.dto.MaterialResponse;
import com.gs.monolito.stock.dto.MovimientoRequest;
import com.gs.monolito.stock.exception.ResourceNotFoundException;
import com.gs.monolito.stock.model.Material;
import com.gs.monolito.stock.model.MovimientoStock;
import com.gs.monolito.stock.repository.MaterialRepository;
import com.gs.monolito.stock.repository.MovimientoStockRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Implementación de {@link IStockService}. Política permisiva: permite stock
 * negativo con advertencia en log en vez de lanzar excepción, reflejando la
 * realidad operativa del laboratorio.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockService implements IStockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final MaterialRepository materialRepo;
    private final MovimientoStockRepository movimientoRepo;
    private final AuditoriaService auditoria;

    public List<MaterialResponse> listarActivos() {
        return materialRepo.findByActivoTrue().stream().map(MaterialResponse::from).toList();
    }

    public List<MaterialResponse> listarBajoStock() {
        return materialRepo.findBajoStock().stream().map(MaterialResponse::from).toList();
    }

    public MaterialResponse buscarPorId(Long id) {
        return materialRepo.findById(id)
                .map(MaterialResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Material", id));
    }

    @Override
    @Transactional
    public MaterialResponse crear(MaterialRequest request) {
        Material m = Material.builder()
                .nombre(request.getNombre())
                .descripcion(request.getDescripcion())
                .categoria(request.getCategoria())
                .stockActual(request.getStockActual())
                .stockMinimo(request.getStockMinimo())
                .unidadMedida(request.getUnidadMedida())
                .precioUnitario(request.getPrecioUnitario())
                .proveedor(request.getProveedor())
                .descuentaStock(request.getDescuentaStock() == null || request.getDescuentaStock())
                .build();
        MaterialResponse resp = MaterialResponse.from(materialRepo.save(m));
        auditoria.registrar(CurrentUser.usernameOrSistema(), "CREAR", "Material creado", "Material " + m.getNombre(),
                "Stock inicial " + m.getStockActual() + " " + m.getUnidadMedida());
        return resp;
    }

    @Override
    @Transactional
    public MaterialResponse actualizar(Long id, MaterialRequest request) {
        Material m = materialRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Material", id));
        m.setNombre(request.getNombre());
        m.setDescripcion(request.getDescripcion());
        m.setCategoria(request.getCategoria());
        m.setStockActual(request.getStockActual());
        m.setStockMinimo(request.getStockMinimo());
        m.setUnidadMedida(request.getUnidadMedida());
        m.setPrecioUnitario(request.getPrecioUnitario());
        m.setProveedor(request.getProveedor());
        if (request.getDescuentaStock() != null) {
            m.setDescuentaStock(request.getDescuentaStock());
        }
        return MaterialResponse.from(materialRepo.save(m));
    }

    @Override
    @Transactional
    public MaterialResponse registrarMovimiento(MovimientoRequest request) {
        Material material = resolverMaterial(request);

        double nuevoStock = calcularNuevoStock(material.getStockActual(), request);
        if (nuevoStock < 0) {
            log.warn("[GS-STOCK] Stock negativo al registrar movimiento {} para material '{}': {} {} disponibles, se descontaron {} → resultado {}",
                request.getTipo(), material.getNombre(),
                material.getStockActual(), material.getUnidadMedida(),
                request.getCantidad(), nuevoStock);
        }

        material.setStockActual(nuevoStock);
        materialRepo.save(material);

        MovimientoStock mov = MovimientoStock.builder()
                .material(material)
                .tipo(request.getTipo())
                .cantidad(request.getCantidad())
                .stockResultante(nuevoStock)
                .motivo(request.getMotivo())
                .pedidoId(request.getPedidoId())
                .build();
        movimientoRepo.save(mov);

        auditoria.registrar(CurrentUser.usernameOrSistema(), "STOCK", "Movimiento de stock", "Material " + material.getNombre(),
                request.getTipo() + " " + request.getCantidad() + " " + material.getUnidadMedida()
                        + " → " + nuevoStock + (request.getMotivo() != null ? " · " + request.getMotivo() : ""));

        return MaterialResponse.from(material);
    }

    /**
     * Resuelve el material del movimiento priorizando el nombre sobre el id.
     * Quien más depende de esto es el módulo pedidos: cuando descuenta stock
     * según la receta del catálogo, el materialId viene de un seed hardcodeado
     * en catalogo. Resolver por nombre evita que el descuento falle en
     * silencio contra un material inexistente o equivocado.
     */
    private Material resolverMaterial(MovimientoRequest request) {
        String nombre = request.getMaterialNombre();
        if (nombre != null && !nombre.isBlank()) {
            Optional<Material> porNombre = materialRepo.findByNombreIgnoreCase(nombre.trim());
            if (porNombre.isPresent()) {
                return porNombre.get();
            }
            log.warn("[GS-STOCK] No hay material con nombre '{}' — se intenta resolver por id={}.",
                nombre, request.getMaterialId());
        }
        return materialRepo.findById(request.getMaterialId())
                .orElseThrow(() -> new ResourceNotFoundException("Material", request.getMaterialId()));
    }

    @Transactional
    public void eliminar(Long id) {
        Material m = materialRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Material", id));
        m.setActivo(false);
        materialRepo.save(m);
    }

    private double calcularNuevoStock(double actual, MovimientoRequest req) {
        return switch (req.getTipo()) {
            case ENTRADA -> actual + req.getCantidad();
            case SALIDA  -> actual - req.getCantidad();
            case AJUSTE  -> req.getCantidad();
        };
    }
}

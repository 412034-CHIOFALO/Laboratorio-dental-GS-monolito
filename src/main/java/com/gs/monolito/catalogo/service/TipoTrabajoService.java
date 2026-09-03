package com.gs.monolito.catalogo.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.catalogo.dto.TipoTrabajoPublicoResponse;
import com.gs.monolito.catalogo.dto.TipoTrabajoRequest;
import com.gs.monolito.catalogo.dto.TipoTrabajoResponse;
import com.gs.monolito.catalogo.exception.ResourceNotFoundException;
import com.gs.monolito.catalogo.model.Categoria;
import com.gs.monolito.catalogo.model.IngredienteReceta;
import com.gs.monolito.catalogo.model.TipoTrabajo;
import com.gs.monolito.catalogo.repository.TipoTrabajoRepository;
import com.gs.monolito.common.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Implementación de {@link ITipoTrabajoService}. La auditoría antes viajaba
 * por HTTP a ms-auth vía {@code AuditoriaClient} (fire-and-forget, @Async);
 * acá se llama directo a {@link AuditoriaService#registrar}, en el mismo
 * proceso y la misma transacción — ya no hace falta @Async, no hay red de por
 * medio.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TipoTrabajoService implements ITipoTrabajoService {

    private final TipoTrabajoRepository repository;
    private final AuditoriaService auditoria;

    public List<TipoTrabajoResponse> listarActivos() {
        return repository.findByActivoTrue()
                .stream()
                .map(TipoTrabajoResponse::from)
                .toList();
    }

    public List<TipoTrabajoPublicoResponse> listarPublico() {
        return repository.findByActivoTrue()
                .stream()
                .map(TipoTrabajoPublicoResponse::from)
                .toList();
    }

    public List<TipoTrabajoResponse> listarPorCategoria(Categoria categoria) {
        return repository.findByCategoriaAndActivoTrue(categoria)
                .stream()
                .map(TipoTrabajoResponse::from)
                .toList();
    }

    public List<TipoTrabajoResponse> buscarPorNombre(String nombre) {
        return repository.findByNombreContainingIgnoreCaseAndActivoTrue(nombre)
                .stream()
                .map(TipoTrabajoResponse::from)
                .toList();
    }

    public TipoTrabajoResponse buscarPorId(Long id) {
        return repository.findById(id)
                .map(TipoTrabajoResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("TipoTrabajo", id));
    }

    @Transactional
    public TipoTrabajoResponse crear(TipoTrabajoRequest request) {
        TipoTrabajo t = TipoTrabajo.builder()
                .nombre(request.getNombre())
                .descripcion(request.getDescripcion())
                .precio(request.getPrecio())
                .categoria(request.getCategoria())
                .tiempoEstimadoDias(request.getTiempoEstimadoDias())
                .fotoUrl(request.getFotoUrl())
                .build();
        t.reemplazarReceta(toIngredientes(request));
        TipoTrabajoResponse resp = TipoTrabajoResponse.from(repository.save(t));
        auditoria.registrar(CurrentUser.usernameOrSistema(), "CREAR", "Tipo de trabajo creado",
                "Catálogo: " + t.getNombre(), "Precio $" + t.getPrecio());
        return resp;
    }

    @Transactional
    public TipoTrabajoResponse actualizar(Long id, TipoTrabajoRequest request) {
        TipoTrabajo t = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TipoTrabajo", id));

        t.setNombre(request.getNombre());
        t.setDescripcion(request.getDescripcion());
        t.setPrecio(request.getPrecio());
        t.setCategoria(request.getCategoria());
        t.setTiempoEstimadoDias(request.getTiempoEstimadoDias());
        t.setFotoUrl(request.getFotoUrl());
        t.reemplazarReceta(toIngredientes(request));

        TipoTrabajoResponse resp = TipoTrabajoResponse.from(repository.save(t));
        auditoria.registrar(CurrentUser.usernameOrSistema(), "EDITAR", "Tipo de trabajo editado",
                "Catálogo: " + t.getNombre(), "Precio $" + t.getPrecio());
        return resp;
    }

    private List<IngredienteReceta> toIngredientes(TipoTrabajoRequest request) {
        if (request.getReceta() == null) return List.of();
        return request.getReceta().stream()
                .map(r -> IngredienteReceta.builder()
                        .materialId(r.getMaterialId())
                        .materialNombre(r.getMaterialNombre())
                        .cantidad(r.getCantidad())
                        .unidad(r.getUnidad())
                        .notas(r.getNotas())
                        .build())
                .toList();
    }

    @Transactional
    public void eliminar(Long id) {
        TipoTrabajo t = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TipoTrabajo", id));
        t.setActivo(false);
        repository.save(t);
        auditoria.registrar(CurrentUser.usernameOrSistema(), "ELIMINAR", "Tipo de trabajo dado de baja",
                "Catálogo: " + t.getNombre(), "");
    }
}

package com.gs.monolito.finanzas.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.common.audit.Cambios;
import com.gs.monolito.common.security.CurrentUser;
import com.gs.monolito.finanzas.dto.ProveedorRequest;
import com.gs.monolito.finanzas.dto.ProveedorResponse;
import com.gs.monolito.finanzas.exception.BusinessException;
import com.gs.monolito.finanzas.exception.ResourceNotFoundException;
import com.gs.monolito.finanzas.model.Proveedor;
import com.gs.monolito.finanzas.repository.DeudaProveedorRepository;
import com.gs.monolito.finanzas.repository.ProveedorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Implementación de {@link IProveedorService}. El listado enriquece cada
 * respuesta con el saldo de deuda pendiente calculado al momento de la
 * consulta. La baja es lógica ({@code activo = false}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProveedorService implements IProveedorService {

    private final ProveedorRepository proveedorRepo;
    private final DeudaProveedorRepository deudaRepo;
    private final AuditoriaService auditoria;

    public List<ProveedorResponse> listarActivos() {
        return proveedorRepo.findByActivoTrue().stream()
            .map(p -> ProveedorResponse.from(p, deudaRepo.sumDeudaPendienteByProveedor(p.getId())))
            .toList();
    }

    public ProveedorResponse buscarPorId(Long id) {
        Proveedor p = proveedorRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Proveedor", id));
        return ProveedorResponse.from(p, deudaRepo.sumDeudaPendienteByProveedor(id));
    }

    @Transactional
    public ProveedorResponse crear(ProveedorRequest request) {
        if (request.getCuit() != null && !request.getCuit().isBlank() && proveedorRepo.existsByCuit(request.getCuit())) {
            throw new BusinessException("Ya existe un proveedor con CUIT: " + request.getCuit());
        }
        Proveedor p = Proveedor.builder()
            .nombre(request.getNombre())
            .cuit(request.getCuit())
            .email(request.getEmail())
            .telefono(request.getTelefono())
            .direccion(request.getDireccion())
            .build();
        Proveedor guardado = proveedorRepo.save(p);
        auditoria.registrar(CurrentUser.usernameOrSistema(), "CREAR", "Alta de proveedor", entidad(guardado),
            Cambios.nuevo().datoSensible("CUIT", guardado.getCuit()).datoSensible("email", guardado.getEmail())
                .datoSensible("teléfono", guardado.getTelefono()).dato("dirección", guardado.getDireccion()).resumen());
        return ProveedorResponse.from(guardado);
    }

    @Transactional
    public ProveedorResponse actualizar(Long id, ProveedorRequest request) {
        Proveedor p = proveedorRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Proveedor", id));
        Cambios cambios = Cambios.nuevo()
            .texto("nombre", p.getNombre(), request.getNombre())
            .texto("dirección", p.getDireccion(), request.getDireccion())
            .sensible("CUIT", p.getCuit(), request.getCuit())
            .sensible("email", p.getEmail(), request.getEmail())
            .sensible("teléfono", p.getTelefono(), request.getTelefono());
        String entidadAntes = entidad(p);
        p.setNombre(request.getNombre());
        p.setCuit(request.getCuit());
        p.setEmail(request.getEmail());
        p.setTelefono(request.getTelefono());
        p.setDireccion(request.getDireccion());
        Proveedor guardado = proveedorRepo.save(p);
        if (cambios.hayCambios()) {
            auditoria.registrar(CurrentUser.usernameOrSistema(), "EDITAR", "Edición de proveedor", entidadAntes, cambios.resumen());
        }
        return ProveedorResponse.from(guardado);
    }

    @Transactional
    public void desactivar(Long id) {
        Proveedor p = proveedorRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Proveedor", id));
        p.setActivo(false);
        proveedorRepo.save(p);
        auditoria.registrar(CurrentUser.usernameOrSistema(), "ELIMINAR", "Baja de proveedor", entidad(p),
            "Queda inactivo: no se lo ofrece más para cargar deudas ni pagos, pero se conserva su historial.");
    }

    private static String entidad(Proveedor p) {
        return "Proveedor " + p.getNombre() + " (id " + p.getId() + ")";
    }
}

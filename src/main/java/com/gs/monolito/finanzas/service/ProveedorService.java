package com.gs.monolito.finanzas.service;

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
        return ProveedorResponse.from(proveedorRepo.save(p));
    }

    @Transactional
    public ProveedorResponse actualizar(Long id, ProveedorRequest request) {
        Proveedor p = proveedorRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Proveedor", id));
        p.setNombre(request.getNombre());
        p.setCuit(request.getCuit());
        p.setEmail(request.getEmail());
        p.setTelefono(request.getTelefono());
        p.setDireccion(request.getDireccion());
        return ProveedorResponse.from(proveedorRepo.save(p));
    }

    @Transactional
    public void desactivar(Long id) {
        Proveedor p = proveedorRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Proveedor", id));
        p.setActivo(false);
        proveedorRepo.save(p);
    }
}

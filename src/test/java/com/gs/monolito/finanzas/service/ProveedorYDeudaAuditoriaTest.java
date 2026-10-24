package com.gs.monolito.finanzas.service;

import com.gs.monolito.auth.service.AuditoriaService;
import com.gs.monolito.finanzas.dto.DeudaProveedorRequest;
import com.gs.monolito.finanzas.dto.ProveedorRequest;
import com.gs.monolito.finanzas.model.DeudaProveedor;
import com.gs.monolito.finanzas.model.Proveedor;
import com.gs.monolito.finanzas.repository.DeudaProveedorRepository;
import com.gs.monolito.finanzas.repository.ProveedorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Proveedores y sus deudas: cada alta, edición, baja y deuda cargada deja rastro con quién y qué cambió. */
@ExtendWith(MockitoExtension.class)
class ProveedorYDeudaAuditoriaTest {

    @Mock private ProveedorRepository proveedorRepo;
    @Mock private DeudaProveedorRepository deudaRepo;
    @Mock private AuditoriaService auditoria;

    private ProveedorService service;

    @BeforeEach
    void preparar() {
        service = new ProveedorService(proveedorRepo, deudaRepo, auditoria);
        lenient().when(proveedorRepo.save(any(Proveedor.class))).thenAnswer(inv -> {
            Proveedor p = inv.getArgument(0);
            if (p.getId() == null) p.setId(3L);
            return p;
        });
    }

    private Proveedor existente() {
        return Proveedor.builder().id(3L).nombre("Dental Norte").cuit("30-71234567-8").telefono("1155554521")
                .email("ventas@dentalnorte.com").direccion("Mitre 100").build();
    }

    private ProveedorRequest req(String nombre, String cuit, String telefono, String email, String direccion) {
        ProveedorRequest r = new ProveedorRequest();
        r.setNombre(nombre);
        r.setCuit(cuit);
        r.setTelefono(telefono);
        r.setEmail(email);
        r.setDireccion(direccion);
        return r;
    }

    @Test
    void elAltaDeUnProveedorQuedaAuditada_sinMostrarSusDatosCompletos() {
        service.crear(req("Dental Norte", "30-71234567-8", "1155554521", "ventas@dentalnorte.com", "Mitre 100"));

        ArgumentCaptor<String> detalle = ArgumentCaptor.forClass(String.class);
        verify(auditoria).registrar(any(), eq("CREAR"), eq("Alta de proveedor"), eq("Proveedor Dental Norte (id 3)"), detalle.capture());
        assertThat(detalle.getValue()).contains("CUIT: …5678").contains("teléfono: …4521").contains("email: v***@dentalnorte.com")
                .contains("dirección: Mitre 100").doesNotContain("30-71234567");
    }

    @Test
    void cambiarElCuitYLaDireccionQuedaRegistrado() {
        when(proveedorRepo.findById(3L)).thenReturn(Optional.of(existente()));

        service.actualizar(3L, req("Dental Norte", "30-79999999-1", "1155554521", "ventas@dentalnorte.com", "Mitre 200"));

        ArgumentCaptor<String> detalle = ArgumentCaptor.forClass(String.class);
        verify(auditoria).registrar(any(), eq("EDITAR"), eq("Edición de proveedor"), eq("Proveedor Dental Norte (id 3)"), detalle.capture());
        assertThat(detalle.getValue()).isEqualTo("dirección: Mitre 100 → Mitre 200; CUIT: …5678 → …9991");
    }

    @Test
    void laEdicionSinCambiosNoGeneraEvento() {
        when(proveedorRepo.findById(3L)).thenReturn(Optional.of(existente()));

        service.actualizar(3L, req("Dental Norte", "30-71234567-8", "1155554521", "ventas@dentalnorte.com", "Mitre 100"));

        verify(auditoria, never()).registrar(any(), any(), any(), any(), any());
    }

    @Test
    void laBajaDelProveedorQuedaAuditada() {
        when(proveedorRepo.findById(3L)).thenReturn(Optional.of(existente()));

        service.desactivar(3L);

        verify(auditoria).registrar(any(), eq("ELIMINAR"), eq("Baja de proveedor"), eq("Proveedor Dental Norte (id 3)"), any());
    }

    @Test
    void cargarUnaDeudaAProveedorQuedaAuditada_conMontoFacturaYVencimiento() {
        DeudaProveedorService deudas = new DeudaProveedorService(deudaRepo, proveedorRepo, null, auditoria);
        when(proveedorRepo.findById(3L)).thenReturn(Optional.of(existente()));
        when(deudaRepo.save(any(DeudaProveedor.class))).thenAnswer(inv -> inv.getArgument(0));
        DeudaProveedorRequest r = new DeudaProveedorRequest();
        r.setProveedorId(3L);
        r.setDescripcion("Yeso piedra 20 kg");
        r.setMonto(new BigDecimal("45000.00"));
        r.setNroFacturaProveedor("A-0001-123");
        r.setFechaVencimiento(LocalDate.of(2026, 11, 15));

        deudas.registrar(r);

        ArgumentCaptor<String> detalle = ArgumentCaptor.forClass(String.class);
        verify(auditoria).registrar(any(), eq("PROVEEDOR"), eq("Deuda a proveedor cargada"), eq("Proveedor Dental Norte"), detalle.capture());
        assertThat(detalle.getValue()).contains("$45000.00").contains("Yeso piedra 20 kg").contains("factura A-0001-123").contains("vence 2026-11-15");
    }
}
